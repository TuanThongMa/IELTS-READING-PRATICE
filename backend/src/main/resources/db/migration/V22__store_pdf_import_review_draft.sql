ALTER TABLE pdf_import_jobs ADD COLUMN review_draft jsonb;

ALTER TABLE pdf_import_jobs ADD CONSTRAINT ck_pdf_import_review_draft_object
    CHECK (review_draft IS NULL OR jsonb_typeof(review_draft) = 'object');
