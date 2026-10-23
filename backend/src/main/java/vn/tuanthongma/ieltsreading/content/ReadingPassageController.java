package vn.tuanthongma.ieltsreading.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reading/passages")
class ReadingPassageController {
    private final JdbcTemplate jdbc;
    ReadingPassageController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Summary(long id, String title, int partNumber, int questionCount, boolean inProgress, int answeredCount, Long latestSubmittedAttemptId) {}
    record Choice(String label, String text) {}
    record Question(long id, int number, String text, String afterBlank, List<Choice> choices) {}
    record Group(long id, String variant, String instructions, boolean candidateReuse, List<Choice> candidates, List<Question> questions) {}
    record Passage(long id, String title, String body, int partNumber, List<Group> groups) {}

    @GetMapping
    List<Summary> list(Principal principal) {
        return jdbc.query("""
                SELECT p.passage_id, p.title, p.part_number, COUNT(q.passage_question_id) AS question_count,
                       a.quiz_attempt_id, (SELECT COUNT(*) FROM user_answers ua WHERE ua.quiz_attempt_id = a.quiz_attempt_id) AS answered_count,
                       (SELECT s.quiz_attempt_id FROM quiz_attempts s WHERE s.passage_id = p.passage_id AND s.user_id = ? AND s.status = 'SUBMITTED' ORDER BY s.submitted_at DESC, s.quiz_attempt_id DESC LIMIT 1) AS latest_submitted_id
                FROM passages p LEFT JOIN question_groups g ON g.passage_id = p.passage_id
                LEFT JOIN passage_questions q ON q.question_group_id = g.question_group_id
                LEFT JOIN quiz_attempts a ON a.passage_id = p.passage_id AND a.user_id = ? AND a.status = 'IN_PROGRESS'
                WHERE p.status = 'PUBLISHED' GROUP BY p.passage_id, p.title, p.part_number, p.published_at, a.quiz_attempt_id
                ORDER BY p.published_at DESC, p.passage_id DESC
                """, (r, n) -> new Summary(r.getLong("passage_id"), r.getString("title"), r.getInt("part_number"), r.getInt("question_count"), r.getObject("quiz_attempt_id") != null, r.getInt("answered_count"), r.getObject("latest_submitted_id") == null ? null : r.getLong("latest_submitted_id")), Long.parseLong(principal.getName()), Long.parseLong(principal.getName()));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    Passage detail(@PathVariable long id) {
        Passage passage = jdbc.query("SELECT passage_id, title, body, part_number FROM passages WHERE passage_id = ? AND status = 'PUBLISHED'",
                (r, n) -> new Passage(r.getLong("passage_id"), r.getString("title"), r.getString("body"), r.getInt("part_number"), new ArrayList<>()), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<Long, List<Choice>> candidates = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.question_group_id, c.answer_label, c.candidate_text FROM matching_questions c
                JOIN question_groups g ON g.question_group_id = c.question_group_id
                WHERE g.passage_id = ? AND c.node_kind = 'CANDIDATE' ORDER BY c.display_order, c.matching_question_id
                """, r -> { candidates.computeIfAbsent(r.getLong("question_group_id"), k -> new ArrayList<>()).add(new Choice(r.getString("answer_label"), r.getString("candidate_text"))); }, id);
        Map<Long, List<Choice>> choices = new LinkedHashMap<>();
        jdbc.query("""
                SELECT q.passage_question_id, o.answer_label, o.option_text FROM passage_questions q
                JOIN question_groups g ON g.question_group_id = q.question_group_id
                JOIN question_answer_options a ON a.passage_question_id = q.passage_question_id
                JOIN option_questions o ON o.option_question_id = a.option_question_id
                WHERE g.passage_id = ? ORDER BY a.display_order
                """, r -> { choices.computeIfAbsent(r.getLong("passage_question_id"), k -> new ArrayList<>()).add(new Choice(r.getString("answer_label"), r.getString("option_text"))); }, id);
        Map<Long, Group> groups = new LinkedHashMap<>();
        jdbc.query("""
                SELECT g.question_group_id, g.task_variant, g.instructions, g.candidate_reuse,
                       q.passage_question_id, q.question_number, q.question_text, b.blank_text
                FROM question_groups g JOIN passage_questions q ON q.question_group_id = g.question_group_id
                LEFT JOIN blank_questions b ON b.passage_question_id = q.passage_question_id
                WHERE g.passage_id = ? ORDER BY g.display_order, q.question_number
                """, r -> {
            long groupId = r.getLong("question_group_id"), questionId = r.getLong("passage_question_id");
            Group group = groups.computeIfAbsent(groupId, key -> {
                try { return new Group(key, r.getString("task_variant"), r.getString("instructions"), r.getBoolean("candidate_reuse"), candidates.getOrDefault(key, List.of()), new ArrayList<>()); }
                catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
            });
            group.questions().add(new Question(questionId, r.getInt("question_number"), r.getString("question_text"), r.getString("blank_text"), choices.getOrDefault(questionId, List.of())));
        }, id);
        passage.groups().addAll(groups.values());
        return passage;
    }
}
