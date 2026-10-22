package vn.tuanthongma.ieltsreading.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/passages/{passageId}/question-groups")
class AdminQuestionGroupController {
    private static final List<String> ANSWERS = List.of("TRUE", "FALSE", "NOT GIVEN");
    private final JdbcTemplate jdbc;

    AdminQuestionGroupController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record QuestionInput(Long id, String questionText, String answer, String explanation) {}
    record GroupInput(String instructions, List<QuestionInput> questions) {}
    record QuestionView(long id, int questionNumber, String questionText, String answer, String explanation) {}
    record GroupView(long id, String taskVariant, String instructions, List<QuestionView> questions) {}

    @GetMapping
    List<GroupView> list(@PathVariable long passageId) {
        requirePassage(passageId, false);
        Map<Long, GroupView> groups = new LinkedHashMap<>();
        jdbc.query("""
                SELECT g.question_group_id, g.task_variant, g.instructions, q.passage_question_id, q.question_number,
                       q.question_text, q.explanation, o.answer_label
                FROM question_groups g
                JOIN passage_questions q ON q.question_group_id = g.question_group_id
                JOIN question_answer_options a ON a.passage_question_id = q.passage_question_id AND a.is_correct = true
                JOIN option_questions o ON o.option_question_id = a.option_question_id
                WHERE g.passage_id = ? AND g.task_variant = 'TRUE_FALSE_NOT_GIVEN'
                ORDER BY g.display_order, q.question_number
                """, rs -> {
            long groupId = rs.getLong("question_group_id");
            GroupView group = groups.get(groupId);
            if (group == null) {
                group = new GroupView(groupId, rs.getString("task_variant"), rs.getString("instructions"), new ArrayList<>());
                groups.put(groupId, group);
            }
            group.questions().add(new QuestionView(rs.getLong("passage_question_id"), rs.getInt("question_number"), rs.getString("question_text"),
                    rs.getString("answer_label"), rs.getString("explanation")));
        }, passageId);
        return List.copyOf(groups.values());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    void create(@PathVariable long passageId, @RequestBody GroupInput input) {
        validate(input);
        if (input.questions().stream().anyMatch(q -> q.id() != null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Câu hỏi mới không được có id.");
        String status = requirePassage(passageId, true);
        if (!"DRAFT".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ được thêm câu hỏi vào bản nháp.");
        checkTotal(passageId, 0, input.questions().size());
        Integer order = jdbc.queryForObject("SELECT COALESCE(MAX(display_order), 0) + 1 FROM question_groups WHERE passage_id = ?", Integer.class, passageId);
        long groupId = insertKey("""
                INSERT INTO question_groups (passage_id, display_order, question_type, task_variant, answer_input_mode, instructions)
                VALUES (?, ?, 'OPTION', 'TRUE_FALSE_NOT_GIVEN', 'PER_QUESTION_CHOICE', ?)
                """, "question_group_id", passageId, order, input.instructions().strip());
        int position = 0;
        for (QuestionInput question : input.questions()) insertQuestion(groupId, question, 2_000_000 + ++position);
        renumber(passageId);
    }

    @PutMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void update(@PathVariable long passageId, @PathVariable long groupId, @RequestBody GroupInput input) {
        validate(input);
        if (!"DRAFT".equals(requirePassage(passageId, true)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ được sửa câu hỏi của bản nháp.");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM question_groups WHERE question_group_id = ? AND passage_id = ? AND task_variant = 'TRUE_FALSE_NOT_GIVEN'", Integer.class, groupId, passageId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        checkTotal(passageId, groupId, input.questions().size());
        List<Long> originalIds = jdbc.queryForList("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ?", Long.class, groupId);
        Set<Long> retainedIds = new HashSet<>();
        for (QuestionInput q : input.questions()) {
            if (q.id() != null && (!originalIds.contains(q.id()) || !retainedIds.add(q.id())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Câu hỏi không thuộc nhóm hoặc bị trùng.");
        }
        jdbc.update("UPDATE question_groups SET instructions = ?, updated_at = CURRENT_TIMESTAMP WHERE question_group_id = ?", input.instructions().strip(), groupId);
        // Temporarily move old numbers away from the new group's ordered positions.
        jdbc.update("UPDATE passage_questions SET question_number = question_number + 3000000 WHERE question_group_id = ?", groupId);
        for (long oldId : originalIds) {
            if (!retainedIds.contains(oldId)) {
                List<Long> optionIds = jdbc.queryForList("SELECT option_question_id FROM question_answer_options WHERE passage_question_id = ?", Long.class, oldId);
                jdbc.update("DELETE FROM question_answer_options WHERE passage_question_id = ?", oldId);
                jdbc.update("DELETE FROM passage_questions WHERE passage_question_id = ?", oldId);
                for (long optionId : optionIds)
                    jdbc.update("DELETE FROM option_questions WHERE option_question_id = ? AND NOT EXISTS (SELECT 1 FROM question_answer_options WHERE option_question_id = ?)", optionId, optionId);
            }
        }
        int position = 0;
        for (QuestionInput q : input.questions()) {
            int number = 2_000_000 + ++position;
            if (q.id() == null) insertQuestion(groupId, q, number);
            else {
                jdbc.update("UPDATE passage_questions SET question_number = ?, question_text = ?, explanation = ?, updated_at = CURRENT_TIMESTAMP WHERE passage_question_id = ?",
                        number, q.questionText().strip(), explanation(q), q.id());
                jdbc.update("""
                        UPDATE question_answer_options SET is_correct = (option_question_id IN
                          (SELECT option_question_id FROM option_questions WHERE answer_label = ?))
                        WHERE passage_question_id = ?
                        """, q.answer(), q.id());
            }
        }
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

    private void insertQuestion(long groupId, QuestionInput question, int number) {
            long questionId = insertKey("""
                    INSERT INTO passage_questions (question_group_id, question_number, question_text, explanation)
                    VALUES (?, ?, ?, ?)
                    """, "passage_question_id", groupId, number, question.questionText().strip(),
                    explanation(question));
            for (int i = 0; i < ANSWERS.size(); i++) {
                String answer = ANSWERS.get(i);
                long optionId = insertKey("INSERT INTO option_questions (answer_label, option_text) VALUES (?, ?)",
                        "option_question_id", answer, answer);
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

    private void validate(GroupInput input) {
        if (input.instructions() == null || input.instructions().isBlank() || input.instructions().length() > 10_000
                || input.questions() == null || input.questions().isEmpty() || input.questions().size() > 40)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hướng dẫn hoặc danh sách câu hỏi không hợp lệ.");
        for (QuestionInput q : input.questions()) {
            if (q == null || q.questionText() == null || q.questionText().isBlank()
                    || q.questionText().length() > 10_000 || q.answer() == null || !ANSWERS.contains(q.answer())
                    || (q.explanation() != null && q.explanation().length() > 10_000))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Câu hỏi hoặc đáp án không hợp lệ.");
        }
    }
}
