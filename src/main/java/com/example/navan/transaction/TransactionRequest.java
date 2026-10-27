package com.example.navan.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.navan.common.Money;
import com.example.navan.transaction.Transaction.FeeLine;
import com.example.navan.transaction.Transaction.TaxLine;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Request bodies. Validated declaratively; the type-dependent {@code metadata} is checked by MetadataCodec. */
public final class TransactionRequest {

	private static final String CURRENCY = "[A-Z]{3}";
	private static final String CURRENCY_MESSAGE = "must be a 3-letter uppercase currency code, e.g. EUR";

	private TransactionRequest() {
	}

	public record Create(
			@NotNull TransactionType type,
			@NotBlank @Size(max = 128) @JsonProperty("external_id") String externalId,
			@NotNull @Pattern(regexp = CURRENCY, message = CURRENCY_MESSAGE) String currency,
			@NotNull @JsonProperty("occurred_at") Instant occurredAt,
			@NotNull @PositiveOrZero BigDecimal total,
			@NotNull @JsonProperty("tax_lines") List<@NotNull @Valid TaxLine> taxLines,
			@NotNull @JsonProperty("fee_lines") List<@NotNull @Valid FeeLine> feeLines,
			@NotNull JsonNode metadata) {

		public Create {
			total = Money.scale(total);
		}
	}

	/**
	 * Every field optional; null means "leave unchanged". type, external_id and currency are immutable (a
	 * different value is rejected by the service). The same constraints apply to whatever is supplied:
	 * null values are skipped by Bean Validation.
	 */
	public record Patch(
			TransactionType type,
			@Size(max = 128) @JsonProperty("external_id") String externalId,
			@Pattern(regexp = CURRENCY, message = CURRENCY_MESSAGE) String currency,
			@JsonProperty("occurred_at") Instant occurredAt,
			@PositiveOrZero BigDecimal total,
			@JsonProperty("tax_lines") List<@NotNull @Valid TaxLine> taxLines,
			@JsonProperty("fee_lines") List<@NotNull @Valid FeeLine> feeLines,
			JsonNode metadata) {

		public Patch {
			total = Money.scale(total);
		}
	}
}
