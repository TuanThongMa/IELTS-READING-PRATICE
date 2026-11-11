ALTER TABLE vocabulary_entries
    ADD COLUMN examples_loaded boolean NOT NULL DEFAULT false;

-- Older lookups linked every provider result to the requested term, including
-- unrelated headwords found only through phrases. Keep canonical links; valid
-- inflection aliases are rebuilt when those forms are looked up again.
DELETE FROM vocabulary_entry_search_terms
WHERE EXISTS (
    SELECT 1
    FROM vocabulary_entries e
    WHERE e.vocabulary_entry_id = vocabulary_entry_search_terms.vocabulary_entry_id
      AND e.normalized_word <> vocabulary_entry_search_terms.normalized_term
);
