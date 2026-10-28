package com.example.navan.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.navan.transaction.Transaction.FeeLine;
import com.example.navan.transaction.Transaction.TaxLine;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

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
			JsonNode metadata) {
	}

	public record Summary(
			String id,
			String currency,
			BigDecimal total,
			@JsonProperty("transaction_count") int transactionCount,
			@JsonProperty("created_at") Instant createdAt) {
	}

	/** Exactly one of {@code transaction_id} or {@code transaction_ids}; a repeated id is a 409 in the service. */
	public record Request(
			@Pattern(regexp = "\\S+", message = "must not be blank") @JsonProperty("transaction_id") String transactionId,
			@Size(min = 1, max = MAX_TRANSACTIONS, message = "must contain between 1 and " + MAX_TRANSACTIONS + " ids")
			@JsonProperty("transaction_ids") List<@NotBlank String> transactionIds) {

		@JsonIgnore
		@AssertTrue(message = "exactly one of transaction_id or transaction_ids is required")
		public boolean isExactlyOneIdGiven() {
			return (transactionId != null) != (transactionIds != null);
		}

		List<String> ids() {
			return transactionId != null ? List.of(transactionId) : transactionIds;
		}
	}

	public static final int MAX_TRANSACTIONS = 100;
}
