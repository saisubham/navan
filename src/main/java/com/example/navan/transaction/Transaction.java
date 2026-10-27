package com.example.navan.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.navan.common.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

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

	/** Government/VAT-style line. Also the request shape, so the constraints live here once. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TaxLine(
			@NotBlank String name,
			@DecimalMin("0") @DecimalMax("1") BigDecimal rate,
			@NotNull @PositiveOrZero BigDecimal amount) {

		public TaxLine {
			amount = Money.scale(amount);
		}
	}

	/** Carrier/merchant fee line. Also the request shape. */
	public record FeeLine(@NotBlank String name, @NotNull @PositiveOrZero BigDecimal amount) {

		public FeeLine {
			amount = Money.scale(amount);
		}
	}
}
