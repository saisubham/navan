# Navan travel transactions and invoices

Spring Boot 4 / Java 25 / MySQL 26.10.0. One create API for flight, hotel, rail and Navan-fee
transactions, duplicate-safe on `external_id`, frozen invoice snapshots, paginated lists.
Design notes: [ARCHITECTURE.md](ARCHITECTURE.md).

## Run (one command)

```bash
docker compose up --build        # MySQL 26.10.0 + the API on http://localhost:8080
```

Credentials in `docker-compose.yml` are dev placeholders (override with a gitignored `.env`, see
`.env.example`). No API keys are needed; nothing calls an LLM or any external service.

Stop with `docker compose down` (add `-v` to also drop the database volume).
The schema is created with `CREATE TABLE IF NOT EXISTS`; if you have a database volume from an older version of
this project, run `docker compose down -v` once so the current schema is created.

## Run the tests (one command)

```bash
./mvnw test
```

Needs Docker running: the integration tests start a real `mysql:26.10.0` through Testcontainers and
call the real HTTP API on a random port (38 tests: transactions, invoices, concurrency, schema, pagination).

## Requirements and caveats

- **Docker is required**, both to run the app (`docker compose`) and for the tests (Testcontainers starts MySQL).
  There is no embedded-database fallback.
- **Recent versions**: Java 25, Spring Boot 4.1.2 and the `mysql:26.10.0` image (pinned in `docker-compose.yml` and in
  the tests). Use a JDK 25 for `./mvnw` outside Docker; the Docker build brings its own.
- **Credentials** (`navan` / `root` defaults) are local development placeholders only; override them with a
  gitignored `.env`.

## CI / image

`.github/workflows/ci.yml` runs `./mvnw verify` on every push and PR, and on pushes to `main` / `v*` tags
publishes the image to GitHub Container Registry: `ghcr.io/<owner>/<repo>` (tags: branch, semver, sha, `latest`).

```bash
docker run --rm -p 8080:8080 -e SPRING_DATASOURCE_URL=jdbc:mysql://host:3306/navan \
  -e SPRING_DATASOURCE_USERNAME=navan -e SPRING_DATASOURCE_PASSWORD=navan ghcr.io/<owner>/<repo>:latest
```

## Conventions

| Topic | Decision |
|---|---|
| Money | `DECIMAL(19,2)` in MySQL, `BigDecimal` scale 2 HALF_UP in Java, JSON numbers like `220.00`. Tax `rate` is `DECIMAL(7,4)`. Never `double`. |
| Duplicate `external_id` | **409 Conflict** with `{"error":"duplicate_external_id","id":"<existing id>"}`. Exactly one row is stored. |
| Same transaction twice on one invoice | **409** (`duplicate_transaction_id`). Never double counted. |
| Mixed currencies on an invoice | **409** (`currency_mismatch`). |
| Pagination | `limit` (default 50, **max 100; larger returns 400**) and `offset`. Response: `{items, limit, offset, has_more}`. |
| Timestamps | ISO-8601, stored and returned in UTC. |
| Errors | `{"error": "<code>", "message": "..."}` with 400 / 404 / 409. |

## Example curls

A longer walkthrough with every scenario from the brief plus corner cases, as plain copy-paste curl commands, is in
[CURL-TESTS.md](CURL-TESTS.md). The assignment text is in
[docs/task-b-travel-inventory-invoice.md](docs/task-b-travel-inventory-invoice.md).

```bash
B=http://localhost:8080
H='Content-Type: application/json'
```

### Create (each type)

