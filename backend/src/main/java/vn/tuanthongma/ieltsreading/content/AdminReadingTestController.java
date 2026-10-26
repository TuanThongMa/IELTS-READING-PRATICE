package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/reading-tests")
class AdminReadingTestController {
    private final JdbcTemplate jdbc;

    AdminReadingTestController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Input(String title, List<Long> passageIds) {}
    record TestSummary(long id, String title, String status, int questionCount) {}
    record PassageChoice(long id, String title, int partNumber, int questionCount) {}
    record TestPassage(long id, String title, int partNumber, int questionCount, int questionStartNumber, int questionEndNumber) {}
    record TestDetail(long id, String title, String status, List<TestPassage> passages) {}
    record CompositionCheck(boolean ready, List<String> issues, List<TestPassage> passages, int questionCount) {}
    record SourcePassage(long id, String title, int partNumber, String status, int questionCount, Integer minimumNumber, Integer maximumNumber, int distinctNumberCount) {}

    @GetMapping
    List<TestSummary> list() {
        return jdbc.query("""
                SELECT t.reading_test_id, t.title, t.status,
                       COALESCE(SUM(tp.question_end_number - tp.question_start_number + 1), 0) AS question_count
                FROM reading_tests t
                LEFT JOIN test_passages tp ON tp.reading_test_id = t.reading_test_id
                GROUP BY t.reading_test_id, t.title, t.status
                ORDER BY t.created_at DESC, t.reading_test_id DESC
                """, (row, index) -> new TestSummary(row.getLong("reading_test_id"), row.getString("title"),
                row.getString("status"), row.getInt("question_count")));
    }

    @GetMapping("/published-passages")
    List<PassageChoice> publishedPassages() {
        return jdbc.query("""
                SELECT p.passage_id, p.title, p.part_number, COUNT(q.passage_question_id) AS question_count
                FROM passages p
                LEFT JOIN question_groups g ON g.passage_id = p.passage_id
                LEFT JOIN passage_questions q ON q.question_group_id = g.question_group_id
                WHERE p.status = 'PUBLISHED'
                GROUP BY p.passage_id, p.title, p.part_number
                ORDER BY p.part_number, p.title, p.passage_id
                """, (row, index) -> new PassageChoice(row.getLong("passage_id"), row.getString("title"),
                row.getInt("part_number"), row.getInt("question_count")));
    }

    @GetMapping("/{id}")
    TestDetail detail(@PathVariable long id) {
        TestHeader header = header(id);
        return new TestDetail(header.id(), header.title(), header.status(), testPassages(id));
    }

