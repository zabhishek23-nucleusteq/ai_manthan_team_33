package com.expenseflow.mockbank.payout;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and validates the BR-17 bank file (docs/requirements.md).
 *
 * <pre>
 * RECORD_TYPE,BATCH_ID,VALUE_DATE,EMPLOYEE_CODE,BENEFICIARY_NAME,ACCOUNT_NO,IFSC,AMOUNT,CURRENCY,NARRATION
 * D,PB-7,2026-11-27,EMP0001,Asha Rao,123456789012,HDFC0001234,1250.50,INR,DEMO-1001
 * T,PB-7,2026-11-27,1,1250.50
 * </pre>
 *
 * File-level problems (header, trailer, batch/date consistency, count/total) throw
 * {@link InvalidPayoutFileException}; row-level problems reject only that D row.
 */
public final class PayoutCsvParser {

    public static final List<String> HEADER = List.of("RECORD_TYPE", "BATCH_ID", "VALUE_DATE", "EMPLOYEE_CODE",
            "BENEFICIARY_NAME", "ACCOUNT_NO", "IFSC", "AMOUNT", "CURRENCY", "NARRATION");

    private static final int TRAILER_COLUMNS = 5;
    private static final Pattern ACCOUNT = Pattern.compile("\\d{6,18}");
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    private static final Pattern AMOUNT = Pattern.compile("-?\\d+\\.\\d{2}");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00"); // per line

    private PayoutCsvParser() {
    }

    public record Result(String batchId, LocalDate valueDate, int accepted, List<PayoutFileResult.RowError> errors,
            BigDecimal acceptedAmount) {
    }

