# Manual API tests (curl)

Plain curl commands for the requirements in
[`task-b-travel-inventory-invoice-CANDIDATE.md`](docs/task-b-travel-inventory-invoice.md)
plus corner cases. Each command is complete: copy it, run it, compare with **Expect**.

- App: `http://localhost:8080` (`docker compose up --build`).
- Start from an empty database, otherwise the fixed `external_id`s already exist and creates return 409:
  `docker compose down -v && docker compose up --build`.
- `-i` prints the HTTP status line and headers. Add `| tail -n +1` or pipe the body to `jq` if you like.
- Ids come back in responses (`txn_...`, `inv_...`). Where a command contains `<FLIGHT_ID>`, `<INVOICE_ID>` and so on,
  replace it with the id you got from the earlier step.

## 0. Health

```bash
curl -i http://localhost:8080/health
```

Expect `200` and `{"status":"UP"}`.

## 1. Create each type

### 1.1 Flight

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"flight-TXL-LHR-2026-03-11","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":220.0,"tax_lines":[{"name":"Airport tax","amount":15.0}],"fee_lines":[{"name":"YQ","amount":25.0}],"metadata":{"origin":"TXL","destination":"LHR","fare":180.0,"coupons":[{"carrier":"BA","flight_number":"BA982","from":"TXL","to":"LHR","departure":"2026-03-11T07:00:00Z","arrival":"2026-03-11T08:20:00Z"}]}}'
```

Expect `201`, an `id` (`<FLIGHT_ID>`), `total` `220.00`, `tax_lines` with Airport tax 15.00, `fee_lines` with YQ 25.00
(taxes and fees are separate lists), and `metadata.coupons[0].flight_number` = `BA982`.

### 1.2 Hotel

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"hotel","external_id":"hotel-LON-2026-03-11","currency":"EUR","occurred_at":"2026-03-11T15:00:00Z","total":331.7,"tax_lines":[{"name":"VAT","rate":0.07,"amount":21.7}],"fee_lines":[],"metadata":{"name":"Hotel Mitte London","address":{"street":"12 Kingsway","city":"London","country":"GB"},"check_in":"2026-03-11","check_out":"2026-03-13","nights":2,"room_rate_per_night":140.0,"extras":[{"name":"Breakfast","amount":30.0}]}}'
```

Expect `201` (`<HOTEL_ID>`), `metadata.name` and `metadata.address` (street, city, country) present.

### 1.3 Rail (two legs)

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"rail","external_id":"rail-STP-PAR-2026-03-13","currency":"EUR","occurred_at":"2026-03-13T09:00:00Z","total":94.0,"tax_lines":[],"fee_lines":[{"name":"Booking fee","amount":4.0}],"metadata":{"legs":[{"origin":"London St Pancras","destination":"Lille Europe","departure":"2026-03-13T09:00:00Z","arrival":"2026-03-13T10:30:00Z","train_number":"EST9108","class":"standard"},{"origin":"Lille Europe","destination":"Paris Nord","departure":"2026-03-13T11:00:00Z","arrival":"2026-03-13T12:05:00Z","train_number":"EST9122","class":"standard"}]}}'
```

Expect `201` (`<RAIL_ID>`) and `metadata.legs` with 2 entries.

### 1.4 Navan fee: `trip_fee`

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"navan_fee","external_id":"navan-trip-fee-2026-03-11","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":25.0,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"trip_fee","description":"Navan trip fee","related_external_id":"flight-TXL-LHR-2026-03-11"}}'
```

Expect `201` (`<TRIP_FEE_ID>`) and `metadata.fee_kind` = `trip_fee`.

