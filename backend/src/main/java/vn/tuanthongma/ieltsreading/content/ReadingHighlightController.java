package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    List<Highlight> list(@PathVariable long passageId, Principal principal) {
        long userId = userId(principal);
        requirePublishedPassage(passageId);
        return jdbc.query("""
                SELECT reading_highlight_id, start_offset, end_offset, selected_text, prefix_context, suffix_context
                FROM reading_highlights WHERE user_id = ? AND passage_id = ? ORDER BY start_offset, reading_highlight_id
                """, (r, n) -> new Highlight(r.getLong(1), r.getInt(2), r.getInt(3), r.getString(4), r.getString(5), r.getString(6)), userId, passageId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Highlight create(@PathVariable long passageId, @RequestBody CreateHighlight input, Principal principal) {
        long userId = userId(principal);
        String body = requirePublishedPassage(passageId);
        if (input.startOffset() == null || input.endOffset() == null || input.selectedText() == null
                || input.startOffset() < 0 || input.endOffset() <= input.startOffset()
                || input.endOffset() > body.length() || input.endOffset() - input.startOffset() != input.selectedText().length()
                || input.selectedText().isBlank() || input.selectedText().length() > 2000
                || input.selectedText().contains("\n") || input.selectedText().contains("\r")
                || !body.substring(input.startOffset(), input.endOffset()).equals(input.selectedText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vùng tô sáng không còn khớp với nội dung passage.");
        }
        Integer overlaps = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reading_highlights
                WHERE user_id = ? AND passage_id = ? AND start_offset < ? AND end_offset > ?
                  AND NOT (start_offset = ? AND end_offset = ?)
                """, Integer.class, userId, passageId, input.endOffset(), input.startOffset(), input.startOffset(), input.endOffset());
        if (overlaps != null && overlaps > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Vùng tô sáng không thể chồng lên highlight khác.");
        int prefixStart = Math.max(0, input.startOffset() - 48);
        int suffixEnd = Math.min(body.length(), input.endOffset() + 48);
        return jdbc.queryForObject("""
                INSERT INTO reading_highlights (user_id, passage_id, start_offset, end_offset, selected_text, prefix_context, suffix_context)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, passage_id, start_offset, end_offset) DO UPDATE SET selected_text = EXCLUDED.selected_text
                RETURNING reading_highlight_id, start_offset, end_offset, selected_text, prefix_context, suffix_context
                """, (r, n) -> new Highlight(r.getLong(1), r.getInt(2), r.getInt(3), r.getString(4), r.getString(5), r.getString(6)),
                userId, passageId, input.startOffset(), input.endOffset(), input.selectedText(), body.substring(prefixStart, input.startOffset()), body.substring(input.endOffset(), suffixEnd));
    }

    @DeleteMapping("/{highlightId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long passageId, @PathVariable long highlightId, Principal principal) {
        int deleted = jdbc.update("DELETE FROM reading_highlights WHERE reading_highlight_id = ? AND passage_id = ? AND user_id = ?",
                highlightId, passageId, userId(principal));
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private String requirePublishedPassage(long passageId) {
        return jdbc.query("SELECT body FROM passages WHERE passage_id = ? AND status = 'PUBLISHED'", (r, n) -> r.getString(1), passageId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static long userId(Principal principal) { return Long.parseLong(principal.getName()); }
}
