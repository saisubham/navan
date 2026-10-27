package com.example.navan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.navan.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

class PaginationIT extends IntegrationTestBase {

	private void createFlights(int n) {
		for (int i = 0; i < n; i++) {
			var body = seed("flight");
			body.put("external_id", "page-" + i);
			assertThat(post("/transactions", body).status()).isEqualTo(201);
		}
	}

	@Test
	void defaultPageSizeIs50AndLimitAbove100IsRejected() {
		// 10.
		createFlights(3);
		Resp r = get("/transactions");
		assertThat(r.status()).isEqualTo(200);
		assertThat(r.body().get("limit").intValue()).isEqualTo(50);
		assertThat(r.body().get("offset").intValue()).isZero();
		assertThat(r.body().get("items")).hasSize(3);
		assertThat(r.body().get("has_more").booleanValue()).isFalse();

		assertThat(get("/transactions?limit=1000").status()).isEqualTo(400);
		assertThat(get("/transactions?limit=0").status()).isEqualTo(400);
		assertThat(get("/transactions?offset=-1").status()).isEqualTo(400);
		assertThat(get("/transactions?limit=100").status()).isEqualTo(200);
		assertThat(get("/invoices?limit=1000").status()).isEqualTo(400);
	}

	@Test
	void pagesWalkTheWholeSetWithoutOverlap() {
		createFlights(5);
		JsonNode p1 = get("/transactions?limit=2&offset=0").body();
		JsonNode p2 = get("/transactions?limit=2&offset=2").body();
		JsonNode p3 = get("/transactions?limit=2&offset=4").body();
		assertThat(p1.get("items")).hasSize(2);
		assertThat(p1.get("has_more").booleanValue()).isTrue();
		assertThat(p2.get("items")).hasSize(2);
		assertThat(p2.get("has_more").booleanValue()).isTrue();
		assertThat(p3.get("items")).hasSize(1);
		assertThat(p3.get("has_more").booleanValue()).isFalse();

		var ids = new java.util.HashSet<String>();
		for (JsonNode page : new JsonNode[] { p1, p2, p3 }) {
			page.get("items").forEach(t -> ids.add(t.get("id").asString()));
		}
		assertThat(ids).hasSize(5);
		assertThat(p1.get("items").get(0).get("metadata").get("coupons")).hasSize(1); // children are loaded in batch
	}

	@Test
	void invoiceListIsPaginatedAndCarriesSummaries() {
		String flight = createSeed("flight");
		for (int i = 0; i < 3; i++) {
			post("/invoices", JSON.createObjectNode().put("transaction_id", flight));
		}
		JsonNode page = get("/invoices?limit=2").body();
		assertThat(page.get("items")).hasSize(2);
		assertThat(page.get("has_more").booleanValue()).isTrue();
		assertThat(page.get("items").get(0).get("transaction_count").intValue()).isEqualTo(1);
		assertThat(page.get("items").get(0).get("total").decimalValue()).isEqualByComparingTo("220.00");
	}
}
