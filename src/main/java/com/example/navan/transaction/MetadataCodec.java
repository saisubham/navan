package com.example.navan.transaction;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.navan.common.Exceptions.ValidationException;
import com.example.navan.transaction.TransactionMetadata.FeeKind;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns the raw {@code metadata} JSON into the record for the transaction's type (a plain switch on the
 * type, no type tag inside metadata), validates it, and reads stored JSON back into records.
 * Every failure on the request path is a 400 that names the offending field.
 */
@Component
public class MetadataCodec {

	private static final Map<String, Class<? extends TransactionMetadata>> TYPES = Map.of(
			"flight", TransactionMetadata.Flight.class,
			"hotel", TransactionMetadata.Hotel.class,
			"rail", TransactionMetadata.Rail.class,
			"navan_fee", TransactionMetadata.NavanFee.class);

	private final ObjectMapper json;
	private final Validator validator;

	MetadataCodec(ObjectMapper json, Validator validator) {
		this.json = json;
		this.validator = validator;
	}

	public void requireKnownType(String type) {
		if (type == null || !TYPES.containsKey(type)) {
			throw new ValidationException("type must be one of " + TYPES.keySet().stream().sorted().toList());
		}
	}

	/** Request path: bind, then validate. */
	public TransactionMetadata decode(String type, JsonNode raw) {
		requireKnownType(type);
		if (raw == null || raw.isNull()) {
			throw new ValidationException("metadata is required");
		}
		if (!raw.isObject()) {
			throw new ValidationException("metadata must be an object");
		}
		TransactionMetadata metadata;
		try {
			metadata = json.treeToValue(raw, TYPES.get(type));
		}
		catch (JacksonException e) {
			throw new ValidationException(describe(e));
		}
		catch (RuntimeException e) { // e.g. a record's compact constructor rejecting the combination of fields
			throw new ValidationException("metadata invalid: " + rootMessage(e));
		}
		List<String> problems = validator.validate(metadata).stream().map(this::describe).sorted().toList();
		if (!problems.isEmpty()) {
			throw new ValidationException(String.join("; ", problems));
		}
		return metadata;
	}

	/** Read path: JSON column text back into the record (already validated when it was written). */
	public TransactionMetadata fromStored(String type, String text) {
		return json.readValue(text, TYPES.get(type));
	}

	private String describe(ConstraintViolation<TransactionMetadata> v) {
		return "metadata." + snake(v.getPropertyPath().toString()) + " " + v.getMessage();
	}

	private String describe(JacksonException e) {
		String path = e.getPath().stream()
				.map(ref -> ref.getPropertyName() != null ? "." + ref.getPropertyName() : "[" + ref.getIndex() + "]")
				.collect(Collectors.joining());
		Throwable cause = e.getCause();
		if (cause instanceof IllegalArgumentException iae) {
			return "metadata invalid: " + iae.getMessage();
		}
		if (path.endsWith(".fee_kind")) {
			return "metadata.fee_kind must be one of " + List.of(FeeKind.values()).stream()
					.map(k -> k.name().toLowerCase()).toList();
		}
		return "metadata" + path + " is invalid: " + e.getOriginalMessage();
	}

	private static String rootMessage(Throwable t) {
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return t.getMessage();
	}

	/** Bean Validation reports Java names (flightNumber); the API speaks snake_case. */
	private static String snake(String path) {
		return path.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
	}
}
