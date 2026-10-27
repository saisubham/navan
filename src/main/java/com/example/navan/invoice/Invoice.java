package com.example.navan.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.navan.transaction.Transaction.FeeLine;
import com.example.navan.transaction.Transaction.TaxLine;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A frozen invoice: every line is a copy of the transaction as it was when the invoice was built. */
public record Invoice(
		String id,
		String currency,
		BigDecimal total,
		List<Line> transactions,
		@JsonProperty("tax_rollup") List<TaxLine> taxRollup,
		@JsonProperty("fee_rollup") List<FeeLine> feeRollup,
		@JsonProperty("created_at") Instant createdAt,
		@JsonProperty("updated_at") Instant updatedAt) {

	public record Line(
			@JsonProperty("transaction_id") String transactionId,
			String type,
			@JsonProperty("external_id") String externalId,
			String currency,
			@JsonProperty("occurred_at") Instant occurredAt,
			BigDecimal total,
			@JsonProperty("tax_lines") List<TaxLine> taxLines,
			@JsonProperty("fee_lines") List<FeeLine> feeLines,
			Map<String, Object> metadata) {
	}

	public record Summary(
			String id,
			String currency,
			BigDecimal total,
			@JsonProperty("transaction_count") int transactionCount,
			@JsonProperty("created_at") Instant createdAt) {
	}

	public record Request(
			@JsonProperty("transaction_id") String transactionId,
			@JsonProperty("transaction_ids") List<String> transactionIds) {
	}
}
