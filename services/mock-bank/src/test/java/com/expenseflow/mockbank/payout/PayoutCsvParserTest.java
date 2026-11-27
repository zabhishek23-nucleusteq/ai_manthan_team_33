package com.expenseflow.mockbank.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PayoutCsvParserTest {

    static final String HEADER = "reference,employee_id,payee_name,account_number,ifsc,amount_minor,currency\n";

    @Test
    void quotedFieldsWithCommasAndEscapedQuotes() {
        assertThat(PayoutCsvParser.splitLine("a,\"b, c\",\"say \"\"hi\"\"\",d"))
                .containsExactly("a", "b, c", "say \"hi\"", "d");
    }

    @Test
    void headerIsCaseInsensitive_andBomAndCrlfTolerated() {
        String csv = "﻿" + HEADER.toUpperCase().replace("\n", "\r\n")
                + "R1,E1,N,123456,HDFC0001234,1,INR\r\n";
        PayoutCsvParser.Result r = PayoutCsvParser.parse(csv);
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.errors()).isEmpty();
    }

    @Test
    void amountBoundaries() {
        String max = String.valueOf(PayoutCsvParser.MAX_AMOUNT_MINOR);
        String over = String.valueOf(PayoutCsvParser.MAX_AMOUNT_MINOR + 1);
        PayoutCsvParser.Result r = PayoutCsvParser.parse(HEADER
                + "R1,E,N,123456,HDFC0001234,1,INR\n"
                + "R2,E,N,123456,HDFC0001234," + max + ",INR\n"
                + "R3,E,N,123456,HDFC0001234," + over + ",INR\n"
                + "R4,E,N,123456,HDFC0001234,12.50,INR\n");
        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.acceptedAmountMinor()).isEqualTo(1 + PayoutCsvParser.MAX_AMOUNT_MINOR);
        assertThat(r.errors()).extracting(PayoutFileResult.RowError::reference).containsExactly("R3", "R4");
    }

    @Test
    void accountLengthBoundaries() {
        PayoutCsvParser.Result r = PayoutCsvParser.parse(HEADER
                + "R1,E,N,12345,HDFC0001234,1,INR\n"                // 5 digits - too short
                + "R2,E,N,123456,HDFC0001234,1,INR\n"               // 6 ok
                + "R3,E,N,123456789012345678,HDFC0001234,1,INR\n"   // 18 ok
                + "R4,E,N,1234567890123456789,HDFC0001234,1,INR\n"); // 19 too long
        assertThat(r.errors()).extracting(PayoutFileResult.RowError::reference).containsExactly("R1", "R4");
    }

    @Test
    void blankLinesIgnored_missingFieldsRejected() {
        PayoutCsvParser.Result r = PayoutCsvParser.parse(HEADER
                + "\n,E,N,123456,HDFC0001234,1,INR\n\nR2,,N,123456,HDFC0001234,1,INR\nR3,E,N,123456,HDFC0001234,1,inr\n");
        assertThat(r.accepted()).isZero();
        assertThat(r.errors()).extracting(PayoutFileResult.RowError::reason)
                .containsExactly("reference is required", "employee_id is required",
                        "currency must be a 3-letter ISO code");
        assertThat(r.errors().get(0).line()).isEqualTo(3);
    }

    @Test
    void emptyOrHeaderlessFileThrows() {
        assertThatThrownBy(() -> PayoutCsvParser.parse("  ")).isInstanceOf(InvalidPayoutFileException.class);
        assertThatThrownBy(() -> PayoutCsvParser.parse("x,y\n1,2")).isInstanceOf(InvalidPayoutFileException.class);
    }
}
