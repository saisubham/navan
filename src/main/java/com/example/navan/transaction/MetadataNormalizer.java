package com.example.navan.transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.navan.common.Exceptions.ValidationException;

/**
 * Validates the type-specific metadata and returns its canonical form (ISO strings for timestamps and
 * dates, scale-2 BigDecimal for money). The result is stored as-is in the {@code transactions.metadata}
 * JSON column. Keys we do not know about are kept after the known ones, so extra fields round-trip.
 */
@Component
public class MetadataNormalizer {

	static final Set<String> TYPES = Set.of("flight", "hotel", "rail", "navan_fee");
	static final Set<String> FEE_KINDS = Set.of("trip_fee", "agent_call_fee");

	public void requireKnownType(String type) {
		if (type == null || !TYPES.contains(type)) {
			throw new ValidationException("type must be one of " + TYPES.stream().sorted().toList());
		}
	}

	public Map<String, Object> normalize(String type, Map<String, Object> raw) {
		requireKnownType(type);
		if (raw == null) {
			throw new ValidationException("metadata is required");
		}
		Map<String, Object> out = switch (type) {
			case "flight" -> flight(raw);
			case "hotel" -> hotel(raw);
			case "rail" -> rail(raw);
			default -> navanFee(raw);
		};
		raw.forEach(out::putIfAbsent);
		return out;
	}

	private Map<String, Object> flight(Map<String, Object> raw) {
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

	private Map<String, Object> hotel(Map<String, Object> raw) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", Meta.string(raw, "name", "metadata.", true));
		Map<String, Object> address = Meta.map(raw, "address", "metadata.");
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("street", Meta.string(address, "street", "metadata.address.", true));
		a.put("city", Meta.string(address, "city", "metadata.address.", true));
		a.put("country", Meta.string(address, "country", "metadata.address.", true));
		out.put("address", a);
		LocalDate checkIn = Meta.date(raw, "check_in", "metadata.");
		LocalDate checkOut = Meta.date(raw, "check_out", "metadata.");
		if (!checkOut.isAfter(checkIn)) {
			throw new ValidationException("metadata.check_out must be after check_in");
		}
		out.put("check_in", checkIn.toString());
		out.put("check_out", checkOut.toString());
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

	private Map<String, Object> rail(Map<String, Object> raw) {
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

	private Map<String, Object> navanFee(Map<String, Object> raw) {
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
}
