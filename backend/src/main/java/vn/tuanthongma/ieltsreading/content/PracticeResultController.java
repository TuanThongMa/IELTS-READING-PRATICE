package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reading/attempts")
class PracticeResultController {
    private final JdbcTemplate jdbc;
    PracticeResultController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Choice(String label, String text) {}
    record Question(long id, int number, String text, String afterBlank, String status, List<Choice> choices,
                    String submittedAnswer, String correctAnswer, List<String> acceptedAnswers, String explanation) {}
    record Group(long id, String family, String variant, String instructions, String wordLimitMode, List<Choice> candidates,
                 PresentationTemplate presentationTemplate, List<Question> questions) {}
    record Breakdown(String family, int total, int correct, int incorrect, int skipped) {}
    record Result(long attemptId, long passageId, String title, String body, int partNumber,
                  int total, int correct, int incorrect, int skipped, double accuracy,
                  List<Breakdown> breakdown, List<Group> groups) {}
    record Header(long passageId, String title, String body, int partNumber, String status) {}

    private String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @GetMapping("/{attemptId}/result")
    @Transactional(readOnly = true)
    Result result(@PathVariable long attemptId, Principal principal) {
        List<Header> headers = jdbc.query("""
                SELECT p.passage_id, p.title, p.body, p.part_number, a.status
                FROM quiz_attempts a JOIN passages p ON p.passage_id = a.passage_id
                WHERE a.quiz_attempt_id = ? AND a.user_id = ?
                """, (r, n) -> new Header(r.getLong(1), r.getString(2), r.getString(3), r.getInt(4), r.getString(5)),
                attemptId, Long.parseLong(principal.getName()));
        if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Header header = headers.getFirst();
        if (!"SUBMITTED".equals(header.status())) throw new ResponseStatusException(HttpStatus.CONFLICT);

        Map<Long, List<String>> alternatives = new LinkedHashMap<>();
        Map<Long, List<String>> multipleCorrect = new LinkedHashMap<>();
        jdbc.query("""
                SELECT b.passage_question_id, v.answer_text FROM blank_answer_variants v
                JOIN blank_questions b ON b.blank_question_id = v.blank_question_id
                JOIN passage_questions q ON q.passage_question_id = b.passage_question_id
                JOIN question_groups g ON g.question_group_id = q.question_group_id
                WHERE g.passage_id = ? ORDER BY v.blank_answer_variant_id
                """, r -> { alternatives.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(r.getString(2)); }, header.passageId());

        Map<Long, List<Choice>> choices = new LinkedHashMap<>(), candidates = new LinkedHashMap<>();
        jdbc.query("""
                SELECT q.passage_question_id, o.answer_label, o.option_text FROM passage_questions q
                JOIN question_groups g ON g.question_group_id = q.question_group_id
                JOIN question_answer_options qa ON qa.passage_question_id = q.passage_question_id
                JOIN option_questions o ON o.option_question_id = qa.option_question_id
                WHERE g.passage_id = ? ORDER BY qa.display_order
                """, r -> { choices.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Choice(r.getString(2), r.getString(3))); }, header.passageId());
        jdbc.query("""
                SELECT DISTINCT g.question_group_id, qa.display_order, o.answer_label
                FROM question_groups g JOIN passage_questions q ON q.question_group_id = g.question_group_id
                JOIN question_answer_options qa ON qa.passage_question_id = q.passage_question_id AND qa.is_correct
                JOIN option_questions o ON o.option_question_id = qa.option_question_id
                WHERE g.passage_id = ? AND g.task_variant = 'MULTIPLE_CHOICE_MULTIPLE'
                ORDER BY g.question_group_id, qa.display_order, o.answer_label
                """, r -> { multipleCorrect.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(r.getString(3)); }, header.passageId());
        jdbc.query("""
                SELECT c.question_group_id, c.answer_label, c.candidate_text FROM matching_questions c
                JOIN question_groups g ON g.question_group_id = c.question_group_id
                WHERE g.passage_id = ? AND c.node_kind = 'CANDIDATE' ORDER BY c.display_order, c.matching_question_id
                """, r -> { candidates.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(new Choice(r.getString(2), r.getString(3))); }, header.passageId());
        Map<Long, Group> groups = new LinkedHashMap<>();
        Map<Long, Set<String>> creditedMultipleLabels = new LinkedHashMap<>();
        jdbc.query("""
                SELECT g.question_group_id, g.question_type, g.task_variant, g.instructions, g.word_limit_mode, g.presentation_template,
                       q.passage_question_id, q.question_number, q.question_text, q.explanation,
                       b.blank_text, b.answer_blank, a.submitted_value, a.submitted_raw_value,
                       o.answer_label AS option_answer, mc.answer_label AS matching_answer, g.word_limit_mode
                FROM question_groups g JOIN passage_questions q ON q.question_group_id = g.question_group_id
                LEFT JOIN blank_questions b ON b.passage_question_id = q.passage_question_id
                LEFT JOIN question_answer_options qa ON qa.passage_question_id = q.passage_question_id AND qa.is_correct = true AND g.task_variant <> 'MULTIPLE_CHOICE_MULTIPLE'
                LEFT JOIN option_questions o ON o.option_question_id = qa.option_question_id
                LEFT JOIN matching_question_answers ma ON ma.passage_question_id = q.passage_question_id
                LEFT JOIN matching_questions mc ON mc.matching_question_id = ma.matching_question_id
                LEFT JOIN user_answers a ON a.passage_question_id = q.passage_question_id AND a.quiz_attempt_id = ?
                WHERE g.passage_id = ? ORDER BY g.display_order, q.question_number
                """, r -> {
            long groupId = r.getLong("question_group_id"), questionId = r.getLong("passage_question_id");
            Group group = groups.get(groupId);
            if (group == null) {
                group = new Group(groupId, r.getString("question_type"), r.getString("task_variant"), r.getString("instructions"), r.getString("word_limit_mode"), candidates.getOrDefault(groupId, List.of()), PresentationTemplateCodec.forLearner(PresentationTemplateCodec.read(r.getString("presentation_template"))), new ArrayList<>());
                groups.put(groupId, group);
            }
            String family = group.family(), answer = r.getString("submitted_value");
            List<String> accepted = "MULTIPLE_CHOICE_MULTIPLE".equals(group.variant()) ? multipleCorrect.getOrDefault(groupId, List.of()) : List.of();
            String expected = "MULTIPLE_CHOICE_MULTIPLE".equals(group.variant()) ? String.join(" / ", accepted)
                    : "BLANK".equals(family) ? r.getString("answer_blank") : "MATCHING".equals(family) ? r.getString("matching_answer") : r.getString("option_answer");
            if (expected == null) throw new IllegalStateException("Published question has no answer key");
            if (!"MULTIPLE_CHOICE_MULTIPLE".equals(group.variant())) {
                accepted = new ArrayList<>();
                accepted.add(expected);
                accepted.addAll(alternatives.getOrDefault(questionId, List.of()));
            }
            boolean correct = answer != null && ("BLANK".equals(family)
                    ? AnswerTextPolicy.isWithinLimit(answer, r.getString("word_limit_mode"))
                        && accepted.stream().anyMatch(value -> normalize(value).equals(normalize(answer)))
                    : "MULTIPLE_CHOICE_MULTIPLE".equals(group.variant())
                        ? accepted.contains(answer) && creditedMultipleLabels.computeIfAbsent(groupId, ignored -> new HashSet<>()).add(answer)
                        : expected.equals(answer));
            String raw = r.getString("submitted_raw_value");
            group.questions().add(new Question(questionId, r.getInt("question_number"), r.getString("question_text"), r.getString("blank_text"),
                    answer == null ? "SKIPPED" : correct ? "CORRECT" : "INCORRECT", choices.getOrDefault(questionId, List.of()), raw == null ? answer : raw,
                    expected, accepted, r.getString("explanation")));
        }, attemptId, header.passageId());
        List<Question> all = groups.values().stream().flatMap(g -> g.questions().stream()).toList();
        int correct = (int) all.stream().filter(q -> "CORRECT".equals(q.status())).count();
        int incorrect = (int) all.stream().filter(q -> "INCORRECT".equals(q.status())).count();
        int skipped = all.size() - correct - incorrect;
        List<Breakdown> breakdown = new ArrayList<>();
        for (String family : List.of("OPTION", "BLANK", "MATCHING")) {
            List<Question> questions = groups.values().stream().filter(g -> family.equals(g.family())).flatMap(g -> g.questions().stream()).toList();
            if (questions.isEmpty()) continue;
            int right = (int) questions.stream().filter(q -> "CORRECT".equals(q.status())).count();
            int wrong = (int) questions.stream().filter(q -> "INCORRECT".equals(q.status())).count();
            breakdown.add(new Breakdown(family, questions.size(), right, wrong, questions.size() - right - wrong));
        }
        return new Result(attemptId, header.passageId(), header.title(), header.body(), header.partNumber(), all.size(), correct, incorrect, skipped,
                all.isEmpty() ? 0 : correct * 100.0 / all.size(), breakdown, List.copyOf(groups.values()));
    }
}