### 1.5 Navan fee: `agent_call_fee`

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"navan_fee","external_id":"navan-agent-call-2026-03-11","currency":"EUR","occurred_at":"2026-03-11T18:00:00Z","total":15.0,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"agent_call_fee","description":"Agent fee for support call","related_external_id":"hotel-LON-2026-03-11"}}'
```

Expect `201` (`<AGENT_FEE_ID>`) and `metadata.fee_kind` = `agent_call_fee`.

### 1.6 Read back (nothing dropped)

```bash
curl -i http://localhost:8080/transactions/<FLIGHT_ID>
curl -i http://localhost:8080/transactions/<HOTEL_ID>
curl -i http://localhost:8080/transactions/<RAIL_ID>
curl -i http://localhost:8080/transactions/<TRIP_FEE_ID>
curl -i http://localhost:8080/transactions/<AGENT_FEE_ID>
```

Expect `200` with the same metadata you posted (coupons, hotel address, both rail legs, `fee_kind`).

### 1.7 Unknown id

```bash
curl -i http://localhost:8080/transactions/txn_does_not_exist
```

Expect `404`.

## 2. Duplicates

### 2.1 Same `external_id` again (identical body)

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"flight-TXL-LHR-2026-03-11","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":220.0,"tax_lines":[{"name":"Airport tax","amount":15.0}],"fee_lines":[{"name":"YQ","amount":25.0}],"metadata":{"origin":"TXL","destination":"LHR","fare":180.0,"coupons":[{"carrier":"BA","flight_number":"BA982","from":"TXL","to":"LHR","departure":"2026-03-11T07:00:00Z","arrival":"2026-03-11T08:20:00Z"}]}}'
```

Expect `409`, `{"error":"duplicate_external_id","id":"<FLIGHT_ID>"}` (the id of the first one).

### 2.2 Same `external_id`, different body (the fixture's `duplicate_flight`)

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"flight-TXL-LHR-2026-03-11","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":220.0,"tax_lines":[{"name":"Airport tax","amount":15.0}],"fee_lines":[{"name":"YQ","amount":25.0}],"metadata":{"origin":"TXL","destination":"LHR","fare":180.0,"coupons":[]}}'
```

Expect `409` again, same id.

### 2.3 Only one row exists

```bash
curl -s 'http://localhost:8080/transactions?limit=100' | jq '[.items[] | select(.external_id=="flight-TXL-LHR-2026-03-11")] | length'
```

Expect `1`.

### 2.4 Concurrent duplicates

Run this one command (eight parallel POSTs with a new `external_id`):

```bash
for i in 1 2 3 4 5 6 7 8; do curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' -d '{"type":"flight","external_id":"race-1","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":220.0,"tax_lines":[],"fee_lines":[],"metadata":{"origin":"TXL","destination":"LHR","fare":180.0,"coupons":[]}}' & done; wait
```

Expect exactly one `201` and seven `409` (any order). Then:

```bash
curl -s 'http://localhost:8080/transactions?limit=100' | jq '[.items[] | select(.external_id=="race-1")] | length'
```

Expect `1`.

## 3. Validation (all expect `400`, nothing stored)

Each command is a valid flight (or fee) body with exactly one thing wrong.

### 3.1 Unknown `type`

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"cruise","external_id":"v-1","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":10.0,"tax_lines":[],"fee_lines":[],"metadata":{"x":1}}'
```

### 3.2 Unknown `fee_kind`

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"navan_fee","external_id":"v-2","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":25.0,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"coffee_fee","description":"x"}}'
```

### 3.3 Empty metadata

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"hotel","external_id":"v-3","currency":"EUR","occurred_at":"2026-03-11T15:00:00Z","total":100.0,"tax_lines":[],"fee_lines":[],"metadata":{}}'
```

### 3.4 Missing metadata

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"v-4","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":100.0,"tax_lines":[],"fee_lines":[]}'
```

### 3.5 Hotel without `address`

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"hotel","external_id":"v-5","currency":"EUR","occurred_at":"2026-03-11T15:00:00Z","total":100.0,"tax_lines":[],"fee_lines":[],"metadata":{"name":"Hotel X","check_in":"2026-03-11","check_out":"2026-03-13","nights":2,"room_rate_per_night":50.0}}'
```

### 3.6 Flight without `fare`

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"v-6","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":100.0,"tax_lines":[],"fee_lines":[],"metadata":{"origin":"TXL","destination":"LHR","coupons":[]}}'
```

### 3.7 Rail with no legs

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"rail","external_id":"v-7","currency":"EUR","occurred_at":"2026-03-13T09:00:00Z","total":94.0,"tax_lines":[],"fee_lines":[],"metadata":{"legs":[]}}'
```

