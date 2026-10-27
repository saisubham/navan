package com.example.navan.transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.example.navan.common.Exceptions.ValidationException;
import com.example.navan.common.Money;

@Component
class HotelMetadataHandler implements MetadataHandler {

	@Override
	public String type() {
		return "hotel";
	}

	@Override
	public Set<String> knownKeys() {
		return Set.of("name", "address", "check_in", "check_out", "nights", "room_rate_per_night", "extras");
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> raw) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", Meta.string(raw, "name", "metadata.", true));
		Map<String, Object> address = Meta.map(raw, "address", "metadata.");
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("street", Meta.string(address, "street", "metadata.address.", true));
		a.put("city", Meta.string(address, "city", "metadata.address.", true));
		a.put("country", Meta.string(address, "country", "metadata.address.", true));
		out.put("address", a);
		LocalDate in = Meta.date(raw, "check_in", "metadata.");
		LocalDate outDate = Meta.date(raw, "check_out", "metadata.");
		if (!outDate.isAfter(in)) {
			throw new ValidationException("metadata.check_out must be after check_in");
		}
		out.put("check_in", in.toString());
		out.put("check_out", outDate.toString());
		out.put("nights", Meta.integer(raw, "nights", "metadata."));
		out.put("room_rate_per_night", Meta.decimal(raw, "room_rate_per_night", "metadata."));
		List<Map<String, Object>> extras = new ArrayList<>();
		List<Map<String, Object>> rawExtras = Meta.list(raw, "extras", "metadata.", false);
		for (int i = 0; i < rawExtras.size(); i++) {
			String p = "metadata.extras[" + i + "].";
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("name", Meta.string(rawExtras.get(i), "name", p, true));
			e.put("amount", Meta.decimal(rawExtras.get(i), "amount", p));
			extras.add(e);
		}
		if (!extras.isEmpty()) {
			out.put("extras", extras);
		}
		return out;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void insert(JdbcClient jdbc, String id, Map<String, Object> m) {
		Map<String, Object> a = (Map<String, Object>) m.get("address");
		jdbc.sql("""
				INSERT INTO hotel_details
				(transaction_id, name, street, city, country, check_in, check_out, nights, room_rate_per_night)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(id, m.get("name"), a.get("street"), a.get("city"), a.get("country"),
						LocalDate.parse((String) m.get("check_in")), LocalDate.parse((String) m.get("check_out")),
						m.get("nights"), m.get("room_rate_per_night"))
				.update();
		int seq = 0;
		for (Map<String, Object> e : (List<Map<String, Object>>) m.getOrDefault("extras", List.of())) {
			jdbc.sql("INSERT INTO hotel_extras (transaction_id, seq, name, amount) VALUES (?, ?, ?, ?)")
					.params(id, seq++, e.get("name"), e.get("amount")).update();
		}
	}

	@Override
	public void delete(JdbcClient jdbc, String id) {
		jdbc.sql("DELETE FROM hotel_extras WHERE transaction_id = ?").param(id).update();
		jdbc.sql("DELETE FROM hotel_details WHERE transaction_id = ?").param(id).update();
	}

	@Override
	public Map<String, Map<String, Object>> load(JdbcClient jdbc, Collection<String> ids) {
		Map<String, Map<String, Object>> out = new HashMap<>();
		jdbc.sql("SELECT * FROM hotel_details WHERE transaction_id IN (:ids)").param("ids", ids).query((rs, n) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", rs.getString("name"));
			Map<String, Object> a = new LinkedHashMap<>();
			a.put("street", rs.getString("street"));
			a.put("city", rs.getString("city"));
			a.put("country", rs.getString("country"));
			m.put("address", a);
			m.put("check_in", rs.getObject("check_in", LocalDate.class).toString());
			m.put("check_out", rs.getObject("check_out", LocalDate.class).toString());
			m.put("nights", rs.getInt("nights"));
			m.put("room_rate_per_night", Money.scale(rs.getBigDecimal("room_rate_per_night")));
			out.put(rs.getString("transaction_id"), m);
			return null;
		}).list();
		jdbc.sql("SELECT transaction_id, name, amount FROM hotel_extras WHERE transaction_id IN (:ids) ORDER BY transaction_id, seq")
				.param("ids", ids).query((rs, n) -> {
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("name", rs.getString("name"));
					e.put("amount", Money.scale(rs.getBigDecimal("amount")));
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> extras = (List<Map<String, Object>>) out.get(rs.getString("transaction_id"))
							.computeIfAbsent("extras", k -> new ArrayList<Map<String, Object>>());
					extras.add(e);
					return null;
				}).list();
		return out;
	}
}
