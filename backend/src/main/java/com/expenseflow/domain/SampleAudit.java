package com.expenseflow.domain;

/** Sample audit state (BR-13): NONE, PENDING (picked for the 10% sample), OK or EXCEPTION after review. */
public enum SampleAudit {
    NONE, PENDING, OK, EXCEPTION
}
