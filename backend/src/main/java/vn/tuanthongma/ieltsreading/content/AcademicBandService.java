package vn.tuanthongma.ieltsreading.content;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class AcademicBandService {
    private final JdbcTemplate jdbc;

    AcademicBandService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record BandResult(long tableId, String tableVersion, BigDecimal score, String label) {}

    BandResult forRawScore(int correctAnswers) {
        List<BandResult> matches = jdbc.query("""
                SELECT t.academic_band_table_id, t.version, r.band_score, r.result_label
                FROM academic_band_tables t
                JOIN academic_band_ranges r ON r.academic_band_table_id = t.academic_band_table_id
                WHERE t.retired_at IS NULL
                  AND t.effective_from <= CURRENT_DATE
                  AND r.min_raw_score <= ? AND r.max_raw_score >= ?
                ORDER BY t.effective_from DESC, t.academic_band_table_id DESC
                LIMIT 1
                """, (rs, row) -> new BandResult(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)),
                correctAnswers, correctAnswers);
        if (matches.isEmpty()) throw new IllegalStateException("No active Academic Reading band range covers the raw score.");
        return matches.getFirst();
    }
}
