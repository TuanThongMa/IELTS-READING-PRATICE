ALTER TABLE reading_test_attempts
    DROP CONSTRAINT IF EXISTS reading_test_attempts_elapsed_seconds_check;

ALTER TABLE reading_test_attempts
    ADD CONSTRAINT ck_reading_test_attempt_elapsed_nonnegative
        CHECK (elapsed_seconds >= 0);

ALTER TABLE reading_test_attempts
    ADD COLUMN clock_started_at timestamp with time zone;

UPDATE reading_test_attempts
SET clock_started_at = CURRENT_TIMESTAMP
WHERE status = 'IN_PROGRESS';
