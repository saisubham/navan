package com.example.navan.transaction;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Strategy per transaction type: validates the type-specific metadata, stores it in the typed child
 * tables and rebuilds it on read. Normalized metadata uses ISO strings for dates/timestamps and
 * scale-2 BigDecimal for money, so it round-trips identically through the DB and through JSON.
 */
interface MetadataHandler {

	String type();

	/** Keys this handler stores in typed tables; anything else goes to the extras column. */
	Set<String> knownKeys();

	/** Validates and returns the canonical form of the known keys. Throws ValidationException (400). */
	Map<String, Object> normalize(Map<String, Object> raw);

	void insert(JdbcClient jdbc, String transactionId, Map<String, Object> normalized);

	void delete(JdbcClient jdbc, String transactionId);

	/** Batched load (one query per child table, regardless of how many ids). */
	Map<String, Map<String, Object>> load(JdbcClient jdbc, Collection<String> transactionIds);
}
