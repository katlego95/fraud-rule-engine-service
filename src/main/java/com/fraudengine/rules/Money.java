package com.fraudengine.rules;

import java.math.BigDecimal;

/** Rand formatting for the human-readable reason strings on rule outcomes. */
final class Money {

    private Money() {}

    static String format(BigDecimal amount) {
        return "R" + amount.toPlainString();
    }
}
