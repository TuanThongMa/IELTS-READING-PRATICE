CREATE TABLE pdf_import_jobs (
    pdf_import_job_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by_user_id bigint NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    scope text NOT NULL CHECK (scope IN ('PASSAGE', 'FULL_TEST')),
    part_number smallint,
    status text NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'REVIEW_READY', 'DRAFT_SAVED', 'PUBLISHED',
                         'FAILED', 'CANCELLED', 'EXPIRED')),
    cancel_requested boolean NOT NULL DEFAULT false,
    source_storage_key uuid NOT NULL UNIQUE,
    original_filename text NOT NULL CHECK (length(original_filename) BETWEEN 1 AND 255),
    page_count integer NOT NULL CHECK (page_count BETWEEN 1 AND 25),
    processed_pages integer NOT NULL DEFAULT 0 CHECK (processed_pages >= 0),
    review_data jsonb,
    review_version integer NOT NULL DEFAULT 0 CHECK (review_version >= 0),
    error_code text,
    applied_passage_ids bigint[],
    applied_test_id bigint REFERENCES reading_tests(reading_test_id),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamp with time zone NOT NULL DEFAULT (CURRENT_TIMESTAMP + interval '7 days'),
    CONSTRAINT ck_pdf_import_scope_part CHECK (
        (scope = 'PASSAGE' AND part_number BETWEEN 1 AND 3)
        OR (scope = 'FULL_TEST' AND part_number IS NULL)
    ),
    CONSTRAINT ck_pdf_import_progress CHECK (processed_pages <= page_count)
);

CREATE INDEX idx_pdf_import_jobs_owner_recent
    ON pdf_import_jobs (created_by_user_id, created_at DESC, pdf_import_job_id DESC);
CREATE INDEX idx_pdf_import_jobs_queue
    ON pdf_import_jobs (created_at, pdf_import_job_id)
    WHERE status = 'QUEUED';
