package com.expenseflow.service;

/** Test helper: exposes ReceiptService's package-private SHA-256 so API tests can compare hashes. */
public final class ReceiptServiceSha {

    private ReceiptServiceSha() {
    }

    public static String sha(byte[] bytes) {
        return ReceiptService.sha256(bytes);
    }
}
