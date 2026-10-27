ALTER TABLE quiz_attempts
    DROP CONSTRAINT IF EXISTS quiz_attempts_status_check;

ALTER TABLE quiz_attempts
    ADD CONSTRAINT ck_quiz_attempts_status
        CHECK (status IN ('IN_PROGRESS', 'PAUSED', 'SUBMITTED', 'ABANDONED'));

ALTER TABLE reading_test_attempts
    DROP CONSTRAINT IF EXISTS reading_test_attempts_status_check;

ALTER TABLE reading_test_attempts
    ADD CONSTRAINT ck_reading_test_attempts_status
        CHECK (status IN ('IN_PROGRESS', 'PAUSED', 'SUBMITTED', 'ABANDONED'));
