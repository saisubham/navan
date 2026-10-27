package com.example.navan.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real MySQL (Testcontainers, same image as docker-compose) + the real HTTP server on a random port.
 * One container is shared by every test class in the JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

	private static final MySQLContainer MYSQL = new MySQLContainer("mysql:26.10.0");

	static {
		MYSQL.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
	}

	protected static final JsonMapper JSON = JsonMapper.builder().build();
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	protected JdbcClient jdbc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.sql("DELETE FROM invoices").update(); // snapshot tables cascade
		jdbc.sql("DELETE FROM transactions").update(); // type/line tables cascade
	}

	public record Resp(int status, JsonNode body) {
	}

	protected Resp get(String path) {
		return send("GET", path, null);
	}

	protected Resp post(String path, Object body) {
		return send("POST", path, body);
	}

	protected Resp patch(String path, Object body) {
		return send("PATCH", path, body);
	}

	protected Resp delete(String path) {
		return send("DELETE", path, null);
	}

	private Resp send(String method, String path, Object body) {
		try {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
					.header("Content-Type", "application/json");
			b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body)));
			HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
			return new Resp(r.statusCode(), r.body().isBlank() ? null : JSON.readTree(r.body()));
		}
		catch (IOException | InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Seed payload from the shipped fixture, e.g. "flight", "hotel", "rail", "trip_fee", "agent_call_fee". */
	protected static ObjectNode seed(String name) {
		try (InputStream in = IntegrationTestBase.class.getResourceAsStream("/fixtures/seed-payloads.json")) {
			return ((ObjectNode) JSON.readTree(in).get(name)).deepCopy();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	protected static JsonNode fixture(String file) {
		try (InputStream in = IntegrationTestBase.class.getResourceAsStream("/fixtures/" + file)) {
			return JSON.readTree(in);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** POSTs a seed payload and returns the new transaction id. */
	protected String createSeed(String name) {
		Resp r = post("/transactions", seed(name));
		if (r.status() != 201) {
			throw new AssertionError("expected 201 creating seed " + name + " but got " + r.status() + " " + r.body());
		}
		return r.body().get("id").asString();
	}

	protected long count(String table) {
		return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
	}
}
