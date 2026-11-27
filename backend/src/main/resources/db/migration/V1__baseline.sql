-- Baseline migration so Flyway owns the schema from the first start.
-- Domain tables come in later migrations (V2__..., one per task).
SELECT 1;
