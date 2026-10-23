package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reading/history")
class PracticeHistoryController {
    private static final int PAGE_SIZE = 20;
    private final JdbcTemplate jdbc;
    private final PracticeResultController results;
    PracticeHistoryController(JdbcTemplate jdbc, PracticeResultController results) { this.jdbc = jdbc; this.results = results; }

    record AttemptRow(long id, long passageId, String title, int partNumber, OffsetDateTime submittedAt) {}
    record Entry(long attemptId, long passageId, String title, int partNumber, OffsetDateTime submittedAt, int correct, int total, double accuracy) {}
    record Page(List<Entry> entries, int page, boolean hasMore) {}

    @GetMapping
    @Transactional(readOnly = true)
    Page history(@RequestParam(required = false) Long passageId, @RequestParam(defaultValue = "0") int page, Principal principal) {
        if (page < 0 || page > 10000 || (passageId != null && passageId <= 0)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        long userId = Long.parseLong(principal.getName());
        String filter = passageId == null ? "" : " AND a.passage_id = ?";
        String sql = """
                SELECT a.quiz_attempt_id, p.passage_id, p.title, p.part_number, a.submitted_at
                FROM quiz_attempts a JOIN passages p ON p.passage_id = a.passage_id
                WHERE a.user_id = ? AND a.status = 'SUBMITTED'
                """ + filter + " ORDER BY a.submitted_at DESC, a.quiz_attempt_id DESC LIMIT ? OFFSET ?";
        Object[] params = passageId == null ? new Object[]{userId, PAGE_SIZE + 1, page * PAGE_SIZE}
                : new Object[]{userId, passageId, PAGE_SIZE + 1, page * PAGE_SIZE};
        List<AttemptRow> rows = jdbc.query(sql, (r, n) -> new AttemptRow(r.getLong(1), r.getLong(2), r.getString(3), r.getInt(4), r.getObject(5, OffsetDateTime.class)), params);
        List<Entry> entries = rows.stream().limit(PAGE_SIZE).map(row -> {
            var result = results.result(row.id(), principal);
            return new Entry(row.id(), row.passageId(), row.title(), row.partNumber(), row.submittedAt(), result.correct(), result.total(), result.accuracy());
        }).toList();
        return new Page(entries, page, rows.size() > PAGE_SIZE);
    }
}
