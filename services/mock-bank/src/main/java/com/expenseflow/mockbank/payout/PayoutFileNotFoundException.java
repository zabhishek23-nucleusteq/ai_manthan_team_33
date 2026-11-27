package com.expenseflow.mockbank.payout;

public class PayoutFileNotFoundException extends RuntimeException {
    public PayoutFileNotFoundException(String fileId) {
        super("Payout file not found: " + fileId);
    }
}
