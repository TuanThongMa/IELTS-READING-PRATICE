package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/passages")
class AdminPassageController {
    private final JdbcTemplate jdbc;
    private final AdminQuestionGroupController questionGroups;

    AdminPassageController(JdbcTemplate jdbc, AdminQuestionGroupController questionGroups) {
        this.jdbc = jdbc;
        this.questionGroups = questionGroups;
    }

    record CreatePassage(String title, String body, Integer partNumber) {}
    record PassageSummary(long id, String title, int partNumber, String status) {}
    record PassageDetail(long id, String title, String body, int partNumber, String status) {}
    record PublicationCheck(boolean ready, List<String> issues) {}

    @GetMapping
    List<PassageSummary> list() {
        return jdbc.query("""
                SELECT passage_id, title, part_number, status FROM passages
                ORDER BY created_at DESC, passage_id DESC
                """, (row, index) -> new PassageSummary(row.getLong("passage_id"), row.getString("title"),
                row.getInt("part_number"), row.getString("status")));
    }

    @GetMapping("/{id}")
    PassageDetail detail(@PathVariable long id) {
        return jdbc.query("""
                SELECT passage_id, title, body, part_number, status FROM passages WHERE passage_id = ?
                """, (row, index) -> new PassageDetail(row.getLong("passage_id"), row.getString("title"),
                row.getString("body"), row.getInt("part_number"), row.getString("status")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    void create(@RequestBody CreatePassage input, Principal principal) {
        validate(input);
        jdbc.update("""
                INSERT INTO passages (title, body, part_number, created_by_user_id)
                VALUES (?, ?, ?, ?)
                """, input.title().strip(), input.body().strip(), input.partNumber(), Long.parseLong(principal.getName()));
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@PathVariable long id, @RequestBody CreatePassage input) {
        validate(input);
        int updated = jdbc.update("""
                UPDATE passages SET title = ?, body = ?, part_number = ?, updated_at = CURRENT_TIMESTAMP
                WHERE passage_id = ? AND status = 'DRAFT'
                """, input.title().strip(), input.body().strip(), input.partNumber(), id);
        if (updated == 0) {
            detail(id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ có thể sửa passage nháp.");
        }
    }

    @GetMapping("/{id}/publish-check")
    @Transactional(readOnly = true)
    PublicationCheck checkPublication(@PathVariable long id) {
        PassageDetail passage = detail(id);
        if (!"DRAFT".equals(passage.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ kiểm tra xuất bản passage nháp.");
        return publicationCheck(passage);
    }

    @PostMapping("/{id}/publish")
    @Transactional
    ResponseEntity<PublicationCheck> publish(@PathVariable long id) {
        List<String> statuses = jdbc.queryForList("SELECT status FROM passages WHERE passage_id = ? FOR UPDATE", String.class, id);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!"DRAFT".equals(statuses.getFirst())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Passage không còn ở trạng thái nháp.");
        PublicationCheck check = publicationCheck(detail(id));
        if (!check.ready()) return ResponseEntity.unprocessableEntity().body(check);
        jdbc.update("UPDATE passages SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE passage_id = ?", id);
        return ResponseEntity.ok(check);
    }

    private PublicationCheck publicationCheck(PassageDetail passage) {
        List<String> issues = new java.util.ArrayList<>();
        if (passage.title() == null || passage.title().isBlank() || passage.body() == null || passage.body().isBlank())
            issues.add("Tên và nội dung passage không được để trống.");
        issues.addAll(questionGroups.publicationIssues(passage.id()));
        return new PublicationCheck(issues.isEmpty(), issues);
    }

    private void validate(CreatePassage input) {
        String title = input.title() == null ? "" : input.title().strip();
        String body = input.body() == null ? "" : input.body().strip();
        if (title.isBlank() || title.length() > 200 || body.isBlank() || body.length() > 100_000
                || input.partNumber() == null || input.partNumber() < 1 || input.partNumber() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin passage không hợp lệ.");
        }
    }
}
