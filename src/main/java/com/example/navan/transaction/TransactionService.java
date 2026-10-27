package com.example.navan.transaction;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.navan.common.Exceptions.ConflictException;
import com.example.navan.common.Exceptions.NotFoundException;
import com.example.navan.common.Exceptions.ValidationException;
import com.example.navan.common.Pagination;
import com.example.navan.common.Pagination.Page;
import com.example.navan.transaction.TransactionRequests.Create;
import com.example.navan.transaction.TransactionRequests.Patch;
import com.example.navan.transaction.TransactionValidator.Draft;

@Service
public class TransactionService {

	private final TransactionRepository repository;
	private final TransactionValidator validator;
	private final MetadataHandlers handlers;
	private final TransactionTemplate tx;

	TransactionService(TransactionRepository repository, TransactionValidator validator, MetadataHandlers handlers,
			TransactionTemplate tx) {
		this.repository = repository;
		this.validator = validator;
		this.handlers = handlers;
		this.tx = tx;
	}

	/**
	 * Header, tax lines, fee lines and typed metadata are written in one DB transaction. Duplicate
	 * identity is enforced by the UNIQUE index on external_id: whichever concurrent INSERT loses gets a
	 * DuplicateKeyException, its transaction rolls back, and we answer 409 with the winner's id.
	 */
	public Transaction create(Create request) {
		Draft draft = validator.validate(request);
		repository.findIdByExternalId(draft.externalId()).ifPresent(this::duplicate);
		String id;
		try {
			id = tx.execute(status -> repository.insert(draft));
		}
		catch (DuplicateKeyException e) {
			repository.findIdByExternalId(draft.externalId()).ifPresent(this::duplicate);
			throw e;
		}
		return get(id);
	}

	private void duplicate(String existingId) {
		throw new ConflictException("duplicate_external_id",
				"A transaction with this external_id already exists", Map.of("id", existingId));
	}

	public Transaction get(String id) {
		return repository.findByIds(List.of(id)).stream().findFirst()
				.orElseThrow(() -> new NotFoundException("transaction " + id + " not found"));
	}

	public Page<Transaction> list(Integer limit, Integer offset) {
		Pagination p = Pagination.of(limit, offset);
		List<String> ids = repository.pageIds(p.limit() + 1, p.offset());
		boolean hasMore = ids.size() > p.limit();
		List<String> pageIds = hasMore ? ids.subList(0, p.limit()) : ids;
		return new Page<>(repository.findByIds(pageIds), p.limit(), p.offset(), hasMore);
	}

	/** Locks the row, refuses if billed, then replaces the supplied parts atomically. */
	public Transaction patch(String id, Patch patch) {
		if (patch == null) {
			throw new ValidationException("request body is required");
		}
		tx.executeWithoutResult(status -> {
			String type = repository.lockById(id)
					.orElseThrow(() -> new NotFoundException("transaction " + id + " not found"));
			assertNotInvoiced(id, "modified");
			Transaction current = repository.findByIds(List.of(id)).getFirst();

			if (patch.type() != null && !patch.type().equals(current.type())
					|| patch.externalId() != null && !patch.externalId().equals(current.externalId())
					|| patch.currency() != null && !patch.currency().equals(current.currency())) {
				throw new ValidationException("type, external_id and currency cannot be changed");
			}
			var total = patch.total() == null ? current.total() : validator.total(patch.total());
			var occurredAt = patch.occurredAt() == null ? current.occurredAt()
					: Meta.parseInstant(patch.occurredAt(), "occurred_at");
			var taxLines = patch.taxLines() == null ? null : validator.taxLines(patch.taxLines());
			var feeLines = patch.feeLines() == null ? null : validator.feeLines(patch.feeLines());
			MetadataHandlers.Split split = patch.metadata() == null ? null : handlers.normalize(type, patch.metadata());

			if (patch.total() == null && patch.occurredAt() == null && taxLines == null && feeLines == null
					&& split == null) {
				throw new ValidationException(
						"nothing to update: supply total, occurred_at, tax_lines, fee_lines or metadata");
			}
			Map<String, Object> extra = split == null ? extraOf(current, type) : split.extra();
			repository.updateHeader(id, occurredAt, total, extra);
			if (taxLines != null) {
				repository.deleteTaxLines(id);
				repository.insertTaxLines(id, taxLines);
			}
			if (feeLines != null) {
				repository.deleteFeeLines(id);
				repository.insertFeeLines(id, feeLines);
			}
			if (split != null) {
				repository.replaceMetadata(id, type, split.known());
			}
		});
		return get(id);
	}

	public void delete(String id) {
		tx.executeWithoutResult(status -> {
			repository.lockById(id).orElseThrow(() -> new NotFoundException("transaction " + id + " not found"));
			assertNotInvoiced(id, "deleted");
			repository.delete(id); // child rows go with it (ON DELETE CASCADE)
		});
	}

	private void assertNotInvoiced(String id, String verb) {
		if (repository.isInvoiced(id)) {
			throw new ConflictException("transaction_invoiced",
					"transaction " + id + " is on an invoice and cannot be " + verb);
		}
	}

	/** The unknown (extras) part of the stored metadata, so a header-only patch keeps it. */
	private Map<String, Object> extraOf(Transaction current, String type) {
		Map<String, Object> extra = new java.util.LinkedHashMap<>(current.metadata());
		extra.keySet().removeAll(handlers.forType(type).knownKeys());
		return extra;
	}
}
