package com.example.navan.transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class TransactionRequests {

	private TransactionRequests() {
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
			Map<String, Object> metadata) {
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
			Map<String, Object> metadata) {
	}
}
