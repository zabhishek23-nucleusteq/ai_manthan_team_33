package com.expenseflow.mockbank.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** BR-17 bank file: header, one D row per claim, T trailer {@code T,<batchId>,<valueDate>,<count>,<total>}. */
class PayoutCsvParserTest {

    static final String HEADER =
            "RECORD_TYPE,BATCH_ID,VALUE_DATE,EMPLOYEE_CODE,BENEFICIARY_NAME,ACCOUNT_NO,IFSC,AMOUNT,CURRENCY,NARRATION\n";

    static String d(String emp, String acct, String ifsc, String amount, String cur, String narration) {
        return String.join(",", "D", "PB-7", "2026-11-27", emp, "Asha Rao", acct, ifsc, amount, cur, narration) + "\n";
    }

    static String t(int count, String total) {
        return "T,PB-7,2026-11-27," + count + "," + total + "\n";
    }

    @Test
    void br17File_isAccepted() {
        PayoutCsvParser.Result r = PayoutCsvParser.parse(HEADER
                + d("EMP0001", "123456789012", "HDFC0001234", "1250.50", "INR", "DEMO-1001")
                + d("EMP0002", "98765432", "ICIC0004321", "99.00", "INR", "DEMO-1002")
                + t(2, "1349.50"));
        assertThat(r.batchId()).isEqualTo("PB-7");
        assertThat(r.valueDate()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.errors()).isEmpty();
        assertThat(r.acceptedAmount()).isEqualByComparingTo("1349.50");
    }

    @Test
    void quotedFieldsWithCommasAndEscapedQuotes() {
        assertThat(PayoutCsvParser.splitLine("a,\"b, c\",\"say \"\"hi\"\"\",d"))
                .containsExactly("a", "b, c", "say \"hi\"", "d");
    }

    @Test
    void bomAndCrlfTolerated() {
        String csv = "﻿" + (HEADER + d("E1", "123456", "HDFC0001234", "1.00", "INR", "C-1") + t(1, "1.00"))
                .replace("\n", "\r\n");
        assertThat(PayoutCsvParser.parse(csv).accepted()).isEqualTo(1);
    }

    @Test
    void rowLevelRejections() {
        PayoutCsvParser.Result r = PayoutCsvParser.parse(HEADER
                + d("EMP1", "123456", "HDFC0001234", "10.00", "INR", "C-1")   // ok
                + d("EMP2", "12ab", "HDFC0001234", "10.00", "INR", "C-2")     // account
                + d("EMP3", "123456", "BAD", "10.00", "INR", "C-3")           // ifsc
                + d("EMP4", "123456", "HDFC0001234", "0.00", "INR", "C-4")    // zero
                + d("EMP5", "123456", "HDFC0001234", "10.5", "INR", "C-5")    // not 2 decimals
                + d("EMP6", "123456", "HDFC0001234", "10.00", "USD", "C-6")   // currency
                + d("EMP7", "123456", "HDFC0001234", "10.00", "INR", "C-1")   // duplicate narration
                + d("", "123456", "HDFC0001234", "10.00", "INR", "C-8")       // employee code
                + t(8, "70.00"));
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.acceptedAmount()).isEqualByComparingTo("10.00");
        assertThat(r.errors()).extracting(PayoutFileResult.RowError::reason).containsExactly(
                "ACCOUNT_NO must be 6-18 digits",
                "IFSC is invalid",
                "AMOUNT must be positive",
                "AMOUNT must have exactly 2 decimals",
                "CURRENCY must be INR",
                "Duplicate NARRATION (claim code) in file",
                "EMPLOYEE_CODE is required");
        assertThat(r.errors().get(0).line()).isEqualTo(3);
        assertThat(r.errors().get(0).reference()).isEqualTo("C-2");
    }

    @Test
    void amountBoundaries() {
        PayoutCsvParser.Result r = PayoutCsvParser.parse(HEADER
                + d("E1", "123456", "HDFC0001234", "0.01", "INR", "C-1")
                + d("E2", "123456", "HDFC0001234", "1000000.00", "INR", "C-2")
                + d("E3", "123456", "HDFC0001234", "1000000.01", "INR", "C-3")
                + t(3, "2000000.02"));
        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.acceptedAmount()).isEqualByComparingTo(new BigDecimal("1000000.01"));
        assertThat(r.errors()).extracting(PayoutFileResult.RowError::reference).containsExactly("C-3");
    }

    @Test
    void accountLengthBoundaries() {
        PayoutCsvParser.Result r = PayoutCsvParser.parse(HEADER
                + d("E", "12345", "HDFC0001234", "1.00", "INR", "R1")
                + d("E", "123456", "HDFC0001234", "1.00", "INR", "R2")
                + d("E", "123456789012345678", "HDFC0001234", "1.00", "INR", "R3")
                + d("E", "1234567890123456789", "HDFC0001234", "1.00", "INR", "R4")
                + t(4, "4.00"));
        assertThat(r.errors()).extracting(PayoutFileResult.RowError::reference).containsExactly("R1", "R4");
    }

    @Test
    void fileLevelErrors() {
        String row = d("E1", "123456", "HDFC0001234", "1.00", "INR", "C-1");
        assertInvalid("  ", "empty");
        assertInvalid("reference,employee_id\nX,1\n", "Header must be");
        assertInvalid(HEADER + t(0, "0.00"), "no D rows");
        assertInvalid(HEADER + row, "trailer");
        assertInvalid(HEADER + t(1, "1.00") + row, "trailer");
        assertInvalid(HEADER + row + t(2, "1.00"), "count");
        assertInvalid(HEADER + row + t(1, "2.00"), "total");
        assertInvalid(HEADER + row + "T,PB-8,2026-11-27,1,1.00\n", "BATCH_ID");
        assertInvalid(HEADER + row + row.replace("PB-7", "PB-9") + t(2, "2.00"), "BATCH_ID");
        assertInvalid(HEADER + row.replace("2026-11-27", "27/11/2026") + t(1, "1.00"), "VALUE_DATE");
        assertInvalid(HEADER + "X,PB-7,2026-11-27,E,N,123456,HDFC0001234,1.00,INR,C\n" + t(1, "1.00"), "RECORD_TYPE");
    }

    private static void assertInvalid(String csv, String messagePart) {
        assertThatThrownBy(() -> PayoutCsvParser.parse(csv))
                .isInstanceOf(InvalidPayoutFileException.class)
                .hasMessageContaining(messagePart);
    }
}
