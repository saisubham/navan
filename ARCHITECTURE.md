# Architecture

Spring Boot 4 (Java 25), `JdbcClient` with plain SQL, MySQL 26.10.0 (InnoDB). No JPA: the locks and
constraints that make this correct are visible in the SQL.

```
web/          HTTP only: controllers, exception -> status mapping
transaction/  TransactionService (use cases, DB transactions) -> TransactionRepository (SQL)
              TransactionRequest (Bean Validation on the body), TransactionType, TransactionMetadata (sealed record per type), MetadataCodec
invoice/      InvoiceService (use cases) -> InvoiceRepository (SQL)
              InvoiceSnapshotBuilder (pure: copy, currency check, totals, rollups)
common/       Money, Pagination, exceptions
```

## `external_id` uniqueness and concurrent POSTs

`transactions.external_id` has a `UNIQUE` index; that index is the only authority on identity. The write is:

1. validate (400s never touch the DB),
2. cheap pre-check by `external_id` (fast 409 for the common case),
3. one DB transaction: insert the header row (typed metadata is its JSON column), then tax lines and fee lines.

Two overlapping POSTs both pass the pre-check, then both INSERT. InnoDB makes the second INSERT wait on the
unique-index lock of the first; when the first commits the second fails with `DuplicateKeyException`, its whole
transaction rolls back (no orphan child rows), and the service answers **409 with the winner's id**. There is no
in-memory map and no lock service. `ConcurrencyIT` fires 8 simultaneous POSTs, three rounds, and asserts one
201, seven 409s that all name the same id, and one row.

## Request validation

Request bodies are validated declaratively. `TransactionRequest.Create` / `Patch` carry Bean Validation
constraints (`@NotBlank`, `@Size`, `@Pattern`, `@PositiveOrZero`, `@Valid` on the lines) and the controller takes
`@Valid @RequestBody`. `TaxLine` and `FeeLine` are both the request and the stored shape, so their rules live in
one place. `type` is a `TransactionType` enum and `occurred_at` an `Instant`, so an unknown type or a bad
timestamp is rejected by Jackson. Failures become a 400 `validation_error` naming the fields, e.g.
`tax_lines[0].name must not be blank; total must be greater than or equal to 0`. PATCH uses the same
constraints on whichever fields are supplied. `Invoice.Request` works the same way (`@Size(1..100)`,
`@NotBlank` ids, and an `@AssertTrue` for "exactly one of `transaction_id` / `transaction_ids`"); the one rule
that needs the service is a repeated id, which is a 409. Only the type-dependent `metadata` needs code
(`MetadataCodec`), and money is rounded to scale 2 in the records' compact constructors.

Every error has the same body, `{"error","message"}`, including the ones Spring raises itself (unknown path 404,
wrong method 405, wrong content type 415) and the 500 safety net; `ApiExceptionHandler` covers them and
`ErrorShapeIT` asserts it.

## Money

`DECIMAL(19,2)` columns, `BigDecimal` scale 2 HALF_UP in Java, `DECIMAL(7,4)` for tax rates. Request bodies are
deserialized with `USE_BIG_DECIMAL_FOR_FLOATS` so `220.0` never becomes a `double`. Invoice totals and rollups
are `BigDecimal` sums (`InvoiceSnapshotBuilderTest` covers `0.1 + 0.2 + 0.3 == 0.60`).

## Tables and models

Five tables. Identity, taxes and fees are real columns/tables; type-specific details and frozen snapshots
are JSON, because they are written once and read back whole (the brief explicitly allows this).

| Concern | Storage |
|---|---|
| Header | `transactions(id, type, external_id UNIQUE, currency, occurred_at, total, metadata JSON, ...)` |
| Taxes (government/VAT style) | `transaction_tax_lines(transaction_id, seq, name, rate, amount)` |
| Fees (carrier/merchant) | `transaction_fee_lines(transaction_id, seq, name, amount)` - a separate table, never mixed with taxes |
| Flight | `metadata`: `origin`, `destination`, `fare`, `coupons[]` (`flight_number`, `carrier`, `from`, `to`, `departure`, `arrival`) |
| Hotel | `metadata`: `name`, `address{street,city,country}`, `check_in`, `check_out`, `nights`, `room_rate_per_night`, `extras[]` |
| Rail | `metadata`: `legs[]` (`origin`, `destination`, `departure`, `arrival`, `train_number`, `class`); a change of train is two legs |
| Navan fee | `metadata`: `fee_kind` (`trip_fee` or `agent_call_fee`; anything else is 400), `description`, `related_external_id` |

`TransactionMetadata` is a sealed interface with one record per type (`Flight`, `Hotel`, `Rail`, `NavanFee`).
`type` is a sibling of `metadata` in the request, so there is no type tag inside metadata: `MetadataCodec` does
a lookup on the transaction's `type` (the `TransactionType` enum maps each type to its record) and binds `metadata` to that record with Jackson. Validation is
declarative (Bean Validation: `@NotBlank`, `@NotEmpty`, `@Valid`, `@PositiveOrZero`) and every failure is a 400
naming the field, e.g. `metadata.coupons[0].flightNumber must not be blank` (Bean Validation reports the Java
property name). An unknown `type` or `fee_kind` is a 400. Records give typed `Instant` / `LocalDate` /
`BigDecimal` (scaled to 2 in the compact constructor) and a `FeeKind` enum.

**Decision: unknown metadata keys are preserved.** Unrecognized keys inside `metadata` are kept in an `extra`
map and returned unchanged, not rejected and not dropped. Carriers and hotels send fields we do not model
(fare basis, loyalty ids); losing them on write would be silent data loss, and rejecting them would make the API
brittle against upstream changes. The known fields are still strictly validated. The trade-off is that a typo in
an optional field name is accepted as an extra key, which is why the fields that matter are required.

