package com.example.navan.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Transaction(
		String id,
		String type,
		@JsonProperty("external_id") String externalId,
		String currency,
		@JsonProperty("occurred_at") Instant occurredAt,
		BigDecimal total,
		@JsonProperty("tax_lines") List<TaxLine> taxLines,
		@JsonProperty("fee_lines") List<FeeLine> feeLines,
		TransactionMetadata metadata,
		@JsonProperty("created_at") Instant createdAt,
		@JsonProperty("updated_at") Instant updatedAt) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {
	}

	public record FeeLine(String name, BigDecimal amount) {
	}
}
