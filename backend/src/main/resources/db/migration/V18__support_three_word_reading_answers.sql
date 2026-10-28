ALTER TABLE question_groups
    DROP CONSTRAINT ck_group_word_limit_mode;

ALTER TABLE question_groups
    ADD CONSTRAINT ck_group_word_limit_mode CHECK (
        word_limit_mode IS NULL OR word_limit_mode IN (
            'ONE_WORD',
            'TWO_WORDS',
            'TWO_WORDS_AND_ONE_NUMBER',
            'THREE_WORDS_AND_ONE_NUMBER'
        )
    );
