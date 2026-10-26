package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reading/tests")
class ReadingTestPracticeController {
    private static final int DURATION_SECONDS = 60 * 60;
    private final JdbcTemplate jdbc;
    ReadingTestPracticeController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Choice(String label, String text) {}
    record Question(long id, int number, String text, String afterBlank, List<Choice> choices) {}
    record Group(long id, String variant, String answerInputMode, String instructions, boolean candidateReuse, List<Choice> candidates, List<Question> questions) {}
    record Passage(long id, int partNumber, String title, String body, int startNumber, List<Group> groups) {}
    record Test(long id, String title, List<Passage> passages) {}
    record TestSummary(long id, String title, int questionCount, boolean inProgress, int elapsedSeconds, int answeredCount, Long latestSubmittedAttemptId) {}
    record Answer(long questionId, String value) {}
    record Attempt(long id, long testId, String status, int elapsedSeconds, List<Answer> answers) {}
    record AnswerInput(String value) {}
    record ClockInput(Integer elapsedSeconds) {}
    record Breakdown(String family, int total, int correct, int incorrect, int skipped) {}
    record ReviewQuestion(long id, int number, String text, String afterBlank, String status, List<Choice> choices,
                          String submittedAnswer, String correctAnswer, List<String> acceptedAnswers, String explanation) {}
    record ReviewGroup(long id, String family, String variant, String instructions, List<Choice> candidates, List<ReviewQuestion> questions) {}
    record ReviewPassage(long id, int partNumber, String title, String body, int startNumber, List<ReviewGroup> groups) {}
    record Result(long attemptId, long testId, String title, int elapsedSeconds, int correct, int incorrect, int skipped,
                  int total, double accuracy, String band, List<Breakdown> breakdown, List<ReviewPassage> passages) {}

    @GetMapping
    List<TestSummary> list(Principal principal) {
        Long userId = principal == null ? null : Long.parseLong(principal.getName());
        return jdbc.query("""
                SELECT t.reading_test_id, t.title, SUM(tp.question_end_number - tp.question_start_number + 1) AS question_count,
                       a.reading_test_attempt_id, COALESCE(a.elapsed_seconds, 0) AS elapsed_seconds,
                       (SELECT COUNT(*) FROM reading_test_answers ra WHERE ra.reading_test_attempt_id = a.reading_test_attempt_id) AS answered_count,
                       (SELECT s.reading_test_attempt_id FROM reading_test_attempts s WHERE s.reading_test_id = t.reading_test_id AND s.user_id = ? AND s.status = 'SUBMITTED' ORDER BY s.submitted_at DESC, s.reading_test_attempt_id DESC LIMIT 1) AS latest_submitted
                FROM reading_tests t JOIN test_passages tp ON tp.reading_test_id = t.reading_test_id
                LEFT JOIN reading_test_attempts a ON a.reading_test_id = t.reading_test_id AND a.user_id = ? AND a.status = 'IN_PROGRESS'
                WHERE t.status = 'PUBLISHED'
                GROUP BY t.reading_test_id, t.title, a.reading_test_attempt_id, a.elapsed_seconds
                ORDER BY t.published_at DESC, t.reading_test_id DESC
                """, (r, n) -> new TestSummary(r.getLong(1), r.getString(2), r.getInt(3), r.getObject(4) != null,
                r.getInt(5), r.getInt(6), r.getObject(7) == null ? null : r.getLong(7)), userId, userId);
    }

