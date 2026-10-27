# Architecture

Spring Boot 4 (Java 25), `JdbcClient` with plain SQL, MySQL 26.10.0 (InnoDB). No JPA: the locks and
constraints that make this correct are visible in the SQL.

```
web/          HTTP only: controllers, exception -> status mapping
transaction/  TransactionService (use cases, DB transactions) -> TransactionRepository (SQL)
              TransactionValidator + MetadataHandler per type (flight, hotel, rail, navan_fee)
invoice/      InvoiceService (use cases) -> InvoiceRepository (SQL)
              InvoiceSnapshotBuilder (pure: copy, currency check, totals, rollups)
common/       Money, Pagination, exceptions
```

## `external_id` uniqueness and concurrent POSTs

`transactions.external_id` has a `UNIQUE` index; that index is the only authority on identity. The write is:

1. validate (400s never touch the DB),
2. cheap pre-check by `external_id` (fast 409 for the common case),
3. one DB transaction: insert header, tax lines, fee lines, typed metadata rows.

Two overlapping POSTs both pass the pre-check, then both INSERT. InnoDB makes the second INSERT wait on the
unique-index lock of the first; when the first commits the second fails with `DuplicateKeyException`, its whole
transaction rolls back (no orphan child rows), and the service answers **409 with the winner's id**. There is no
in-memory map and no lock service. `ConcurrencyIT` fires 8 simultaneous POSTs, three rounds, and asserts one
201, seven 409s that all name the same id, and one row.

## Money

`DECIMAL(19,2)` columns, `BigDecimal` scale 2 HALF_UP in Java, `DECIMAL(7,4)` for tax rates. Request bodies are
deserialized with `USE_BIG_DECIMAL_FOR_FLOATS` so `220.0` never becomes a `double`. Invoice totals and rollups
are `BigDecimal` sums (`InvoiceSnapshotBuilderTest` covers `0.1 + 0.2 + 0.3 == 0.60`).

## Tables and models

Shared header plus typed child tables, so everything is real columns that can be queried and indexed:

| Concern | Tables |
|---|---|
| Header | `transactions(id, type, external_id UNIQUE, currency, occurred_at, total, metadata_extra, ...)` |
| Taxes (government/VAT style) | `transaction_tax_lines(transaction_id, seq, name, rate, amount)` |
| Fees (carrier/merchant) | `transaction_fee_lines(transaction_id, seq, name, amount)` - a separate table, never mixed with taxes |
| Flight | `flight_details(origin, destination, fare)` + `flight_coupons(seq, flight_number, carrier, from, to, departure, arrival)` |
| Hotel | `hotel_details(name, street, city, country, check_in, check_out, nights, room_rate_per_night)` + `hotel_extras` |
| Rail | `rail_legs(seq, origin, destination, departure, arrival, train_number, class)`; a change of train is two rows |
| Navan fee | `navan_fee_details(fee_kind, description, related_external_id)`; `fee_kind` is `trip_fee` or `agent_call_fee`, anything else is 400 |

`metadata` is a view of those tables: each `MetadataHandler` validates the shape on write, stores it in its
tables, and rebuilds the JSON on read, so GET returns coupons, the hotel address, rail legs and `fee_kind` and
never an empty blob. A flight cannot be stored as a hotel because the handler is chosen by `type`. Extra,
unrecognized metadata keys are kept in `transactions.metadata_extra` and returned unchanged.

Loading is batched: any number of transactions costs a fixed number of queries (header, tax, fee, one or two
per type), so lists have no N+1.

## Invoice snapshot

`invoices` + `invoice_transactions` (one row per included transaction: type, external_id, currency, occurred_at,
total, `metadata_json`) + `invoice_tax_lines` / `invoice_fee_lines` (copies of the transaction's lines) +
`invoice_tax_rollup` / `invoice_fee_rollup`. Content is **copied** when the invoice is built; `transaction_id`
is only a reference. Nothing on the invoice read path joins to `transactions`, so later edits cannot change an
invoice. Tax rollup groups by `(name, rate)`, fee rollup by `name`; the grand total is the sum of the lines.

Rules enforced:

- one currency per invoice (409), repeated id in one request (409), any missing id (404),
- `PATCH /transactions/{id}` and `DELETE /transactions/{id}` return 409 if the id is on any invoice
  (checked via `idx_invoice_txn_transaction`); the FK `invoice_transactions -> transactions` is
  `ON DELETE RESTRICT`, so the database refuses even if application code were wrong,
- `PATCH /invoices/{id}` deletes and rebuilds the snapshot from current data inside one DB transaction; any
  failure rolls back to the previous snapshot,
- `DELETE /invoices/{id}` cascades only through invoice tables.

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
   `LIMIT/OFFSET` (we fetch `limit+1` to compute `has_more`, no `COUNT(*)`). Children are loaded for the page
   only, in batched `IN (...)` queries. Nothing paginates in memory.
4. **Deep `OFFSET` scans.** `OFFSET` cost grows with the offset. The list order is `(created_at, id)` which has
   a covering index (`idx_transactions_created`, `idx_invoices_created`), so it is an index scan, not a sort. The
   next step at larger scale is keyset pagination on that same index.
5. **"Is this transaction invoiced?"** is on every PATCH/DELETE; `idx_invoice_txn_transaction` makes it an
   index probe instead of a table scan.

Indexes: `UNIQUE(transactions.external_id)`; `(created_at, id)` on `transactions` and `invoices`;
`invoice_transactions(transaction_id)` and `(invoice_id, transaction_id)` unique; `(transaction_id, seq)` on every
child table; `(invoice_id, seq)` on rollups. `SchemaIT` asserts these (and InnoDB, `DECIMAL(19,2)`) against
`information_schema`, so they cannot silently disappear.

## Tests

`./mvnw test` runs everything against a real MySQL started by Testcontainers (same image tag as
`docker-compose.yml`), through the real HTTP API: `TransactionApiIT`, `InvoiceApiIT`, `ConcurrencyIT`,
`PaginationIT`, `SchemaIT`, plus pure unit tests for the snapshot builder and money scaling.

## Delivery

`Dockerfile` (multi-stage JDK 25 build, JRE 25 runtime), `docker-compose.yml` (MySQL with healthcheck, app waits
for it), GitHub Actions `ci.yml` (verify on every push/PR, publish to GHCR from `main` and tags).