GET returns coupons, the hotel address, rail legs and `fee_kind`, never an empty blob, and a flight cannot be
stored as a hotel because the record is chosen by `type`. The record is serialized into the `metadata` JSON
column on write and read back into the same record on read, so output order is the record's declared order.
`metadata` is never queried today, so it has no index. When a lookup such as "all flights from TXL" is needed,
the field is promoted to a generated column and indexed, with no change to the stored JSON or the API:

```sql
ALTER TABLE transactions
  ADD COLUMN origin VARCHAR(8) GENERATED ALWAYS AS (metadata->>'$.origin') VIRTUAL,
  ADD INDEX idx_transactions_origin (type, origin);
```

(Not in `schema.sql` on purpose: no endpoint filters on it, and an unused index only slows every write.)

Loading is batched: any number of transactions costs three queries (headers, tax lines, fee lines), so lists
have no N+1.

## Invoice snapshot

`invoices(id, currency, total, tax_rollup JSON, fee_rollup JSON, ...)` and
`invoice_transactions(invoice_id, position, transaction_id, snapshot JSON)`. When an invoice is built, each
transaction (type, external_id, currency, occurred_at, total, tax lines, fee lines, metadata) is **copied** into
`snapshot`; `transaction_id` is only a reference. Reading an invoice is two queries against these two tables and
never joins `transactions`, so later edits cannot change an invoice. Tax rollup groups by `(name, rate)`, fee
rollup by `name`; the grand total is the sum of the lines.

`transaction_id` stays a real indexed column on purpose: it answers "is this transaction billed?" and, with
the `ON DELETE RESTRICT` foreign key, makes the database refuse to delete a billed transaction even if
application code were wrong.

Rules enforced:

- one currency per invoice (409), repeated id in one request (409), any missing id (404),
- `PATCH /transactions/{id}` and `DELETE /transactions/{id}` return 409 if the id is on any invoice
  (checked via `idx_invoice_txn_transaction`),
- `PATCH /invoices/{id}` deletes and rebuilds the snapshot from current data inside one DB transaction; any
  failure rolls back to the previous snapshot,
- `DELETE /invoices/{id}` cascades only through `invoice_transactions`.

### Locking order (why races are safe)

- PATCH/DELETE transaction: `SELECT ... FOR UPDATE` on the transaction row, then check "is it invoiced".
- Create/patch invoice: lock all referenced transaction rows with `FOR UPDATE` **in sorted id order**, then
  read them and write the snapshot.

A transaction is therefore either billed before a PATCH/DELETE sees it (they get 409), or modified/deleted
before invoice creation reads it (the invoice contains the new value, or gets 404). The two writers serialize on
the same row lock and always take locks in the same order, so there is no deadlock cycle. `ConcurrencyIT` races
PATCH vs invoice-create and DELETE vs invoice-create ten times each and checks the outcome is one of the two
legal interleavings, and races six concurrent PATCHes to prove tax/fee lists are replaced whole, never torn
(last write wins, atomically).

## High volume

What breaks first, in order, and what is done about it:

1. **Duplicate POST storms** (retries, double clicks). They contend on one unique-index key. Handled by the
   `UNIQUE(external_id)` constraint plus a pre-check that answers most duplicates without opening a write
   transaction. The losers hold locks only for the duration of one INSERT.
2. **Hot-row PATCH contention.** Concurrent PATCHes of one transaction serialize on its row lock; each is a
   short transaction (a handful of statements on indexed `transaction_id`), so the queue drains quickly and the
   result is last-write-wins, never torn.
3. **Unbounded lists.** `limit` is mandatory with default 50 and hard max 100 (larger is 400); SQL always has
   `LIMIT/OFFSET` (we fetch `limit+1` to compute `has_more`, no `COUNT(*)`). Tax and fee lines are loaded for the page
   only, in batched `IN (...)` queries. Nothing paginates in memory.
4. **Deep `OFFSET` scans.** `OFFSET` cost grows with the offset. The list order is `(created_at, id)` which has
   a covering index (`idx_transactions_created`, `idx_invoices_created`), so it is an index scan, not a sort. The
   next step at larger scale is keyset pagination on that same index.
5. **"Is this transaction invoiced?"** is on every PATCH/DELETE; `idx_invoice_txn_transaction` makes it an
   index probe instead of a table scan.

Indexes: `UNIQUE(transactions.external_id)`; `(created_at, id)` on `transactions` and `invoices`;
`invoice_transactions(transaction_id)` and `(invoice_id, transaction_id)` unique; `(transaction_id, seq)` on the
tax and fee line tables. `SchemaIT` asserts these (and InnoDB, `DECIMAL(19,2)`) against
`information_schema`, so they cannot silently disappear.

## Tests

`./mvnw test` runs everything against a real MySQL started by Testcontainers (same image tag as
`docker-compose.yml`), through the real HTTP API: `TransactionApiIT`, `InvoiceApiIT`, `ConcurrencyIT`,
`PaginationIT`, `SchemaIT`, `ErrorShapeIT`, plus pure unit tests for the snapshot builder and money scaling.

## Delivery

`Dockerfile` (multi-stage JDK 25 build, JRE 25 runtime), `docker-compose.yml` (MySQL with healthcheck, the app
waits for it and has its own `/health` healthcheck), GitHub Actions `ci.yml` (verify on every push/PR; on every
merge to `main` publish the image to GHCR and create the tag and GitHub release `v1.0.<run number>`).
