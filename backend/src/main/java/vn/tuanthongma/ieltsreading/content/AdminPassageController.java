package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/passages")
class AdminPassageController {
    private final JdbcTemplate jdbc;

    AdminPassageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record CreatePassage(String title, String body, Integer partNumber) {}
    record PassageSummary(long id, String title, int partNumber, String status) {}
    record PassageDetail(long id, String title, String body, int partNumber, String status) {}

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

    private void validate(CreatePassage input) {
        String title = input.title() == null ? "" : input.title().strip();
        String body = input.body() == null ? "" : input.body().strip();
        if (title.isBlank() || title.length() > 200 || body.isBlank() || body.length() > 100_000
                || input.partNumber() == null || input.partNumber() < 1 || input.partNumber() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin passage không hợp lệ.");
        }
    }
}
