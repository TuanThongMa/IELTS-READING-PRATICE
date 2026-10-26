CREATE TABLE reading_test_attempts (
    reading_test_attempt_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(user_id),
    reading_test_id bigint NOT NULL REFERENCES reading_tests(reading_test_id),
    status text NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'ABANDONED')),
    active_marker boolean,
    elapsed_seconds integer NOT NULL DEFAULT 0 CHECK (elapsed_seconds BETWEEN 0 AND 3600),
    started_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_reading_test_attempt_active_marker CHECK ((status = 'IN_PROGRESS' AND active_marker IS TRUE) OR (status <> 'IN_PROGRESS' AND active_marker IS NULL)),
    CONSTRAINT uq_active_reading_test_attempt UNIQUE (user_id, reading_test_id, active_marker)
);

CREATE TABLE reading_test_answers (
    reading_test_answer_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reading_test_attempt_id bigint NOT NULL REFERENCES reading_test_attempts(reading_test_attempt_id),
    passage_question_id bigint NOT NULL REFERENCES passage_questions(passage_question_id),
    submitted_value text NOT NULL,
    submitted_raw_value text,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reading_test_attempt_answer UNIQUE (reading_test_attempt_id, passage_question_id)
);

CREATE INDEX idx_reading_test_attempts_test ON reading_test_attempts(reading_test_id);
CREATE INDEX idx_reading_test_answers_question ON reading_test_answers(passage_question_id);
