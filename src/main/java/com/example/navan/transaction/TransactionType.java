package com.example.navan.transaction;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The four transaction types and the metadata record each one binds to. An unknown value in a request is
 * rejected by Jackson (400) because this is an enum.
 */
public enum TransactionType {

	FLIGHT("flight", TransactionMetadata.Flight.class),
	HOTEL("hotel", TransactionMetadata.Hotel.class),
	RAIL("rail", TransactionMetadata.Rail.class),
	NAVAN_FEE("navan_fee", TransactionMetadata.NavanFee.class);

	private final String value;
	private final Class<? extends TransactionMetadata> metadataType;

	TransactionType(String value, Class<? extends TransactionMetadata> metadataType) {
		this.value = value;
		this.metadataType = metadataType;
	}

	/** The wire / database value, e.g. "navan_fee". */
	@JsonValue
	public String value() {
		return value;
	}

	Class<? extends TransactionMetadata> metadataType() {
		return metadataType;
	}

	static TransactionType fromValue(String value) {
		for (TransactionType t : values()) {
			if (t.value.equals(value)) {
				return t;
			}
		}
		throw new IllegalArgumentException("unknown transaction type: " + value);
	}
}
