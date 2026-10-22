CREATE TABLE passages (
    passage_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title text NOT NULL,
    body text NOT NULL,
    part_number smallint NOT NULL,
    status text NOT NULL DEFAULT 'DRAFT',
    version_number integer NOT NULL DEFAULT 1,
    replaces_passage_id bigint REFERENCES passages(passage_id),
    created_by_user_id bigint NOT NULL REFERENCES users(user_id),
    published_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_passages_part CHECK (part_number BETWEEN 1 AND 3),
    CONSTRAINT ck_passages_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_passages_version CHECK (version_number > 0),
    CONSTRAINT ck_passages_published_at CHECK (
        (status = 'DRAFT' AND published_at IS NULL) OR
        (status = 'PUBLISHED' AND published_at IS NOT NULL) OR status = 'ARCHIVED'
    )
);

CREATE INDEX idx_passages_created_by ON passages(created_by_user_id);
CREATE INDEX idx_passages_replaces ON passages(replaces_passage_id);
