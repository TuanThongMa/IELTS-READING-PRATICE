ALTER TABLE quiz_attempts
    ADD COLUMN elapsed_seconds integer NOT NULL DEFAULT 0 CHECK (elapsed_seconds >= 0);

CREATE INDEX idx_quiz_attempts_user_status_submitted_at
    ON quiz_attempts (user_id, status, submitted_at);

CREATE INDEX idx_reading_test_attempts_user_status_submitted_at
    ON reading_test_attempts (user_id, status, submitted_at);
