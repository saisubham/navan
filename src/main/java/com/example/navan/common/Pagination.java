package com.example.navan.common;

import java.util.List;

import com.example.navan.common.Exceptions.ValidationException;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Pagination(int limit, int offset) {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 100;

	public static Pagination of(Integer limit, Integer offset) {
		int l = limit == null ? DEFAULT_LIMIT : limit;
		int o = offset == null ? 0 : offset;
		if (l < 1 || l > MAX_LIMIT) {
			throw new ValidationException("limit must be between 1 and " + MAX_LIMIT);
		}
		if (o < 0) {
			throw new ValidationException("offset must be >= 0");
		}
		return new Pagination(l, o);
	}

	public record Page<T>(List<T> items, int limit, int offset, @JsonProperty("has_more") boolean hasMore) {
	}
}
