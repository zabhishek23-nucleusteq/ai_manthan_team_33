package com.expenseflow.mockbank.payout;

public class InvalidPayoutFileException extends RuntimeException {
    public InvalidPayoutFileException(String message) {
        super(message);
    }
}
