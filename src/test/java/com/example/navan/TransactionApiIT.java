package com.example.navan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.navan.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class TransactionApiIT extends IntegrationTestBase {

	@Test
	void createsAndReadsBackEveryType() {
		// 1. flight, hotel, rail and both navan_fee kinds round-trip their typed metadata
		JsonNode flight = get("/transactions/" + createSeed("flight")).body();
		assertThat(flight.get("type").asString()).isEqualTo("flight");
		assertThat(flight.get("total").decimalValue()).isEqualByComparingTo("220.00");
		assertThat(flight.get("tax_lines").get(0).get("name").asString()).isEqualTo("Airport tax");
		assertThat(flight.get("fee_lines").get(0).get("name").asString()).isEqualTo("YQ");
		JsonNode md = flight.get("metadata");
		assertThat(md.get("origin").asString()).isEqualTo("TXL");
		assertThat(md.get("destination").asString()).isEqualTo("LHR");
		assertThat(md.get("fare").decimalValue()).isEqualByComparingTo("180.00");
		assertThat(md.get("coupons")).hasSize(1);
		assertThat(md.get("coupons").get(0).get("flight_number").asString()).isEqualTo("BA982");
		assertThat(md.get("coupons").get(0).get("departure").asString()).isEqualTo("2026-03-11T07:00:00Z");

		JsonNode hotel = get("/transactions/" + createSeed("hotel")).body().get("metadata");
		assertThat(hotel.get("name").asString()).isEqualTo("Hotel Mitte London");
		assertThat(hotel.get("address").get("street").asString()).isEqualTo("12 Kingsway");
		assertThat(hotel.get("address").get("city").asString()).isEqualTo("London");
		assertThat(hotel.get("address").get("country").asString()).isEqualTo("GB");
		assertThat(hotel.get("nights").intValue()).isEqualTo(2);
		assertThat(hotel.get("extras").get(0).get("name").asString()).isEqualTo("Breakfast");

		JsonNode rail = get("/transactions/" + createSeed("rail")).body();
		assertThat(rail.get("tax_lines")).isEmpty();
		assertThat(rail.get("fee_lines").get(0).get("name").asString()).isEqualTo("Booking fee");
		JsonNode legs = rail.get("metadata").get("legs");
		assertThat(legs).hasSize(2);
		assertThat(legs.get(0).get("train_number").asString()).isEqualTo("EST9108");
		assertThat(legs.get(1).get("origin").asString()).isEqualTo("Lille Europe");
		assertThat(legs.get(1).get("class").asString()).isEqualTo("standard");

		JsonNode tripFee = get("/transactions/" + createSeed("trip_fee")).body();
		assertThat(tripFee.get("type").asString()).isEqualTo("navan_fee");
		assertThat(tripFee.get("metadata").get("fee_kind").asString()).isEqualTo("trip_fee");
		assertThat(tripFee.get("metadata").get("related_external_id").asString())
				.isEqualTo("flight-TXL-LHR-2026-03-11");
		JsonNode agentFee = get("/transactions/" + createSeed("agent_call_fee")).body();
		assertThat(agentFee.get("metadata").get("fee_kind").asString()).isEqualTo("agent_call_fee");

		assertThat(count("transactions")).isEqualTo(5);
	}

	@Test
	void duplicateExternalIdIsRejectedWithTheExistingId() {
		// 2. second POST does not insert a row: 409 + existing id
		String id = createSeed("flight");
		Resp second = post("/transactions", seed("duplicate_flight"));
		assertThat(second.status()).isEqualTo(409);
		assertThat(second.body().get("id").asString()).isEqualTo(id);
		assertThat(second.body().get("error").asString()).isEqualTo("duplicate_external_id");

		Resp samePayloadAgain = post("/transactions", seed("flight"));
		assertThat(samePayloadAgain.status()).isEqualTo(409);
		assertThat(count("transactions")).isEqualTo(1);
	}

	@Test
	void unknownTypeOrFeeKindIsRejected() {
		ObjectNode badType = seed("flight");
		badType.put("type", "cruise");
		assertThat(post("/transactions", badType).status()).isEqualTo(400);

		ObjectNode badKind = seed("trip_fee");
		((ObjectNode) badKind.get("metadata")).put("fee_kind", "coffee_fee");
		assertThat(post("/transactions", badKind).status()).isEqualTo(400);

		ObjectNode noMetadata = seed("hotel");
		noMetadata.putObject("metadata");
		assertThat(post("/transactions", noMetadata).status()).isEqualTo(400);

		ObjectNode noLegs = seed("rail");
		((ObjectNode) noLegs.get("metadata")).putArray("legs");
		assertThat(post("/transactions", noLegs).status()).isEqualTo(400);

		ObjectNode noTaxLines = seed("flight");
		noTaxLines.remove("tax_lines");
		assertThat(post("/transactions", noTaxLines).status()).isEqualTo(400);

		assertThat(count("transactions")).isZero();
	}

	@Test
	void invalidCreateLeavesNothingBehind() {
		// a bad coupon timestamp is rejected while normalizing metadata: nothing may persist
		ObjectNode bad = seed("flight");
		((ObjectNode) bad.get("metadata").get("coupons").get(0)).put("departure", "not-a-date");
		assertThat(post("/transactions", bad).status()).isEqualTo(400);
		assertThat(count("transactions")).isZero();
		assertThat(count("transaction_tax_lines")).isZero();
	}

	@Test
	void unknownMetadataFieldsRoundTrip() {
		ObjectNode flight = seed("flight");
		((ObjectNode) flight.get("metadata")).put("seat_class", "economy");
		String id = post("/transactions", flight).body().get("id").asString();
		assertThat(get("/transactions/" + id).body().get("metadata").get("seat_class").asString())
				.isEqualTo("economy");
	}

	@Test
	void getMissingTransactionIs404() {
		assertThat(get("/transactions/txn_nope").status()).isEqualTo(404);
	}

	@Test
	void patchReplacesPartsAtomicallyWhenNotInvoiced() {
		String id = createSeed("flight");
		ObjectNode patch = JSON.createObjectNode();
		patch.put("total", "230.50");
		patch.putArray("fee_lines").addObject().put("name", "YQ").put("amount", 35.5);
		Resp r = patch("/transactions/" + id, patch);
		assertThat(r.status()).isEqualTo(200);
		assertThat(r.body().get("total").decimalValue()).isEqualByComparingTo("230.50");
		assertThat(r.body().get("fee_lines")).hasSize(1);
		assertThat(r.body().get("fee_lines").get(0).get("amount").decimalValue()).isEqualByComparingTo("35.50");
		assertThat(r.body().get("tax_lines")).hasSize(1); // untouched
		assertThat(r.body().get("metadata").get("coupons")).hasSize(1); // untouched

		ObjectNode meta = JSON.createObjectNode();
		meta.putObject("metadata").put("origin", "BER").put("destination", "LHR").put("fare", 190)
				.putArray("coupons");
		Resp r2 = patch("/transactions/" + id, meta);
		assertThat(r2.status()).isEqualTo(200);
		assertThat(r2.body().get("metadata").get("origin").asString()).isEqualTo("BER");
		assertThat(r2.body().get("metadata").get("coupons")).isEmpty();

		assertThat(patch("/transactions/txn_nope", patch).status()).isEqualTo(404);
		assertThat(patch("/transactions/" + id, JSON.createObjectNode().put("currency", "USD")).status())
				.isEqualTo(400);
	}

	@Test
	void deleteRemovesTransactionAndItsLines() {
		String id = createSeed("hotel");
		assertThat(delete("/transactions/" + id).status()).isEqualTo(204);
		assertThat(get("/transactions/" + id).status()).isEqualTo(404);
		assertThat(count("transaction_tax_lines")).isZero();
		assertThat(delete("/transactions/" + id).status()).isEqualTo(404);
	}

	@Test
	void healthIsUp() {
		assertThat(get("/health").status()).isEqualTo(200);
	}

	@Test
	void validationErrorsNameTheOffendingField() {
		ObjectNode badCoupon = seed("flight");
		((ObjectNode) badCoupon.get("metadata").get("coupons").get(0)).put("departure", "not-a-date");
		assertThat(post("/transactions", badCoupon).body().get("message").asString())
				.contains("coupons[0].departure");

		ObjectNode noFlightNumber = seed("flight");
		((ObjectNode) noFlightNumber.get("metadata").get("coupons").get(0)).remove("flight_number");
		assertThat(post("/transactions", noFlightNumber).body().get("message").asString())
				.contains("metadata.coupons[0].flight_number");

		ObjectNode badKind = seed("trip_fee");
		((ObjectNode) badKind.get("metadata")).put("fee_kind", "coffee_fee");
		assertThat(post("/transactions", badKind).body().get("message").asString())
				.contains("fee_kind", "trip_fee", "agent_call_fee");

		ObjectNode backwardsStay = seed("hotel");
		((ObjectNode) backwardsStay.get("metadata")).put("check_out", "2026-03-10");
		assertThat(post("/transactions", backwardsStay).body().get("message").asString())
				.contains("check_out must be after check_in");

		ObjectNode noAddressCity = seed("hotel");
		((ObjectNode) noAddressCity.get("metadata").get("address")).remove("city");
		assertThat(post("/transactions", noAddressCity).body().get("message").asString())
				.contains("metadata.address.city");
		assertThat(count("transactions")).isZero();
	}

	@Test
	void requestFieldErrorsAreReportedByName() {
		ObjectNode bad = seed("flight");
		bad.put("external_id", "");
		bad.put("currency", "eur");
		bad.put("total", -1);
		((ObjectNode) bad.get("tax_lines").get(0)).put("name", " ");
		((ObjectNode) bad.get("fee_lines").get(0)).put("amount", -5);
		Resp r = post("/transactions", bad);
		assertThat(r.status()).isEqualTo(400);
		assertThat(r.body().get("error").asString()).isEqualTo("validation_error");
		assertThat(r.body().get("message").asString()).contains("external_id", "currency", "total",
				"tax_lines[0].name", "fee_lines[0].amount");

		ObjectNode noOccurredAt = seed("flight");
		noOccurredAt.remove("occurred_at");
		assertThat(post("/transactions", noOccurredAt).body().get("message").asString()).contains("occurred_at");

		ObjectNode badRate = seed("hotel");
		((ObjectNode) badRate.get("tax_lines").get(0)).put("rate", 7);
		assertThat(post("/transactions", badRate).body().get("message").asString()).contains("tax_lines[0].rate");

		assertThat(post("/transactions", seed("flight").put("occurred_at", "yesterday")).status()).isEqualTo(400);
		assertThat(post("/transactions", "").status()).isEqualTo(400);
		assertThat(count("transactions")).isZero();

		String id = createSeed("flight");
		Resp patch = patch("/transactions/" + id, JSON.createObjectNode().put("total", -3));
		assertThat(patch.status()).isEqualTo(400);
		assertThat(patch.body().get("message").asString()).contains("total");
	}
}
