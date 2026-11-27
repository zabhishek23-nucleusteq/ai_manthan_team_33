package com.expenseflow.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Indian number formatting for reason messages: 1500 -> "1,500", 100000.5 -> "1,00,000.50". */
public final class PolicyFormat {

    private static final Locale EN_IN = Locale.forLanguageTag("en-IN");

    private PolicyFormat() {
    }

    public static String money(BigDecimal v) {
        if (v == null) return "-";
        BigDecimal x = v.setScale(2, RoundingMode.HALF_UP);
        boolean whole = x.stripTrailingZeros().scale() <= 0;
        NumberFormat f = NumberFormat.getNumberInstance(EN_IN);
        f.setMinimumFractionDigits(whole ? 0 : 2);
        f.setMaximumFractionDigits(whole ? 0 : 2);
        f.setGroupingUsed(true);
        return f.format(x);
    }
}