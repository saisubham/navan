package com.example.navan.transaction;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import com.example.navan.common.Exceptions.ValidationException;

/** Timestamps are UTC everywhere: DATETIME(3) columns hold UTC wall-clock values. */
final class Times {

	private Times() {
	}

	static LocalDateTime utc(Instant i) {
		return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
	}

	static Instant instant(LocalDateTime t) {
		return t.toInstant(ZoneOffset.UTC);
	}

	static Instant parse(String s, String name) {
		try {
			return OffsetDateTime.parse(s).toInstant();
		}
		catch (DateTimeParseException e) {
			throw new ValidationException(name + " must be an ISO-8601 timestamp with offset, e.g. 2026-03-11T06:40:00Z");
		}
	}
}
