-- A note represents one selected dictionary sense in one source context.
-- Keep distinct contexts/passages while merging repeat saves of the same occurrence.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM vocabulary_notes
        WHERE vocabulary_sense_id IS NOT NULL
        GROUP BY user_id, vocabulary_sense_id, COALESCE(passage_id, 0), COALESCE(context_sentence, '')
        HAVING COUNT(*) > 1
           AND COUNT(DISTINCT NULLIF(btrim(custom_meaning), '')) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate vocabulary notes contain conflicting custom meanings; review them before V20';
    END IF;
END $$;

CREATE TEMP TABLE vocabulary_note_duplicate_map AS
SELECT vocabulary_note_id,
       FIRST_VALUE(vocabulary_note_id) OVER (
           PARTITION BY user_id, vocabulary_sense_id, COALESCE(passage_id, 0), COALESCE(context_sentence, '')
           ORDER BY updated_at DESC, vocabulary_note_id DESC
       ) AS keeper_id
FROM vocabulary_notes
WHERE vocabulary_sense_id IS NOT NULL;

INSERT INTO note_categories (user_id, vocabulary_note_id, category_id)
SELECT nc.user_id, dm.keeper_id, nc.category_id
FROM vocabulary_note_duplicate_map dm
JOIN note_categories nc ON nc.vocabulary_note_id = dm.vocabulary_note_id
WHERE dm.vocabulary_note_id <> dm.keeper_id
ON CONFLICT DO NOTHING;

UPDATE vocabulary_notes keeper
SET custom_meaning = source.custom_meaning
FROM (
    SELECT dm.keeper_id, MAX(NULLIF(btrim(n.custom_meaning), '')) AS custom_meaning
    FROM vocabulary_note_duplicate_map dm
    JOIN vocabulary_notes n ON n.vocabulary_note_id = dm.vocabulary_note_id
    GROUP BY dm.keeper_id
) source
WHERE keeper.vocabulary_note_id = source.keeper_id
  AND NULLIF(btrim(keeper.custom_meaning), '') IS NULL
  AND source.custom_meaning IS NOT NULL;

DELETE FROM vocabulary_notes n
USING vocabulary_note_duplicate_map dm
WHERE n.vocabulary_note_id = dm.vocabulary_note_id
  AND dm.vocabulary_note_id <> dm.keeper_id;

-- Hash the optional 4,000-character context so the index stays small.
CREATE UNIQUE INDEX uq_vocabulary_notes_occurrence
ON vocabulary_notes (
    user_id,
    vocabulary_sense_id,
    COALESCE(passage_id, 0),
    md5(COALESCE(context_sentence, ''))
)
WHERE vocabulary_sense_id IS NOT NULL;

DROP TABLE vocabulary_note_duplicate_map;
