package com.example.navan.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.navan.common.Money;
import com.example.navan.invoice.Invoice.Line;
import com.example.navan.invoice.Invoice.Summary;
import com.example.navan.invoice.InvoiceSnapshotBuilder.Snapshot;
import com.example.navan.transaction.Transaction.FeeLine;
import com.example.navan.transaction.Transaction.TaxLine;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** SQL for invoices and their snapshot tables. Callers own the DB transaction. */
@Repository
public class InvoiceRepository {

	private final JdbcClient jdbc;
	private final ObjectMapper json;

	InvoiceRepository(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	String insert(Snapshot s) {
		String id = "inv_" + UUID.randomUUID();
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		jdbc.sql("INSERT INTO invoices (id, currency, total, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")
				.params(id, s.currency(), s.total(), now, now).update();
		insertSnapshot(id, s);
		return id;
	}

	/** Replaces the snapshot of an existing (locked) invoice. */
	void replace(String id, Snapshot s) {
		deleteSnapshot(id);
		jdbc.sql("UPDATE invoices SET currency = ?, total = ?, updated_at = ? WHERE id = ?")
				.params(s.currency(), s.total(), LocalDateTime.now(ZoneOffset.UTC), id).update();
		insertSnapshot(id, s);
	}

	private void insertSnapshot(String id, Snapshot s) {
		int position = 0;
		for (Line l : s.lines()) {
			jdbc.sql("""
					INSERT INTO invoice_transactions
					(invoice_id, position, transaction_id, type, external_id, currency, occurred_at, total, metadata_json)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
					.params(id, position++, l.transactionId(), l.type(), l.externalId(), l.currency(),
							LocalDateTime.ofInstant(l.occurredAt(), ZoneOffset.UTC), l.total(),
							json.writeValueAsString(l.metadata()))
					.update();
			long lineId = jdbc.sql("SELECT id FROM invoice_transactions WHERE invoice_id = ? AND transaction_id = ?")
					.params(id, l.transactionId()).query(Long.class).single();
			int seq = 0;
			for (TaxLine t : l.taxLines()) {
				jdbc.sql("INSERT INTO invoice_tax_lines (invoice_transaction_id, seq, name, rate, amount) VALUES (?, ?, ?, ?, ?)")
						.params(lineId, seq++, t.name(), t.rate(), t.amount()).update();
			}
			seq = 0;
			for (FeeLine f : l.feeLines()) {
				jdbc.sql("INSERT INTO invoice_fee_lines (invoice_transaction_id, seq, name, amount) VALUES (?, ?, ?, ?)")
						.params(lineId, seq++, f.name(), f.amount()).update();
			}
		}
		int seq = 0;
		for (TaxLine t : s.taxRollup()) {
			jdbc.sql("INSERT INTO invoice_tax_rollup (invoice_id, seq, name, rate, amount) VALUES (?, ?, ?, ?, ?)")
					.params(id, seq++, t.name(), t.rate(), t.amount()).update();
		}
		seq = 0;
		for (FeeLine f : s.feeRollup()) {
			jdbc.sql("INSERT INTO invoice_fee_rollup (invoice_id, seq, name, amount) VALUES (?, ?, ?, ?)")
					.params(id, seq++, f.name(), f.amount()).update();
		}
	}

	private void deleteSnapshot(String id) {
		jdbc.sql("DELETE FROM invoice_tax_rollup WHERE invoice_id = ?").param(id).update();
		jdbc.sql("DELETE FROM invoice_fee_rollup WHERE invoice_id = ?").param(id).update();
		jdbc.sql("DELETE FROM invoice_transactions WHERE invoice_id = ?").param(id).update(); // lines cascade
	}

	/** Row lock; true when the invoice exists. */
	boolean lock(String id) {
		return jdbc.sql("SELECT id FROM invoices WHERE id = ? FOR UPDATE").param(id).query(String.class).optional()
				.isPresent();
	}

	void delete(String id) {
		jdbc.sql("DELETE FROM invoices WHERE id = ?").param(id).update(); // everything else cascades
	}

	List<Summary> page(int limit, int offset) {
		return jdbc.sql("""
				SELECT i.id, i.currency, i.total, i.created_at,
				       (SELECT COUNT(*) FROM invoice_transactions it WHERE it.invoice_id = i.id) AS txn_count
				FROM invoices i ORDER BY i.created_at, i.id LIMIT ? OFFSET ?""")
				.params(limit, offset)
				.query((rs, n) -> new Summary(rs.getString("id"), rs.getString("currency"),
						Money.scale(rs.getBigDecimal("total")), rs.getInt("txn_count"),
						rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
				.list();
	}

	Optional<Invoice> findById(String id) {
		record Header(String currency, BigDecimal total, Instant createdAt, Instant updatedAt) {
		}
		Optional<Header> header = jdbc.sql("SELECT currency, total, created_at, updated_at FROM invoices WHERE id = ?")
				.param(id).query((rs, n) -> new Header(rs.getString("currency"), Money.scale(rs.getBigDecimal("total")),
						rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
						rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
				.optional();
		if (header.isEmpty()) {
			return Optional.empty();
		}

		record Row(long id, Line line) {
		}
		List<Row> rows = jdbc.sql("SELECT * FROM invoice_transactions WHERE invoice_id = ? ORDER BY position")
				.param(id).query((rs, n) -> new Row(rs.getLong("id"),
						new Line(rs.getString("transaction_id"), rs.getString("type"), rs.getString("external_id"),
								rs.getString("currency"),
								rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
								Money.scale(rs.getBigDecimal("total")), new ArrayList<>(), new ArrayList<>(),
								json.readValue(rs.getString("metadata_json"),
										new TypeReference<Map<String, Object>>() {
										}))))
				.list();

		Map<Long, List<TaxLine>> taxes = new HashMap<>();
		Map<Long, List<FeeLine>> fees = new HashMap<>();
		if (!rows.isEmpty()) {
			List<Long> lineIds = rows.stream().map(Row::id).toList();
			jdbc.sql("SELECT invoice_transaction_id, name, rate, amount FROM invoice_tax_lines WHERE invoice_transaction_id IN (:ids) ORDER BY invoice_transaction_id, seq")
					.param("ids", lineIds).query((rs, n) -> {
						taxes.computeIfAbsent(rs.getLong("invoice_transaction_id"), k -> new ArrayList<>())
								.add(new TaxLine(rs.getString("name"), Money.rate(rs.getBigDecimal("rate")),
										Money.scale(rs.getBigDecimal("amount"))));
						return null;
					}).list();
			jdbc.sql("SELECT invoice_transaction_id, name, amount FROM invoice_fee_lines WHERE invoice_transaction_id IN (:ids) ORDER BY invoice_transaction_id, seq")
					.param("ids", lineIds).query((rs, n) -> {
						fees.computeIfAbsent(rs.getLong("invoice_transaction_id"), k -> new ArrayList<>())
								.add(new FeeLine(rs.getString("name"), Money.scale(rs.getBigDecimal("amount"))));
						return null;
					}).list();
		}
		List<Line> lines = rows.stream().map(r -> new Line(r.line().transactionId(), r.line().type(),
				r.line().externalId(), r.line().currency(), r.line().occurredAt(), r.line().total(),
				taxes.getOrDefault(r.id(), List.of()), fees.getOrDefault(r.id(), List.of()), r.line().metadata()))
				.toList();

		List<TaxLine> taxRollup = jdbc.sql("SELECT name, rate, amount FROM invoice_tax_rollup WHERE invoice_id = ? ORDER BY seq")
				.param(id).query((rs, n) -> new TaxLine(rs.getString("name"), Money.rate(rs.getBigDecimal("rate")),
						Money.scale(rs.getBigDecimal("amount"))))
				.list();
		List<FeeLine> feeRollup = jdbc.sql("SELECT name, amount FROM invoice_fee_rollup WHERE invoice_id = ? ORDER BY seq")
				.param(id).query((rs, n) -> new FeeLine(rs.getString("name"), Money.scale(rs.getBigDecimal("amount"))))
				.list();

		Header h = header.get();
		return Optional.of(new Invoice(id, h.currency(), h.total(), lines, taxRollup, feeRollup, h.createdAt(),
				h.updatedAt()));
	}
}