### 3.8 Missing `tax_lines`

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"v-8","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":100.0,"fee_lines":[],"metadata":{"origin":"TXL","destination":"LHR","fare":80.0,"coupons":[]}}'
```

### 3.9 Missing `fee_lines`

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"v-9","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":100.0,"tax_lines":[],"metadata":{"origin":"TXL","destination":"LHR","fare":80.0,"coupons":[]}}'
```

### 3.10 Negative total

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"v-10","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":-1,"tax_lines":[],"fee_lines":[],"metadata":{"origin":"TXL","destination":"LHR","fare":80.0,"coupons":[]}}'
```

### 3.11 Negative fee amount

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"v-11","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":100.0,"tax_lines":[],"fee_lines":[{"name":"YQ","amount":-5}],"metadata":{"origin":"TXL","destination":"LHR","fare":80.0,"coupons":[]}}'
```

### 3.12 Lower-case currency

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"v-12","currency":"eur","occurred_at":"2026-03-11T06:40:00Z","total":100.0,"tax_lines":[],"fee_lines":[],"metadata":{"origin":"TXL","destination":"LHR","fare":80.0,"coupons":[]}}'
```

### 3.13 Bad `occurred_at`

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"v-13","currency":"EUR","occurred_at":"yesterday","total":100.0,"tax_lines":[],"fee_lines":[],"metadata":{"origin":"TXL","destination":"LHR","fare":80.0,"coupons":[]}}'
```

