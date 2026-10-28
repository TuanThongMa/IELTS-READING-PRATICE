CREATE TABLE academic_band_tables (
    academic_band_table_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version text NOT NULL UNIQUE,
    source_reference text NOT NULL,
    effective_from date NOT NULL,
    retired_at date,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_academic_band_table_retired_after_effective
        CHECK (retired_at IS NULL OR retired_at > effective_from)
);

CREATE TABLE academic_band_ranges (
    academic_band_range_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academic_band_table_id bigint NOT NULL REFERENCES academic_band_tables(academic_band_table_id),
    min_raw_score smallint NOT NULL CHECK (min_raw_score BETWEEN 0 AND 40),
    max_raw_score smallint NOT NULL CHECK (max_raw_score BETWEEN 0 AND 40),
    band_score numeric(2,1),
    result_label text NOT NULL,
    CONSTRAINT uq_academic_band_range_start UNIQUE (academic_band_table_id, min_raw_score),
    CONSTRAINT ck_academic_band_range_bounds CHECK (min_raw_score <= max_raw_score),
    CONSTRAINT ck_academic_band_range_result CHECK (
        (band_score IS NULL AND result_label = 'Below 4.0')
        OR (band_score BETWEEN 4.0 AND 9.0 AND result_label <> 'Below 4.0')
    )
);

INSERT INTO academic_band_tables (version, source_reference, effective_from)
VALUES (
    'academic-reading-practice-v2',
    'IELTS Academic scoring guidance: https://www.ielts.org/take-a-test/your-results/ielts-scoring-in-detail ; https://ielts.idp.com/thailand/results/scores/reading. Practice guidance; raw-score thresholds vary by test version.',
    DATE '2026-10-23'
);

INSERT INTO academic_band_ranges (academic_band_table_id, min_raw_score, max_raw_score, band_score, result_label)
SELECT t.academic_band_table_id, r.min_raw_score, r.max_raw_score, r.band_score, r.result_label
FROM academic_band_tables t
CROSS JOIN (VALUES
    (0, 10, NULL::numeric, 'Below 4.0'),
    (11, 12, 4.0, '4.0'),
    (13, 15, 4.5, '4.5'),
    (16, 17, 5.0, '5.0'),
    (18, 22, 5.5, '5.5'),
    (23, 25, 6.0, '6.0'),
    (26, 29, 6.5, '6.5'),
    (30, 31, 7.0, '7.0'),
    (32, 34, 7.5, '7.5'),
    (35, 36, 8.0, '8.0'),
    (37, 38, 8.5, '8.5'),
    (39, 40, 9.0, '9.0')
) AS r(min_raw_score, max_raw_score, band_score, result_label)
WHERE t.version = 'academic-reading-practice-v2';

ALTER TABLE reading_test_attempts
    ADD COLUMN total_answered integer NOT NULL DEFAULT 0,
    ADD COLUMN total_correct integer NOT NULL DEFAULT 0,
    ADD COLUMN academic_band_table_id bigint REFERENCES academic_band_tables(academic_band_table_id),
    ADD COLUMN band_score numeric(2,1),
    ADD COLUMN band_result_label text,
    ADD CONSTRAINT ck_reading_test_attempt_answer_counts
        CHECK (total_correct BETWEEN 0 AND total_answered),
    ADD CONSTRAINT ck_reading_test_attempt_band_snapshot
        CHECK (
            (academic_band_table_id IS NULL AND band_score IS NULL AND band_result_label IS NULL)
            OR (academic_band_table_id IS NOT NULL AND band_result_label IS NOT NULL
                AND ((band_score IS NULL AND band_result_label = 'Below 4.0')
                     OR (band_score BETWEEN 4.0 AND 9.0 AND band_result_label <> 'Below 4.0')))
        );

WITH scores AS (
    SELECT a.reading_test_attempt_id,
           COUNT(*) FILTER (WHERE ra.reading_test_answer_id IS NOT NULL)::integer AS total_answered,
           COUNT(*) FILTER (WHERE ra.reading_test_answer_id IS NOT NULL AND CASE g.question_type
               WHEN 'BLANK' THEN EXISTS (
                   SELECT 1
                   FROM blank_questions b
                   WHERE b.passage_question_id = q.passage_question_id
                     AND (
                         regexp_replace(lower(normalize(b.answer_blank, NFC)), '\s+', ' ', 'g') = ra.submitted_value
                         OR EXISTS (
                             SELECT 1 FROM blank_answer_variants v
                             WHERE v.blank_question_id = b.blank_question_id
                               AND v.answer_normalized = ra.submitted_value
                         )
                     )
               )
               WHEN 'MATCHING' THEN EXISTS (
                   SELECT 1 FROM matching_question_answers ma
                   JOIN matching_questions m ON m.matching_question_id = ma.matching_question_id
                   WHERE ma.passage_question_id = q.passage_question_id
                     AND m.answer_label = ra.submitted_value
               )
               ELSE EXISTS (
                   SELECT 1 FROM question_answer_options qa
                   JOIN option_questions o ON o.option_question_id = qa.option_question_id
                   WHERE qa.passage_question_id = q.passage_question_id
                     AND qa.is_correct AND o.answer_label = ra.submitted_value
               )
           END)::integer AS total_correct
    FROM reading_test_attempts a
    JOIN test_passages tp ON tp.reading_test_id = a.reading_test_id
    JOIN question_groups g ON g.passage_id = tp.passage_id
    JOIN passage_questions q ON q.question_group_id = g.question_group_id
    LEFT JOIN reading_test_answers ra
        ON ra.reading_test_attempt_id = a.reading_test_attempt_id
       AND ra.passage_question_id = q.passage_question_id
    WHERE a.status = 'SUBMITTED'
    GROUP BY a.reading_test_attempt_id
), active_table AS (
    SELECT academic_band_table_id
    FROM academic_band_tables
    WHERE version = 'academic-reading-practice-v2'
)
UPDATE reading_test_attempts a
SET total_answered = s.total_answered,
    total_correct = s.total_correct,
    academic_band_table_id = t.academic_band_table_id,
    band_score = r.band_score,
    band_result_label = r.result_label
FROM scores s
CROSS JOIN active_table t
JOIN academic_band_ranges r ON r.academic_band_table_id = t.academic_band_table_id
WHERE a.reading_test_attempt_id = s.reading_test_attempt_id
  AND s.total_correct BETWEEN r.min_raw_score AND r.max_raw_score;

ALTER TABLE reading_test_attempts
    ADD CONSTRAINT ck_submitted_reading_test_has_band_snapshot
        CHECK (status <> 'SUBMITTED' OR academic_band_table_id IS NOT NULL);
