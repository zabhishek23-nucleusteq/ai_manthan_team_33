package com.expenseflow.mockbank.fault;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Behaviour of the bank endpoints when a fault is triggered. */
public enum FaultMode {
    OK("ok"),
    ERROR_500("500"),
    TIMEOUT("timeout");

    private final String value;

    FaultMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static FaultMode from(String raw) {
        if (raw == null) {
            return null;
        }
        for (FaultMode m : values()) {
            if (m.value.equalsIgnoreCase(raw.trim())) {
                return m;
            }
        }
        throw new IllegalArgumentException("mode must be one of ok|500|timeout");
    }
}
