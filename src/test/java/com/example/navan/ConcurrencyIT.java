package com.example.navan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.example.navan.support.IntegrationTestBase;

import tools.jackson.databind.JsonNode;

class ConcurrencyIT extends IntegrationTestBase {

	/** Runs the tasks so that they all start at the same instant. */
	private <T> List<T> race(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch ready = new CountDownLatch(tasks.size());
			CountDownLatch go = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return task.call();
				}));
			}
			ready.await();
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get());
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void overlappingDuplicateCreatesLeaveExactlyOneRow() throws Exception {
		// 6.
		for (int round = 0; round < 3; round++) {
			String externalId = "race-" + round;
			List<Callable<Resp>> tasks = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				tasks.add(() -> {
					var body = seed("flight");
					body.put("external_id", externalId);
					return post("/transactions", body);
				});
			}
			List<Resp> results = race(tasks);

			List<Resp> created = results.stream().filter(r -> r.status() == 201).toList();
			List<Resp> conflicts = results.stream().filter(r -> r.status() == 409).toList();
			assertThat(created).hasSize(1);
			assertThat(conflicts).hasSize(7);
			String winner = created.getFirst().body().get("id").asString();
			assertThat(conflicts).allSatisfy(c -> assertThat(c.body().get("id").asString()).isEqualTo(winner));

			assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE external_id = ?").param(externalId)
					.query(Long.class).single()).isEqualTo(1);
		}
		// children were written once, by the winner only
		assertThat(count("transaction_tax_lines")).isEqualTo(3);
		assertThat(count("transactions")).isEqualTo(3);
	}

	@Test
	void patchRacingInvoiceCreationNeverRewritesBilledMoney() throws Exception {
		for (int i = 0; i < 10; i++) {
			String id = uniqueFlight(i);
			List<Callable<Resp>> tasks = List.of(
					() -> patch("/transactions/" + id, JSON.createObjectNode().put("total", "500.00")),
					() -> post("/invoices", JSON.createObjectNode().put("transaction_id", id)));
			List<Resp> r = race(tasks);
			Resp patched = r.get(0);
			Resp invoiced = r.get(1);

			assertThat(invoiced.status()).isEqualTo(201);
			assertThat(patched.status()).isIn(200, 409);
			JsonNode invoice = get("/invoices/" + invoiced.body().get("id").asString()).body();
			// whatever interleaving happened, the invoice shows exactly what the transaction held when billed
			// and the transaction can no longer drift from it
			String expected = patched.status() == 200 ? "500.00" : "220.00";
			assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo(expected);
			assertThat(get("/transactions/" + id).body().get("total").decimalValue())
					.isEqualByComparingTo(invoice.get("total").decimalValue());
		}
	}

	@Test
	void deleteRacingInvoiceCreationNeverLeavesADanglingInvoice() throws Exception {
		for (int i = 0; i < 10; i++) {
			String id = uniqueFlight(100 + i);
			List<Callable<Resp>> tasks = List.of(() -> delete("/transactions/" + id),
					() -> post("/invoices", JSON.createObjectNode().put("transaction_id", id)));
			List<Resp> r = race(tasks);
			Resp deleted = r.get(0);
			Resp invoiced = r.get(1);

			if (deleted.status() == 204) {
				assertThat(invoiced.status()).isEqualTo(404);
				assertThat(get("/transactions/" + id).status()).isEqualTo(404);
			}
			else {
				assertThat(deleted.status()).isEqualTo(409);
				assertThat(invoiced.status()).isEqualTo(201);
				assertThat(get("/transactions/" + id).status()).isEqualTo(200);
			}
		}
		assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM invoice_transactions it
				LEFT JOIN transactions t ON t.id = it.transaction_id WHERE t.id IS NULL""").query(Long.class).single())
				.isZero();
	}

	@Test
	void concurrentPatchesNeverTearTaxOrFeeLists() throws Exception {
		String id = createSeed("flight");
		List<Callable<Resp>> tasks = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			int n = i;
			tasks.add(() -> {
				var body = JSON.createObjectNode().put("total", n + ".00");
				body.putArray("tax_lines").addObject().put("name", "T" + n).put("amount", n);
				body.putArray("fee_lines").addObject().put("name", "F" + n).put("amount", n);
				return patch("/transactions/" + id, body);
			});
		}
		race(tasks).forEach(r -> assertThat(r.status()).isEqualTo(200));

		JsonNode t = get("/transactions/" + id).body();
		assertThat(t.get("tax_lines")).hasSize(1);
		assertThat(t.get("fee_lines")).hasSize(1);
		String n = t.get("tax_lines").get(0).get("name").asString().substring(1);
		assertThat(t.get("fee_lines").get(0).get("name").asString()).isEqualTo("F" + n); // same writer won both lists
		assertThat(t.get("total").decimalValue()).isEqualByComparingTo(n + ".00");
	}

	private String uniqueFlight(int i) {
		// createSeed posts seed("flight"), so give each iteration its own external_id first
		var body = seed("flight");
		body.put("external_id", "flight-iter-" + i + "-" + System.nanoTime());
		return post("/transactions", body).body().get("id").asString();
	}
}
