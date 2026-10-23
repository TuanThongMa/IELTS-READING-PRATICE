ALTER TABLE question_groups ADD COLUMN word_limit_mode text;
ALTER TABLE question_groups ADD CONSTRAINT ck_group_word_limit_mode CHECK (
    word_limit_mode IS NULL OR word_limit_mode IN ('ONE_WORD', 'TWO_WORDS', 'TWO_WORDS_AND_ONE_NUMBER')
);

CREATE TABLE blank_questions (
    blank_question_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    passage_question_id bigint NOT NULL UNIQUE REFERENCES passage_questions(passage_question_id),
    blank_text text NOT NULL,
    answer_blank text NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE blank_answer_variants (
    blank_answer_variant_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    blank_question_id bigint NOT NULL REFERENCES blank_questions(blank_question_id),
    answer_text text NOT NULL,
    answer_normalized text NOT NULL,
    CONSTRAINT uq_blank_answer_variant UNIQUE (blank_question_id, answer_normalized)
);