### 3.14 Missing `external_id`

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"flight","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":100.0,"tax_lines":[],"fee_lines":[],"metadata":{"origin":"TXL","destination":"LHR","fare":80.0,"coupons":[]}}'
```

### 3.15 Malformed JSON

```bash
curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' -d '{"type": '
```

### 3.16 Nothing was stored by any of the above

```bash
curl -s 'http://localhost:8080/transactions?limit=100' | jq '[.items[] | select(.external_id | startswith("v-"))] | length'
```

Expect `0`.

## 4. Invoices

### 4.1 One transaction

```bash
curl -i -X POST http://localhost:8080/invoices \
  -H 'Content-Type: application/json' \
  -d '{"transaction_id":"<FLIGHT_ID>"}'
```

Expect `201`, `total` `220.00`, one entry in `transactions` (`<INVOICE_1_ID>`).

### 4.2 Whole trip: flight + hotel + rail + both Navan fees

The flight is already on invoice 4.1, which is fine: a transaction may be on several invoices.

```bash
curl -i -X POST http://localhost:8080/invoices \
  -H 'Content-Type: application/json' \
  -d '{"transaction_ids":["<FLIGHT_ID>","<HOTEL_ID>","<RAIL_ID>","<TRIP_FEE_ID>","<AGENT_FEE_ID>"]}'
```

Expect `201` (`<INVOICE_ID>`) matching `src/test/resources/fixtures/expected-invoice.json`:

- `currency` `EUR`, `total` `685.70`
- lines: flight 220.00, hotel 331.70, rail 94.00, trip_fee 25.00, agent_call_fee 15.00
- `tax_rollup`: Airport tax 15.00, VAT (rate 0.07) 21.70
- `fee_rollup`: YQ 25.00, Booking fee 4.00
- each line carries its metadata (coupons, hotel name and address, legs, `fee_kind`)

```bash
curl -i http://localhost:8080/invoices/<INVOICE_ID>
```

Expect the same body again with `200`.

### 4.3 Snapshot stays frozen

The API refuses to edit a billed transaction (see 5.4), so change the stored row directly in MySQL:

```bash
docker compose exec -T mysql mysql -unavan -pnavan navan -e "UPDATE transactions SET total = 999.99 WHERE id = '<FLIGHT_ID>'"
```

```bash
curl -i http://localhost:8080/transactions/<FLIGHT_ID>
curl -i http://localhost:8080/invoices/<INVOICE_ID>
```

Expect the transaction to show `999.99`, while the invoice still shows the flight line at `220.00` and `total`
`685.70`. Restore the row:

```bash
docker compose exec -T mysql mysql -unavan -pnavan navan -e "UPDATE transactions SET total = 220.00 WHERE id = '<FLIGHT_ID>'"
```

### 4.4 Mixed currencies

Create a USD hotel:

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"hotel","external_id":"hotel-usd-1","currency":"USD","occurred_at":"2026-03-11T15:00:00Z","total":331.7,"tax_lines":[],"fee_lines":[],"metadata":{"name":"Hotel NYC","address":{"street":"1 Main St","city":"New York","country":"US"},"check_in":"2026-03-11","check_out":"2026-03-13","nights":2,"room_rate_per_night":140.0}}'
```

(`<USD_HOTEL_ID>`) then invoice it together with the EUR rail:

```bash
curl -i -X POST http://localhost:8080/invoices \
  -H 'Content-Type: application/json' \
  -d '{"transaction_ids":["<RAIL_ID>","<USD_HOTEL_ID>"]}'
```

Expect `409`, `"error":"currency_mismatch"`, and no invoice created.

### 4.5 Same id twice on one invoice

```bash
curl -i -X POST http://localhost:8080/invoices \
  -H 'Content-Type: application/json' \
  -d '{"transaction_ids":["<RAIL_ID>","<RAIL_ID>"]}'
```

Expect `409`, `"error":"duplicate_transaction_id"` (never double counted).

### 4.6 Missing id

```bash
curl -i -X POST http://localhost:8080/invoices \
  -H 'Content-Type: application/json' \
  -d '{"transaction_ids":["<RAIL_ID>","txn_missing"]}'
```

```bash
curl -i -X POST http://localhost:8080/invoices \
  -H 'Content-Type: application/json' \
  -d '{"transaction_id":"txn_missing"}'
```

Expect `404` for both and no invoice created.

### 4.7 Bad bodies

```bash
curl -i -X POST http://localhost:8080/invoices -H 'Content-Type: application/json' -d '{}'
curl -i -X POST http://localhost:8080/invoices -H 'Content-Type: application/json' -d '{"transaction_id":"<RAIL_ID>","transaction_ids":["<HOTEL_ID>"]}'
curl -i -X POST http://localhost:8080/invoices -H 'Content-Type: application/json' -d '{"transaction_ids":[]}'
```

Expect `400` for each (id required, not both, empty list).

### 4.8 Unknown invoice

```bash
curl -i http://localhost:8080/invoices/inv_does_not_exist
```

Expect `404`.

## 5. Update and delete

### 5.1 PATCH a transaction that is on no invoice

Create one:

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"flight","external_id":"flight-free-1","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":220.0,"tax_lines":[{"name":"Airport tax","amount":15.0}],"fee_lines":[{"name":"YQ","amount":25.0}],"metadata":{"origin":"TXL","destination":"LHR","fare":180.0,"coupons":[]}}'
```

(`<FREE_ID>`), then change the total and replace the fee list:

```bash
curl -i -X PATCH http://localhost:8080/transactions/<FREE_ID> \
  -H 'Content-Type: application/json' \
  -d '{"total":230.50,"fee_lines":[{"name":"YQ","amount":35.50}]}'
```

Expect `200`, `total` `230.50`, `fee_lines` = YQ 35.50, `tax_lines` unchanged.

### 5.2 PATCH errors

```bash
curl -i -X PATCH http://localhost:8080/transactions/txn_does_not_exist -H 'Content-Type: application/json' -d '{"total":1}'
curl -i -X PATCH http://localhost:8080/transactions/<FREE_ID> -H 'Content-Type: application/json' -d '{}'
curl -i -X PATCH http://localhost:8080/transactions/<FREE_ID> -H 'Content-Type: application/json' -d '{"currency":"USD"}'
curl -i -X PATCH http://localhost:8080/transactions/<FREE_ID> -H 'Content-Type: application/json' -d '{"total":-3}'
```

Expect `404`, `400` (nothing to update), `400` (currency cannot change), `400`.

### 5.3 DELETE a transaction that is on no invoice

```bash
curl -i -X DELETE http://localhost:8080/transactions/<FREE_ID>
curl -i http://localhost:8080/transactions/<FREE_ID>
curl -i -X DELETE http://localhost:8080/transactions/<FREE_ID>
```

Expect `204`, then `404`, then `404`.

### 5.4 PATCH and DELETE a transaction that is on an invoice

```bash
curl -i -X PATCH http://localhost:8080/transactions/<FLIGHT_ID> \
  -H 'Content-Type: application/json' \
  -d '{"total":1.00}'
