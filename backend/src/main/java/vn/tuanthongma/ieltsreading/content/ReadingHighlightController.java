package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reading/passages/{passageId}/highlights")
class ReadingHighlightController {
    private final JdbcTemplate jdbc;

    ReadingHighlightController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Highlight(long id, int startOffset, int endOffset, String selectedText, String prefixContext, String suffixContext) {}
    record CreateHighlight(Integer startOffset, Integer endOffset, String selectedText) {}

    @GetMapping
    List<Highlight> list(@PathVariable long passageId, @RequestParam String attemptType,
                         @RequestParam long attemptId, Principal principal) {
        long userId = userId(principal);
        String scopeColumn = requireAttempt(passageId, attemptType, attemptId, userId, false);
        return jdbc.query("""
                SELECT reading_highlight_id, start_offset, end_offset, selected_text, prefix_context, suffix_context
                FROM reading_highlights WHERE user_id = ? AND passage_id = ? AND %s = ?
                ORDER BY start_offset, reading_highlight_id
                """.formatted(scopeColumn),
                (r, n) -> new Highlight(r.getLong(1), r.getInt(2), r.getInt(3), r.getString(4), r.getString(5), r.getString(6)), userId, passageId, attemptId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Highlight create(@PathVariable long passageId, @RequestParam String attemptType, @RequestParam long attemptId,
                     @RequestBody CreateHighlight input, Principal principal) {
        long userId = userId(principal);
        String scopeColumn = requireAttempt(passageId, attemptType, attemptId, userId, true);
        String body = requirePublishedPassage(passageId);
        if (input == null || input.startOffset() == null || input.endOffset() == null || input.selectedText() == null
                || input.startOffset() < 0 || input.endOffset() <= input.startOffset()
                || input.endOffset() > body.length() || input.endOffset() - input.startOffset() != input.selectedText().length()
                || input.selectedText().isBlank() || input.selectedText().length() > 2000
                || input.selectedText().contains("\n") || input.selectedText().contains("\r")
                || !body.substring(input.startOffset(), input.endOffset()).equals(input.selectedText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vùng tô sáng không còn khớp với nội dung passage.");
        }
        Integer overlaps = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reading_highlights
                WHERE user_id = ? AND passage_id = ? AND %s = ?
                  AND start_offset < ? AND end_offset > ?
                  AND NOT (start_offset = ? AND end_offset = ?)
                """.formatted(scopeColumn), Integer.class, userId, passageId, attemptId, input.endOffset(), input.startOffset(), input.startOffset(), input.endOffset());
        if (overlaps != null && overlaps > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Vùng tô sáng không thể chồng lên highlight khác.");
        int prefixStart = Math.max(0, input.startOffset() - 48);
        int suffixEnd = Math.min(body.length(), input.endOffset() + 48);
        List<Highlight> inserted = jdbc.query("""
                INSERT INTO reading_highlights (user_id, passage_id, quiz_attempt_id, reading_test_attempt_id,
                                                start_offset, end_offset, selected_text, prefix_context, suffix_context)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING reading_highlight_id, start_offset, end_offset, selected_text, prefix_context, suffix_context
                """, (r, n) -> new Highlight(r.getLong(1), r.getInt(2), r.getInt(3), r.getString(4), r.getString(5), r.getString(6)),
                userId, passageId, "PASSAGE".equals(attemptType) ? attemptId : null,
                "FULL_TEST".equals(attemptType) ? attemptId : null, input.startOffset(), input.endOffset(), input.selectedText(),
                body.substring(prefixStart, input.startOffset()), body.substring(input.endOffset(), suffixEnd));
        if (!inserted.isEmpty()) return inserted.getFirst();
        return jdbc.queryForObject("""
                SELECT reading_highlight_id, start_offset, end_offset, selected_text, prefix_context, suffix_context
                FROM reading_highlights WHERE user_id = ? AND passage_id = ? AND %s = ? AND start_offset = ? AND end_offset = ?
                """.formatted(scopeColumn),
                (r, n) -> new Highlight(r.getLong(1), r.getInt(2), r.getInt(3), r.getString(4), r.getString(5), r.getString(6)),
                userId, passageId, attemptId, input.startOffset(), input.endOffset());
    }

    @DeleteMapping("/{highlightId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable long passageId, @PathVariable long highlightId, @RequestParam String attemptType,
                @RequestParam long attemptId, Principal principal) {
        long userId = userId(principal);
        String scopeColumn = requireAttempt(passageId, attemptType, attemptId, userId, true);
        int deleted = jdbc.update("DELETE FROM reading_highlights WHERE reading_highlight_id = ? AND passage_id = ? AND user_id = ? AND " + scopeColumn + " = ?",
                highlightId, passageId, userId, attemptId);
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private String requireAttempt(long passageId, String attemptType, long attemptId, long userId, boolean mutable) {
        if (attemptId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lượt làm không hợp lệ.");
        String scopeColumn;
        String sql;
        if ("PASSAGE".equals(attemptType)) {
            scopeColumn = "quiz_attempt_id";
            sql = "SELECT a.status FROM quiz_attempts a WHERE a.quiz_attempt_id = ? AND a.user_id = ? AND a.passage_id = ?";
        } else if ("FULL_TEST".equals(attemptType)) {
            scopeColumn = "reading_test_attempt_id";
            sql = """
                    SELECT a.status FROM reading_test_attempts a
                    JOIN test_passages tp ON tp.reading_test_id = a.reading_test_id
                    WHERE a.reading_test_attempt_id = ? AND a.user_id = ? AND tp.passage_id = ?
                    """;
        } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại lượt làm không hợp lệ.");
        List<String> statuses = jdbc.queryForList(sql + (mutable ? " FOR UPDATE OF a" : ""), String.class, attemptId, userId, passageId);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt làm của passage này.");
        if (mutable && !"IN_PROGRESS".equals(statuses.getFirst()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ có thể sửa highlight khi đang làm bài.");
        return scopeColumn;
    }

    private String requirePublishedPassage(long passageId) {
        return jdbc.query("SELECT body FROM passages WHERE passage_id = ? AND status = 'PUBLISHED'", (r, n) -> r.getString(1), passageId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static long userId(Principal principal) { return Long.parseLong(principal.getName()); }
}
