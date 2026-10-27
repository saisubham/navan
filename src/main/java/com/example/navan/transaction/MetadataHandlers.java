package com.example.navan.transaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.navan.common.Exceptions.ValidationException;

/** Registry of the per-type handlers. */
@Component
public class MetadataHandlers {

	private final Map<String, MetadataHandler> byType = new LinkedHashMap<>();

	MetadataHandlers(List<MetadataHandler> handlers) {
		handlers.forEach(h -> byType.put(h.type(), h));
	}

	MetadataHandler forType(String type) {
		MetadataHandler handler = type == null ? null : byType.get(type);
		if (handler == null) {
			throw new ValidationException("type must be one of " + byType.keySet().stream().sorted().toList());
		}
		return handler;
	}

	/** Known (typed) part and unknown (extras) part of a normalized metadata object. */
	record Split(Map<String, Object> known, Map<String, Object> extra) {
	}

	Split normalize(String type, Map<String, Object> raw) {
		MetadataHandler handler = forType(type);
		if (raw == null) {
			throw new ValidationException("metadata is required");
		}
		Map<String, Object> known = handler.normalize(raw);
		Map<String, Object> extra = new LinkedHashMap<>();
		raw.forEach((k, v) -> {
			if (!handler.knownKeys().contains(k)) {
				extra.put(k, v);
			}
		});
		return new Split(known, extra);
	}
}
