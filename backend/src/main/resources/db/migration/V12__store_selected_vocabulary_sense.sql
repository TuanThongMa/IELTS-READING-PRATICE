ALTER TABLE vocabulary_notes
    ADD COLUMN vocabulary_sense_id bigint;

-- Preserve the matching dictionary sense for existing notes where possible.
UPDATE vocabulary_notes n
SET vocabulary_sense_id = COALESCE(
    (
        SELECT s.vocabulary_sense_id
        FROM vocabulary_senses s
        JOIN vocabulary_meanings m ON m.vocabulary_meaning_id = s.vocabulary_meaning_id
        WHERE s.vocabulary_entry_id = n.vocabulary_entry_id
          AND m.definition_text = n.selected_definition
        ORDER BY s.display_order, s.vocabulary_sense_id
        LIMIT 1
    ),
    (
        SELECT s.vocabulary_sense_id
        FROM vocabulary_senses s
        WHERE s.vocabulary_entry_id = n.vocabulary_entry_id
        ORDER BY s.display_order, s.vocabulary_sense_id
        LIMIT 1
    )
)
WHERE EXISTS (
    SELECT 1
    FROM vocabulary_senses s
    WHERE s.vocabulary_entry_id = n.vocabulary_entry_id
);

ALTER TABLE vocabulary_notes
    ADD CONSTRAINT fk_vocabulary_notes_selected_sense
        FOREIGN KEY (vocabulary_sense_id)
        REFERENCES vocabulary_senses(vocabulary_sense_id)
        ON DELETE SET NULL;

CREATE INDEX idx_vocabulary_notes_sense ON vocabulary_notes(vocabulary_sense_id);
