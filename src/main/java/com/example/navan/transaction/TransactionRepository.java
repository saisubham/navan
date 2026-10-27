package com.example.navan.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.navan.common.Money;
import com.example.navan.transaction.Transaction.FeeLine;
import com.example.navan.transaction.Transaction.TaxLine;
import com.example.navan.transaction.TransactionRequest.Create;

import tools.jackson.databind.ObjectMapper;

/** SQL for transactions and their child tables. Callers own the DB transaction. */
@Repository
public class TransactionRepository {

	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final MetadataCodec metadata;

	TransactionRepository(JdbcClient jdbc, ObjectMapper json, MetadataCodec metadata) {
		this.jdbc = jdbc;
		this.json = json;
		this.metadata = metadata;
	}

	/** Throws DuplicateKeyException when external_id already exists. */
	String insert(Create r, TransactionMetadata md) {
		String id = "txn_" + UUID.randomUUID();
		LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
		jdbc.sql("""
				INSERT INTO transactions
				(id, type, external_id, currency, occurred_at, total, metadata, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(id, r.type().value(), r.externalId(), r.currency(), Times.utc(r.occurredAt()), r.total(),
						json.writeValueAsString(md), now, now)
				.update();
		insertLines(id, r.taxLines(), r.feeLines());
		return id;
	}

	void insertLines(String id, List<TaxLine> taxLines, List<FeeLine> feeLines) {
		insertTaxLines(id, taxLines);
		insertFeeLines(id, feeLines);
	}

	void insertTaxLines(String id, List<TaxLine> taxLines) {
		int seq = 0;
		for (TaxLine t : taxLines) {
			jdbc.sql("INSERT INTO transaction_tax_lines (transaction_id, seq, name, rate, amount) VALUES (?, ?, ?, ?, ?)")
					.params(id, seq++, t.name(), t.rate(), t.amount()).update();
		}
	}

	void insertFeeLines(String id, List<FeeLine> feeLines) {
		int seq = 0;
		for (FeeLine f : feeLines) {
			jdbc.sql("INSERT INTO transaction_fee_lines (transaction_id, seq, name, amount) VALUES (?, ?, ?, ?)")
					.params(id, seq++, f.name(), f.amount()).update();
		}
	}

	void deleteTaxLines(String id) {
		jdbc.sql("DELETE FROM transaction_tax_lines WHERE transaction_id = ?").param(id).update();
	}

	void deleteFeeLines(String id) {
		jdbc.sql("DELETE FROM transaction_fee_lines WHERE transaction_id = ?").param(id).update();
	}

	void updateHeader(String id, Instant occurredAt, BigDecimal total, TransactionMetadata metadata) {
		jdbc.sql("UPDATE transactions SET occurred_at = ?, total = ?, metadata = ?, updated_at = ? WHERE id = ?")
				.params(Times.utc(occurredAt), total, json.writeValueAsString(metadata),
						LocalDateTime.now(java.time.ZoneOffset.UTC), id)
				.update();
	}

	Optional<String> findIdByExternalId(String externalId) {
		return jdbc.sql("SELECT id FROM transactions WHERE external_id = ?").param(externalId).query(String.class)
				.optional();
	}

	/** Row lock (SELECT ... FOR UPDATE). Returns the transaction's type, or empty if it does not exist. */
	Optional<String> lockById(String id) {
		return jdbc.sql("SELECT type FROM transactions WHERE id = ? FOR UPDATE").param(id).query(String.class)
				.optional();
	}

	/** Locks all given ids in sorted order (avoids deadlocks between concurrent multi-row writers). */
	public List<String> lockAll(Collection<String> ids) {
		return jdbc.sql("SELECT id FROM transactions WHERE id IN (:ids) ORDER BY id FOR UPDATE")
				.param("ids", new TreeSet<>(ids)).query(String.class).list();
	}

	boolean isInvoiced(String id) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM invoice_transactions WHERE transaction_id = ?)").param(id)
				.query(Boolean.class).single();
	}

	void delete(String id) {
		jdbc.sql("DELETE FROM transactions WHERE id = ?").param(id).update();
	}

	/** One bounded page of ids, newest-stable ordering; fetches limit+1 so the caller can tell has_more. */
	List<String> pageIds(int limit, int offset) {
		return jdbc.sql("SELECT id FROM transactions ORDER BY created_at, id LIMIT ? OFFSET ?")
				.params(limit, offset).query(String.class).list();
	}

	/** Batched load (a fixed number of queries regardless of how many ids). Order follows the input ids. */
	public List<Transaction> findByIds(List<String> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		record Header(String id, String type, String externalId, String currency, Instant occurredAt, BigDecimal total,
				String metadata, Instant createdAt, Instant updatedAt) {
		}
		Map<String, Header> headers = new HashMap<>();
		jdbc.sql("SELECT * FROM transactions WHERE id IN (:ids)").param("ids", ids).query((rs, n) -> {
			Header h = new Header(rs.getString("id"), rs.getString("type"), rs.getString("external_id"),
					rs.getString("currency"), Times.instant(rs.getObject("occurred_at", LocalDateTime.class)),
					Money.scale(rs.getBigDecimal("total")), rs.getString("metadata"),
					Times.instant(rs.getObject("created_at", LocalDateTime.class)),
					Times.instant(rs.getObject("updated_at", LocalDateTime.class)));
			headers.put(h.id(), h);
			return null;
		}).list();

		Map<String, List<TaxLine>> taxes = new HashMap<>();
		jdbc.sql("SELECT transaction_id, name, rate, amount FROM transaction_tax_lines WHERE transaction_id IN (:ids) ORDER BY transaction_id, seq")
				.param("ids", ids).query((rs, n) -> {
					taxes.computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
							.add(new TaxLine(rs.getString("name"), Money.rate(rs.getBigDecimal("rate")),
									Money.scale(rs.getBigDecimal("amount"))));
					return null;
				}).list();
		Map<String, List<FeeLine>> fees = new HashMap<>();
		jdbc.sql("SELECT transaction_id, name, amount FROM transaction_fee_lines WHERE transaction_id IN (:ids) ORDER BY transaction_id, seq")
				.param("ids", ids).query((rs, n) -> {
					fees.computeIfAbsent(rs.getString("transaction_id"), k -> new ArrayList<>())
							.add(new FeeLine(rs.getString("name"), Money.scale(rs.getBigDecimal("amount"))));
					return null;
				}).list();

		List<Transaction> out = new ArrayList<>();
		for (String id : ids) {
			Header h = headers.get(id);
			if (h == null) {
				continue;
			}
			out.add(new Transaction(h.id(), h.type(), h.externalId(), h.currency(), h.occurredAt(), h.total(),
					taxes.getOrDefault(id, List.of()), fees.getOrDefault(id, List.of()), metadata.fromStored(h.type(), h.metadata()),
					h.createdAt(), h.updatedAt()));
		}
		return out;
	}
}
