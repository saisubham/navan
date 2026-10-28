package com.example.navan.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.navan.common.Exceptions.ConflictException;
import com.example.navan.common.Exceptions.NotFoundException;
import com.example.navan.common.Exceptions.ValidationException;

@RestControllerAdvice
class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ValidationException.class)
	ResponseEntity<Map<String, Object>> validation(ValidationException e) {
		return body(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage(), Map.of());
	}

	/** Bean Validation on request bodies; field names are reported in the API's snake_case. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.map(f -> snake(f.getField()) + " " + f.getDefaultMessage()).sorted().distinct()
				.collect(Collectors.joining("; "));
		return body(HttpStatus.BAD_REQUEST, "validation_error", message, Map.of());
	}

	private static String snake(String path) {
		return path.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<Map<String, Object>> unreadable(Exception e) {
		return body(HttpStatus.BAD_REQUEST, "bad_request", "malformed request: " + e.getMessage(), Map.of());
	}

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
		return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage(), Map.of());
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
		return body(HttpStatus.CONFLICT, e.code(), e.getMessage(), e.details());
	}

	/** Safety net: a unique/FK violation we did not translate is still a conflict, never a 500. */
	@ExceptionHandler({ DuplicateKeyException.class, PessimisticLockingFailureException.class })
	ResponseEntity<Map<String, Object>> dataConflict(Exception e) {
		return body(HttpStatus.CONFLICT, "conflict", "concurrent modification, retry", Map.of());
	}

	/**
	 * Everything else. Spring's own web errors (unknown path 404, wrong method 405, wrong content type 415, ...)
	 * implement {@link ErrorResponse} and keep their status; anything unexpected is a 500. Either way the body
	 * has the same {"error","message"} shape as every other error.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, Object>> fallback(Exception e) {
		if (e instanceof ErrorResponse er) {
			HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
			String code = switch (status) {
				case NOT_FOUND -> "not_found";
				case METHOD_NOT_ALLOWED -> "method_not_allowed";
				case UNSUPPORTED_MEDIA_TYPE -> "unsupported_media_type";
				case NOT_ACCEPTABLE -> "not_acceptable";
				default -> "bad_request";
			};
			String detail = er.getBody().getDetail();
			ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
			er.getHeaders().forEach((name, values) -> response.header(name, values.toArray(String[]::new)));
			return response.body(body(code, detail != null ? detail : status.getReasonPhrase(), Map.of()));
		}
		log.error("Unhandled exception", e);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "unexpected error", Map.of());
	}

	private static Map<String, Object> body(String error, String message, Map<String, Object> details) {
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("error", error);
		b.put("message", message);
		b.putAll(details);
		return b;
	}

	private ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message,
			Map<String, Object> details) {
		return ResponseEntity.status(status).body(body(error, message, details));
	}
}
