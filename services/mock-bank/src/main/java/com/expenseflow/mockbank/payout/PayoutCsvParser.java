package com.expenseflow.mockbank.payout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and validates the mock bank payout file.
 *
 * <pre>
 * reference,employee_id,payee_name,account_number,ifsc,amount_minor,currency
 * CLM-1001,EMP-001,Asha Rao,123456789012,HDFC0001234,125050,INR
 * </pre>
 *
 * Each row is accepted or rejected on its own; a missing/unknown header rejects the whole file.
 */
public final class PayoutCsvParser {

    public static final List<String> HEADER = List.of(
            "reference", "employee_id", "payee_name", "account_number", "ifsc", "amount_minor", "currency");

    private static final Pattern ACCOUNT = Pattern.compile("\\d{6,18}");
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    static final long MAX_AMOUNT_MINOR = 100_000_000L; // 10 lakh in paise per line

    private PayoutCsvParser() {
    }

    public record Result(int accepted, List<PayoutFileResult.RowError> errors, long acceptedAmountMinor) {
    }

    public static Result parse(String content) {
        if (content == null || content.isBlank()) {
            throw new InvalidPayoutFileException("File is empty");
        }
        String[] lines = content.replace("﻿", "").split("\\r?\\n");
        List<String> header = splitLine(lines[0]).stream().map(s -> s.trim().toLowerCase()).toList();
        if (!header.equals(HEADER)) {
            throw new InvalidPayoutFileException("Header must be: " + String.join(",", HEADER));
        }
        int accepted = 0;
        long amount = 0;
        List<PayoutFileResult.RowError> errors = new ArrayList<>();
        Set<String> references = new HashSet<>();
        int dataRows = 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            dataRows++;
            int lineNo = i + 1;
            List<String> cols = splitLine(lines[i]).stream().map(String::trim).toList();
            String reason = validate(cols, references);
            if (reason == null) {
                accepted++;
                amount += Long.parseLong(cols.get(5));
            } else {
                errors.add(new PayoutFileResult.RowError(lineNo, cols.isEmpty() ? null : cols.get(0), reason));
            }
        }
        if (dataRows == 0) {
            throw new InvalidPayoutFileException("File has no payout rows");
        }
        return new Result(accepted, errors, amount);
    }

    private static String validate(List<String> c, Set<String> references) {
        if (c.size() != HEADER.size()) {
            return "Expected " + HEADER.size() + " columns, got " + c.size();
        }
        if (c.get(0).isEmpty()) {
            return "reference is required";
        }
        if (!references.add(c.get(0))) {
            return "Duplicate reference in file";
        }
        if (c.get(1).isEmpty()) {
            return "employee_id is required";
        }
        if (c.get(2).isEmpty()) {
            return "payee_name is required";
        }
        if (!ACCOUNT.matcher(c.get(3)).matches()) {
            return "account_number must be 6-18 digits";
        }
        if (!IFSC.matcher(c.get(4)).matches()) {
            return "ifsc is invalid";
        }
        long amount;
        try {
            amount = Long.parseLong(c.get(5));
        } catch (NumberFormatException e) {
            return "amount_minor must be a whole number";
        }
        if (amount <= 0) {
            return "amount_minor must be positive";
        }
        if (amount > MAX_AMOUNT_MINOR) {
            return "amount_minor exceeds per-line limit " + MAX_AMOUNT_MINOR;
        }
        if (!CURRENCY.matcher(c.get(6)).matches()) {
            return "currency must be a 3-letter ISO code";
        }
        return null;
    }

    /** Splits one CSV line, honouring double-quoted fields with "" escapes. */
    static List<String> splitLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
