package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
    private final AcademicBandService academicBandService;
    ReadingTestPracticeController(JdbcTemplate jdbc, AcademicBandService academicBandService) { this.jdbc = jdbc; this.academicBandService = academicBandService; }

    record Choice(String label, String text) {}
    record Question(long id, int number, String text, String afterBlank, List<Choice> choices) {}
    record Group(long id, String variant, String answerInputMode, String instructions, String wordLimitMode, boolean candidateReuse,
                 List<Choice> candidates, PresentationTemplate presentationTemplate, List<Question> questions) {}
    record Passage(long id, int partNumber, String title, String body, int startNumber, List<Group> groups) {}
    record Test(long id, String title, List<Passage> passages) {}
    record TestSummary(long id, String title, int questionCount, boolean inProgress, String attemptMode, int elapsedSeconds, int answeredCount, Long latestSubmittedAttemptId) {}
    record Answer(long questionId, String value) {}
    record Attempt(long id, long testId, String status, String mode, int elapsedSeconds, List<Answer> answers) {}
    record StartAttempt(String mode) {}
    record AnswerInput(String value) {}
    record ClockState(String status, int elapsedSeconds) {}
    record Breakdown(String family, int total, int correct, int incorrect, int skipped) {}
    record ReviewQuestion(long id, int number, String text, String afterBlank, String status, List<Choice> choices,
                          String submittedAnswer, String correctAnswer, List<String> acceptedAnswers, String explanation) {}
    record ReviewGroup(long id, String family, String variant, String instructions, String wordLimitMode, List<Choice> candidates,
                       PresentationTemplate presentationTemplate, List<ReviewQuestion> questions) {}
    record ReviewPassage(long id, int partNumber, String title, String body, int startNumber, List<ReviewGroup> groups) {}
    record Result(long attemptId, long testId, String title, int elapsedSeconds, int correct, int incorrect, int skipped,
                  int total, double accuracy, String band, List<Breakdown> breakdown, List<ReviewPassage> passages) {}
    private record AnswerMode(String inputMode, String wordLimitMode) {}
    private record ResultHeader(long id, long testId, String status, String mode, int elapsedSeconds,
                                int totalAnswered, int totalCorrect, Long bandTableId, BigDecimal bandScore, String bandResultLabel) {}
    private record ScoreSummary(int total, int correct, int answered, int skipped, List<Breakdown> breakdown) {}

    @GetMapping
    List<TestSummary> list(Principal principal) {
        Long userId = principal == null ? null : Long.parseLong(principal.getName());
        return jdbc.query("""
                SELECT t.reading_test_id, t.title, SUM(tp.question_end_number - tp.question_start_number + 1) AS question_count,
                       a.reading_test_attempt_id, COALESCE(a.elapsed_seconds, 0) AS elapsed_seconds, a.mode AS attempt_mode,
                       (SELECT COUNT(*) FROM reading_test_answers ra WHERE ra.reading_test_attempt_id = a.reading_test_attempt_id) AS answered_count,
                       (SELECT s.reading_test_attempt_id FROM reading_test_attempts s WHERE s.reading_test_id = t.reading_test_id AND s.user_id = ? AND s.status = 'SUBMITTED' ORDER BY s.submitted_at DESC, s.reading_test_attempt_id DESC LIMIT 1) AS latest_submitted
                FROM reading_tests t JOIN test_passages tp ON tp.reading_test_id = t.reading_test_id
                LEFT JOIN reading_test_attempts a ON a.reading_test_id = t.reading_test_id AND a.user_id = ? AND a.status IN ('IN_PROGRESS', 'PAUSED')
                WHERE t.status = 'PUBLISHED'
                GROUP BY t.reading_test_id, t.title, a.reading_test_attempt_id, a.elapsed_seconds, a.mode
                ORDER BY t.published_at DESC, t.reading_test_id DESC
                """, (r, n) -> new TestSummary(r.getLong(1), r.getString(2), r.getInt(3), r.getObject(4) != null,
                r.getString(6), r.getInt(5), r.getInt(7), r.getObject(8) == null ? null : r.getLong(8)), userId, userId);
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
    Attempt start(@PathVariable long testId, @RequestBody(required = false) StartAttempt input, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        String requestedMode = input == null || input.mode() == null ? "PRACTICE" : input.mode();
        if (!List.of("PRACTICE", "REAL_TEST").contains(requestedMode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chế độ làm bài không hợp lệ.");
        jdbc.queryForObject("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", Long.class, userId);
        Integer valid = jdbc.queryForObject("SELECT COUNT(*) FROM reading_tests WHERE reading_test_id = ? AND status = 'PUBLISHED'", Integer.class, testId);
        if (valid == null || valid == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Long> ids = jdbc.queryForList("SELECT reading_test_attempt_id FROM reading_test_attempts WHERE user_id = ? AND reading_test_id = ? AND status IN ('IN_PROGRESS', 'PAUSED') ORDER BY CASE status WHEN 'IN_PROGRESS' THEN 0 ELSE 1 END, reading_test_attempt_id DESC LIMIT 1", Long.class, userId, testId);
        long id;
        if (!ids.isEmpty()) {
            id = ids.getFirst();
            AttemptHeader existing = lockedAttempt(id, userId);
            if ("PAUSED".equals(existing.status())) {
                jdbc.update("UPDATE reading_test_attempts SET status = 'IN_PROGRESS', active_marker = true, clock_started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE reading_test_attempt_id = ?", id);
            } else {
                accrue(existing, true);
            }
        }
        else {
            var keys = new GeneratedKeyHolder();
            jdbc.update(connection -> { var statement = connection.prepareStatement("INSERT INTO reading_test_attempts (user_id, reading_test_id, mode, active_marker, clock_started_at) VALUES (?, ?, ?, true, CURRENT_TIMESTAMP)", new String[]{"reading_test_attempt_id"}); statement.setLong(1, userId); statement.setLong(2, testId); statement.setString(3, requestedMode); return statement; }, keys);
            if (keys.getKey() == null) throw new IllegalStateException("No attempt key");
            id = keys.getKey().longValue();
        }
        return attempt(id, userId);
    }

    @PutMapping("/attempts/{attemptId}/pause")
    @Transactional
    Attempt pause(@PathVariable long attemptId, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        AttemptHeader current = lockedAttempt(attemptId, userId);
        if ("SUBMITTED".equals(current.status())) return attempt(attemptId, userId);
        if (!"IN_PROGRESS".equals(current.status())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        AttemptHeader updated = accrue(current, false);
        if ("SUBMITTED".equals(updated.status())) return attempt(attemptId, userId);
        jdbc.update("UPDATE reading_test_attempts SET status = 'PAUSED', active_marker = NULL, clock_started_at = NULL, updated_at = CURRENT_TIMESTAMP WHERE reading_test_attempt_id = ?", attemptId);
        return attempt(attemptId, userId);
    }

    @PutMapping("/attempts/{attemptId}/heartbeat")
    @Transactional
    ClockState heartbeat(@PathVariable long attemptId, @RequestParam(defaultValue = "false") boolean recovered, Principal principal) {
        AttemptHeader current = lockedAttempt(attemptId, Long.parseLong(principal.getName()));
        if ("SUBMITTED".equals(current.status())) return new ClockState(current.status(), current.elapsedSeconds());
        if (!"IN_PROGRESS".equals(current.status())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        AttemptHeader updated = accrue(current, recovered);
        return new ClockState(updated.status(), updated.elapsedSeconds());
    }

    @PutMapping("/attempts/{attemptId}/answers/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(noRollbackFor = ResponseStatusException.class)
    void saveAnswer(@PathVariable long attemptId, @PathVariable long questionId, @RequestBody AnswerInput input, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        AttemptHeader attempt = lockedAttempt(attemptId, userId);
        if (!"IN_PROGRESS".equals(attempt.status())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        attempt = accrue(attempt, false);
        if (!"IN_PROGRESS".equals(attempt.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Thời gian thi đã hết.");
        if (input == null || input.value() == null || input.value().length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đáp án không hợp lệ.");
        List<AnswerMode> modes = jdbc.query("""
                SELECT g.answer_input_mode, g.word_limit_mode FROM reading_test_attempts a JOIN test_passages tp ON tp.reading_test_id = a.reading_test_id
                JOIN question_groups g ON g.passage_id = tp.passage_id JOIN passage_questions q ON q.question_group_id = g.question_group_id
                WHERE a.reading_test_attempt_id = ? AND q.passage_question_id = ?
                """, (r, n) -> new AnswerMode(r.getString(1), r.getString(2)), attemptId, questionId);
        if (modes.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String raw = input.value();
        if (raw.isBlank()) { jdbc.update("DELETE FROM reading_test_answers WHERE reading_test_attempt_id = ? AND passage_question_id = ?", attemptId, questionId); return; }
        AnswerMode mode = modes.getFirst();
        boolean text = "TEXT_ENTRY".equals(mode.inputMode());
        String value = text ? normalize(raw) : raw.strip();
        if (text) {
            String answerError = AnswerTextPolicy.validationError(raw, mode.wordLimitMode());
            if (answerError != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, answerError);
        }
        if (!text) {
            String sql = "SHARED_CHOICE_POOL".equals(mode.inputMode())
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
    Attempt submit(@PathVariable long attemptId, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        AttemptHeader current = lockedAttempt(attemptId, userId);
        if ("SUBMITTED".equals(current.status())) return attempt(attemptId, userId);
        if (!"IN_PROGRESS".equals(current.status())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        AttemptHeader updated = accrue(current, false);
        if (!"SUBMITTED".equals(updated.status())) finalizeAttempt(attemptId, updated.testId(), updated.elapsedSeconds());
        return attempt(attemptId, userId);
    }

    @GetMapping("/attempts/{attemptId}/result")
    @Transactional(readOnly = true)
    Result result(@PathVariable long attemptId, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        List<ResultHeader> headers = jdbc.query("""
                SELECT a.reading_test_attempt_id, a.reading_test_id, a.status, a.mode, a.elapsed_seconds,
                       a.total_answered, a.total_correct, a.academic_band_table_id, a.band_score, a.band_result_label
                FROM reading_test_attempts a
                WHERE a.reading_test_attempt_id = ? AND a.user_id = ?
                """, (r, n) -> new ResultHeader(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getInt(5),
                        r.getInt(6), r.getInt(7), r.getObject(8) == null ? null : r.getLong(8), r.getBigDecimal(9), r.getString(10)), attemptId, userId);
        if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        ResultHeader header = headers.getFirst();
        if (!"SUBMITTED".equals(header.status())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        ScoreSummary summary = scoreSummary(header.testId(), attemptId);
        String title = jdbc.queryForObject("SELECT title FROM reading_tests WHERE reading_test_id = ?", String.class, header.testId());
        int skipped = Math.max(0, summary.total() - header.totalAnswered());
        int incorrect = Math.max(0, summary.total() - header.totalCorrect() - skipped);
        return new Result(attemptId, header.testId(), title, header.elapsedSeconds(), header.totalCorrect(), incorrect, skipped, summary.total(),
                summary.total() == 0 ? 0 : header.totalCorrect() * 100.0 / summary.total(), header.bandResultLabel(), summary.breakdown(), review(header.testId(), attemptId));
    }

    private ScoreSummary scoreSummary(long testId, long attemptId) {
        Map<Long, List<String>> alternatives = new LinkedHashMap<>();
        jdbc.query("""
                SELECT q.passage_question_id, v.answer_text FROM test_passages tp
                JOIN question_groups g ON g.passage_id = tp.passage_id
                JOIN passage_questions q ON q.question_group_id = g.question_group_id
                JOIN blank_questions b ON b.passage_question_id = q.passage_question_id
                JOIN blank_answer_variants v ON v.blank_question_id = b.blank_question_id
                WHERE tp.reading_test_id = ? ORDER BY v.blank_answer_variant_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> alternatives
                .computeIfAbsent(rs.getLong(1), ignored -> new ArrayList<>()).add(rs.getString(2)), testId);
        List<QuestionScore> scores = jdbc.query("""
                SELECT g.question_type, g.question_group_id, g.task_variant, b.answer_blank, mc.answer_label,
                       (SELECT option.answer_label FROM question_answer_options answer_map JOIN option_questions option ON option.option_question_id = answer_map.option_question_id
                        WHERE answer_map.passage_question_id = q.passage_question_id AND answer_map.is_correct ORDER BY answer_map.display_order LIMIT 1) AS option_correct,
                       (SELECT string_agg(option.answer_label, ',' ORDER BY answer_map.display_order)
                        FROM question_answer_options answer_map JOIN option_questions option ON option.option_question_id = answer_map.option_question_id
                        WHERE answer_map.passage_question_id = q.passage_question_id AND answer_map.is_correct) AS multiple_correct,
                       a.submitted_value, q.passage_question_id, g.word_limit_mode
                FROM test_passages tp
                JOIN question_groups g ON g.passage_id = tp.passage_id
                JOIN passage_questions q ON q.question_group_id = g.question_group_id
                LEFT JOIN blank_questions b ON b.passage_question_id = q.passage_question_id
                LEFT JOIN matching_question_answers ma ON ma.passage_question_id = q.passage_question_id
                LEFT JOIN matching_questions mc ON mc.matching_question_id = ma.matching_question_id
                LEFT JOIN reading_test_answers a ON a.reading_test_attempt_id = ? AND a.passage_question_id = q.passage_question_id
                WHERE tp.reading_test_id = ? ORDER BY tp.display_order, g.display_order, q.question_number
                """, (rs, row) -> {
                    String family = rs.getString(1);
                    String variant = rs.getString(3);
                    return new QuestionScore(family, rs.getLong(2), variant, rs.getString(8),
                            "MULTIPLE_CHOICE_MULTIPLE".equals(variant) ? rs.getString(7)
                                    : "BLANK".equals(family) ? rs.getString(4) : "MATCHING".equals(family) ? rs.getString(5) : rs.getString(6),
                            rs.getLong(9), rs.getString(10));
                }, attemptId, testId);
        Map<String, int[]> byFamily = new LinkedHashMap<>();
        Map<Long, java.util.Set<String>> creditedMultipleLabels = new LinkedHashMap<>();
        int total = 0, correct = 0, answered = 0, skipped = 0;
        for (QuestionScore score : scores) {
            total++;
            int[] counts = byFamily.computeIfAbsent(score.family(), ignored -> new int[4]);
            counts[0]++;
            if (score.answer() == null) {
                skipped++;
                counts[3]++;
                continue;
            }
            answered++;
            boolean isCorrect;
            if ("BLANK".equals(score.family())) {
                List<String> accepted = new ArrayList<>();
                if (score.expected() != null) accepted.add(score.expected());
                accepted.addAll(alternatives.getOrDefault(score.questionId(), List.of()));
                isCorrect = AnswerTextPolicy.isWithinLimit(score.answer(), score.wordLimitMode())
                        && accepted.stream().anyMatch(value -> normalize(value).equals(normalize(score.answer())));
            } else if ("MULTIPLE_CHOICE_MULTIPLE".equals(score.variant())) {
                List<String> accepted = score.expected() == null ? List.of() : List.of(score.expected().split(","));
                isCorrect = accepted.contains(score.answer()) && creditedMultipleLabels.computeIfAbsent(score.groupId(), ignored -> new java.util.HashSet<>()).add(score.answer());
            } else isCorrect = score.answer().equals(score.expected());
            if (isCorrect) { correct++; counts[1]++; }
            else counts[2]++;
        }
        List<Breakdown> breakdown = byFamily.entrySet().stream()
                .map(entry -> new Breakdown(entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3]))
                .toList();
        return new ScoreSummary(total, correct, answered, skipped, breakdown);
    }

    private void finalizeAttempt(long attemptId, long testId, int elapsedSeconds) {
        ScoreSummary summary = scoreSummary(testId, attemptId);
        AcademicBandService.BandResult band = academicBandService.forRawScore(summary.correct());
        jdbc.update("""
                UPDATE reading_test_attempts
                SET status = 'SUBMITTED', active_marker = NULL, clock_started_at = NULL,
                    elapsed_seconds = ?, total_answered = ?, total_correct = ?,
                    academic_band_table_id = ?, band_score = ?, band_result_label = ?,
                    submitted_at = COALESCE(submitted_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
                WHERE reading_test_attempt_id = ? AND status = 'IN_PROGRESS'
                """, elapsedSeconds, summary.answered(), summary.correct(), band.tableId(), band.score(), band.label(), attemptId);
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
        Map<Long, java.util.Set<String>> creditedMultipleLabels = new LinkedHashMap<>();
        jdbc.query("""
                SELECT p.passage_id, tp.question_start_number, p.part_number, p.title, p.body,
                       g.question_group_id, g.question_type, g.task_variant, g.instructions, g.word_limit_mode, g.presentation_template,
                       q.passage_question_id, q.question_number, q.question_text, q.explanation,
                       b.blank_text, b.answer_blank, a.submitted_value, a.submitted_raw_value,
                       (SELECT string_agg(o.answer_label, ',' ORDER BY qa.display_order) FROM question_answer_options qa JOIN option_questions o ON o.option_question_id = qa.option_question_id
                        WHERE qa.passage_question_id = q.passage_question_id AND qa.is_correct) AS option_answer,
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
                group = new ReviewGroup(groupId, r.getString("question_type"), r.getString("task_variant"), r.getString("instructions"), r.getString("word_limit_mode"), candidates.getOrDefault(groupId, List.of()), PresentationTemplateCodec.forLearner(PresentationTemplateCodec.read(r.getString("presentation_template"))), new ArrayList<>());
                groups.put(groupId, group);
                passage.groups().add(group);
            }
            String answer = r.getString("submitted_value"), family = group.family();
            List<String> accepted;
            String expected;
            if ("MULTIPLE_CHOICE_MULTIPLE".equals(group.variant())) {
                accepted = r.getString("option_answer") == null ? List.of() : List.of(r.getString("option_answer").split(","));
                expected = String.join(" / ", accepted);
            } else {
                expected = "BLANK".equals(family) ? r.getString("answer_blank") : "MATCHING".equals(family) ? r.getString("matching_answer") : r.getString("option_answer");
                accepted = new ArrayList<>();
                if (expected != null) accepted.add(expected);
                accepted.addAll(alternatives.getOrDefault(questionId, List.of()));
            }
            if (expected == null) throw new IllegalStateException("Published question has no answer key");
            boolean isCorrect = answer != null && ("BLANK".equals(family)
                    ? accepted.stream().anyMatch(value -> normalize(value).equals(normalize(answer)))
                    : "MULTIPLE_CHOICE_MULTIPLE".equals(group.variant())
                        ? accepted.contains(answer) && creditedMultipleLabels.computeIfAbsent(groupId, ignored -> new java.util.HashSet<>()).add(answer)
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
                SELECT g.question_group_id,g.task_variant,g.answer_input_mode,g.instructions,g.word_limit_mode,g.candidate_reuse,g.presentation_template,
                       q.passage_question_id,q.question_number,q.question_text,b.blank_text
                FROM question_groups g JOIN passage_questions q ON q.question_group_id=g.question_group_id LEFT JOIN blank_questions b ON b.passage_question_id=q.passage_question_id
                WHERE g.passage_id=? ORDER BY g.display_order,q.question_number
                """, r -> { long id = r.getLong(1), qid = r.getLong(8); Group group = result.computeIfAbsent(id, k -> new Group(k, get(r,2), get(r,3), get(r,4), get(r,5), bool(r,6), candidates.getOrDefault(k, List.of()), PresentationTemplateCodec.forLearner(PresentationTemplateCodec.read(get(r,7))), new ArrayList<>())); group.questions().add(new Question(qid, offset + r.getInt(9), get(r,10), get(r,11), choices.getOrDefault(qid, List.of()))); }, passageId);
        return List.copyOf(result.values());
    }
    private AttemptHeader accrue(AttemptHeader current, boolean recovered) {
        if (!"IN_PROGRESS".equals(current.status())) return current;
        Instant now = Instant.now();
        Instant started = current.clockStartedAt() == null ? now : current.clockStartedAt().toInstant();
        long delta = Math.max(0, Duration.between(started, now).getSeconds());
        if (recovered) delta = Math.min(15, delta);
        long total = (long) current.elapsedSeconds() + delta;
        boolean expired = "REAL_TEST".equals(current.mode()) && total >= DURATION_SECONDS;
        int elapsedSeconds = (int) Math.min(expired ? DURATION_SECONDS : Integer.MAX_VALUE, total);
        jdbc.update("""
                UPDATE reading_test_attempts
                SET elapsed_seconds = ?, clock_started_at = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE reading_test_attempt_id = ?
                """, elapsedSeconds, expired ? null : Timestamp.from(now), current.id());
        if (expired) {
            finalizeAttempt(current.id(), current.testId(), elapsedSeconds);
            return new AttemptHeader(current.id(), current.testId(), "SUBMITTED", current.mode(), elapsedSeconds, null);
        }
        return new AttemptHeader(current.id(), current.testId(), "IN_PROGRESS", current.mode(), elapsedSeconds, Timestamp.from(now));
    }

    private AttemptHeader lockedAttempt(long attemptId, long userId) {
        List<AttemptHeader> found = jdbc.query("SELECT reading_test_attempt_id, reading_test_id, status, mode, elapsed_seconds, clock_started_at FROM reading_test_attempts WHERE reading_test_attempt_id = ? AND user_id = ? FOR UPDATE",
                (r, n) -> new AttemptHeader(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getInt(5), r.getTimestamp(6)), attemptId, userId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return found.getFirst();
    }

    private Attempt attempt(long id, long userId) {
        AttemptHeader header = jdbc.query("SELECT reading_test_attempt_id, reading_test_id, status, mode, elapsed_seconds, clock_started_at FROM reading_test_attempts WHERE reading_test_attempt_id = ? AND user_id = ?",
                (r, n) -> new AttemptHeader(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getInt(5), r.getTimestamp(6)), id, userId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<Answer> answers = jdbc.query("SELECT passage_question_id, COALESCE(submitted_raw_value, submitted_value) FROM reading_test_answers WHERE reading_test_attempt_id = ?",
                (r, n) -> new Answer(r.getLong(1), r.getString(2)), id);
        return new Attempt(header.id(), header.testId(), header.status(), header.mode(), header.elapsedSeconds(), answers);
    }

    private record AttemptHeader(long id, long testId, String status, String mode, int elapsedSeconds, Timestamp clockStartedAt) {}
    private record QuestionScore(String family, long groupId, String variant, String answer, String expected, long questionId, String wordLimitMode) {}
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String get(java.sql.ResultSet r, int column) { try { return r.getString(column); } catch (java.sql.SQLException e) { throw new IllegalStateException(e); } }
    private static boolean bool(java.sql.ResultSet r, int column) { try { return r.getBoolean(column); } catch (java.sql.SQLException e) { throw new IllegalStateException(e); } }
}
