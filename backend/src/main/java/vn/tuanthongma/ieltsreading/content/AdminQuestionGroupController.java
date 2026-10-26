package vn.tuanthongma.ieltsreading.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.text.Normalizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/passages/{passageId}/question-groups")
class AdminQuestionGroupController {
    private static final Map<String, List<String>> ANSWERS = Map.of(
            "TRUE_FALSE_NOT_GIVEN", List.of("TRUE", "FALSE", "NOT GIVEN"),
            "YES_NO_NOT_GIVEN", List.of("YES", "NO", "NOT GIVEN"));
    private final JdbcTemplate jdbc;

    AdminQuestionGroupController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record QuestionInput(Long id, String questionText, String answer, String explanation, List<String> options, String blankText, List<String> alternatives) {}
    record CandidateInput(Long id, String label, String text) {}
    record CandidateView(long id, String label, String text) {}
    record GroupConfig(String variant, String answerInputMode) {}
    record GroupInput(String taskVariant, String instructions, List<QuestionInput> questions, String wordLimitMode, List<CandidateInput> candidates, Boolean candidateReuse, String answerInputMode) {}
    record QuestionView(long id, int questionNumber, String questionText, String answer, String explanation, List<String> options, String blankText, List<String> alternatives) {}
    record GroupView(long id, String taskVariant, String instructions, List<QuestionView> questions, String wordLimitMode, List<CandidateView> candidates, boolean candidateReuse, String answerInputMode) {}
    private static final Set<String> MATCHING = Set.of("MATCHING_INFORMATION", "MATCHING_HEADINGS", "MATCHING_FEATURES", "MATCHING_SENTENCE_ENDINGS");
    private static final Set<String> TEXT_COMPLETION = Set.of("SENTENCE_COMPLETION", "NOTE_COMPLETION");
    private static final Map<String, String> WORD_LIMITS = Map.of(
            "ONE_WORD", "ONE WORD ONLY", "TWO_WORDS", "NO MORE THAN TWO WORDS",
            "TWO_WORDS_AND_ONE_NUMBER", "NO MORE THAN TWO WORDS AND/OR A NUMBER");

    private static class InvalidBlankAnswer extends RuntimeException {
        InvalidBlankAnswer(String message) { super(message); }
    }

