-- T-017 (L2): result of the last bank submit attempt (SUBMIT_FAILED reason, or rows the bank rejected)
-- and the bank's file id, so a retry can be traced.
ALTER TABLE payout_batches ADD COLUMN last_error VARCHAR(1000);
ALTER TABLE payout_batches ADD COLUMN bank_file_id VARCHAR(80);
