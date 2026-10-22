CREATE TABLE question_groups (
    question_group_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    passage_id bigint NOT NULL REFERENCES passages(passage_id),
    display_order integer NOT NULL CHECK (display_order > 0),
    question_type text NOT NULL CHECK (question_type IN ('OPTION', 'BLANK', 'MATCHING')),
    task_variant text NOT NULL CHECK (task_variant IN ('MULTIPLE_CHOICE_SINGLE', 'MULTIPLE_CHOICE_MULTIPLE', 'TRUE_FALSE_NOT_GIVEN', 'YES_NO_NOT_GIVEN', 'MATCHING_INFORMATION', 'MATCHING_HEADINGS', 'MATCHING_FEATURES', 'MATCHING_SENTENCE_ENDINGS', 'SENTENCE_COMPLETION', 'SUMMARY_COMPLETION', 'NOTE_COMPLETION', 'TABLE_COMPLETION', 'FLOW_CHART_COMPLETION', 'SHORT_ANSWER')),
    answer_input_mode text NOT NULL CHECK (answer_input_mode IN ('PER_QUESTION_CHOICE', 'SHARED_CHOICE_POOL', 'TEXT_ENTRY')),
    candidate_reuse boolean NOT NULL DEFAULT false,
    instructions text NOT NULL,
    presentation_template text,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_question_groups_order UNIQUE (passage_id, display_order)
);

CREATE TABLE passage_questions (
    passage_question_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_group_id bigint NOT NULL REFERENCES question_groups(question_group_id),
    question_number integer NOT NULL CHECK (question_number > 0),
    question_text text NOT NULL,
    explanation text,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_group_question_number UNIQUE (question_group_id, question_number)
);

CREATE TABLE option_questions (
    option_question_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    answer_label text NOT NULL,
    option_text text NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE question_answer_options (
    passage_question_id bigint NOT NULL REFERENCES passage_questions(passage_question_id),
    option_question_id bigint NOT NULL REFERENCES option_questions(option_question_id),
    is_correct boolean NOT NULL DEFAULT false,
    display_order integer NOT NULL CHECK (display_order > 0),
    PRIMARY KEY (passage_question_id, option_question_id),
    CONSTRAINT uq_question_option_order UNIQUE (passage_question_id, display_order)
);

CREATE INDEX idx_question_answer_options_option ON question_answer_options(option_question_id);
