package com.example.navan.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.example.navan.common.Money;

@Component
class FlightMetadataHandler implements MetadataHandler {

	@Override
	public String type() {
		return "flight";
	}

	@Override
	public Set<String> knownKeys() {
		return Set.of("origin", "destination", "fare", "coupons");
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> raw) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("origin", Meta.string(raw, "origin", "metadata.", true));
		out.put("destination", Meta.string(raw, "destination", "metadata.", true));
		out.put("fare", Meta.decimal(raw, "fare", "metadata."));
		List<Map<String, Object>> coupons = new ArrayList<>();
		List<Map<String, Object>> rawCoupons = Meta.list(raw, "coupons", "metadata.", true);
		for (int i = 0; i < rawCoupons.size(); i++) {
			Map<String, Object> c = rawCoupons.get(i);
			String p = "metadata.coupons[" + i + "].";
			Map<String, Object> n = new LinkedHashMap<>();
			String carrier = Meta.string(c, "carrier", p, false);
			if (carrier != null) {
				n.put("carrier", carrier);
			}
			n.put("flight_number", Meta.string(c, "flight_number", p, true));
			n.put("from", Meta.string(c, "from", p, true));
			n.put("to", Meta.string(c, "to", p, true));
			n.put("departure", Meta.instant(c, "departure", p).toString());
			n.put("arrival", Meta.instant(c, "arrival", p).toString());
			coupons.add(n);
		}
		out.put("coupons", coupons);
		return out;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void insert(JdbcClient jdbc, String id, Map<String, Object> m) {
		jdbc.sql("INSERT INTO flight_details (transaction_id, origin, destination, fare) VALUES (?, ?, ?, ?)")
				.params(id, m.get("origin"), m.get("destination"), m.get("fare")).update();
		int seq = 0;
		for (Map<String, Object> c : (List<Map<String, Object>>) m.get("coupons")) {
			jdbc.sql("""
					INSERT INTO flight_coupons
					(transaction_id, seq, flight_number, carrier, from_location, to_location, departure, arrival)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
					.params(id, seq++, c.get("flight_number"), c.get("carrier"), c.get("from"), c.get("to"),
							Meta.utc(Instant.parse((String) c.get("departure"))),
							Meta.utc(Instant.parse((String) c.get("arrival"))))
					.update();
		}
	}

	@Override
	public void delete(JdbcClient jdbc, String id) {
		jdbc.sql("DELETE FROM flight_coupons WHERE transaction_id = ?").param(id).update();
		jdbc.sql("DELETE FROM flight_details WHERE transaction_id = ?").param(id).update();
	}

	@Override
	public Map<String, Map<String, Object>> load(JdbcClient jdbc, Collection<String> ids) {
		Map<String, Map<String, Object>> out = new HashMap<>();
		jdbc.sql("SELECT transaction_id, origin, destination, fare FROM flight_details WHERE transaction_id IN (:ids)")
				.param("ids", ids).query((rs, n) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("origin", rs.getString("origin"));
					m.put("destination", rs.getString("destination"));
					m.put("fare", Money.scale(rs.getBigDecimal("fare")));
					m.put("coupons", new ArrayList<Map<String, Object>>());
					out.put(rs.getString("transaction_id"), m);
					return null;
				}).list();
		jdbc.sql("""
				SELECT transaction_id, carrier, flight_number, from_location, to_location, departure, arrival
				FROM flight_coupons WHERE transaction_id IN (:ids) ORDER BY transaction_id, seq""")
				.param("ids", ids).query((rs, n) -> {
					Map<String, Object> c = new LinkedHashMap<>();
					if (rs.getString("carrier") != null) {
						c.put("carrier", rs.getString("carrier"));
					}
					c.put("flight_number", rs.getString("flight_number"));
					c.put("from", rs.getString("from_location"));
					c.put("to", rs.getString("to_location"));
					c.put("departure", Meta.instant(rs.getObject("departure", java.time.LocalDateTime.class)).toString());
					c.put("arrival", Meta.instant(rs.getObject("arrival", java.time.LocalDateTime.class)).toString());
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> coupons = (List<Map<String, Object>>) out.get(rs.getString("transaction_id"))
							.get("coupons");
					coupons.add(c);
					return null;
				}).list();
		return out;
	}
}
