package com.example.navan.invoice;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

/**
 * SQL for invoices. An invoice is two tables: the header (with the tax/fee rollups as JSON) and one row
 * per included transaction holding that transaction's frozen copy as JSON. Callers own the DB transaction.
 */
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
		jdbc.sql("""
				INSERT INTO invoices (id, currency, total, tax_rollup, fee_rollup, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)""")
				.params(id, s.currency(), s.total(), json.writeValueAsString(s.taxRollup()),
						json.writeValueAsString(s.feeRollup()), now, now)
				.update();
		insertLines(id, s);
		return id;
	}

	/** Replaces the snapshot of an existing (locked) invoice. */
	void replace(String id, Snapshot s) {
		jdbc.sql("DELETE FROM invoice_transactions WHERE invoice_id = ?").param(id).update();
		jdbc.sql("UPDATE invoices SET currency = ?, total = ?, tax_rollup = ?, fee_rollup = ?, updated_at = ? WHERE id = ?")
				.params(s.currency(), s.total(), json.writeValueAsString(s.taxRollup()),
						json.writeValueAsString(s.feeRollup()), LocalDateTime.now(ZoneOffset.UTC), id)
				.update();
		insertLines(id, s);
	}

	private void insertLines(String invoiceId, Snapshot s) {
		int position = 0;
		for (Line l : s.lines()) {
			jdbc.sql("INSERT INTO invoice_transactions (invoice_id, position, transaction_id, snapshot) VALUES (?, ?, ?, ?)")
					.params(invoiceId, position++, l.transactionId(), json.writeValueAsString(l)).update();
		}
	}

	/** Row lock; true when the invoice exists. */
	boolean lock(String id) {
		return jdbc.sql("SELECT id FROM invoices WHERE id = ? FOR UPDATE").param(id).query(String.class).optional()
				.isPresent();
	}

	void delete(String id) {
		jdbc.sql("DELETE FROM invoices WHERE id = ?").param(id).update(); // snapshot rows cascade
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

	/** Two queries: the header and all snapshot lines. Never touches the live transactions tables. */
	Optional<Invoice> findById(String id) {
		Optional<Invoice> header = jdbc
				.sql("SELECT currency, total, tax_rollup, fee_rollup, created_at, updated_at FROM invoices WHERE id = ?")
				.param(id).query((rs, n) -> {
					Instant created = rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC);
					Instant updated = rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC);
					return new Invoice(id, rs.getString("currency"), Money.scale(rs.getBigDecimal("total")), List.of(),
							json.readValue(rs.getString("tax_rollup"), new TypeReference<List<TaxLine>>() {
							}), json.readValue(rs.getString("fee_rollup"), new TypeReference<List<FeeLine>>() {
							}), created, updated);
				}).optional();
		return header.map(h -> {
			List<Line> lines = jdbc
					.sql("SELECT snapshot FROM invoice_transactions WHERE invoice_id = ? ORDER BY position")
					.param(id).query((rs, n) -> json.readValue(rs.getString("snapshot"), Line.class)).list();
			return new Invoice(h.id(), h.currency(), h.total(), lines, h.taxRollup(), h.feeRollup(), h.createdAt(),
					h.updatedAt());
		});
	}
}
