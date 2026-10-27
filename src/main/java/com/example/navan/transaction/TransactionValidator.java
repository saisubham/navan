package com.example.navan.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.navan.common.Exceptions.ValidationException;
import com.example.navan.common.Money;
import com.example.navan.transaction.Transaction.FeeLine;
import com.example.navan.transaction.Transaction.TaxLine;
import com.example.navan.transaction.TransactionRequest.Create;
import com.example.navan.transaction.TransactionRequest.FeeLineRequest;
import com.example.navan.transaction.TransactionRequest.TaxLineRequest;

@Component
public class TransactionValidator {

	private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

	private final MetadataCodec codec;

	TransactionValidator(MetadataCodec codec) {
		this.codec = codec;
	}

	/** A fully validated, normalized create request. */
	record Draft(String type, String externalId, String currency, Instant occurredAt, BigDecimal total,
			List<TaxLine> taxLines, List<FeeLine> feeLines, TransactionMetadata metadata) {
	}

	Draft validate(Create r) {
		if (r == null) {
			throw new ValidationException("request body is required");
		}
		codec.requireKnownType(r.type());
		if (r.externalId() == null || r.externalId().isBlank() || r.externalId().length() > 128) {
			throw new ValidationException("external_id is required (1-128 characters)");
		}
		if (r.currency() == null || !CURRENCY.matcher(r.currency()).matches()) {
			throw new ValidationException("currency is required: 3 uppercase letters, e.g. EUR");
		}
		if (r.occurredAt() == null) {
			throw new ValidationException("occurred_at is required");
		}
		Instant occurredAt = Times.parse(r.occurredAt(), "occurred_at");
		BigDecimal total = total(r.total());
		if (r.taxLines() == null) {
			throw new ValidationException("tax_lines is required (may be empty)");
		}
		if (r.feeLines() == null) {
			throw new ValidationException("fee_lines is required (may be empty)");
		}
		return new Draft(r.type(), r.externalId(), r.currency(), occurredAt, total, taxLines(r.taxLines()),
				feeLines(r.feeLines()), codec.decode(r.type(), r.metadata()));
	}

	BigDecimal total(BigDecimal total) {
		if (total == null || total.signum() < 0) {
			throw new ValidationException("total is required and must not be negative");
		}
		return Money.scale(total);
	}

	List<TaxLine> taxLines(List<TaxLineRequest> in) {
		List<TaxLine> out = new ArrayList<>();
		for (int i = 0; i < in.size(); i++) {
			TaxLineRequest t = in.get(i);
			if (t == null || t.name() == null || t.name().isBlank() || t.amount() == null || t.amount().signum() < 0) {
				throw new ValidationException("tax_lines[" + i + "] needs a name and a non-negative amount");
			}
			if (t.rate() != null && (t.rate().signum() < 0 || t.rate().compareTo(BigDecimal.ONE) > 0)) {
				throw new ValidationException("tax_lines[" + i + "].rate must be between 0 and 1");
			}
			out.add(new TaxLine(t.name(), t.rate(), Money.scale(t.amount())));
		}
		return out;
	}

	List<FeeLine> feeLines(List<FeeLineRequest> in) {
		List<FeeLine> out = new ArrayList<>();
		for (int i = 0; i < in.size(); i++) {
			FeeLineRequest f = in.get(i);
			if (f == null || f.name() == null || f.name().isBlank() || f.amount() == null || f.amount().signum() < 0) {
				throw new ValidationException("fee_lines[" + i + "] needs a name and a non-negative amount");
			}
			out.add(new FeeLine(f.name(), Money.scale(f.amount())));
		}
		return out;
	}
}
