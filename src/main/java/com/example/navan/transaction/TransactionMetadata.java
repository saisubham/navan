package com.example.navan.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.navan.common.Money;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The type-specific part of a transaction. The discriminator is the transaction's own {@code type}, a
 * sibling of {@code metadata} in the JSON, so {@link MetadataCodec} picks the record from it (a
 * {@code switch} on the type) instead of embedding a type tag inside metadata.
 *
 * <p>Validation is declarative (Bean Validation on the components). Unknown keys are kept in {@code extra}
 * and written back unchanged.
 */
public sealed interface TransactionMetadata {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Flight(
			@NotBlank String origin,
			@NotBlank String destination,
			@NotNull @PositiveOrZero BigDecimal fare,
			@NotNull List<@NotNull @Valid Coupon> coupons,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> extra) implements TransactionMetadata {

		public Flight {
			fare = Money.scale(fare);
			extra = extra == null ? new LinkedHashMap<>() : extra;
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Coupon(
			String carrier,
			@NotBlank @JsonProperty("flight_number") String flightNumber,
			@NotBlank String from,
			@NotBlank String to,
			@NotNull Instant departure,
			@NotNull Instant arrival) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Hotel(
			@NotBlank String name,
			@NotNull @Valid Address address,
			@NotNull @JsonProperty("check_in") LocalDate checkIn,
			@NotNull @JsonProperty("check_out") LocalDate checkOut,
			@NotNull @PositiveOrZero Integer nights,
			@NotNull @PositiveOrZero @JsonProperty("room_rate_per_night") BigDecimal roomRatePerNight,
			List<@NotNull @Valid Extra> extras,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> extra) implements TransactionMetadata {

		public Hotel {
			roomRatePerNight = Money.scale(roomRatePerNight);
			extra = extra == null ? new LinkedHashMap<>() : extra;
		}

		@JsonIgnore
		@AssertTrue(message = "check_out must be after check_in")
		public boolean isCheckOutAfterCheckIn() {
			return checkIn == null || checkOut == null || checkOut.isAfter(checkIn);
		}
	}

	record Address(@NotBlank String street, @NotBlank String city, @NotBlank String country) {
	}

	record Extra(@NotBlank String name, @NotNull @PositiveOrZero BigDecimal amount) {

		public Extra {
			amount = Money.scale(amount);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Rail(
			@NotEmpty List<@NotNull @Valid Leg> legs,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> extra) implements TransactionMetadata {

		public Rail {
			extra = extra == null ? new LinkedHashMap<>() : extra;
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Leg(
			@NotBlank String origin,
			@NotBlank String destination,
			@NotNull Instant departure,
			@NotNull Instant arrival,
			@JsonProperty("train_number") String trainNumber,
			@JsonProperty("class") String travelClass) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record NavanFee(
			@NotNull @JsonProperty("fee_kind") FeeKind feeKind,
			String description,
			@JsonProperty("related_external_id") String relatedExternalId,
			@JsonAnySetter @JsonAnyGetter Map<String, Object> extra) implements TransactionMetadata {

		public NavanFee {
			extra = extra == null ? new LinkedHashMap<>() : extra;
		}
	}

	enum FeeKind {
		@JsonProperty("trip_fee")
		TRIP_FEE,
		@JsonProperty("agent_call_fee")
		AGENT_CALL_FEE
	}
}
