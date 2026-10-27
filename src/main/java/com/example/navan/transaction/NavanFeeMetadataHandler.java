package com.example.navan.transaction;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.example.navan.common.Exceptions.ValidationException;

@Component
class NavanFeeMetadataHandler implements MetadataHandler {

	static final Set<String> FEE_KINDS = Set.of("trip_fee", "agent_call_fee");

	@Override
	public String type() {
		return "navan_fee";
	}

	@Override
	public Set<String> knownKeys() {
		return Set.of("fee_kind", "description", "related_external_id");
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> raw) {
		String kind = Meta.string(raw, "fee_kind", "metadata.", true);
		if (!FEE_KINDS.contains(kind)) {
			throw new ValidationException("metadata.fee_kind must be one of " + FEE_KINDS.stream().sorted().toList());
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("fee_kind", kind);
		String description = Meta.string(raw, "description", "metadata.", false);
		if (description != null) {
			out.put("description", description);
		}
		String related = Meta.string(raw, "related_external_id", "metadata.", false);
		if (related != null) {
			out.put("related_external_id", related);
		}
		return out;
	}

	@Override
	public void insert(JdbcClient jdbc, String id, Map<String, Object> m) {
		jdbc.sql("INSERT INTO navan_fee_details (transaction_id, fee_kind, description, related_external_id) VALUES (?, ?, ?, ?)")
				.params(id, m.get("fee_kind"), m.get("description"), m.get("related_external_id")).update();
	}

	@Override
	public void delete(JdbcClient jdbc, String id) {
		jdbc.sql("DELETE FROM navan_fee_details WHERE transaction_id = ?").param(id).update();
	}

	@Override
	public Map<String, Map<String, Object>> load(JdbcClient jdbc, Collection<String> ids) {
		Map<String, Map<String, Object>> out = new HashMap<>();
		jdbc.sql("SELECT * FROM navan_fee_details WHERE transaction_id IN (:ids)").param("ids", ids).query((rs, n) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("fee_kind", rs.getString("fee_kind"));
			if (rs.getString("description") != null) {
				m.put("description", rs.getString("description"));
			}
			if (rs.getString("related_external_id") != null) {
				m.put("related_external_id", rs.getString("related_external_id"));
			}
			out.put(rs.getString("transaction_id"), m);
			return null;
		}).list();
		return out;
	}
}