    @GetMapping("/{testId}")
    @Transactional(readOnly = true)
    Test detail(@PathVariable long testId) {
        List<Test> headers = jdbc.query("SELECT reading_test_id, title FROM reading_tests WHERE reading_test_id = ? AND status = 'PUBLISHED'", (r, n) -> new Test(r.getLong(1), r.getString(2), new ArrayList<>()), testId);
        if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Test test = headers.getFirst();
        jdbc.query("""
                SELECT p.passage_id, p.part_number, p.title, p.body, tp.question_start_number
                FROM test_passages tp JOIN passages p ON p.passage_id = tp.passage_id
                WHERE tp.reading_test_id = ? ORDER BY tp.display_order
                """, (org.springframework.jdbc.core.RowCallbackHandler) r -> test.passages().add(new Passage(r.getLong(1), r.getInt(2), r.getString(3), r.getString(4), r.getInt(5), groups(r.getLong(1), r.getInt(5) - 1))), testId);
        return test;
    }

    @PostMapping("/{testId}/attempts")
    @Transactional
    Attempt start(@PathVariable long testId, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        jdbc.queryForObject("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", Long.class, userId);
        Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM reading_tests WHERE reading_test_id = ? AND status = 'PUBLISHED'", Integer.class, testId);
        if (valid == null || valid == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Long> ids = jdbc.queryForList("SELECT reading_test_attempt_id FROM reading_test_attempts WHERE user_id = ? AND reading_test_id = ? AND status = 'IN_PROGRESS'", Long.class, userId, testId);
        long id;
        if (!ids.isEmpty()) id = ids.getFirst();
        else {
            var keys = new GeneratedKeyHolder();
            jdbc.update(connection -> { var statement = connection.prepareStatement("INSERT INTO reading_test_attempts (user_id, reading_test_id, active_marker) VALUES (?, ?, true)", new String[]{"reading_test_attempt_id"}); statement.setLong(1, userId); statement.setLong(2, testId); return statement; }, keys);
            if (keys.getKey() == null) throw new IllegalStateException("No attempt key");
            id = keys.getKey().longValue();
        }
        return attempt(id, userId);
    }

    @PutMapping("/attempts/{attemptId}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void heartbeat(@PathVariable long attemptId, @RequestBody ClockInput input, Principal principal) { saveClock(attemptId, input, Long.parseLong(principal.getName()), false); }

    @PutMapping("/attempts/{attemptId}/answers/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void saveAnswer(@PathVariable long attemptId, @PathVariable long questionId, @RequestBody AnswerInput input, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        List<AttemptHeader> attempts = lockedAttempt(attemptId, userId);
        AttemptHeader attempt = attempts.getFirst();
        if (input == null || input.value() == null || input.value().length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đáp án không hợp lệ.");
        List<String> modes = jdbc.queryForList("""
                SELECT g.answer_input_mode FROM reading_test_attempts a JOIN test_passages tp ON tp.reading_test_id = a.reading_test_id
                JOIN question_groups g ON g.passage_id = tp.passage_id JOIN passage_questions q ON q.question_group_id = g.question_group_id
                WHERE a.reading_test_attempt_id = ? AND q.passage_question_id = ?
                """, String.class, attemptId, questionId);
        if (modes.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String raw = input.value();
        if (raw.isBlank()) { jdbc.update("DELETE FROM reading_test_answers WHERE reading_test_attempt_id = ? AND passage_question_id = ?", attemptId, questionId); return; }
        boolean text = "TEXT_ENTRY".equals(modes.getFirst());
        String value = text ? normalize(raw) : raw.strip();
        if (!text) {
            String sql = "SHARED_CHOICE_POOL".equals(modes.getFirst())
                    ? "SELECT COUNT(*) FROM matching_questions c JOIN passage_questions q ON q.question_group_id = c.question_group_id WHERE q.passage_question_id = ? AND c.node_kind = 'CANDIDATE' AND c.answer_label = ?"
                    : "SELECT COUNT(*) FROM question_answer_options a JOIN option_questions o ON o.option_question_id = a.option_question_id WHERE a.passage_question_id = ? AND o.answer_label = ?";
            if (jdbc.queryForObject(sql, Integer.class, questionId, value) != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lựa chọn không hợp lệ.");
        }
        int changed = jdbc.update("UPDATE reading_test_answers SET submitted_value = ?, submitted_raw_value = ?, updated_at = CURRENT_TIMESTAMP WHERE reading_test_attempt_id = ? AND passage_question_id = ?", value, text ? raw : null, attemptId, questionId);
        if (changed == 0) jdbc.update("INSERT INTO reading_test_answers (reading_test_attempt_id, passage_question_id, submitted_value, submitted_raw_value) VALUES (?, ?, ?, ?)", attemptId, questionId, value, text ? raw : null);
        jdbc.update("UPDATE reading_test_attempts SET updated_at = CURRENT_TIMESTAMP WHERE reading_test_attempt_id = ?", attemptId);
    }

    @PostMapping("/attempts/{attemptId}/submit")
    @Transactional
    Attempt submit(@PathVariable long attemptId, @RequestBody ClockInput input, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        saveClock(attemptId, input, userId, true);
        jdbc.update("UPDATE reading_test_attempts SET status = 'SUBMITTED', active_marker = NULL, submitted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE reading_test_attempt_id = ? AND user_id = ? AND status = 'IN_PROGRESS'", attemptId, userId);
        return attempt(attemptId, userId);
    }

    @GetMapping("/attempts/{attemptId}/result")
    @Transactional(readOnly = true)
    Result result(@PathVariable long attemptId, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        List<AttemptHeader> headers = jdbc.query("""
                SELECT a.reading_test_attempt_id, a.reading_test_id, a.status, a.elapsed_seconds FROM reading_test_attempts a
                WHERE a.reading_test_attempt_id = ? AND a.user_id = ?
                """, (r, n) -> new AttemptHeader(r.getLong(1), r.getLong(2), r.getString(3), r.getInt(4)), attemptId, userId);
        if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        AttemptHeader header = headers.getFirst();
        if (!"SUBMITTED".equals(header.status())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        List<QuestionScore> scores = jdbc.query("""
                SELECT g.question_type, b.answer_blank, mc.answer_label, o.answer_label, a.submitted_value, q.passage_question_id
                FROM test_passages tp JOIN question_groups g ON g.passage_id = tp.passage_id JOIN passage_questions q ON q.question_group_id = g.question_group_id
                LEFT JOIN blank_questions b ON b.passage_question_id = q.passage_question_id
                LEFT JOIN matching_question_answers ma ON ma.passage_question_id = q.passage_question_id LEFT JOIN matching_questions mc ON mc.matching_question_id = ma.matching_question_id
                LEFT JOIN question_answer_options qa ON qa.passage_question_id = q.passage_question_id AND qa.is_correct LEFT JOIN option_questions o ON o.option_question_id = qa.option_question_id
                LEFT JOIN reading_test_answers a ON a.reading_test_attempt_id = ? AND a.passage_question_id = q.passage_question_id
                WHERE tp.reading_test_id = ?
                """, (rs, row) -> new QuestionScore(rs.getString(1), rs.getString(5),
                "BLANK".equals(rs.getString(1)) ? rs.getString(2) : "MATCHING".equals(rs.getString(1)) ? rs.getString(3) : rs.getString(4)), attemptId, header.testId());
        Map<String, int[]> byFamily = new LinkedHashMap<>();
        int total = 0, correct = 0, skipped = 0;
        for (QuestionScore score : scores) {
            total++;
            int[] counts = byFamily.computeIfAbsent(score.family(), ignored -> new int[4]);
            counts[0]++;
            if (score.answer() == null) {
                skipped++;
                counts[3]++;
            } else {
                boolean isCorrect = "BLANK".equals(score.family())
                        ? normalize(score.answer()).equals(normalize(score.expected()))
                        : score.answer().equals(score.expected());
                if (isCorrect) { correct++; counts[1]++; }
                else counts[2]++;
            }
        }
        List<Breakdown> breakdown = byFamily.entrySet().stream()
                .map(entry -> new Breakdown(entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3]))
                .toList();
        String title = jdbc.queryForObject("SELECT title FROM reading_tests WHERE reading_test_id = ?", String.class, header.testId());
        int incorrect = total - correct - skipped;
        return new Result(attemptId, header.testId(), title, header.elapsedSeconds(), correct, incorrect, skipped, total,
                total == 0 ? 0 : correct * 100.0 / total, band(correct), breakdown, review(header.testId(), attemptId));
    }

    private List<ReviewPassage> review(long testId, long attemptId) {
        List<ReviewPassage> passages = jdbc.query("""
                SELECT p.passage_id, p.part_number, p.title, p.body, tp.question_start_number
                FROM test_passages tp JOIN passages p ON p.passage_id = tp.passage_id
                WHERE tp.reading_test_id = ? ORDER BY tp.display_order
                """, (r, n) -> new ReviewPassage(r.getLong(1), r.getInt(2), r.getString(3), r.getString(4), r.getInt(5), new ArrayList<>()), testId);
        Map<Long, ReviewPassage> passageById = new LinkedHashMap<>();
        passages.forEach(passage -> passageById.put(passage.id(), passage));

        Map<Long, List<String>> alternatives = new LinkedHashMap<>();
        jdbc.query("""
                SELECT b.passage_question_id, v.answer_text FROM test_passages tp
                JOIN passages p ON p.passage_id = tp.passage_id
                JOIN question_groups g ON g.passage_id = p.passage_id
                JOIN passage_questions q ON q.question_group_id = g.question_group_id
                JOIN blank_questions b ON b.passage_question_id = q.passage_question_id
                JOIN blank_answer_variants v ON v.blank_question_id = b.blank_question_id
                WHERE tp.reading_test_id = ? ORDER BY v.blank_answer_variant_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) r -> alternatives.computeIfAbsent(r.getLong(1), ignored -> new ArrayList<>()).add(r.getString(2)), testId);

        Map<Long, List<Choice>> choices = new LinkedHashMap<>(), candidates = new LinkedHashMap<>();
        jdbc.query("""
                SELECT q.passage_question_id, o.answer_label, o.option_text FROM test_passages tp
                JOIN passages p ON p.passage_id = tp.passage_id JOIN question_groups g ON g.passage_id = p.passage_id
                JOIN passage_questions q ON q.question_group_id = g.question_group_id
                JOIN question_answer_options qa ON qa.passage_question_id = q.passage_question_id
                JOIN option_questions o ON o.option_question_id = qa.option_question_id
                WHERE tp.reading_test_id = ? ORDER BY qa.display_order
                """, (org.springframework.jdbc.core.RowCallbackHandler) r -> choices.computeIfAbsent(r.getLong(1), ignored -> new ArrayList<>()).add(new Choice(r.getString(2), r.getString(3))), testId);
        jdbc.query("""
                SELECT c.question_group_id, c.answer_label, c.candidate_text FROM test_passages tp
                JOIN passages p ON p.passage_id = tp.passage_id JOIN question_groups g ON g.passage_id = p.passage_id
                JOIN matching_questions c ON c.question_group_id = g.question_group_id
                WHERE tp.reading_test_id = ? AND c.node_kind = 'CANDIDATE' ORDER BY c.display_order, c.matching_question_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) r -> candidates.computeIfAbsent(r.getLong(1), ignored -> new ArrayList<>()).add(new Choice(r.getString(2), r.getString(3))), testId);

        Map<Long, ReviewGroup> groups = new LinkedHashMap<>();
        jdbc.query("""
                SELECT p.passage_id, tp.question_start_number, p.part_number, p.title, p.body,
                       g.question_group_id, g.question_type, g.task_variant, g.instructions,
                       q.passage_question_id, q.question_number, q.question_text, q.explanation,
                       b.blank_text, b.answer_blank, a.submitted_value, a.submitted_raw_value,
                       (SELECT o.answer_label FROM question_answer_options qa JOIN option_questions o ON o.option_question_id = qa.option_question_id
                        WHERE qa.passage_question_id = q.passage_question_id AND qa.is_correct ORDER BY qa.display_order LIMIT 1) AS option_answer,
                       mc.answer_label AS matching_answer
                FROM test_passages tp JOIN passages p ON p.passage_id = tp.passage_id
                JOIN question_groups g ON g.passage_id = p.passage_id
                JOIN passage_questions q ON q.question_group_id = g.question_group_id
                LEFT JOIN blank_questions b ON b.passage_question_id = q.passage_question_id
                LEFT JOIN matching_question_answers ma ON ma.passage_question_id = q.passage_question_id
                LEFT JOIN matching_questions mc ON mc.matching_question_id = ma.matching_question_id
                LEFT JOIN reading_test_answers a ON a.reading_test_attempt_id = ? AND a.passage_question_id = q.passage_question_id
                WHERE tp.reading_test_id = ? ORDER BY tp.display_order, g.display_order, q.question_number
                """, r -> {
            long passageId = r.getLong("passage_id"), groupId = r.getLong("question_group_id"), questionId = r.getLong("passage_question_id");
            ReviewPassage passage = passageById.get(passageId);
            ReviewGroup group = groups.get(groupId);
            if (group == null) {
                group = new ReviewGroup(groupId, r.getString("question_type"), r.getString("task_variant"), r.getString("instructions"), candidates.getOrDefault(groupId, List.of()), new ArrayList<>());
                groups.put(groupId, group);
                passage.groups().add(group);
            }
            String answer = r.getString("submitted_value"), family = group.family();
            String expected = "BLANK".equals(family) ? r.getString("answer_blank") : "MATCHING".equals(family) ? r.getString("matching_answer") : r.getString("option_answer");
            if (expected == null) throw new IllegalStateException("Published question has no answer key");
            List<String> accepted = new ArrayList<>();
            accepted.add(expected);
            accepted.addAll(alternatives.getOrDefault(questionId, List.of()));
            boolean isCorrect = answer != null && ("BLANK".equals(family)
                    ? accepted.stream().anyMatch(value -> normalize(value).equals(normalize(answer)))
                    : expected.equals(answer));
            String raw = r.getString("submitted_raw_value");
            group.questions().add(new ReviewQuestion(questionId, r.getInt("question_start_number") + r.getInt("question_number") - 1,
                    r.getString("question_text"), r.getString("blank_text"), answer == null ? "SKIPPED" : isCorrect ? "CORRECT" : "INCORRECT",
                    choices.getOrDefault(questionId, List.of()), raw == null ? answer : raw, expected, accepted, r.getString("explanation")));
        }, attemptId, testId);
        return List.copyOf(passages);
    }

    private List<Group> groups(long passageId, int offset) {
        Map<Long, List<Choice>> candidates = new LinkedHashMap<>(), choices = new LinkedHashMap<>();
        jdbc.query("SELECT c.question_group_id, c.answer_label, c.candidate_text FROM matching_questions c WHERE c.question_group_id IN (SELECT question_group_id FROM question_groups WHERE passage_id = ?) AND c.node_kind = 'CANDIDATE' ORDER BY c.display_order", (org.springframework.jdbc.core.RowCallbackHandler) r -> candidates.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Choice(r.getString(2), r.getString(3))), passageId);
        jdbc.query("SELECT q.passage_question_id, o.answer_label, o.option_text FROM passage_questions q JOIN question_answer_options a ON a.passage_question_id=q.passage_question_id JOIN option_questions o ON o.option_question_id=a.option_question_id WHERE q.question_group_id IN (SELECT question_group_id FROM question_groups WHERE passage_id = ?) ORDER BY a.display_order", (org.springframework.jdbc.core.RowCallbackHandler) r -> choices.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Choice(r.getString(2), r.getString(3))), passageId);
        Map<Long, Group> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT g.question_group_id,g.task_variant,g.answer_input_mode,g.instructions,g.candidate_reuse,q.passage_question_id,q.question_number,q.question_text,b.blank_text
                FROM question_groups g JOIN passage_questions q ON q.question_group_id=g.question_group_id LEFT JOIN blank_questions b ON b.passage_question_id=q.passage_question_id
                WHERE g.passage_id=? ORDER BY g.display_order,q.question_number
                """, r -> { long id = r.getLong(1), qid = r.getLong(6); Group group = result.computeIfAbsent(id, k -> new Group(k, get(r,2), get(r,3), get(r,4), bool(r,5), candidates.getOrDefault(k, List.of()), new ArrayList<>())); group.questions().add(new Question(qid, offset + r.getInt(7), get(r,8), get(r,9), choices.getOrDefault(qid, List.of()))); }, passageId);
        return List.copyOf(result.values());
    }
    private void saveClock(long attemptId, ClockInput input, long userId, boolean submitting) {
        int seconds = input == null || input.elapsedSeconds() == null ? 0 : input.elapsedSeconds();
        if (seconds < 0 || seconds > DURATION_SECONDS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời gian không hợp lệ.");
        List<AttemptHeader> attempts = lockedAttempt(attemptId, userId);
        if (!"IN_PROGRESS".equals(attempts.getFirst().status()) && !submitting) throw new ResponseStatusException(HttpStatus.CONFLICT);
        jdbc.update("UPDATE reading_test_attempts SET elapsed_seconds = GREATEST(elapsed_seconds, ?), updated_at = CURRENT_TIMESTAMP WHERE reading_test_attempt_id = ?", seconds, attemptId);
    }
    private List<AttemptHeader> lockedAttempt(long attemptId, long userId) { List<AttemptHeader> found = jdbc.query("SELECT reading_test_attempt_id,reading_test_id,status,elapsed_seconds FROM reading_test_attempts WHERE reading_test_attempt_id=? AND user_id=? FOR UPDATE", (r,n) -> new AttemptHeader(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4)), attemptId,userId); if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND); if (!"IN_PROGRESS".equals(found.getFirst().status())) throw new ResponseStatusException(HttpStatus.CONFLICT); return found; }
    private Attempt attempt(long id, long userId) { List<AttemptHeader> headers = jdbc.query("SELECT reading_test_attempt_id,reading_test_id,status,elapsed_seconds FROM reading_test_attempts WHERE reading_test_attempt_id=? AND user_id=?", (r,n) -> new AttemptHeader(r.getLong(1),r.getLong(2),r.getString(3),r.getInt(4)),id,userId); if(headers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND); AttemptHeader h=headers.getFirst(); return new Attempt(h.id(),h.testId(),h.status(),h.elapsedSeconds(),jdbc.query("SELECT passage_question_id,COALESCE(submitted_raw_value,submitted_value) FROM reading_test_answers WHERE reading_test_attempt_id=?",(r,n)->new Answer(r.getLong(1),r.getString(2)),id)); }
    private record AttemptHeader(long id, long testId, String status, int elapsedSeconds) {}
    private record QuestionScore(String family, String answer, String expected) {}
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String band(int score) { return score <= 10 ? "Below 4.0" : score <= 12 ? "4.0" : score <= 15 ? "4.5" : score <= 17 ? "5.0" : score <= 22 ? "5.5" : score <= 25 ? "6.0" : score <= 29 ? "6.5" : score <= 31 ? "7.0" : score <= 34 ? "7.5" : score <= 36 ? "8.0" : score <= 38 ? "8.5" : "9.0"; }
    private static String get(java.sql.ResultSet r, int column) { try { return r.getString(column); } catch (java.sql.SQLException e) { throw new IllegalStateException(e); } }
    private static boolean bool(java.sql.ResultSet r, int column) { try { return r.getBoolean(column); } catch (java.sql.SQLException e) { throw new IllegalStateException(e); } }
}
