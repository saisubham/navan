package com.example.navan.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is BigDecimal, scale 2, HALF_UP. Rates are scale 4 in the DB. Never double. */
public final class Money {

	public static final int SCALE = 2;

	private Money() {
	}

	public static BigDecimal scale(BigDecimal value) {
		return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
	}

	/** Rate for output: drop trailing zeros but keep at least 2 decimals (0.0700 -> 0.07). */
	public static BigDecimal rate(BigDecimal value) {
		if (value == null) {
			return null;
		}
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 2 ? stripped.setScale(2, RoundingMode.UNNECESSARY) : stripped;
	}
}