```

```bash
curl -i -X DELETE http://localhost:8080/transactions/<HOTEL_ID>
```

Expect `409` for both, `"error":"transaction_invoiced"`. Check nothing changed:

```bash
curl -i http://localhost:8080/transactions/<HOTEL_ID>
curl -i http://localhost:8080/invoices/<INVOICE_ID>
```

Expect the hotel still present (`200`) and the invoice total still `685.70`.

### 5.5 PATCH an invoice (replace the set; rebuilt from current data)

```bash
curl -i -X PATCH http://localhost:8080/invoices/<INVOICE_ID> \
  -H 'Content-Type: application/json' \
  -d '{"transaction_ids":["<RAIL_ID>","<TRIP_FEE_ID>"]}'
```

Expect `200`, two lines, `total` `119.00` (94.00 + 25.00). Failures leave it untouched:

```bash
curl -i -X PATCH http://localhost:8080/invoices/<INVOICE_ID> -H 'Content-Type: application/json' -d '{"transaction_ids":["<RAIL_ID>","<USD_HOTEL_ID>"]}'
curl -i -X PATCH http://localhost:8080/invoices/<INVOICE_ID> -H 'Content-Type: application/json' -d '{"transaction_ids":["<RAIL_ID>","txn_missing"]}'
curl -i -X PATCH http://localhost:8080/invoices/inv_does_not_exist -H 'Content-Type: application/json' -d '{"transaction_id":"<RAIL_ID>"}'
curl -i http://localhost:8080/invoices/<INVOICE_ID>
```

Expect `409` (mixed currency), `404`, `404`, and the last GET still showing `119.00` with two lines.

The hotel is no longer on that invoice and is not on invoice 4.1, so it can now be deleted:

```bash
curl -i -X DELETE http://localhost:8080/transactions/<HOTEL_ID>
```

Expect `204`.

### 5.6 DELETE an invoice (transactions survive)

```bash
curl -i -X DELETE http://localhost:8080/invoices/<INVOICE_ID>
curl -i http://localhost:8080/invoices/<INVOICE_ID>
curl -i -X DELETE http://localhost:8080/invoices/<INVOICE_ID>
curl -i http://localhost:8080/transactions/<RAIL_ID>
curl -i http://localhost:8080/transactions/<TRIP_FEE_ID>
```

Expect `204`, `404`, `404`, `200`, `200`.

## 6. Lists are bounded

### 6.1 Default page

```bash
curl -i http://localhost:8080/transactions
```

Expect `200`, `"limit":50`, `"offset":0`, `items`, `has_more`.

### 6.2 Limit too large or invalid

```bash
curl -i 'http://localhost:8080/transactions?limit=1000'
curl -i 'http://localhost:8080/transactions?limit=0'
curl -i 'http://localhost:8080/transactions?offset=-1'
curl -i 'http://localhost:8080/invoices?limit=1000'
```

Expect `400` for each (maximum limit is 100).

### 6.3 Paging

```bash
curl -i 'http://localhost:8080/transactions?limit=2&offset=0'
curl -i 'http://localhost:8080/transactions?limit=2&offset=2'
```

Expect 2 items per page with different ids, and `has_more` `true` while more rows remain.

### 6.4 Invoice list

```bash
curl -i 'http://localhost:8080/invoices?limit=2'
```

Expect `200` with summaries (`id`, `currency`, `total`, `transaction_count`, `created_at`), not full snapshots.

## Status reference

| Case | Status |
|---|---|
| Create transaction or invoice | 201 |
| Delete transaction or invoice | 204 |
| Unknown `type` / `fee_kind`, missing or invalid field, bad `limit` | 400 |
| Unknown transaction or invoice id (also ids inside an invoice request) | 404 |
| Duplicate `external_id`, PATCH/DELETE of an invoiced transaction, mixed currency, repeated id on an invoice | 409 |
