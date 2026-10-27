package com.example.navan.transaction;

import java.time.Instant;
import java.time.LocalDateTime;
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

@Component
class RailMetadataHandler implements MetadataHandler {

	@Override
	public String type() {
		return "rail";
	}

	@Override
	public Set<String> knownKeys() {
		return Set.of("legs");
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> raw) {
		List<Map<String, Object>> rawLegs = Meta.list(raw, "legs", "metadata.", true);
		if (rawLegs.isEmpty()) {
			throw new ValidationException("metadata.legs must contain at least one leg");
		}
		List<Map<String, Object>> legs = new ArrayList<>();
		for (int i = 0; i < rawLegs.size(); i++) {
			Map<String, Object> l = rawLegs.get(i);
			String p = "metadata.legs[" + i + "].";
			Map<String, Object> n = new LinkedHashMap<>();
			n.put("origin", Meta.string(l, "origin", p, true));
			n.put("destination", Meta.string(l, "destination", p, true));
			n.put("departure", Meta.instant(l, "departure", p).toString());
			n.put("arrival", Meta.instant(l, "arrival", p).toString());
			String train = Meta.string(l, "train_number", p, false);
			if (train != null) {
				n.put("train_number", train);
			}
			String cls = Meta.string(l, "class", p, false);
			if (cls != null) {
				n.put("class", cls);
			}
			legs.add(n);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("legs", legs);
		return out;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void insert(JdbcClient jdbc, String id, Map<String, Object> m) {
		int seq = 0;
		for (Map<String, Object> l : (List<Map<String, Object>>) m.get("legs")) {
			jdbc.sql("""
					INSERT INTO rail_legs
					(transaction_id, seq, origin, destination, departure, arrival, train_number, travel_class)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
					.params(id, seq++, l.get("origin"), l.get("destination"),
							Meta.utc(Instant.parse((String) l.get("departure"))),
							Meta.utc(Instant.parse((String) l.get("arrival"))), l.get("train_number"), l.get("class"))
					.update();
		}
	}

	@Override
	public void delete(JdbcClient jdbc, String id) {
		jdbc.sql("DELETE FROM rail_legs WHERE transaction_id = ?").param(id).update();
	}

	@Override
	public Map<String, Map<String, Object>> load(JdbcClient jdbc, Collection<String> ids) {
		Map<String, Map<String, Object>> out = new HashMap<>();
		jdbc.sql("SELECT * FROM rail_legs WHERE transaction_id IN (:ids) ORDER BY transaction_id, seq")
				.param("ids", ids).query((rs, n) -> {
					Map<String, Object> l = new LinkedHashMap<>();
					l.put("origin", rs.getString("origin"));
					l.put("destination", rs.getString("destination"));
					l.put("departure", Meta.instant(rs.getObject("departure", LocalDateTime.class)).toString());
					l.put("arrival", Meta.instant(rs.getObject("arrival", LocalDateTime.class)).toString());
					if (rs.getString("train_number") != null) {
						l.put("train_number", rs.getString("train_number"));
					}
					if (rs.getString("travel_class") != null) {
						l.put("class", rs.getString("travel_class"));
					}
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> legs = (List<Map<String, Object>>) out
							.computeIfAbsent(rs.getString("transaction_id"), k -> {
								Map<String, Object> m = new LinkedHashMap<>();
								m.put("legs", new ArrayList<Map<String, Object>>());
								return m;
							}).get("legs");
					legs.add(l);
					return null;
				}).list();
		return out;
	}
}
