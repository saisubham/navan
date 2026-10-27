package com.example.navan.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.navan.common.Exceptions.ValidationException;
import com.example.navan.common.Money;

/** Typed accessors over the raw metadata map; every failure is a 400 with the offending path. */
final class Meta {

	private Meta() {
	}

	static String string(Map<String, Object> m, String key, String path, boolean required) {
		Object v = m.get(key);
		if (v == null) {
			if (required) {
				throw new ValidationException(path + key + " is required");
			}
			return null;
		}
		if (!(v instanceof String s) || (required && s.isBlank())) {
			throw new ValidationException(path + key + " must be a non-empty string");
		}
		return s;
	}

	static BigDecimal decimal(Map<String, Object> m, String key, String path) {
		Object v = m.get(key);
		if (!(v instanceof Number) && !(v instanceof String)) {
			throw new ValidationException(path + key + " is required and must be a number");
		}
		try {
			BigDecimal d = new BigDecimal(v.toString());
			if (d.signum() < 0) {
				throw new ValidationException(path + key + " must not be negative");
			}
			return Money.scale(d);
		}
		catch (NumberFormatException e) {
			throw new ValidationException(path + key + " must be a number");
		}
	}

	static int integer(Map<String, Object> m, String key, String path) {
		Object v = m.get(key);
		if (v instanceof Number n && new BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0) {
			int i = n.intValue();
			if (i < 0) {
				throw new ValidationException(path + key + " must not be negative");
			}
			return i;
		}
		throw new ValidationException(path + key + " is required and must be an integer");
	}

	static Instant instant(Map<String, Object> m, String key, String path) {
		String s = string(m, key, path, true);
		return parseInstant(s, path + key);
	}

	static Instant parseInstant(String s, String name) {
		try {
			return OffsetDateTime.parse(s).toInstant();
		}
		catch (DateTimeParseException e) {
			throw new ValidationException(name + " must be an ISO-8601 timestamp with offset, e.g. 2026-03-11T06:40:00Z");
		}
	}

	static LocalDate date(Map<String, Object> m, String key, String path) {
		String s = string(m, key, path, true);
		try {
			return LocalDate.parse(s);
		}
		catch (DateTimeParseException e) {
			throw new ValidationException(path + key + " must be an ISO date, e.g. 2026-03-11");
		}
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Map<String, Object> m, String key, String path) {
		if (m.get(key) instanceof Map<?, ?> v) {
			return (Map<String, Object>) v;
		}
		throw new ValidationException(path + key + " is required and must be an object");
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> list(Map<String, Object> m, String key, String path, boolean required) {
		Object v = m.get(key);
		if (v == null && !required) {
			return List.of();
		}
		if (!(v instanceof List<?> raw)) {
			throw new ValidationException(path + key + " is required and must be a list");
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < raw.size(); i++) {
			if (!(raw.get(i) instanceof Map<?, ?> item)) {
				throw new ValidationException(path + key + "[" + i + "] must be an object");
			}
			out.add((Map<String, Object>) item);
		}
		return out;
	}

	static LocalDateTime utc(Instant i) {
		return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
	}

	static Instant instant(LocalDateTime t) {
		return t.toInstant(ZoneOffset.UTC);
	}
}
