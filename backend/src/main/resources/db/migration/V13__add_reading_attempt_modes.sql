ALTER TABLE quiz_attempts
    DROP CONSTRAINT IF EXISTS quiz_attempts_mode_check;

ALTER TABLE quiz_attempts
    ADD CONSTRAINT ck_quiz_attempts_mode
        CHECK (mode IN ('PRACTICE', 'REAL_TEST'));

ALTER TABLE reading_test_attempts
    ADD COLUMN mode text NOT NULL DEFAULT 'PRACTICE';

ALTER TABLE reading_test_attempts
    ADD CONSTRAINT ck_reading_test_attempts_mode
        CHECK (mode IN ('PRACTICE', 'REAL_TEST'));
