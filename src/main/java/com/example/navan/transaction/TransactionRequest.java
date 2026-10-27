package com.example.navan.transaction;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

public final class TransactionRequest {

	private TransactionRequest() {
	}

	public record TaxLineRequest(String name, BigDecimal rate, BigDecimal amount) {
	}

	public record FeeLineRequest(String name, BigDecimal amount) {
	}

	public record Create(
			String type,
			@JsonProperty("external_id") String externalId,
			String currency,
			@JsonProperty("occurred_at") String occurredAt,
			BigDecimal total,
			@JsonProperty("tax_lines") List<TaxLineRequest> taxLines,
			@JsonProperty("fee_lines") List<FeeLineRequest> feeLines,
			JsonNode metadata) {
	}

	/** Every field optional; null means "leave unchanged". type/external_id/currency are immutable. */
	public record Patch(
			String type,
			@JsonProperty("external_id") String externalId,
			String currency,
			@JsonProperty("occurred_at") String occurredAt,
			BigDecimal total,
			@JsonProperty("tax_lines") List<TaxLineRequest> taxLines,
			@JsonProperty("fee_lines") List<FeeLineRequest> feeLines,
			JsonNode metadata) {
	}
}
