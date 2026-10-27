CREATE TABLE categories (
    category_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    name text NOT NULL,
    name_normalized text NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_categories_name CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    CONSTRAINT uq_categories_user_name UNIQUE (user_id, name_normalized),
    CONSTRAINT uq_categories_user_id UNIQUE (user_id, category_id)
);

CREATE TABLE vocabulary_entries (
    vocabulary_entry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    word text NOT NULL,
    normalized_word text NOT NULL,
    language_code text NOT NULL DEFAULT 'en',
    provider text NOT NULL,
    provider_entry_id text,
    source_url text NOT NULL,
    fetched_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_vocabulary_entries_word UNIQUE (language_code, normalized_word)
);

CREATE TABLE vocabulary_entry_search_terms (
    language_code text NOT NULL DEFAULT 'en',
    normalized_term text NOT NULL,
    vocabulary_entry_id bigint NOT NULL REFERENCES vocabulary_entries(vocabulary_entry_id) ON DELETE CASCADE,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (language_code, normalized_term, vocabulary_entry_id)
);
CREATE INDEX idx_vocabulary_entry_search_terms_entry ON vocabulary_entry_search_terms(vocabulary_entry_id);

CREATE TABLE vocabulary_phonetics (
    vocabulary_phonetic_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    phonetic_text text,
    audio_url text,
    provider_audio_id text,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vocabulary_phonetics_content CHECK (phonetic_text IS NOT NULL OR audio_url IS NOT NULL)
);

CREATE TABLE vocabulary_meanings (
    vocabulary_meaning_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    definition_text text NOT NULL,
    example text,
    provider_definition_id text,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vocabulary_meanings_definition CHECK (length(btrim(definition_text)) > 0)
);

CREATE TABLE vocabulary_senses (
    vocabulary_sense_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vocabulary_entry_id bigint NOT NULL REFERENCES vocabulary_entries(vocabulary_entry_id) ON DELETE CASCADE,
    vocabulary_phonetic_id bigint REFERENCES vocabulary_phonetics(vocabulary_phonetic_id) ON DELETE SET NULL,
    vocabulary_meaning_id bigint NOT NULL REFERENCES vocabulary_meanings(vocabulary_meaning_id) ON DELETE CASCADE,
    part_of_speech text NOT NULL,
    provider_entry_id text,
    display_order integer NOT NULL DEFAULT 1 CHECK (display_order > 0),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vocabulary_senses_part_of_speech CHECK (length(btrim(part_of_speech)) > 0)
);
CREATE INDEX idx_vocabulary_senses_entry_order ON vocabulary_senses(vocabulary_entry_id, display_order, vocabulary_sense_id);

CREATE TABLE vocabulary_related_words (
    vocabulary_related_word_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vocabulary_meaning_id bigint NOT NULL REFERENCES vocabulary_meanings(vocabulary_meaning_id) ON DELETE CASCADE,
    related_word text NOT NULL,
    normalized_related_word text NOT NULL,
    relation_type text NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vocabulary_related_words_type CHECK (relation_type IN ('SYNONYM', 'ANTONYM')),
    CONSTRAINT uq_vocabulary_related_words_meaning UNIQUE (vocabulary_meaning_id, relation_type, normalized_related_word)
);

CREATE TABLE vocabulary_notes (
    vocabulary_note_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    vocabulary_entry_id bigint NOT NULL REFERENCES vocabulary_entries(vocabulary_entry_id),
    passage_id bigint REFERENCES passages(passage_id),
    selected_text text NOT NULL,
    context_sentence text NOT NULL,
    custom_meaning text,
    selected_definition text NOT NULL,
    review_status text NOT NULL DEFAULT 'NEW',
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vocabulary_notes_selected_text CHECK (length(btrim(selected_text)) > 0),
    CONSTRAINT ck_vocabulary_notes_definition CHECK (length(btrim(selected_definition)) > 0),
    CONSTRAINT ck_vocabulary_notes_review_status CHECK (review_status IN ('NEW', 'LEARNING', 'MASTERED')),
    CONSTRAINT uq_vocabulary_notes_user_id UNIQUE (user_id, vocabulary_note_id)
);
CREATE INDEX idx_vocabulary_notes_user_recent ON vocabulary_notes(user_id, created_at DESC, vocabulary_note_id DESC);
CREATE INDEX idx_vocabulary_notes_user_status ON vocabulary_notes(user_id, review_status, created_at DESC);

CREATE TABLE note_categories (
    user_id bigint NOT NULL,
    vocabulary_note_id bigint NOT NULL,
    category_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, vocabulary_note_id, category_id),
    CONSTRAINT fk_note_categories_note_owner FOREIGN KEY (user_id, vocabulary_note_id)
        REFERENCES vocabulary_notes(user_id, vocabulary_note_id) ON DELETE CASCADE,
    CONSTRAINT fk_note_categories_category_owner FOREIGN KEY (user_id, category_id)
        REFERENCES categories(user_id, category_id) ON DELETE CASCADE
);
CREATE INDEX idx_note_categories_category ON note_categories(user_id, category_id, vocabulary_note_id);

CREATE TABLE reading_highlights (
    reading_highlight_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    passage_id bigint NOT NULL REFERENCES passages(passage_id) ON DELETE CASCADE,
    start_offset integer NOT NULL,
    end_offset integer NOT NULL,
    selected_text text NOT NULL,
    prefix_context text NOT NULL DEFAULT '',
    suffix_context text NOT NULL DEFAULT '',
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_reading_highlights_offsets CHECK (start_offset >= 0 AND end_offset > start_offset),
    CONSTRAINT ck_reading_highlights_quote CHECK (length(btrim(selected_text)) BETWEEN 1 AND 2000),
    CONSTRAINT uq_reading_highlights_range UNIQUE (user_id, passage_id, start_offset, end_offset)
);
CREATE INDEX idx_reading_highlights_user_passage ON reading_highlights(user_id, passage_id, start_offset);
