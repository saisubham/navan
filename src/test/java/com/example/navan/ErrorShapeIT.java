package com.example.navan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.navan.support.IntegrationTestBase;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Every error, including the ones Spring raises itself, uses the same {"error","message"} body. */
class ErrorShapeIT extends IntegrationTestBase {

	private void assertShape(Resp r, int status, String error) {
		assertThat(r.status()).isEqualTo(status);
		assertThat(r.body().get("error").asString()).isEqualTo(error);
		assertThat(r.body().get("message").asString()).isNotBlank();
	}

	@Test
	void unknownPathIsAJson404() {
		assertShape(get("/nope"), 404, "not_found");
	}

	@Test
	void wrongMethodIsAJson405() {
		assertShape(send("PUT", "/transactions", "{}"), 405, "method_not_allowed");
	}

	@Test
	void malformedJsonIsAJson400() {
		assertShape(post("/transactions", "{\"type\": "), 400, "bad_request");
		assertShape(post("/invoices", "{\"transaction_ids\": "), 400, "bad_request");
	}

	@Test
	void invoiceRequestRulesAreReportedAs400() {
		String id = createSeed("flight");

		ObjectNode neither = JSON.createObjectNode();
		assertShape(post("/invoices", neither), 400, "validation_error");

		ObjectNode both = JSON.createObjectNode().put("transaction_id", id);
		both.putArray("transaction_ids").add(id);
		assertShape(post("/invoices", both), 400, "validation_error");

		ObjectNode empty = JSON.createObjectNode();
		empty.putArray("transaction_ids");
		assertShape(post("/invoices", empty), 400, "validation_error");

		ObjectNode blank = JSON.createObjectNode();
		blank.putArray("transaction_ids").add(" ");
		assertShape(post("/invoices", blank), 400, "validation_error");

		assertShape(post("/invoices", JSON.createObjectNode().put("transaction_id", "")), 400, "validation_error");

		ObjectNode tooMany = JSON.createObjectNode();
		ArrayNode ids = tooMany.putArray("transaction_ids");
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 101; i++) {
			many.add("txn_" + i);
		}
		many.forEach(ids::add);
		assertShape(post("/invoices", tooMany), 400, "validation_error");

		assertThat(count("invoices")).isZero();
	}
}