    public static Result parse(String content) {
        if (content == null || content.isBlank()) {
            throw new InvalidPayoutFileException("File is empty");
        }
        List<String[]> lines = new ArrayList<>(); // [lineNo, raw]
        String[] raw = content.replace("﻿", "").split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                lines.add(new String[] {String.valueOf(i + 1), raw[i]});
            }
        }
        List<String> header = splitLine(lines.get(0)[1]).stream().map(s -> s.trim().toUpperCase()).toList();
        if (!header.equals(HEADER)) {
            throw new InvalidPayoutFileException("Header must be: " + String.join(",", HEADER));
        }

        List<String> trailer = null;
        String batchId = null;
        LocalDate valueDate = null;
        int dRows = 0;
        boolean allAmountsParsed = true;
        BigDecimal fileTotal = BigDecimal.ZERO;
        BigDecimal accepted = BigDecimal.ZERO;
        int acceptedCount = 0;
        List<PayoutFileResult.RowError> errors = new ArrayList<>();
        Set<String> narrations = new HashSet<>();

        for (int i = 1; i < lines.size(); i++) {
            int lineNo = Integer.parseInt(lines.get(i)[0]);
            List<String> c = splitLine(lines.get(i)[1]).stream().map(String::trim).toList();
            String type = c.get(0).toUpperCase();
            if (trailer != null) {
                throw new InvalidPayoutFileException("Line " + lineNo + ": trailer (T) must be the last row");
            }
            if (type.equals("T")) {
                trailer = c;
                continue;
            }
            if (!type.equals("D")) {
                throw new InvalidPayoutFileException("Line " + lineNo + ": RECORD_TYPE must be D or T");
            }
            dRows++;
            if (c.size() != HEADER.size()) {
                errors.add(new PayoutFileResult.RowError(lineNo, null,
                        "Expected " + HEADER.size() + " columns, got " + c.size()));
                allAmountsParsed = false;
                continue;
            }
            if (batchId == null) {
                batchId = requireBatchId(c.get(1), lineNo);
                valueDate = parseDate(c.get(2), lineNo);
            } else if (!batchId.equals(c.get(1))) {
                throw new InvalidPayoutFileException("Line " + lineNo + ": all rows must have BATCH_ID " + batchId);
            } else if (!valueDate.equals(parseDate(c.get(2), lineNo))) {
                throw new InvalidPayoutFileException("Line " + lineNo + ": all rows must have VALUE_DATE " + valueDate);
            }
            BigDecimal amount = AMOUNT.matcher(c.get(7)).matches() ? new BigDecimal(c.get(7)) : null;
            if (amount == null) {
                allAmountsParsed = false;
            } else {
                fileTotal = fileTotal.add(amount);
            }
            String reason = validateRow(c, amount, narrations);
            if (reason == null) {
                acceptedCount++;
                accepted = accepted.add(amount);
            } else {
                errors.add(new PayoutFileResult.RowError(lineNo, c.get(9).isEmpty() ? null : c.get(9), reason));
            }
        }

        if (dRows == 0) {
            throw new InvalidPayoutFileException("File has no D rows");
        }
        if (trailer == null) {
            throw new InvalidPayoutFileException("Missing trailer row T,<batchId>,<valueDate>,<count>,<total>");
        }
        checkTrailer(trailer, batchId, valueDate, dRows, allAmountsParsed ? fileTotal : null);
        return new Result(batchId, valueDate, acceptedCount, errors, accepted);
    }

    private static String validateRow(List<String> c, BigDecimal amount, Set<String> narrations) {
        if (c.get(9).isEmpty()) {
            return "NARRATION (claim code) is required";
        }
        if (!narrations.add(c.get(9))) {
            return "Duplicate NARRATION (claim code) in file";
        }
        if (c.get(3).isEmpty()) {
            return "EMPLOYEE_CODE is required";
        }
        if (c.get(4).isEmpty()) {
            return "BENEFICIARY_NAME is required";
        }
        if (!ACCOUNT.matcher(c.get(5)).matches()) {
            return "ACCOUNT_NO must be 6-18 digits";
        }
        if (!IFSC.matcher(c.get(6)).matches()) {
            return "IFSC is invalid";
        }
        if (amount == null) {
            return "AMOUNT must have exactly 2 decimals";
        }
        if (amount.signum() <= 0) {
            return "AMOUNT must be positive";
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            return "AMOUNT exceeds per-line limit " + MAX_AMOUNT;
        }
        if (!"INR".equals(c.get(8))) {
            return "CURRENCY must be INR";
        }
        return null;
    }

    private static void checkTrailer(List<String> t, String batchId, LocalDate valueDate, int dRows,
            BigDecimal fileTotal) {
        if (t.size() != TRAILER_COLUMNS) {
            throw new InvalidPayoutFileException("Trailer must be T,<batchId>,<valueDate>,<count>,<total>");
        }
        if (batchId != null && !batchId.equals(t.get(1))) {
            throw new InvalidPayoutFileException("Trailer BATCH_ID " + t.get(1) + " does not match " + batchId);
        }
        if (valueDate != null && !valueDate.toString().equals(t.get(2))) {
            throw new InvalidPayoutFileException("Trailer VALUE_DATE " + t.get(2) + " does not match " + valueDate);
        }
        if (!String.valueOf(dRows).equals(t.get(3))) {
            throw new InvalidPayoutFileException("Trailer count " + t.get(3) + " does not match " + dRows + " D rows");
        }
        if (fileTotal != null) {
            BigDecimal total;
            try {
                total = new BigDecimal(t.get(4));
            } catch (NumberFormatException e) {
                throw new InvalidPayoutFileException("Trailer total is not a number");
            }
            if (total.compareTo(fileTotal) != 0) {
                throw new InvalidPayoutFileException("Trailer total " + t.get(4) + " does not match sum " + fileTotal);
            }
        }
    }

    private static String requireBatchId(String id, int lineNo) {
        if (id.isEmpty()) {
            throw new InvalidPayoutFileException("Line " + lineNo + ": BATCH_ID is required");
        }
        return id;
    }

    private static LocalDate parseDate(String s, int lineNo) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new InvalidPayoutFileException("Line " + lineNo + ": VALUE_DATE must be yyyy-MM-dd");
        }
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
