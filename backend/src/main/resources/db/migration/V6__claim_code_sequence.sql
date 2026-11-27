-- T-016 (L2): codes for claims created through the API, e.g. EXP-2026-50001.
-- Starts at 50001 so it can never collide with seeded codes (EXP-<year>-00001..03000) or DEMO-10xx.
CREATE SEQUENCE claim_code_seq START WITH 50001;
