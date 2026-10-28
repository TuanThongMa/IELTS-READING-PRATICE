package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reading")
class PracticeAttemptController {
    private final JdbcTemplate jdbc;
    PracticeAttemptController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    record Answer(long questionId, String value) {}
    record Attempt(long id, long passageId, String status, String mode, int elapsedSeconds, List<Answer> answers) {}
    record StartAttempt(String mode) {}
    record AnswerInput(String value) {}
    record ClockInput(Integer elapsedSeconds) {}
    record PauseInput(Integer elapsedSeconds) {}

    @PostMapping("/passages/{passageId}/attempts")
    @Transactional
    Attempt start(@PathVariable long passageId, @RequestBody(required = false) StartAttempt input, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        String requestedMode = input == null || input.mode() == null ? "PRACTICE" : input.mode();
        if (!List.of("PRACTICE", "REAL_TEST").contains(requestedMode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chế độ làm bài không hợp lệ.");
        jdbc.queryForObject("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", Long.class, userId);
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM passages WHERE passage_id = ? AND status = 'PUBLISHED'", Integer.class, passageId);
        if (found == null || found == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Long> ids = jdbc.queryForList("SELECT quiz_attempt_id FROM quiz_attempts WHERE user_id = ? AND passage_id = ? AND status IN ('IN_PROGRESS', 'PAUSED') ORDER BY CASE status WHEN 'IN_PROGRESS' THEN 0 ELSE 1 END, quiz_attempt_id DESC LIMIT 1", Long.class, userId, passageId);
        long id;
        if (!ids.isEmpty()) { id = ids.getFirst(); jdbc.update("UPDATE quiz_attempts SET status = 'IN_PROGRESS', active_marker = true, updated_at = CURRENT_TIMESTAMP WHERE quiz_attempt_id = ? AND status = 'PAUSED'", id); }
        else {
            var keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO quiz_attempts (user_id, passage_id, mode, active_marker) VALUES (?, ?, ?, true)", new String[]{"quiz_attempt_id"});
                statement.setLong(1, userId); statement.setLong(2, passageId); statement.setString(3, requestedMode); return statement;
            }, keys);
            if (keys.getKey() == null) throw new IllegalStateException("No attempt key");
            id = keys.getKey().longValue();
        }
        return readAttempt(id, userId);
    }

    @PutMapping("/attempts/{attemptId}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void pause(@PathVariable long attemptId, @RequestBody PauseInput input, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        int seconds = checkedElapsedSeconds(input == null ? null : new ClockInput(input.elapsedSeconds()));
        List<Attempt> found = jdbc.query("SELECT quiz_attempt_id, passage_id, status, mode, elapsed_seconds FROM quiz_attempts WHERE quiz_attempt_id = ? AND user_id = ? FOR UPDATE",
                (r, n) -> new Attempt(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getInt(5), List.of()), attemptId, userId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Attempt attempt = found.getFirst();
        if (!"IN_PROGRESS".equals(attempt.status()) || !"REAL_TEST".equals(attempt.mode())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        jdbc.update("UPDATE quiz_attempts SET status = 'PAUSED', active_marker = NULL, elapsed_seconds = GREATEST(elapsed_seconds, ?), updated_at = CURRENT_TIMESTAMP WHERE quiz_attempt_id = ?", seconds, attemptId);
    }

    @GetMapping("/attempts/{attemptId}")
    Attempt get(@PathVariable long attemptId, Principal principal) {
        return readAttempt(attemptId, Long.parseLong(principal.getName()));
    }

    @PutMapping("/attempts/{attemptId}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void heartbeat(@PathVariable long attemptId, @RequestBody ClockInput input, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        int seconds = checkedElapsedSeconds(input);
        List<String> statuses = jdbc.queryForList("SELECT status FROM quiz_attempts WHERE quiz_attempt_id = ? AND user_id = ? FOR UPDATE", String.class, attemptId, userId);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!"IN_PROGRESS".equals(statuses.getFirst())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        jdbc.update("UPDATE quiz_attempts SET elapsed_seconds = GREATEST(elapsed_seconds, ?), updated_at = CURRENT_TIMESTAMP WHERE quiz_attempt_id = ?", seconds, attemptId);
    }

    @PostMapping("/attempts/{attemptId}/submit")
    @Transactional
    Attempt submit(@PathVariable long attemptId, @RequestBody(required = false) ClockInput input, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        List<String> statuses = jdbc.queryForList("SELECT status FROM quiz_attempts WHERE quiz_attempt_id = ? AND user_id = ? FOR UPDATE", String.class, attemptId, userId);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if ("IN_PROGRESS".equals(statuses.getFirst())) {
            if (input != null && input.elapsedSeconds() != null) {
                int seconds = checkedElapsedSeconds(input);
                jdbc.update("UPDATE quiz_attempts SET elapsed_seconds = GREATEST(elapsed_seconds, ?) WHERE quiz_attempt_id = ?", seconds, attemptId);
            }
            jdbc.update("UPDATE quiz_attempts SET status = 'SUBMITTED', active_marker = NULL, submitted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE quiz_attempt_id = ?", attemptId);
        } else if (!"SUBMITTED".equals(statuses.getFirst())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return readAttempt(attemptId, userId);
    }

    @PutMapping("/attempts/{attemptId}/answers/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void save(@PathVariable long attemptId, @PathVariable long questionId, @RequestBody AnswerInput input, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        List<Attempt> attempts = jdbc.query("SELECT quiz_attempt_id, passage_id, status, mode, elapsed_seconds FROM quiz_attempts WHERE quiz_attempt_id = ? AND user_id = ? FOR UPDATE",
                (r, n) -> new Attempt(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getInt(5), List.of()), attemptId, userId);
        if (attempts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Attempt attempt = attempts.getFirst();
        if (!"IN_PROGRESS".equals(attempt.status())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        List<QuestionMode> modes = jdbc.query("SELECT g.task_variant, g.answer_input_mode, g.word_limit_mode FROM passage_questions q JOIN question_groups g ON g.question_group_id = q.question_group_id WHERE q.passage_question_id = ? AND g.passage_id = ?", (r, n) -> new QuestionMode(r.getString(1), r.getString(2), r.getString(3)), questionId, attempt.passageId());
        if (modes.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (input.value() == null || input.value().length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đáp án không hợp lệ.");
        String raw = input.value();
        if (raw.isBlank()) {
            jdbc.update("DELETE FROM user_answers WHERE quiz_attempt_id = ? AND passage_question_id = ?", attemptId, questionId);
        } else {
            QuestionMode mode = modes.getFirst();
            boolean textEntry = "TEXT_ENTRY".equals(mode.answerInputMode());
            String value = textEntry ? Normalizer.normalize(raw, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT) : raw.strip();
            if (textEntry) {
                String answerError = AnswerTextPolicy.validationError(raw, mode.wordLimitMode());
                if (answerError != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, answerError);
            }
            if (!textEntry) {
                String sql = "SHARED_CHOICE_POOL".equals(mode.answerInputMode())
                        ? "SELECT COUNT(*) FROM matching_questions c JOIN passage_questions q ON q.question_group_id = c.question_group_id WHERE q.passage_question_id = ? AND c.node_kind = 'CANDIDATE' AND c.answer_label = ?"
                        : "SELECT COUNT(*) FROM question_answer_options a JOIN option_questions o ON o.option_question_id = a.option_question_id WHERE a.passage_question_id = ? AND o.answer_label = ?";
                if (jdbc.queryForObject(sql, Integer.class, questionId, value) != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lựa chọn không hợp lệ.");
            }
            int updated = jdbc.update("UPDATE user_answers SET submitted_value = ?, submitted_raw_value = ?, updated_at = CURRENT_TIMESTAMP WHERE quiz_attempt_id = ? AND passage_question_id = ?", value, textEntry ? raw : null, attemptId, questionId);
            if (updated == 0) jdbc.update("INSERT INTO user_answers (quiz_attempt_id, passage_question_id, submitted_value, submitted_raw_value) VALUES (?, ?, ?, ?)", attemptId, questionId, value, textEntry ? raw : null);
        }
        jdbc.update("UPDATE quiz_attempts SET updated_at = CURRENT_TIMESTAMP WHERE quiz_attempt_id = ?", attemptId);
    }

    record QuestionMode(String variant, String answerInputMode, String wordLimitMode) {}

    private int checkedElapsedSeconds(ClockInput input) {
        int seconds = input == null || input.elapsedSeconds() == null ? -1 : input.elapsedSeconds();
        if (seconds < 0 || seconds > 604_800) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời gian không hợp lệ.");
        return seconds;
    }

    private Attempt readAttempt(long id, long userId) {
        List<Attempt> attempts = jdbc.query("SELECT quiz_attempt_id, passage_id, status, mode, elapsed_seconds FROM quiz_attempts WHERE quiz_attempt_id = ? AND user_id = ?",
                (r, n) -> new Attempt(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getInt(5), List.of()), id, userId);
        if (attempts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Attempt attempt = attempts.getFirst();
        List<Answer> answers = jdbc.query("SELECT passage_question_id, COALESCE(submitted_raw_value, submitted_value) AS answer_value FROM user_answers WHERE quiz_attempt_id = ? ORDER BY passage_question_id",
                (r, n) -> new Answer(r.getLong(1), r.getString(2)), id);
        return new Attempt(attempt.id(), attempt.passageId(), attempt.status(), attempt.mode(), attempt.elapsedSeconds(), answers);
    }
}
