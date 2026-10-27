package com.example.navan.invoice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.navan.common.Exceptions.ConflictException;
import com.example.navan.common.Money;
import com.example.navan.invoice.Invoice.Line;
import com.example.navan.transaction.Transaction;
import com.example.navan.transaction.Transaction.FeeLine;
import com.example.navan.transaction.Transaction.TaxLine;

/**
 * Pure logic: copies transactions into invoice lines, enforces a single currency, and computes totals
 * and rollups. Tax rollup groups by (name, rate); fee rollup groups by name. Both keep first-seen order.
 */
@Component
public class InvoiceSnapshotBuilder {

	public record Snapshot(String currency, BigDecimal total, List<Line> lines, List<TaxLine> taxRollup,
			List<FeeLine> feeRollup) {
	}

	private record TaxKey(String name, BigDecimal rate) {
	}

	public Snapshot build(List<Transaction> transactions) {
		String currency = transactions.getFirst().currency();
		BigDecimal total = BigDecimal.ZERO;
		Map<TaxKey, BigDecimal> taxes = new LinkedHashMap<>();
		Map<String, BigDecimal> fees = new LinkedHashMap<>();
		List<Line> lines = new ArrayList<>();

		for (Transaction t : transactions) {
			if (!t.currency().equals(currency)) {
				throw new ConflictException("currency_mismatch",
						"cannot invoice mixed currencies: " + currency + " and " + t.currency());
			}
			total = total.add(t.total());
			t.taxLines().forEach(l -> taxes.merge(new TaxKey(l.name(), l.rate()), l.amount(), BigDecimal::add));
			t.feeLines().forEach(l -> fees.merge(l.name(), l.amount(), BigDecimal::add));
			lines.add(new Line(t.id(), t.type(), t.externalId(), t.currency(), t.occurredAt(), t.total(),
					List.copyOf(t.taxLines()), List.copyOf(t.feeLines()), t.metadata()));
		}
		List<TaxLine> taxRollup = new ArrayList<>();
		taxes.forEach((k, v) -> taxRollup.add(new TaxLine(k.name(), k.rate(), Money.scale(v))));
		List<FeeLine> feeRollup = new ArrayList<>();
		fees.forEach((k, v) -> feeRollup.add(new FeeLine(k, Money.scale(v))));
		return new Snapshot(currency, Money.scale(total), lines, taxRollup, feeRollup);
	}
}
