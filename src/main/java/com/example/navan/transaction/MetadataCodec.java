package com.example.navan.transaction;

import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.navan.common.Exceptions.ValidationException;

import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Binds the raw {@code metadata} JSON to the record for the transaction's type (a plain switch on the type,
 * no type tag inside metadata) and validates it. Every failure is a 400 that names the offending field.
 */
@Component
public class MetadataCodec {

	private final ObjectMapper json;
	private final Validator validator;

	MetadataCodec(ObjectMapper json, Validator validator) {
		this.json = json;
		this.validator = validator;
	}

	public TransactionMetadata decode(TransactionType type, JsonNode raw) {
		if (raw == null || !raw.isObject()) {
			throw new ValidationException("metadata is required and must be an object");
		}
		TransactionMetadata metadata;
		try {
			metadata = json.treeToValue(raw, type.metadataType());
		}
		catch (JacksonException e) {
			String path = e.getPath().stream()
					.map(ref -> ref.getPropertyName() != null ? "." + ref.getPropertyName() : "[" + ref.getIndex() + "]")
					.collect(Collectors.joining());
			throw new ValidationException("metadata" + path + " is invalid: " + e.getOriginalMessage());
		}
		String problems = validator.validate(metadata).stream()
				.map(v -> "metadata." + v.getPropertyPath() + " " + v.getMessage())
				.sorted()
				.collect(Collectors.joining("; "));
		if (!problems.isEmpty()) {
			throw new ValidationException(problems);
		}
		return metadata;
	}
}
