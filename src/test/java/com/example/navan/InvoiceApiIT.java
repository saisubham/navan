package com.example.navan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.navan.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class InvoiceApiIT extends IntegrationTestBase {

	private record Trip(String flight, String hotel, String rail, String tripFee, String agentFee) {
		List<String> ids() {
			return List.of(flight, hotel, rail, tripFee, agentFee);
		}
	}

	private Trip createTrip() {
		return new Trip(createSeed("flight"), createSeed("hotel"), createSeed("rail"), createSeed("trip_fee"),
				createSeed("agent_call_fee"));
	}

	private Resp invoiceFor(List<String> ids) {
		return post("/invoices", JSON.createObjectNode().set("transaction_ids", JSON.valueToTree(ids)));
	}

	private static JsonNode line(JsonNode invoice, String transactionId) {
		for (JsonNode l : invoice.get("transactions")) {
			if (l.get("transaction_id").asString().equals(transactionId)) {
				return l;
			}
		}
		throw new AssertionError("no line for " + transactionId);
	}

	@Test
	void tripInvoiceMatchesTheExpectedFixture() {
		// 3. flight + hotel + rail + both navan fees
		JsonNode expected = fixture("expected-invoice.json");
		Trip trip = createTrip();
		Resp created = invoiceFor(trip.ids());
		assertThat(created.status()).isEqualTo(201);

		JsonNode invoice = get("/invoices/" + created.body().get("id").asString()).body();
		assertThat(invoice.get("currency").asString()).isEqualTo("EUR");
		assertThat(invoice.get("total").decimalValue())
				.isEqualByComparingTo(expected.get("totals").get("grand_total").decimalValue())
				.isEqualByComparingTo("685.70");
		assertThat(invoice.get("transactions").size()).isGreaterThanOrEqualTo(expected.get("line_count_minimum").intValue());

		JsonNode totals = expected.get("totals");
		assertThat(line(invoice, trip.flight).get("total").decimalValue()).isEqualByComparingTo(totals.get("flight").decimalValue());
		assertThat(line(invoice, trip.hotel).get("total").decimalValue()).isEqualByComparingTo(totals.get("hotel").decimalValue());
		assertThat(line(invoice, trip.rail).get("total").decimalValue()).isEqualByComparingTo(totals.get("rail").decimalValue());
		assertThat(line(invoice, trip.tripFee).get("total").decimalValue()).isEqualByComparingTo(totals.get("trip_fee").decimalValue());
		assertThat(line(invoice, trip.agentFee).get("total").decimalValue()).isEqualByComparingTo(totals.get("agent_call_fee").decimalValue());

		// snapshot carries the typed metadata too
		assertThat(line(invoice, trip.flight).get("metadata").get("coupons").get(0).get("flight_number").asString()).isEqualTo("BA982");
		assertThat(line(invoice, trip.hotel).get("metadata").get("address").get("city").asString()).isEqualTo("London");
		assertThat(line(invoice, trip.rail).get("metadata").get("legs")).hasSize(2);
		assertThat(line(invoice, trip.agentFee).get("metadata").get("fee_kind").asString()).isEqualTo("agent_call_fee");
		assertThat(line(invoice, trip.flight).get("metadata").get("fare").decimalValue()).isEqualByComparingTo("180.00");

		// rollups: separate tax and fee lists, matching the fixture
		JsonNode taxRollup = invoice.get("tax_rollup");
		assertThat(taxRollup).hasSize(expected.get("tax_rollup_example").size());
		for (JsonNode e : expected.get("tax_rollup_example")) {
			JsonNode actual = find(taxRollup, e.get("name").asString());
			assertThat(actual.get("amount").decimalValue()).isEqualByComparingTo(e.get("amount").decimalValue());
			if (e.has("rate")) {
				assertThat(actual.get("rate").decimalValue()).isEqualByComparingTo(e.get("rate").decimalValue());
			}
		}
		JsonNode feeRollup = invoice.get("fee_rollup");
		assertThat(feeRollup).hasSize(expected.get("fee_rollup_example").size());
		for (JsonNode e : expected.get("fee_rollup_example")) {
			assertThat(find(feeRollup, e.get("name").asString()).get("amount").decimalValue())
					.isEqualByComparingTo(e.get("amount").decimalValue());
		}
	}

	private static JsonNode find(JsonNode array, String name) {
		for (JsonNode n : array) {
			if (n.get("name").asString().equals(name)) {
				return n;
			}
		}
		throw new AssertionError("no rollup entry " + name);
	}

	@Test
	void singleTransactionIdIsSupported() {
		String flight = createSeed("flight");
		Resp r = post("/invoices", JSON.createObjectNode().put("transaction_id", flight));
		assertThat(r.status()).isEqualTo(201);
		assertThat(r.body().get("total").decimalValue()).isEqualByComparingTo("220.00");
		assertThat(r.body().get("transactions")).hasSize(1);
	}

	@Test
	void invoiceIsAFrozenSnapshot() {
		// 4. change the stored flight total behind the API's back; the invoice must not move
		Trip trip = createTrip();
		String invoiceId = invoiceFor(trip.ids()).body().get("id").asString();

		jdbc.sql("UPDATE transactions SET total = 999.99 WHERE id = ?").param(trip.flight).update();
		jdbc.sql("DELETE FROM transaction_fee_lines WHERE transaction_id = ?").param(trip.flight).update();
		jdbc.sql("UPDATE flight_coupons SET flight_number = 'XX1' WHERE transaction_id = ?").param(trip.flight).update();
		assertThat(get("/transactions/" + trip.flight).body().get("total").decimalValue()).isEqualByComparingTo("999.99");

		JsonNode invoice = get("/invoices/" + invoiceId).body();
		assertThat(line(invoice, trip.flight).get("total").decimalValue()).isEqualByComparingTo("220.00");
		assertThat(line(invoice, trip.flight).get("fee_lines")).hasSize(1);
		assertThat(line(invoice, trip.flight).get("metadata").get("coupons").get(0).get("flight_number").asString())
				.isEqualTo("BA982");
		assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo("685.70");
		assertThat(find(invoice.get("fee_rollup"), "YQ").get("amount").decimalValue()).isEqualByComparingTo("25.00");
	}

	@Test
	void mixedCurrenciesAreRejected() {
		// 5. EUR + USD
		String eur = createSeed("flight");
		ObjectNode usd = seed("hotel");
		usd.put("currency", "USD");
		String usdId = post("/transactions", usd).body().get("id").asString();

		Resp r = invoiceFor(List.of(eur, usdId));
		assertThat(r.status()).isEqualTo(409);
		assertThat(r.body().get("error").asString()).isEqualTo("currency_mismatch");
		assertThat(count("invoices")).isZero();
	}

	@Test
	void missingTransactionIs404AndSameIdTwiceIs409() {
		String flight = createSeed("flight");
		assertThat(invoiceFor(List.of(flight, "txn_missing")).status()).isEqualTo(404);
		Resp twice = invoiceFor(List.of(flight, flight));
		assertThat(twice.status()).isEqualTo(409);
		assertThat(twice.body().get("error").asString()).isEqualTo("duplicate_transaction_id");
		assertThat(post("/invoices", JSON.createObjectNode()).status()).isEqualTo(400);
		assertThat(count("invoices")).isZero();
	}

	@Test
	void invoicedTransactionCannotBePatched() {
		// 7. 409, nothing persisted, invoice unchanged
		Trip trip = createTrip();
		String invoiceId = invoiceFor(trip.ids()).body().get("id").asString();

		Resp r = patch("/transactions/" + trip.flight, JSON.createObjectNode().put("total", "300.00"));
		assertThat(r.status()).isEqualTo(409);
		assertThat(get("/transactions/" + trip.flight).body().get("total").decimalValue()).isEqualByComparingTo("220.00");
		assertThat(get("/invoices/" + invoiceId).body().get("total").decimalValue()).isEqualByComparingTo("685.70");
	}

	@Test
	void invoicedTransactionCannotBeDeleted() {
		// 8. 409, row still exists
		Trip trip = createTrip();
		invoiceFor(trip.ids());
		assertThat(delete("/transactions/" + trip.hotel).status()).isEqualTo(409);
		assertThat(get("/transactions/" + trip.hotel).status()).isEqualTo(200);
		assertThat(count("transactions")).isEqualTo(5);
	}

	@Test
	void deletingAnInvoiceKeepsTheTransactions() {
		// 9.
		Trip trip = createTrip();
		String invoiceId = invoiceFor(trip.ids()).body().get("id").asString();
		assertThat(delete("/invoices/" + invoiceId).status()).isEqualTo(204);
		assertThat(get("/invoices/" + invoiceId).status()).isEqualTo(404);
		for (String id : trip.ids()) {
			assertThat(get("/transactions/" + id).status()).isEqualTo(200);
		}
		assertThat(count("invoice_transactions")).isZero();
		assertThat(count("invoice_tax_rollup")).isZero();
		assertThat(delete("/invoices/" + invoiceId).status()).isEqualTo(404);
		// and the transactions are billable-free again
		assertThat(delete("/transactions/" + trip.hotel).status()).isEqualTo(204);
	}

	@Test
	void patchingAnInvoiceRebuildsTheSnapshotFromCurrentData() {
		Trip trip = createTrip();
		String invoiceId = invoiceFor(List.of(trip.flight, trip.hotel)).body().get("id").asString();
		assertThat(get("/invoices/" + invoiceId).body().get("total").decimalValue()).isEqualByComparingTo("551.70");

		jdbc.sql("UPDATE transactions SET total = 230.00 WHERE id = ?").param(trip.flight).update();

		Resp r = patch("/invoices/" + invoiceId, JSON.createObjectNode().set("transaction_ids",
				JSON.valueToTree(List.of(trip.flight, trip.rail))));
		assertThat(r.status()).isEqualTo(200);
		assertThat(r.body().get("transactions")).hasSize(2);
		assertThat(r.body().get("total").decimalValue()).isEqualByComparingTo("324.00"); // 230 + 94, current data
		assertThat(r.body().get("tax_rollup")).hasSize(1);

		// the hotel is no longer billed, so it can be deleted
		assertThat(delete("/transactions/" + trip.hotel).status()).isEqualTo(204);
	}

	@Test
	void failedInvoicePatchLeavesTheInvoiceUntouched() {
		String flight = createSeed("flight");
		ObjectNode usd = seed("hotel");
		usd.put("currency", "USD");
		String usdId = post("/transactions", usd).body().get("id").asString();
		String invoiceId = invoiceFor(List.of(flight)).body().get("id").asString();

		assertThat(patch("/invoices/" + invoiceId, JSON.createObjectNode().set("transaction_ids",
				JSON.valueToTree(List.of(flight, usdId)))).status()).isEqualTo(409);
		assertThat(patch("/invoices/" + invoiceId, JSON.createObjectNode().set("transaction_ids",
				JSON.valueToTree(List.of(flight, "txn_missing")))).status()).isEqualTo(404);
		assertThat(patch("/invoices/inv_missing", JSON.createObjectNode().put("transaction_id", flight)).status())
				.isEqualTo(404);

		JsonNode invoice = get("/invoices/" + invoiceId).body();
		assertThat(invoice.get("transactions")).hasSize(1);
		assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo("220.00");
	}

	@Test
	void transactionCanAppearOnSeveralInvoices() {
		String flight = createSeed("flight");
		assertThat(post("/invoices", JSON.createObjectNode().put("transaction_id", flight)).status()).isEqualTo(201);
		assertThat(post("/invoices", JSON.createObjectNode().put("transaction_id", flight)).status()).isEqualTo(201);
		assertThat(count("invoices")).isEqualTo(2);
	}
}