    @PostMapping("/composition-check")
    CompositionCheck compositionCheck(@RequestBody Input input) {
        return composition(input == null ? null : input.passageIds());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    TestDetail create(@RequestBody Input input, Principal principal) {
        validateTitle(input);
        CompositionCheck check = composition(input == null ? null : input.passageIds());
        if (!check.ready()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cấu trúc full test chưa hợp lệ.");
        Long id = jdbc.queryForObject("""
                INSERT INTO reading_tests (title, created_by_user_id) VALUES (?, ?) RETURNING reading_test_id
                """, Long.class, input.title().strip(), Long.parseLong(principal.getName()));
        replacePassages(id, check.passages());
        return detail(id);
    }

    @PutMapping("/{id}")
    @Transactional
    TestDetail update(@PathVariable long id, @RequestBody Input input) {
        validateTitle(input);
        lockDraft(id);
        CompositionCheck check = composition(input == null ? null : input.passageIds());
        if (!check.ready()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cấu trúc full test chưa hợp lệ.");
        jdbc.update("UPDATE reading_tests SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE reading_test_id = ?", input.title().strip(), id);
        jdbc.update("DELETE FROM test_passages WHERE reading_test_id = ?", id);
        replacePassages(id, check.passages());
        return detail(id);
    }

    @GetMapping("/{id}/publish-check")
    @Transactional(readOnly = true)
    CompositionCheck publishCheck(@PathVariable long id) {
        TestHeader test = header(id);
        if (!"DRAFT".equals(test.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ kiểm tra xuất bản đề nháp.");
        return composition(testPassageIds(id));
    }

    @PostMapping("/{id}/publish")
    @Transactional
    ResponseEntity<CompositionCheck> publish(@PathVariable long id) {
        lockDraft(id);
        CompositionCheck check = composition(testPassageIds(id));
        if (!check.ready()) return ResponseEntity.unprocessableEntity().body(check);
        jdbc.update("UPDATE reading_tests SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE reading_test_id = ?", id);
        return ResponseEntity.ok(check);
    }

    private CompositionCheck composition(List<Long> ids) {
        List<String> issues = new ArrayList<>();
        List<TestPassage> ranges = new ArrayList<>();
        if (ids == null || ids.size() != 3) {
            issues.add("Full test cần đúng 3 passage.");
            return new CompositionCheck(false, issues, ranges, 0);
        }
        Set<Long> uniqueIds = new HashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) issues.add("Mỗi vị trí cần chọn một passage hợp lệ.");
            else if (!uniqueIds.add(id)) issues.add("Một passage không thể xuất hiện hai lần trong cùng đề.");
        }
        if (!issues.isEmpty()) return new CompositionCheck(false, issues, ranges, 0);

        int start = 1;
        for (int i = 0; i < ids.size(); i++) {
            int position = i + 1;
            SourcePassage source = sourcePassage(ids.get(i));
            if (source == null) {
                issues.add("Không tìm thấy passage ở vị trí " + position + ".");
                continue;
            }
            if (!"PUBLISHED".equals(source.status())) issues.add("Passage " + position + " phải ở trạng thái đã xuất bản.");
            if (source.partNumber() != position) issues.add("Vị trí Passage " + position + " phải chọn nội dung được đánh dấu Passage " + position + ".");
            if (source.questionCount() == 0 || source.minimumNumber() == null || source.minimumNumber() != 1
                    || source.maximumNumber() != source.questionCount() || source.distinctNumberCount() != source.questionCount()) {
                issues.add("Passage " + position + " phải có các câu được đánh số liên tục từ 1 đến số câu cuối.");
            }
            int end = start + source.questionCount() - 1;
            ranges.add(new TestPassage(source.id(), source.title(), source.partNumber(), source.questionCount(), start, end));
            start = end + 1;
        }
        int total = ranges.stream().mapToInt(TestPassage::questionCount).sum();
        if (ranges.size() == 3 && total != 40) issues.add("Ba passage hiện có tổng " + total + "/40 câu.");
        return new CompositionCheck(issues.isEmpty() && ranges.size() == 3 && total == 40, List.copyOf(issues), List.copyOf(ranges), total);
    }

    private SourcePassage sourcePassage(long id) {
        return jdbc.query("""
                SELECT p.passage_id, p.title, p.part_number, p.status,
                       COUNT(q.passage_question_id) AS question_count,
                       MIN(q.question_number) AS minimum_number,
                       MAX(q.question_number) AS maximum_number,
                       COUNT(DISTINCT q.question_number) AS distinct_number_count
                FROM passages p
                LEFT JOIN question_groups g ON g.passage_id = p.passage_id
                LEFT JOIN passage_questions q ON q.question_group_id = g.question_group_id
                WHERE p.passage_id = ?
                GROUP BY p.passage_id, p.title, p.part_number, p.status
                """, (row, index) -> new SourcePassage(row.getLong("passage_id"), row.getString("title"),
                row.getInt("part_number"), row.getString("status"), row.getInt("question_count"),
                row.getObject("minimum_number", Integer.class), row.getObject("maximum_number", Integer.class),
                row.getInt("distinct_number_count")), id).stream().findFirst().orElse(null);
    }

    private void replacePassages(long testId, List<TestPassage> passages) {
        for (int i = 0; i < passages.size(); i++) {
            TestPassage passage = passages.get(i);
            jdbc.update("""
                    INSERT INTO test_passages (reading_test_id, passage_id, display_order, question_start_number, question_end_number)
                    VALUES (?, ?, ?, ?, ?)
                    """, testId, passage.id(), i + 1, passage.questionStartNumber(), passage.questionEndNumber());
        }
    }

    private List<TestPassage> testPassages(long id) {
        return jdbc.query("""
                SELECT p.passage_id, p.title, p.part_number, tp.question_start_number, tp.question_end_number
                FROM test_passages tp
                JOIN passages p ON p.passage_id = tp.passage_id
                WHERE tp.reading_test_id = ?
                ORDER BY tp.display_order
                """, (row, index) -> {
            int start = row.getInt("question_start_number");
            int end = row.getInt("question_end_number");
            return new TestPassage(row.getLong("passage_id"), row.getString("title"), row.getInt("part_number"),
                    end - start + 1, start, end);
        }, id);
    }

    private List<Long> testPassageIds(long id) {
        return jdbc.queryForList("SELECT passage_id FROM test_passages WHERE reading_test_id = ? ORDER BY display_order", Long.class, id);
    }

    private record TestHeader(long id, String title, String status) {}

    private TestHeader header(long id) {
        return jdbc.query("SELECT reading_test_id, title, status FROM reading_tests WHERE reading_test_id = ?",
                (row, index) -> new TestHeader(row.getLong("reading_test_id"), row.getString("title"), row.getString("status")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void lockDraft(long id) {
        List<String> statuses = jdbc.queryForList("SELECT status FROM reading_tests WHERE reading_test_id = ? FOR UPDATE", String.class, id);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!"DRAFT".equals(statuses.getFirst())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ có thể sửa hoặc xuất bản đề nháp.");
    }

    private void validateTitle(Input input) {
        String title = input == null || input.title() == null ? "" : input.title().strip();
        if (title.isBlank() || title.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên full test không hợp lệ.");
    }
}
