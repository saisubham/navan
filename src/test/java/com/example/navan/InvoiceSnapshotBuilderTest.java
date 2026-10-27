package com.example.navan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.navan.common.Exceptions.ConflictException;
import com.example.navan.common.Money;
import com.example.navan.invoice.InvoiceSnapshotBuilder;
import com.example.navan.invoice.InvoiceSnapshotBuilder.Snapshot;
import com.example.navan.transaction.Transaction;
import com.example.navan.transaction.Transaction.FeeLine;
import com.example.navan.transaction.Transaction.TaxLine;

/** Pure logic, no container. */
class InvoiceSnapshotBuilderTest {

	private final InvoiceSnapshotBuilder builder = new InvoiceSnapshotBuilder();

	private static BigDecimal d(String s) {
		return new BigDecimal(s);
	}

	private static Transaction txn(String id, String currency, String total, List<TaxLine> taxes, List<FeeLine> fees) {
		return new Transaction(id, "flight", "ext-" + id, currency, Instant.parse("2026-03-11T06:40:00Z"), d(total),
				taxes, fees, Map.of(), Instant.now(), Instant.now());
	}

	@Test
	void sumsWithoutFloatingPointDrift() {
		Snapshot s = builder.build(List.of(
				txn("a", "EUR", "0.10", List.of(), List.of()),
				txn("b", "EUR", "0.20", List.of(), List.of()),
				txn("c", "EUR", "0.30", List.of(), List.of())));
		assertThat(s.total()).isEqualTo(d("0.60"));
		assertThat(s.total().scale()).isEqualTo(2);
	}

	@Test
	void groupsTaxesByNameAndRateAndFeesByName() {
		Snapshot s = builder.build(List.of(
				txn("a", "EUR", "100.00", List.of(new TaxLine("VAT", d("0.07"), d("7.00"))),
						List.of(new FeeLine("YQ", d("10.00")))),
				txn("b", "EUR", "100.00",
						List.of(new TaxLine("VAT", d("0.07"), d("7.00")), new TaxLine("VAT", d("0.19"), d("19.00"))),
						List.of(new FeeLine("YQ", d("5.50")), new FeeLine("Booking fee", d("4.00"))))));

		assertThat(s.taxRollup()).containsExactly(new TaxLine("VAT", d("0.07"), d("14.00")),
				new TaxLine("VAT", d("0.19"), d("19.00")));
		assertThat(s.feeRollup()).containsExactly(new FeeLine("YQ", d("15.50")), new FeeLine("Booking fee", d("4.00")));
		assertThat(s.lines()).hasSize(2);
	}

	@Test
	void mixedCurrenciesAreAConflict() {
		assertThatThrownBy(() -> builder.build(List.of(txn("a", "EUR", "1.00", List.of(), List.of()),
				txn("b", "USD", "1.00", List.of(), List.of())))).isInstanceOf(ConflictException.class);
	}

	@Test
	void moneyScalesToTwoDecimalsHalfUp() {
		assertThat(Money.scale(d("1.005"))).isEqualTo(d("1.01"));
		assertThat(Money.scale(d("220"))).isEqualTo(d("220.00"));
		assertThat(Money.rate(d("0.0700"))).isEqualTo(d("0.07"));
	}
}
