ALTER TABLE reading_highlights
    ADD COLUMN quiz_attempt_id bigint REFERENCES quiz_attempts(quiz_attempt_id) ON DELETE CASCADE,
    ADD COLUMN reading_test_attempt_id bigint REFERENCES reading_test_attempts(reading_test_attempt_id) ON DELETE CASCADE;

-- Legacy highlights had no attempt ID. Match the attempt that was open when
-- the highlight was created; leave unmatched rows intact rather than guess.
WITH matches AS (
    SELECT h.reading_highlight_id, a.quiz_attempt_id,
           row_number() OVER (PARTITION BY h.reading_highlight_id ORDER BY a.started_at DESC, a.quiz_attempt_id DESC) AS rank
    FROM reading_highlights h
    JOIN quiz_attempts a ON a.user_id = h.user_id AND a.passage_id = h.passage_id
        AND a.started_at <= h.created_at
        AND (a.submitted_at IS NULL OR h.created_at <= a.submitted_at)
)
UPDATE reading_highlights h
SET quiz_attempt_id = matches.quiz_attempt_id
FROM matches
WHERE h.reading_highlight_id = matches.reading_highlight_id AND matches.rank = 1;

WITH matches AS (
    SELECT h.reading_highlight_id, a.reading_test_attempt_id,
           row_number() OVER (PARTITION BY h.reading_highlight_id ORDER BY a.started_at DESC, a.reading_test_attempt_id DESC) AS rank
    FROM reading_highlights h
    JOIN test_passages tp ON tp.passage_id = h.passage_id
    JOIN reading_test_attempts a ON a.reading_test_id = tp.reading_test_id AND a.user_id = h.user_id
        AND a.started_at <= h.created_at
        AND (a.submitted_at IS NULL OR h.created_at <= a.submitted_at)
    WHERE h.quiz_attempt_id IS NULL
)
UPDATE reading_highlights h
SET reading_test_attempt_id = matches.reading_test_attempt_id
FROM matches
WHERE h.reading_highlight_id = matches.reading_highlight_id AND matches.rank = 1;

ALTER TABLE reading_highlights
    DROP CONSTRAINT uq_reading_highlights_range,
    ADD CONSTRAINT ck_reading_highlights_one_attempt
        CHECK (num_nonnulls(quiz_attempt_id, reading_test_attempt_id) <= 1);

CREATE UNIQUE INDEX uq_reading_highlights_quiz_attempt_range
    ON reading_highlights (quiz_attempt_id, passage_id, start_offset, end_offset)
    WHERE quiz_attempt_id IS NOT NULL;
CREATE UNIQUE INDEX uq_reading_highlights_test_attempt_range
    ON reading_highlights (reading_test_attempt_id, passage_id, start_offset, end_offset)
    WHERE reading_test_attempt_id IS NOT NULL;
CREATE INDEX idx_reading_highlights_passage ON reading_highlights (passage_id);
