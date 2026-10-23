CREATE TABLE quiz_attempts (
    quiz_attempt_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(user_id),
    passage_id bigint NOT NULL REFERENCES passages(passage_id),
    mode text NOT NULL DEFAULT 'PRACTICE' CHECK (mode = 'PRACTICE'),
    status text NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'ABANDONED')),
    active_marker boolean,
    started_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_attempt_active_marker CHECK ((status = 'IN_PROGRESS' AND active_marker IS TRUE) OR (status <> 'IN_PROGRESS' AND active_marker IS NULL)),
    CONSTRAINT uq_active_practice UNIQUE (user_id, passage_id, active_marker)
);

CREATE TABLE user_answers (
    user_answer_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quiz_attempt_id bigint NOT NULL REFERENCES quiz_attempts(quiz_attempt_id),
    passage_question_id bigint NOT NULL REFERENCES passage_questions(passage_question_id),
    submitted_value text NOT NULL,
    submitted_raw_value text,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_attempt_question_answer UNIQUE (quiz_attempt_id, passage_question_id)
);

CREATE INDEX idx_attempt_passage ON quiz_attempts(passage_id);
CREATE INDEX idx_user_answers_question ON user_answers(passage_question_id);
