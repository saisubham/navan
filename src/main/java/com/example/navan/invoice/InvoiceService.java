package com.example.navan.invoice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.navan.common.Exceptions.ConflictException;
import com.example.navan.common.Exceptions.NotFoundException;
import com.example.navan.common.Exceptions.ValidationException;
import com.example.navan.common.Pagination;
import com.example.navan.common.Pagination.Page;
import com.example.navan.invoice.Invoice.Request;
import com.example.navan.invoice.Invoice.Summary;
import com.example.navan.invoice.InvoiceSnapshotBuilder.Snapshot;
import com.example.navan.transaction.Transaction;
import com.example.navan.transaction.TransactionRepository;

@Service
public class InvoiceService {

	private static final int MAX_TRANSACTIONS = 100;

	private final InvoiceRepository invoices;
	private final TransactionRepository transactions;
	private final InvoiceSnapshotBuilder builder;
	private final TransactionTemplate tx;

	InvoiceService(InvoiceRepository invoices, TransactionRepository transactions, InvoiceSnapshotBuilder builder,
			TransactionTemplate tx) {
		this.invoices = invoices;
		this.transactions = transactions;
		this.builder = builder;
		this.tx = tx;
	}

	public Invoice create(Request request) {
		List<String> ids = resolveIds(request);
		String id = tx.execute(_ -> invoices.insert(snapshotOf(ids)));
		return get(id);
	}

	/** Rebuilds the snapshot from the CURRENT stored transactions, atomically; failures leave it untouched. */
	public Invoice replace(String id, Request request) {
		List<String> ids = resolveIds(request);
		tx.executeWithoutResult(_ -> {
			if (!invoices.lock(id)) {
				throw new NotFoundException("invoice " + id + " not found");
			}
			invoices.replace(id, snapshotOf(ids));
		});
		return get(id);
	}

	public void delete(String id) {
		tx.executeWithoutResult(_ -> {
			if (!invoices.lock(id)) {
				throw new NotFoundException("invoice " + id + " not found");
			}
			invoices.delete(id); // transactions are never touched
		});
	}

	public Invoice get(String id) {
		return invoices.findById(id).orElseThrow(() -> new NotFoundException("invoice " + id + " not found"));
	}

	public Page<Summary> list(Integer limit, Integer offset) {
		Pagination p = Pagination.of(limit, offset);
		List<Summary> rows = invoices.page(p.limit() + 1, p.offset());
		boolean hasMore = rows.size() > p.limit();
		return new Page<>(hasMore ? rows.subList(0, p.limit()) : rows, p.limit(), p.offset(), hasMore);
	}

	/** Must run inside a DB transaction: locks the source rows (sorted) so they cannot change mid-snapshot. */
	private Snapshot snapshotOf(List<String> ids) {
		Set<String> found = new HashSet<>(transactions.lockAll(ids));
		List<String> missing = ids.stream().filter(i -> !found.contains(i)).toList();
		if (!missing.isEmpty()) {
			throw new NotFoundException("transaction(s) not found: " + missing);
		}
		List<Transaction> loaded = transactions.findByIds(ids);
		return builder.build(loaded);
	}

	private List<String> resolveIds(Request r) {
		if (r == null || (r.transactionId() == null && r.transactionIds() == null)) {
			throw new ValidationException("transaction_id or transaction_ids is required");
		}
		if (r.transactionId() != null && r.transactionIds() != null) {
			throw new ValidationException("supply either transaction_id or transaction_ids, not both");
		}
		List<String> ids = r.transactionId() != null ? List.of(r.transactionId()) : r.transactionIds();
		if (ids.isEmpty() || ids.size() > MAX_TRANSACTIONS) {
			throw new ValidationException("between 1 and " + MAX_TRANSACTIONS + " transaction ids are required");
		}
		Set<String> seen = new HashSet<>();
		for (String id : ids) {
			if (id == null || id.isBlank()) {
				throw new ValidationException("transaction ids must be non-empty strings");
			}
			if (!seen.add(id)) {
				throw new ConflictException("duplicate_transaction_id",
						"transaction " + id + " is listed more than once on this invoice");
			}
		}
		return new ArrayList<>(ids);
	}
}
