CREATE TABLE reading_tests (
    reading_test_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title text NOT NULL,
    status text NOT NULL DEFAULT 'DRAFT',
    created_by_user_id bigint NOT NULL REFERENCES users(user_id),
    published_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_reading_tests_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_reading_tests_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_reading_tests_published_at CHECK (
        (status = 'DRAFT' AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR status = 'ARCHIVED'
    )
);

CREATE TABLE test_passages (
    reading_test_id bigint NOT NULL REFERENCES reading_tests(reading_test_id),
    passage_id bigint NOT NULL REFERENCES passages(passage_id),
    display_order smallint NOT NULL CHECK (display_order BETWEEN 1 AND 3),
    question_start_number smallint NOT NULL CHECK (question_start_number BETWEEN 1 AND 40),
    question_end_number smallint NOT NULL CHECK (question_end_number BETWEEN 1 AND 40),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reading_test_id, passage_id),
    CONSTRAINT uq_test_passages_display_order UNIQUE (reading_test_id, display_order),
    CONSTRAINT ck_test_passage_question_range CHECK (question_start_number <= question_end_number)
);

CREATE INDEX idx_test_passages_passage ON test_passages(passage_id);
CREATE INDEX idx_reading_tests_created_by ON reading_tests(created_by_user_id);
