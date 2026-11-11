-- V24 can associate an old highlight with a passage attempt even when a full
-- test attempt containing the same passage was open at the same time. Neither
-- timestamp proves which reader created it, so retain the row without a scope.
WITH ambiguous AS (
    SELECT h.reading_highlight_id
    FROM reading_highlights h
    JOIN flyway_schema_history migration ON migration.version = '24'
    WHERE h.created_at < migration.installed_on AT TIME ZONE current_setting('TimeZone')
      AND (
          (SELECT count(*) FROM quiz_attempts a
           WHERE a.user_id = h.user_id AND a.passage_id = h.passage_id
             AND a.started_at <= h.created_at
             AND (a.submitted_at IS NULL OR h.created_at <= a.submitted_at))
          +
          (SELECT count(*) FROM reading_test_attempts a
           JOIN test_passages tp ON tp.reading_test_id = a.reading_test_id
           WHERE a.user_id = h.user_id AND tp.passage_id = h.passage_id
             AND a.started_at <= h.created_at
             AND (a.submitted_at IS NULL OR h.created_at <= a.submitted_at))
      ) > 1
)
UPDATE reading_highlights h
SET quiz_attempt_id = NULL, reading_test_attempt_id = NULL
FROM ambiguous
WHERE h.reading_highlight_id = ambiguous.reading_highlight_id;
