CREATE TABLE matching_questions (
    matching_question_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_group_id bigint NOT NULL REFERENCES question_groups(question_group_id),
    parent_matching_question_id bigint,
    node_kind text NOT NULL CHECK (node_kind IN ('FRAME', 'CANDIDATE')),
    answer_label text,
    candidate_text text,
    display_order integer NOT NULL CHECK (display_order > 0),
    CONSTRAINT ck_matching_node CHECK ((node_kind = 'FRAME' AND parent_matching_question_id IS NULL AND answer_label IS NULL AND candidate_text IS NULL) OR (node_kind = 'CANDIDATE' AND parent_matching_question_id IS NOT NULL AND answer_label IS NOT NULL AND candidate_text IS NOT NULL AND length(trim(answer_label)) > 0 AND length(trim(candidate_text)) > 0)),
    CONSTRAINT uq_matching_candidate_label UNIQUE (question_group_id, answer_label),
    CONSTRAINT uq_matching_candidate_group_id UNIQUE (question_group_id, matching_question_id),
    FOREIGN KEY (question_group_id, parent_matching_question_id) REFERENCES matching_questions(question_group_id, matching_question_id)
);

ALTER TABLE passage_questions ADD CONSTRAINT uq_passage_question_group_id UNIQUE (question_group_id, passage_question_id);

CREATE TABLE matching_question_answers (
    question_group_id bigint NOT NULL,
    passage_question_id bigint PRIMARY KEY,
    matching_question_id bigint NOT NULL,
    FOREIGN KEY (question_group_id, passage_question_id) REFERENCES passage_questions(question_group_id, passage_question_id),
    FOREIGN KEY (question_group_id, matching_question_id) REFERENCES matching_questions(question_group_id, matching_question_id)
);

CREATE INDEX idx_matching_parent ON matching_questions(question_group_id, parent_matching_question_id);
CREATE INDEX idx_matching_answer_candidate ON matching_question_answers(question_group_id, matching_question_id);
