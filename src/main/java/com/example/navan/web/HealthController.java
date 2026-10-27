package com.example.navan.web;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {

	private final JdbcClient jdbc;

	HealthController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/health")
	Map<String, String> health() {
		jdbc.sql("SELECT 1").query(Integer.class).single();
		return Map.of("status", "UP");
	}
}
