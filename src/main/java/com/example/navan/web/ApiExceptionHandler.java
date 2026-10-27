package com.example.navan.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.navan.common.Exceptions.ConflictException;
import com.example.navan.common.Exceptions.NotFoundException;
import com.example.navan.common.Exceptions.ValidationException;

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(ValidationException.class)
	ResponseEntity<Map<String, Object>> validation(ValidationException e) {
		return body(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage(), Map.of());
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

	private ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message,
			Map<String, Object> details) {
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("error", error);
		b.put("message", message);
		b.putAll(details);
		return ResponseEntity.status(status).body(b);
	}
}
