package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class VocabularyController {
    private final JdbcTemplate jdbc;
    private final DictionaryLookupService dictionary;

    VocabularyController(JdbcTemplate jdbc, DictionaryLookupService dictionary) {
        this.jdbc = jdbc;
        this.dictionary = dictionary;
    }

    record Category(long id, String name, long noteCount) {}
    record Note(long id, long entryId, String word, String selectedText, String pronunciation, String audioUrl, String selectedDefinition,
                String customMeaning, String contextSentence, Long passageId, String passageTitle,
                String reviewStatus, String createdAt) {}
    record CreateCategory(String name) {}
    record CreateNote(Long senseId, String selectedText, String contextSentence, String customMeaning,
                      Long passageId, List<Long> categoryIds) {}
    record StatusChange(String reviewStatus) {}
    record CreatedNote(long id) {}

    @GetMapping("/vocabulary/lookup")
    List<DictionaryLookupService.Entry> lookup(@RequestParam String word, Principal principal) {
        if (hasActiveRealTest(userId(principal))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tra cứu từ vựng bị tắt trong chế độ thi thật.");
        return dictionary.lookup(word);
    }

    @GetMapping("/vocabulary/categories")
    List<Category> categories(Principal principal) {
        long userId = userId(principal);
        return jdbc.query("""
                SELECT c.category_id, c.name, COUNT(nc.vocabulary_note_id) AS note_count
                FROM categories c LEFT JOIN note_categories nc
                  ON nc.category_id = c.category_id AND nc.user_id = c.user_id
                WHERE c.user_id = ? GROUP BY c.category_id, c.name
                ORDER BY c.created_at, c.category_id
                """, (r, n) -> new Category(r.getLong(1), r.getString(2), r.getLong(3)), userId);
    }

    @PostMapping("/vocabulary/categories")
    @ResponseStatus(HttpStatus.CREATED)
    Category createCategory(@RequestBody CreateCategory input, Principal principal) {
        String name = input == null || input.name() == null ? "" : input.name().strip();
        String normalized = normalize(name);
        if (name.isBlank() || name.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên danh mục cần từ 1 đến 80 ký tự.");
        long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO categories (user_id, name, name_normalized) VALUES (?, ?, ?)
                    RETURNING category_id
                    """, Long.class, userId(principal), name, normalized);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Danh mục này đã tồn tại.");
        }
        return new Category(id, name, 0);
    }

    @DeleteMapping("/vocabulary/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCategory(@PathVariable long categoryId, Principal principal) {
        int deleted = jdbc.update("DELETE FROM categories WHERE category_id = ? AND user_id = ?", categoryId, userId(principal));
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/vocabulary-notes")
    List<Note> notes(Principal principal, @RequestParam(required = false) Long categoryId,
                     @RequestParam(required = false) String reviewStatus,
                     @RequestParam(required = false) String query) {
        return findNotes(userId(principal), categoryId, reviewStatus, query, 200);
    }

    @GetMapping("/vocabulary-notes/recent")
    List<Note> recentNotes(Principal principal, @RequestParam(defaultValue = "5") int limit) {
        return findNotes(userId(principal), null, null, null, Math.max(1, Math.min(10, limit)));
    }

    @PostMapping("/vocabulary-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    CreatedNote createNote(@RequestBody CreateNote input, Principal principal) {
        if (input == null || input.senseId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hãy chọn một nghĩa của từ.");
        String selectedText = input.selectedText() == null ? "" : input.selectedText().strip();
        String context = input.contextSentence() == null ? "" : input.contextSentence().strip();
        String customMeaning = input.customMeaning() == null ? null : input.customMeaning().strip();
        if (selectedText.isBlank() || selectedText.length() > 200 || context.isBlank() || context.length() > 4000
                || (customMeaning != null && customMeaning.length() > 2000)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin ghi chú chưa hợp lệ.");
        long userId = userId(principal);
        if (hasActiveRealTest(userId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể lưu từ trong chế độ thi thật.");
        List<Long> entryIds = jdbc.queryForList("SELECT vocabulary_entry_id FROM vocabulary_senses WHERE vocabulary_sense_id = ?", Long.class, input.senseId());
        if (entryIds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nghĩa từ điển không còn tồn tại.");
        if (input.passageId() != null) {
            Integer passageExists = jdbc.queryForObject("SELECT COUNT(*) FROM passages WHERE passage_id = ? AND status = 'PUBLISHED'", Integer.class, input.passageId());
            if (passageExists == null || passageExists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Passage không tồn tại.");
        }
        List<Long> categoryIds = input.categoryIds() == null ? List.of() : input.categoryIds().stream().distinct().toList();
        for (Long categoryId : categoryIds) {
            if (categoryId == null || jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE category_id = ? AND user_id = ?", Integer.class, categoryId, userId) != 1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Một danh mục được chọn không hợp lệ.");
        }
        Long noteId = jdbc.queryForObject("""
                INSERT INTO vocabulary_notes (user_id, vocabulary_entry_id, vocabulary_sense_id, passage_id, selected_text, context_sentence,
                                              custom_meaning, selected_definition)
                SELECT ?, s.vocabulary_entry_id, s.vocabulary_sense_id, ?, ?, ?, ?, m.definition_text
                FROM vocabulary_senses s JOIN vocabulary_meanings m ON m.vocabulary_meaning_id = s.vocabulary_meaning_id
                WHERE s.vocabulary_sense_id = ?
                RETURNING vocabulary_note_id
                """, Long.class, userId, input.passageId(), selectedText, context, blankToNull(customMeaning), input.senseId());
        for (Long categoryId : categoryIds) jdbc.update("INSERT INTO note_categories (user_id, vocabulary_note_id, category_id) VALUES (?, ?, ?)", userId, noteId, categoryId);
        return new CreatedNote(noteId);
    }

    @PatchMapping("/vocabulary-notes/{noteId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeStatus(@PathVariable long noteId, @RequestBody StatusChange input, Principal principal) {
        if (input == null || !List.of("NEW", "LEARNING", "MASTERED").contains(input.reviewStatus())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái ôn tập không hợp lệ.");
        int updated = jdbc.update("UPDATE vocabulary_notes SET review_status = ?, updated_at = CURRENT_TIMESTAMP WHERE vocabulary_note_id = ? AND user_id = ?",
                input.reviewStatus(), noteId, userId(principal));
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/vocabulary-notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteNote(@PathVariable long noteId, Principal principal) {
        int deleted = jdbc.update("DELETE FROM vocabulary_notes WHERE vocabulary_note_id = ? AND user_id = ?", noteId, userId(principal));
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private List<Note> findNotes(long userId, Long categoryId, String reviewStatus, String query, int limit) {
        if (reviewStatus != null && !List.of("NEW", "LEARNING", "MASTERED").contains(reviewStatus)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái ôn tập không hợp lệ.");
        StringBuilder sql = new StringBuilder("""
                SELECT n.vocabulary_note_id, e.vocabulary_entry_id, e.word, n.selected_text,
                       phonetic.phonetic_text AS pronunciation, phonetic.audio_url,
                       n.selected_definition, n.custom_meaning, n.context_sentence, n.passage_id, p.title AS passage_title,
                       n.review_status, n.created_at
                FROM vocabulary_notes n JOIN vocabulary_entries e ON e.vocabulary_entry_id = n.vocabulary_entry_id
                LEFT JOIN vocabulary_senses selected_sense ON selected_sense.vocabulary_sense_id = n.vocabulary_sense_id
                LEFT JOIN vocabulary_phonetics phonetic ON phonetic.vocabulary_phonetic_id = selected_sense.vocabulary_phonetic_id
                LEFT JOIN passages p ON p.passage_id = n.passage_id
                WHERE n.user_id = ?
                """);
        List<Object> args = new ArrayList<>(); args.add(userId);
        if (categoryId != null) { sql.append(" AND EXISTS (SELECT 1 FROM note_categories nc WHERE nc.user_id = n.user_id AND nc.vocabulary_note_id = n.vocabulary_note_id AND nc.category_id = ?)"); args.add(categoryId); }
        if (reviewStatus != null) { sql.append(" AND n.review_status = ?"); args.add(reviewStatus); }
        if (query != null && !query.isBlank()) { sql.append(" AND (e.normalized_word LIKE ? OR lower(n.selected_definition) LIKE ?)"); String pattern = "%" + normalize(query) + "%"; args.add(pattern); args.add(pattern); }
        sql.append(" ORDER BY n.created_at DESC, n.vocabulary_note_id DESC LIMIT ?"); args.add(limit);
        return jdbc.query(sql.toString(), (r, n) -> new Note(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9),
                r.getObject(10) == null ? null : r.getLong(10), r.getString(11), r.getString(12), r.getObject(13, java.time.OffsetDateTime.class).toString()), args.toArray());
    }

    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static long userId(Principal principal) { return Long.parseLong(principal.getName()); }
    private boolean hasActiveRealTest(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM quiz_attempts WHERE user_id = ? AND status = 'IN_PROGRESS' AND mode = 'REAL_TEST')
                    OR EXISTS(SELECT 1 FROM reading_test_attempts WHERE user_id = ? AND status = 'IN_PROGRESS' AND mode = 'REAL_TEST')
                """, Boolean.class, userId, userId));
    }
}
