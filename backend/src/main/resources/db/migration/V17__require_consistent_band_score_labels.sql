ALTER TABLE academic_band_ranges
    DROP CONSTRAINT ck_academic_band_range_result,
    ADD CONSTRAINT ck_academic_band_range_result CHECK (
        (band_score IS NULL AND result_label = 'Below 4.0')
        OR (band_score IS NOT NULL AND band_score BETWEEN 4.0 AND 9.0 AND result_label <> 'Below 4.0')
    );

ALTER TABLE reading_test_attempts
    DROP CONSTRAINT ck_reading_test_attempt_band_snapshot,
    ADD CONSTRAINT ck_reading_test_attempt_band_snapshot CHECK (
        (academic_band_table_id IS NULL AND band_score IS NULL AND band_result_label IS NULL)
        OR (academic_band_table_id IS NOT NULL AND band_result_label IS NOT NULL
            AND ((band_score IS NULL AND band_result_label = 'Below 4.0')
                 OR (band_score IS NOT NULL AND band_score BETWEEN 4.0 AND 9.0 AND band_result_label <> 'Below 4.0')))
    );