    @ExceptionHandler(InvalidBlankAnswer.class)
    ResponseEntity<Map<String, String>> invalidBlankAnswer(InvalidBlankAnswer error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @GetMapping
    List<GroupView> list(@PathVariable long passageId) {
        requirePassage(passageId, false);
        Map<Long, GroupView> groups = new LinkedHashMap<>();
        Map<Long, List<String>> choices = new LinkedHashMap<>();
        Map<Long, List<String>> alternatives = new LinkedHashMap<>();
        Map<Long, List<CandidateView>> candidates = new LinkedHashMap<>();
        jdbc.query("SELECT c.question_group_id, c.matching_question_id, c.answer_label, c.candidate_text FROM matching_questions c JOIN question_groups g ON g.question_group_id = c.question_group_id WHERE g.passage_id = ? AND c.node_kind = 'CANDIDATE' ORDER BY c.display_order, c.matching_question_id", rs -> {
            candidates.computeIfAbsent(rs.getLong("question_group_id"), ignored -> new ArrayList<>()).add(new CandidateView(rs.getLong("matching_question_id"), rs.getString("answer_label"), rs.getString("candidate_text")));
        }, passageId);
        jdbc.query("""
                SELECT b.passage_question_id, a.answer_text FROM blank_questions b
                JOIN passage_questions q ON q.passage_question_id = b.passage_question_id
                JOIN question_groups g ON g.question_group_id = q.question_group_id
                JOIN blank_answer_variants a ON a.blank_question_id = b.blank_question_id
                WHERE g.passage_id = ? ORDER BY a.blank_answer_variant_id
                """, rs -> { alternatives.computeIfAbsent(rs.getLong("passage_question_id"), ignored -> new ArrayList<>()).add(rs.getString("answer_text")); }, passageId);
        jdbc.query("""
                SELECT q.passage_question_id, o.option_text FROM passage_questions q
                JOIN question_groups g ON g.question_group_id = q.question_group_id
                JOIN question_answer_options a ON a.passage_question_id = q.passage_question_id
                JOIN option_questions o ON o.option_question_id = a.option_question_id
                WHERE g.passage_id = ? AND g.task_variant = 'MULTIPLE_CHOICE_SINGLE'
                ORDER BY q.passage_question_id, a.display_order
                """, rs -> { choices.computeIfAbsent(rs.getLong("passage_question_id"), ignored -> new ArrayList<>()).add(rs.getString("option_text")); }, passageId);
        jdbc.query("""
                SELECT g.question_group_id, g.task_variant, g.instructions, g.word_limit_mode, g.candidate_reuse, g.answer_input_mode, q.passage_question_id, q.question_number,
                       q.question_text, q.explanation, o.answer_label, b.answer_blank, b.blank_text, mc.answer_label AS matching_answer
                FROM question_groups g
                JOIN passage_questions q ON q.question_group_id = g.question_group_id
                LEFT JOIN question_answer_options a ON a.passage_question_id = q.passage_question_id AND a.is_correct = true
                LEFT JOIN option_questions o ON o.option_question_id = a.option_question_id
                LEFT JOIN blank_questions b ON b.passage_question_id = q.passage_question_id
                LEFT JOIN matching_question_answers ma ON ma.passage_question_id = q.passage_question_id
                LEFT JOIN matching_questions mc ON mc.matching_question_id = ma.matching_question_id
                WHERE g.passage_id = ? AND g.task_variant IN ('TRUE_FALSE_NOT_GIVEN', 'YES_NO_NOT_GIVEN', 'MULTIPLE_CHOICE_SINGLE', 'SENTENCE_COMPLETION', 'NOTE_COMPLETION', 'SUMMARY_COMPLETION', 'MATCHING_INFORMATION', 'MATCHING_HEADINGS', 'MATCHING_FEATURES', 'MATCHING_SENTENCE_ENDINGS')
                ORDER BY g.display_order, q.question_number
                """, rs -> {
            long groupId = rs.getLong("question_group_id");
            GroupView group = groups.get(groupId);
            if (group == null) {
                group = new GroupView(groupId, rs.getString("task_variant"), rs.getString("instructions"), new ArrayList<>(), rs.getString("word_limit_mode"), candidates.getOrDefault(groupId, List.of()), rs.getBoolean("candidate_reuse"), rs.getString("answer_input_mode"));
                groups.put(groupId, group);
            }
            group.questions().add(new QuestionView(rs.getLong("passage_question_id"), rs.getInt("question_number"), rs.getString("question_text"),
                    isTextEntry(group.taskVariant(), group.answerInputMode()) ? rs.getString("answer_blank") : usesSharedChoice(group.taskVariant(), group.answerInputMode()) ? rs.getString("matching_answer") : rs.getString("answer_label"), rs.getString("explanation"), choices.getOrDefault(rs.getLong("passage_question_id"), List.of()), rs.getString("blank_text"), alternatives.getOrDefault(rs.getLong("passage_question_id"), List.of())));
        }, passageId);
        return List.copyOf(groups.values());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    void create(@PathVariable long passageId, @RequestBody GroupInput input) {
        String variant = input.taskVariant() == null ? "TRUE_FALSE_NOT_GIVEN" : input.taskVariant();
        validate(input, variant);
        boolean textEntry = isTextEntry(variant, input.answerInputMode());
        boolean sharedChoice = usesSharedChoice(variant, input.answerInputMode());
        if (input.questions().stream().anyMatch(q -> q.id() != null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Câu hỏi mới không được có id.");
        if (sharedChoice && input.candidates().stream().anyMatch(c -> c.id() != null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lựa chọn mới không được có id.");
        String status = requirePassage(passageId, true);
        if (!"DRAFT".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ được thêm câu hỏi vào bản nháp.");
        checkTotal(passageId, 0, input.questions().size());
        Integer order = jdbc.queryForObject("SELECT COALESCE(MAX(display_order), 0) + 1 FROM question_groups WHERE passage_id = ?", Integer.class, passageId);
        long groupId = insertKey("""
                INSERT INTO question_groups (passage_id, display_order, question_type, task_variant, answer_input_mode, instructions, word_limit_mode, candidate_reuse)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, "question_group_id", passageId, order, textEntry ? "BLANK" : sharedChoice ? "MATCHING" : "OPTION", variant,
                textEntry ? "TEXT_ENTRY" : sharedChoice ? "SHARED_CHOICE_POOL" : "PER_QUESTION_CHOICE", instructions(input, variant), textEntry ? input.wordLimitMode() : null, sharedChoice && Boolean.TRUE.equals(input.candidateReuse()));
        if (sharedChoice) {
            jdbc.update("INSERT INTO matching_questions (question_group_id, node_kind, display_order) VALUES (?, 'FRAME', 1)", groupId);
            syncCandidates(groupId, input.candidates());
        }
        int position = 0;
        for (QuestionInput question : input.questions()) insertQuestion(groupId, question, 2_000_000 + ++position, variant, input.answerInputMode());
        renumber(passageId);
    }

    @PutMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void update(@PathVariable long passageId, @PathVariable long groupId, @RequestBody GroupInput input) {
        if (!"DRAFT".equals(requirePassage(passageId, true)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ được sửa câu hỏi của bản nháp.");
        List<GroupConfig> configs = jdbc.query("SELECT task_variant, answer_input_mode FROM question_groups WHERE question_group_id = ? AND passage_id = ? AND task_variant IN ('TRUE_FALSE_NOT_GIVEN', 'YES_NO_NOT_GIVEN', 'MULTIPLE_CHOICE_SINGLE', 'SENTENCE_COMPLETION', 'NOTE_COMPLETION', 'SUMMARY_COMPLETION', 'MATCHING_INFORMATION', 'MATCHING_HEADINGS', 'MATCHING_FEATURES', 'MATCHING_SENTENCE_ENDINGS')", (r, n) -> new GroupConfig(r.getString(1), r.getString(2)), groupId, passageId);
        if (configs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        GroupConfig config = configs.getFirst();
        String variant = config.variant();
        if (input.taskVariant() != null && !variant.equals(input.taskVariant()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể đổi dạng nhóm đã lưu.");
        validate(input, variant);
        checkTotal(passageId, groupId, input.questions().size());
        List<Long> originalIds = jdbc.queryForList("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ?", Long.class, groupId);
        Set<Long> retainedIds = new HashSet<>();
        for (QuestionInput q : input.questions()) {
            if (q.id() != null && (!originalIds.contains(q.id()) || !retainedIds.add(q.id())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Câu hỏi không thuộc nhóm hoặc bị trùng.");
        }
        boolean textEntry = isTextEntry(variant, config.answerInputMode());
        boolean sharedChoice = usesSharedChoice(variant, config.answerInputMode());
        jdbc.update("UPDATE question_groups SET instructions = ?, word_limit_mode = ?, candidate_reuse = ?, updated_at = CURRENT_TIMESTAMP WHERE question_group_id = ?", instructions(input, variant), textEntry ? input.wordLimitMode() : null, sharedChoice && Boolean.TRUE.equals(input.candidateReuse()), groupId);
        if (sharedChoice) {
            jdbc.update("DELETE FROM matching_question_answers WHERE question_group_id = ?", groupId);
            syncCandidates(groupId, input.candidates());
        }
        // Temporarily move old numbers away from the new group's ordered positions.
        jdbc.update("UPDATE passage_questions SET question_number = question_number + 3000000 WHERE question_group_id = ?", groupId);
        for (long oldId : originalIds) {
            if (!retainedIds.contains(oldId)) {
                if (textEntry) removeBlank(oldId); else if (!sharedChoice) removeOptions(oldId);
                jdbc.update("DELETE FROM passage_questions WHERE passage_question_id = ?", oldId);
            }
        }
        int position = 0;
        for (QuestionInput q : input.questions()) {
            int number = 2_000_000 + ++position;
            if (q.id() == null) insertQuestion(groupId, q, number, variant, config.answerInputMode());
            else {
                jdbc.update("UPDATE passage_questions SET question_number = ?, question_text = ?, explanation = ?, updated_at = CURRENT_TIMESTAMP WHERE passage_question_id = ?",
                        number, q.questionText().strip(), explanation(q), q.id());
                if (sharedChoice) insertMatchingAnswer(groupId, q.id(), q.answer());
                else if (textEntry) {
                    removeBlank(q.id());
                    insertBlank(q.id(), q);
                } else if ("MULTIPLE_CHOICE_SINGLE".equals(variant)) {
                    removeOptions(q.id());
                    insertOptions(q.id(), q, variant);
                } else jdbc.update("""
                        UPDATE question_answer_options SET is_correct = (option_question_id IN
                          (SELECT option_question_id FROM option_questions WHERE answer_label = ?))
                        WHERE passage_question_id = ?
                        """, q.answer(), q.id());
            }
        }
        renumber(passageId);
    }

    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable long passageId, @PathVariable long groupId) {
        if (!"DRAFT".equals(requirePassage(passageId, true)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ được xóa nhóm câu hỏi của bản nháp.");
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM question_groups WHERE question_group_id = ? AND passage_id = ?
                AND task_variant IN ('TRUE_FALSE_NOT_GIVEN', 'YES_NO_NOT_GIVEN', 'MULTIPLE_CHOICE_SINGLE', 'SENTENCE_COMPLETION', 'NOTE_COMPLETION', 'SUMMARY_COMPLETION', 'MATCHING_INFORMATION', 'MATCHING_HEADINGS', 'MATCHING_FEATURES', 'MATCHING_SENTENCE_ENDINGS')
                """, Integer.class, groupId, passageId);
        if (found == null || found == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Long> questionIds = jdbc.queryForList("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ?", Long.class, groupId);
        jdbc.update("DELETE FROM matching_question_answers WHERE question_group_id = ?", groupId);
        for (long questionId : questionIds) {
            removeBlank(questionId);
            removeOptions(questionId);
            jdbc.update("DELETE FROM passage_questions WHERE passage_question_id = ?", questionId);
        }
        jdbc.update("DELETE FROM matching_questions WHERE question_group_id = ? AND node_kind = 'CANDIDATE'", groupId);
        jdbc.update("DELETE FROM matching_questions WHERE question_group_id = ? AND node_kind = 'FRAME'", groupId);
        jdbc.update("DELETE FROM question_groups WHERE question_group_id = ?", groupId);
        renumber(passageId);
    }

    private void checkTotal(long passageId, long excludedGroup, int incomingCount) {
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM passage_questions q
                JOIN question_groups g ON g.question_group_id = q.question_group_id
                WHERE g.passage_id = ? AND g.question_group_id <> ?
                """, Integer.class, passageId, excludedGroup);
        if (existing != null && existing + incomingCount > 40)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Một passage không được vượt quá 40 câu hỏi.");
    }

    private void renumber(long passageId) {
        List<Long> ids = jdbc.queryForList("""
                SELECT q.passage_question_id FROM passage_questions q
                JOIN question_groups g ON g.question_group_id = q.question_group_id
                WHERE g.passage_id = ? ORDER BY g.display_order, q.question_number, q.passage_question_id
                """, Long.class, passageId);
        for (int i = 0; i < ids.size(); i++)
            jdbc.update("UPDATE passage_questions SET question_number = ? WHERE passage_question_id = ?", 1_000_000 + i, ids.get(i));
        for (int i = 0; i < ids.size(); i++)
            jdbc.update("UPDATE passage_questions SET question_number = ?, updated_at = CURRENT_TIMESTAMP WHERE passage_question_id = ?", i + 1, ids.get(i));
    }

    private String explanation(QuestionInput question) {
        return question.explanation() == null || question.explanation().isBlank() ? null : question.explanation().strip();
    }

    private void insertQuestion(long groupId, QuestionInput question, int number, String variant, String answerInputMode) {
            long questionId = insertKey("""
                    INSERT INTO passage_questions (question_group_id, question_number, question_text, explanation)
                    VALUES (?, ?, ?, ?)
                    """, "passage_question_id", groupId, number, question.questionText().strip(),
                    explanation(question));
            if (isTextEntry(variant, answerInputMode)) insertBlank(questionId, question); else if (usesSharedChoice(variant, answerInputMode)) insertMatchingAnswer(groupId, questionId, question.answer()); else insertOptions(questionId, question, variant);
    }

    private boolean isTextEntry(String variant, String inputMode) {
        return TEXT_COMPLETION.contains(variant) || ("SUMMARY_COMPLETION".equals(variant) && "TEXT_ENTRY".equals(inputMode));
    }
    private boolean usesSharedChoice(String variant, String inputMode) {
        return MATCHING.contains(variant) || ("SUMMARY_COMPLETION".equals(variant) && "SHARED_CHOICE_POOL".equals(inputMode));
    }
    private String instructions(GroupInput input, String variant) { return isTextEntry(variant, input.answerInputMode()) ? WORD_LIMITS.get(input.wordLimitMode()) : input.instructions().strip(); }
    private String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT); }

    private void validateBlankLimit(String answer, String mode, String label) {
        int words = 0;
        int numbers = 0;
        for (String token : normalize(answer).split(" ")) {
            if (token.matches("[0-9]+")) numbers++;
            else if (token.matches("[\\p{L}\\p{M}]+(?:-[\\p{L}\\p{M}]+)*")) words++;
            else throw new InvalidBlankAnswer(label + ": định dạng chưa được hỗ trợ. Hiện chỉ hỗ trợ từ, từ nối bằng dấu gạch ngang và số nguyên viết bằng chữ số.");
        }
        int maxWords = "ONE_WORD".equals(mode) ? 1 : 2;
        int maxNumbers = "TWO_WORDS_AND_ONE_NUMBER".equals(mode) ? 1 : 0;
        if (words > maxWords || numbers > maxNumbers)
            throw new InvalidBlankAnswer(label + ": vượt giới hạn " + WORD_LIMITS.get(mode) + ".");
    }

    private void insertBlank(long questionId, QuestionInput question) {
        long blankId = insertKey("INSERT INTO blank_questions (passage_question_id, blank_text, answer_blank) VALUES (?, ?, ?)",
                "blank_question_id", questionId, question.blankText().strip(), question.answer().strip());
        for (String alternative : question.alternatives())
            jdbc.update("INSERT INTO blank_answer_variants (blank_question_id, answer_text, answer_normalized) VALUES (?, ?, ?)", blankId, alternative.strip(), normalize(alternative));
    }

    private void removeBlank(long questionId) {
        jdbc.update("DELETE FROM blank_answer_variants WHERE blank_question_id IN (SELECT blank_question_id FROM blank_questions WHERE passage_question_id = ?)", questionId);
        jdbc.update("DELETE FROM blank_questions WHERE passage_question_id = ?", questionId);
    }

    private void removeOptions(long questionId) {
        List<Long> optionIds = jdbc.queryForList("SELECT option_question_id FROM question_answer_options WHERE passage_question_id = ?", Long.class, questionId);
        jdbc.update("DELETE FROM question_answer_options WHERE passage_question_id = ?", questionId);
        for (long optionId : optionIds)
            jdbc.update("DELETE FROM option_questions WHERE option_question_id = ? AND NOT EXISTS (SELECT 1 FROM question_answer_options WHERE option_question_id = ?)", optionId, optionId);
    }

    private void syncCandidates(long groupId, List<CandidateInput> candidates) {
        Map<Long, String> existing = new LinkedHashMap<>();
        jdbc.query("SELECT matching_question_id, answer_label FROM matching_questions WHERE question_group_id = ? AND node_kind = 'CANDIDATE'", rs -> { existing.put(rs.getLong(1), rs.getString(2)); }, groupId);
        Set<Long> retained = new HashSet<>();
        for (CandidateInput candidate : candidates) {
            if (candidate.id() == null && existing.containsValue(candidate.label().strip()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể thay thế lựa chọn đã lưu bằng lựa chọn mới cùng mã.");
            if (candidate.id() != null && (!existing.containsKey(candidate.id()) || !retained.add(candidate.id()) || !existing.get(candidate.id()).equals(candidate.label().strip())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lựa chọn không thuộc nhóm, bị trùng hoặc đã đổi mã đáp án.");
        }
        for (long id : existing.keySet()) if (!retained.contains(id)) jdbc.update("DELETE FROM matching_questions WHERE matching_question_id = ?", id);
        int order = 0;
        for (CandidateInput candidate : candidates) {
            if (candidate.id() == null) jdbc.update("INSERT INTO matching_questions (question_group_id, parent_matching_question_id, node_kind, answer_label, candidate_text, display_order) SELECT ?, matching_question_id, 'CANDIDATE', ?, ?, ? FROM matching_questions WHERE question_group_id = ? AND node_kind = 'FRAME'", groupId, candidate.label().strip(), candidate.text().strip(), ++order, groupId);
            else jdbc.update("UPDATE matching_questions SET candidate_text = ?, display_order = ? WHERE matching_question_id = ?", candidate.text().strip(), ++order, candidate.id());
        }
    }

    private void insertMatchingAnswer(long groupId, long questionId, String answer) {
        jdbc.update("INSERT INTO matching_question_answers (question_group_id, passage_question_id, matching_question_id) SELECT ?, ?, matching_question_id FROM matching_questions WHERE question_group_id = ? AND answer_label = ?", groupId, questionId, groupId, answer);
    }

    private void insertOptions(long questionId, QuestionInput question, String variant) {
            boolean multipleChoice = "MULTIPLE_CHOICE_SINGLE".equals(variant);
            List<String> options = multipleChoice ? question.options() : ANSWERS.get(variant);
            for (int i = 0; i < options.size(); i++) {
                String answer = multipleChoice ? String.valueOf((char) ('A' + i)) : options.get(i);
                long optionId = insertKey("INSERT INTO option_questions (answer_label, option_text) VALUES (?, ?)",
                        "option_question_id", answer, options.get(i).strip());
                jdbc.update("INSERT INTO question_answer_options (passage_question_id, option_question_id, is_correct, display_order) VALUES (?, ?, ?, ?)",
                        questionId, optionId, answer.equals(question.answer()), i + 1);
            }
    }

    private long insertKey(String sql, String keyColumn, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{keyColumn});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("No generated key");
        return key.longValue();
    }

    private String requirePassage(long id, boolean lock) {
        List<String> result = jdbc.queryForList("SELECT status FROM passages WHERE passage_id = ?" + (lock ? " FOR UPDATE" : ""), String.class, id);
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return result.getFirst();
    }

    private void validate(GroupInput input, String variant) {
        List<String> allowedAnswers = ANSWERS.get(variant);
        if ("SUMMARY_COMPLETION".equals(variant) && !Set.of("TEXT_ENTRY", "SHARED_CHOICE_POOL").contains(input.answerInputMode()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Summary Completion cần chọn cách nhập đáp án.");
        if (TEXT_COMPLETION.contains(variant) && input.answerInputMode() != null && !"TEXT_ENTRY".equals(input.answerInputMode()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dạng điền từ cần dùng ô nhập đáp án.");
        boolean multipleChoice = "MULTIPLE_CHOICE_SINGLE".equals(variant);
        boolean blank = isTextEntry(variant, input.answerInputMode());
        boolean matching = usesSharedChoice(variant, input.answerInputMode());
        if (allowedAnswers == null && !multipleChoice && !blank && !matching) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dạng câu hỏi không được hỗ trợ.");
        if ((blank ? input.wordLimitMode() == null || !WORD_LIMITS.containsKey(input.wordLimitMode()) : input.instructions() == null || input.instructions().isBlank() || input.instructions().length() > 10_000)
                || input.questions() == null || input.questions().isEmpty() || input.questions().size() > 40)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hướng dẫn hoặc danh sách câu hỏi không hợp lệ.");
        Set<String> matchingLabels = new HashSet<>();
        Set<String> selectedLabels = new HashSet<>();
        if (matching) {
            if (input.candidates() == null || input.candidates().isEmpty() || input.candidates().size() > 50 || input.candidateReuse() == null
                    || (("MATCHING_HEADINGS".equals(variant) || "MATCHING_SENTENCE_ENDINGS".equals(variant) || "SUMMARY_COMPLETION".equals(variant)) && input.candidateReuse()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Danh sách lựa chọn hoặc quy tắc sử dụng lại không hợp lệ.");
            for (CandidateInput candidate : input.candidates()) {
                if (candidate == null || candidate.label() == null || candidate.label().isBlank() || candidate.label().length() > 30 || candidate.text() == null || candidate.text().isBlank() || candidate.text().length() > 10000 || !matchingLabels.add(candidate.label().strip()))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã hoặc nội dung lựa chọn bị trùng hoặc không hợp lệ.");
            }
        }
        int questionIndex = 0;
        for (QuestionInput q : input.questions()) {
            questionIndex++;
            if (q == null || q.questionText() == null || (!blank && q.questionText().isBlank())
                    || q.questionText().length() > 10_000 || q.answer() == null
                    || (q.explanation() != null && q.explanation().length() > 10_000))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Câu hỏi hoặc đáp án không hợp lệ.");
            if (blank) {
                if (q.blankText() == null || q.blankText().length() > 10_000 || (q.questionText().isBlank() && q.blankText().isBlank())
                        || q.answer().isBlank() || q.answer().length() > 1000 || q.alternatives() == null || q.alternatives().size() > 20)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Câu điền từ không hợp lệ.");
                Set<String> normalized = new HashSet<>();
                normalized.add(normalize(q.answer()));
                validateBlankLimit(q.answer(), input.wordLimitMode(), "Câu trong nhóm " + questionIndex + ", đáp án chính");
                int alternativeIndex = 0;
                for (String value : q.alternatives())
                    if (value == null || value.isBlank() || value.length() > 1000 || !normalized.add(normalize(value)))
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đáp án thay thế bị trùng hoặc không hợp lệ.");
                    else validateBlankLimit(value, input.wordLimitMode(), "Câu trong nhóm " + questionIndex + ", đáp án thay thế " + ++alternativeIndex);
            } else if (matching) {
                if (!matchingLabels.contains(q.answer()) || (!input.candidateReuse() && !selectedLabels.add(q.answer())))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đáp án không có trong danh sách hoặc đã được sử dụng ở câu khác.");
            } else if (multipleChoice) {
                if (q.options() == null || q.options().size() < 2 || q.options().size() > 26
                        || q.options().stream().anyMatch(option -> option == null || option.isBlank() || option.length() > 10_000)
                        || q.answer().length() != 1 || q.answer().charAt(0) < 'A' || q.answer().charAt(0) >= 'A' + q.options().size())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lựa chọn hoặc đáp án đúng không hợp lệ.");
            } else if (!allowedAnswers.contains(q.answer()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Câu hỏi hoặc đáp án không hợp lệ.");
        }
    }

    List<String> publicationIssues(long passageId) {
        List<String> issues = new ArrayList<>();
        int groupCount = jdbc.queryForObject("SELECT COUNT(*) FROM question_groups WHERE passage_id = ?", Integer.class, passageId);
        if (groupCount == 0) return List.of("Hãy thêm ít nhất một nhóm câu hỏi trước khi xuất bản.");
        List<GroupView> groups = list(passageId);
        if (groups.size() != groupCount) issues.add("Có nhóm chưa có câu hỏi hoặc thuộc dạng chưa được hỗ trợ. Hãy kiểm tra lại các nhóm.");
        Set<Long> seenQuestions = new HashSet<>();
        int expectedNumber = 1;
        for (GroupView group : groups) {
            List<QuestionInput> questions = new ArrayList<>();
            for (QuestionView q : group.questions()) {
                if (!seenQuestions.add(q.id()) || q.questionNumber() != expectedNumber++) issues.add("Thứ tự câu hỏi hoặc số đáp án đúng không hợp lệ. Hãy kiểm tra nhóm câu hỏi.");
                questions.add(new QuestionInput(q.id(), q.questionText(), q.answer(), q.explanation(), q.options(), q.blankText(), q.alternatives()));
                if (!isTextEntry(group.taskVariant(), group.answerInputMode()) && !usesSharedChoice(group.taskVariant(), group.answerInputMode())) {
                    int correct = jdbc.queryForObject("SELECT COUNT(*) FROM question_answer_options WHERE passage_question_id = ? AND is_correct = true", Integer.class, q.id());
                    if (correct != 1) issues.add("Câu " + q.questionNumber() + " phải có đúng một đáp án đúng.");
                }
            }
            if (usesSharedChoice(group.taskVariant(), group.answerInputMode())) {
                int frames = jdbc.queryForObject("SELECT COUNT(*) FROM matching_questions WHERE question_group_id = ? AND node_kind = 'FRAME'", Integer.class, group.id());
                int invalid = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM matching_questions c LEFT JOIN matching_questions f
                        ON f.question_group_id = c.question_group_id AND f.matching_question_id = c.parent_matching_question_id AND f.node_kind = 'FRAME'
                        WHERE c.question_group_id = ? AND c.node_kind = 'CANDIDATE' AND f.matching_question_id IS NULL
                        """, Integer.class, group.id());
                if (frames != 1 || invalid != 0) issues.add("Nhóm Matching có danh sách lựa chọn không hợp lệ.");
            }
            try {
                validate(new GroupInput(group.taskVariant(), group.instructions(), questions, group.wordLimitMode(),
                        group.candidates().stream().map(c -> new CandidateInput(c.id(), c.label(), c.text())).toList(), group.candidateReuse(), group.answerInputMode()), group.taskVariant());
            } catch (ResponseStatusException | InvalidBlankAnswer e) {
                issues.add("Nhóm " + group.taskVariant() + " có câu hỏi, lựa chọn hoặc đáp án chưa hợp lệ.");
            }
        }
        if (seenQuestions.size() > 40) issues.add("Một passage không được vượt quá 40 câu hỏi.");
        return issues;
    }
}
