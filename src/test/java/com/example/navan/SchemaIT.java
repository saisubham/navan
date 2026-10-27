package com.example.navan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.navan.support.IntegrationTestBase;

/** The volume/identity guarantees must live in the schema, not only in a comment. */
class SchemaIT extends IntegrationTestBase {

	private List<Integer> nonUnique(String table, String column) {
		return jdbc.sql("""
				SELECT NON_UNIQUE FROM information_schema.statistics
				WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ? AND seq_in_index = 1""")
				.params(table, column).query(Integer.class).list();
	}

	@Test
	void externalIdHasAUniqueIndex() {
		assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM information_schema.statistics
				WHERE table_schema = DATABASE() AND table_name = 'transactions'
				  AND column_name = 'external_id' AND non_unique = 0""").query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void isInvoicedLookupIsIndexed() {
		assertThat(nonUnique("invoice_transactions", "transaction_id")).isNotEmpty();
	}

	@Test
	void listAndChildLookupsAreIndexed() {
		assertThat(nonUnique("transactions", "created_at")).isNotEmpty();
		assertThat(nonUnique("invoices", "created_at")).isNotEmpty();
		assertThat(nonUnique("transaction_tax_lines", "transaction_id")).isNotEmpty();
		assertThat(nonUnique("transaction_fee_lines", "transaction_id")).isNotEmpty();
		assertThat(nonUnique("flight_coupons", "transaction_id")).isNotEmpty();
		assertThat(nonUnique("rail_legs", "transaction_id")).isNotEmpty();
	}

	@Test
	void everyTableIsInnoDbForRowLocksAndTransactions() {
		assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = DATABASE() AND engine <> 'InnoDB'""").query(Long.class).single()).isZero();
	}

	@Test
	void moneyColumnsAreDecimal() {
		assertThat(jdbc.sql("""
				SELECT COLUMN_TYPE FROM information_schema.columns
				WHERE table_schema = DATABASE() AND table_name = 'transactions' AND column_name = 'total'""")
				.query(String.class).single()).isEqualTo("decimal(19,2)");
	}
}