```bash
curl -s -XPOST $B/transactions -H "$H" -d '{
  "type":"flight","external_id":"flight-TXL-LHR-2026-03-11","currency":"EUR",
  "occurred_at":"2026-03-11T06:40:00Z","total":220.00,
  "tax_lines":[{"name":"Airport tax","amount":15.00}],
  "fee_lines":[{"name":"YQ","amount":25.00}],
  "metadata":{"origin":"TXL","destination":"LHR","fare":180.00,"coupons":[
    {"carrier":"BA","flight_number":"BA982","from":"TXL","to":"LHR",
     "departure":"2026-03-11T07:00:00Z","arrival":"2026-03-11T08:20:00Z"}]}}'

curl -s -XPOST $B/transactions -H "$H" -d '{
  "type":"hotel","external_id":"hotel-LON-2026-03-11","currency":"EUR",
  "occurred_at":"2026-03-11T15:00:00Z","total":331.70,
  "tax_lines":[{"name":"VAT","rate":0.07,"amount":21.70}],"fee_lines":[],
  "metadata":{"name":"Hotel Mitte London",
    "address":{"street":"12 Kingsway","city":"London","country":"GB"},
    "check_in":"2026-03-11","check_out":"2026-03-13","nights":2,"room_rate_per_night":140.00,
    "extras":[{"name":"Breakfast","amount":30.00}]}}'

curl -s -XPOST $B/transactions -H "$H" -d '{
  "type":"rail","external_id":"rail-STP-PAR-2026-03-13","currency":"EUR",
  "occurred_at":"2026-03-13T09:00:00Z","total":94.00,"tax_lines":[],
  "fee_lines":[{"name":"Booking fee","amount":4.00}],
  "metadata":{"legs":[
    {"origin":"London St Pancras","destination":"Lille Europe","departure":"2026-03-13T09:00:00Z",
     "arrival":"2026-03-13T10:30:00Z","train_number":"EST9108","class":"standard"},
    {"origin":"Lille Europe","destination":"Paris Nord","departure":"2026-03-13T11:00:00Z",
     "arrival":"2026-03-13T12:05:00Z","train_number":"EST9122","class":"standard"}]}}'

curl -s -XPOST $B/transactions -H "$H" -d '{
  "type":"navan_fee","external_id":"navan-trip-fee-2026-03-11","currency":"EUR",
  "occurred_at":"2026-03-11T06:40:00Z","total":25.00,"tax_lines":[],"fee_lines":[],
  "metadata":{"fee_kind":"trip_fee","description":"Navan trip fee",
              "related_external_id":"flight-TXL-LHR-2026-03-11"}}'

curl -s -XPOST $B/transactions -H "$H" -d '{
  "type":"navan_fee","external_id":"navan-agent-call-2026-03-11","currency":"EUR",
  "occurred_at":"2026-03-11T18:00:00Z","total":15.00,"tax_lines":[],"fee_lines":[],
  "metadata":{"fee_kind":"agent_call_fee","description":"Agent fee for support call",
              "related_external_id":"hotel-LON-2026-03-11"}}'
```

The full set of bodies is in `src/test/resources/fixtures/seed-payloads.json`. To load them all with `jq`:

```bash
S=src/test/resources/fixtures/seed-payloads.json
for k in flight hotel rail trip_fee agent_call_fee; do
  curl -s -XPOST $B/transactions -H "$H" -d "$(jq -c ".$k" $S)" | jq -r '"\(.type) \(.id)"'
done
```

### Duplicate create: same `external_id` twice, one row

```bash
FLIGHT=$(jq -c .flight $S)
curl -s -o /dev/null -w 'first:  %{http_code}\n' -XPOST $B/transactions -H "$H" -d "$FLIGHT"   # 201
curl -s -w '\nsecond: %{http_code}\n'           -XPOST $B/transactions -H "$H" -d "$FLIGHT"   # 409 + existing id
curl -s "$B/transactions?limit=100" | jq '[.items[] | select(.external_id=="flight-TXL-LHR-2026-03-11")] | length'   # 1
```

### Get / list transactions

```bash
curl -s $B/transactions/<id>
curl -s "$B/transactions?limit=20&offset=0"     # limit > 100 -> 400
```

### Patch / delete a transaction

```bash
curl -s -XPATCH $B/transactions/<id> -H "$H" -d '{"total":230.00,"fee_lines":[{"name":"YQ","amount":35.00}]}'
curl -s -XDELETE -w '%{http_code}\n' $B/transactions/<id>      # 204
# once the transaction is on any invoice, both return 409 and change nothing:
curl -s -XPATCH  $B/transactions/<invoiced id> -H "$H" -d '{"total":1.00}'
curl -s -XDELETE $B/transactions/<invoiced id>
```

### Invoices

```bash
# one transaction
curl -s -XPOST $B/invoices -H "$H" -d '{"transaction_id":"<id>"}'

# a whole trip: flight + hotel + rail + both Navan fees (grand total 685.70 EUR)
curl -s -XPOST $B/invoices -H "$H" \
  -d '{"transaction_ids":["<flight>","<hotel>","<rail>","<trip_fee>","<agent_call_fee>"]}'

curl -s $B/invoices/<invoice id>                 # snapshot lines + tax_rollup + fee_rollup + total
curl -s "$B/invoices?limit=20&offset=0"          # paginated summaries

# replace the set of transactions: the snapshot is rebuilt from CURRENT data, atomically
curl -s -XPATCH $B/invoices/<invoice id> -H "$H" -d '{"transaction_ids":["<flight>","<rail>"]}'

curl -s -XDELETE -w '%{http_code}\n' $B/invoices/<invoice id>   # 204; transactions are untouched
```

`GET /health` returns `{"status":"UP"}`.

## Status codes

| Case | Status |
|---|---|
| Create transaction / invoice | 201 |
| Unknown `type` or `metadata.fee_kind`, missing/invalid field, `limit` outside 1..100 | 400 |
| Unknown transaction or invoice id (including ids inside an invoice request) | 404 |
| Duplicate `external_id`, PATCH/DELETE of an invoiced transaction, mixed currency, repeated id on an invoice | 409 |
| Delete | 204 |
