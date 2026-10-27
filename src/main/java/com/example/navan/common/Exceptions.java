package com.example.navan.common;

import java.util.Map;

public final class Exceptions {

	private Exceptions() {
	}

	/** 400 */
	public static class ValidationException extends RuntimeException {
		public ValidationException(String message) {
			super(message);
		}
	}

	/** 404 */
	public static class NotFoundException extends RuntimeException {
		public NotFoundException(String message) {
			super(message);
		}
	}

	/** 409, with optional extra body fields (e.g. the existing id). */
	public static class ConflictException extends RuntimeException {
		private final String code;
		private final transient Map<String, Object> details;

		public ConflictException(String code, String message) {
			this(code, message, Map.of());
		}

		public ConflictException(String code, String message, Map<String, Object> details) {
			super(message);
			this.code = code;
			this.details = details;
		}

		public String code() {
			return code;
		}

		public Map<String, Object> details() {
			return details;
		}
	}
}
