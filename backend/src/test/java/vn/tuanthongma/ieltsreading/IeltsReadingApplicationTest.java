package vn.tuanthongma.ieltsreading;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ielts_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
class IeltsReadingApplicationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void publishRequiresCompleteDraftAndFreezesContent() throws Exception {
        var csrfResponse = mvc.perform(get("/auth/csrf")).andReturn();
        var session = (MockHttpSession) csrfResponse.getRequest().getSession(false);
        String token = JsonPath.read(csrfResponse.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token).contentType("application/json")
                .content("{\"email\":\"publish-test@example.com\",\"password\":\"password123\",\"displayName\":\"Publish\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token).contentType("application/json")
                .content("{\"email\":\"publish-test@example.com\",\"password\":\"password123\"}")).andExpect(status().isOk());
        token = JsonPath.read(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/admin/passages/1/publish").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email_normalized = ?", "publish-test@example.com");
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token).contentType("application/json")
                .content("{\"email\":\"publish-test@example.com\",\"password\":\"password123\"}")).andExpect(status().isOk());
        token = JsonPath.read(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString(), "$.token");
        jdbc.update("INSERT INTO passages (title, body, part_number, status, created_by_user_id) VALUES (?, ?, ?, ?, (SELECT user_id FROM users WHERE email_normalized = ?))", "Publish test", "text", 1, "DRAFT", "publish-test@example.com");
        long passageId = jdbc.queryForObject("SELECT passage_id FROM passages WHERE title = ?", Long.class, "Publish test");
        String url = "/api/admin/passages/" + passageId;
        mvc.perform(get("/api/reading/passages/" + passageId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reading/passages/" + passageId).session(session)).andExpect(status().isNotFound());
        mvc.perform(get(url + "/publish-check").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false));
        mvc.perform(post(url + "/publish").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isUnprocessableEntity());
        String group = "{\"instructions\":\"Choose\",\"questions\":[{\"questionText\":\"First\",\"answer\":\"TRUE\"}]}";
        mvc.perform(post(url + "/question-groups").session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(group)).andExpect(status().isCreated());
        mvc.perform(get(url + "/publish-check").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        long questionId = jdbc.queryForObject("SELECT q.passage_question_id FROM passage_questions q JOIN question_groups g ON g.question_group_id = q.question_group_id WHERE g.passage_id = ?", Long.class, passageId);
        jdbc.update("UPDATE question_answer_options SET is_correct = false WHERE passage_question_id = ?", questionId);
        mvc.perform(post(url + "/publish").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isUnprocessableEntity());
        jdbc.update("UPDATE question_answer_options SET is_correct = true WHERE passage_question_id = ? AND option_question_id IN (SELECT option_question_id FROM option_questions WHERE answer_label = 'TRUE')", questionId);
        mvc.perform(post(url + "/publish").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        mvc.perform(get("/api/reading/passages/" + passageId).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].questions[0].choices.length()").value(3))
                .andExpect(jsonPath("$.groups[0].questions[0].answer").doesNotExist())
                .andExpect(jsonPath("$.groups[0].questions[0].explanation").doesNotExist())
                .andExpect(jsonPath("$.groups[0].questions[0].alternatives").doesNotExist())
                .andExpect(jsonPath("$.groups[0].questions[0].choices[0].correct").doesNotExist());
        mvc.perform(get("/api/reading/passages").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("Publish test"))
                .andExpect(jsonPath("$[0].inProgress").value(false)).andExpect(jsonPath("$[0].answeredCount").value(0));
        String attemptsUrl = "/api/reading/passages/" + passageId + "/attempts";
        var started = mvc.perform(post(attemptsUrl).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk()).andReturn();
        int attemptId = JsonPath.read(started.getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/reading/attempts/" + attemptId + "/result").session(session)).andExpect(status().isConflict());
        mvc.perform(get("/api/reading/passages").session(session)).andExpect(jsonPath("$[0].inProgress").value(true)).andExpect(jsonPath("$[0].answeredCount").value(0));
        mvc.perform(post(attemptsUrl).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(attemptId));
        String answerUrl = "/api/reading/attempts/" + attemptId + "/answers/" + questionId;
        mvc.perform(put(answerUrl).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"value\":\"INVALID\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(answerUrl).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"value\":\"FALSE\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/reading/passages").session(session)).andExpect(jsonPath("$[0].answeredCount").value(1));
        mvc.perform(get("/api/reading/attempts/" + attemptId).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.answers[0].value").value("FALSE"));
        mvc.perform(put(answerUrl).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"value\":\"TRUE\"}")).andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM user_answers WHERE quiz_attempt_id = ?", Integer.class, attemptId));
        mvc.perform(put(answerUrl).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"value\":\"\"}")).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_answers WHERE quiz_attempt_id = ?", Integer.class, attemptId));
        var otherCsrf = mvc.perform(get("/auth/csrf")).andReturn();
        var otherSession = (MockHttpSession) otherCsrf.getRequest().getSession(false);
        String otherToken = JsonPath.read(otherCsrf.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/register").session(otherSession).header("X-CSRF-TOKEN", otherToken).contentType("application/json").content("{\"email\":\"other-attempt@example.com\",\"password\":\"password123\",\"displayName\":\"Other\"}")).andExpect(status().isCreated());
        mvc.perform(post("/auth/sessions").session(otherSession).header("X-CSRF-TOKEN", otherToken).contentType("application/json").content("{\"email\":\"other-attempt@example.com\",\"password\":\"password123\"}")).andExpect(status().isOk());
        otherToken = JsonPath.read(mvc.perform(get("/auth/csrf").session(otherSession)).andReturn().getResponse().getContentAsString(), "$.token");
        mvc.perform(get("/api/reading/attempts/" + attemptId).session(otherSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/reading/passages").session(otherSession)).andExpect(jsonPath("$[0].inProgress").value(false)).andExpect(jsonPath("$[0].answeredCount").value(0));
        mvc.perform(put(answerUrl).session(otherSession).header("X-CSRF-TOKEN", otherToken).contentType("application/json").content("{\"value\":\"TRUE\"}")).andExpect(status().isNotFound());
        String submitUrl = "/api/reading/attempts/" + attemptId + "/submit";
        mvc.perform(post(submitUrl).session(otherSession).header("X-CSRF-TOKEN", otherToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/reading/attempts/" + attemptId + "/result").session(otherSession)).andExpect(status().isNotFound());
        mvc.perform(post(submitUrl).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        mvc.perform(get("/api/reading/attempts/" + attemptId + "/result").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.groups[0].questions[0].correctAnswer").value("TRUE"));
        var submittedAt = jdbc.queryForObject("SELECT submitted_at FROM quiz_attempts WHERE quiz_attempt_id = ?", java.time.OffsetDateTime.class, attemptId);
        mvc.perform(post(submitUrl).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        assertEquals(submittedAt, jdbc.queryForObject("SELECT submitted_at FROM quiz_attempts WHERE quiz_attempt_id = ?", java.time.OffsetDateTime.class, attemptId));
        mvc.perform(put(answerUrl).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"value\":\"TRUE\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/reading/passages").session(session)).andExpect(jsonPath("$[0].inProgress").value(false)).andExpect(jsonPath("$[0].latestSubmittedAttemptId").value(attemptId));
        var restarted = mvc.perform(post(attemptsUrl).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk()).andReturn();
        int secondAttemptId = JsonPath.read(restarted.getResponse().getContentAsString(), "$.id");
        org.junit.jupiter.api.Assertions.assertNotEquals(attemptId, secondAttemptId);
        mvc.perform(put("/api/reading/attempts/" + secondAttemptId + "/answers/" + questionId).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"value\":\"TRUE\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/reading/attempts/" + secondAttemptId + "/submit").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk());
        jdbc.update("INSERT INTO passages (title, body, part_number, status, created_by_user_id) VALUES (?, ?, ?, ?, (SELECT user_id FROM users WHERE email_normalized = ?))", "History second", "text", 2, "DRAFT", "publish-test@example.com");
        long secondPassageId = jdbc.queryForObject("SELECT passage_id FROM passages WHERE title = ?", Long.class, "History second");
        String secondUrl = "/api/admin/passages/" + secondPassageId;
        mvc.perform(post(secondUrl + "/question-groups").session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(group)).andExpect(status().isCreated());
        mvc.perform(post(secondUrl + "/publish").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk());
        var thirdStarted = mvc.perform(post("/api/reading/passages/" + secondPassageId + "/attempts").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk()).andReturn();
        int thirdAttemptId = JsonPath.read(thirdStarted.getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/reading/attempts/" + thirdAttemptId + "/submit").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk());
        mvc.perform(post(attemptsUrl).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk());
        mvc.perform(get("/api/reading/history").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3)).andExpect(jsonPath("$.entries[0].attemptId").value(thirdAttemptId))
                .andExpect(jsonPath("$.entries[1].attemptId").value(secondAttemptId)).andExpect(jsonPath("$.entries[1].correct").value(1))
                .andExpect(jsonPath("$.entries[2].attemptId").value(attemptId));
        mvc.perform(get("/api/reading/history").param("passageId", String.valueOf(passageId)).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2)).andExpect(jsonPath("$.entries[0].attemptId").value(secondAttemptId));
        mvc.perform(get("/api/reading/history").session(otherSession)).andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(0));
        mvc.perform(get("/api/reading/history").param("page", "-1").session(session)).andExpect(status().isBadRequest());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM passages WHERE passage_id = ?", String.class, passageId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM passages WHERE passage_id = ? AND published_at IS NOT NULL", Integer.class, passageId));
        mvc.perform(post(url + "/publish").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isConflict());
        mvc.perform(patch(url).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"title\":\"Edited\",\"body\":\"text\",\"partNumber\":1}")).andExpect(status().isConflict());
        mvc.perform(post(url + "/question-groups").session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(group)).andExpect(status().isConflict());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void matchingGroupsKeepCandidateIdentityAndValidateRules() throws Exception {
        var response = mvc.perform(get("/auth/csrf")).andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        String token = JsonPath.read(response.getResponse().getContentAsString(), "$.token");
        String credentials = "{\"email\":\"matching-test@example.com\",\"password\":\"password123\"}";
        mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token).contentType("application/json")
                .content("{\"email\":\"matching-test@example.com\",\"password\":\"password123\",\"displayName\":\"Matching\"}"))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email_normalized = ?", "matching-test@example.com");
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(credentials)).andExpect(status().isOk());
        token = JsonPath.read(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString(), "$.token");
        jdbc.update("INSERT INTO passages (title, body, part_number, status, created_by_user_id) VALUES (?, ?, ?, ?, (SELECT user_id FROM users WHERE email_normalized = ?))", "Matching test", "text", 1, "DRAFT", "matching-test@example.com");
        long passageId = jdbc.queryForObject("SELECT passage_id FROM passages WHERE title = ?", Long.class, "Matching test");
        String url = "/api/admin/passages/" + passageId + "/question-groups";
        String template = """
                {"taskVariant":"%s","instructions":"Match","candidateReuse":%s,
                 "candidates":[{"label":"A","text":"First"},{"label":"B","text":"Second"}],
                 "questions":[{"questionText":"One","answer":"A"},{"questionText":"Two","answer":"%s"}]}
                """;
        for (String variant : java.util.List.of("MATCHING_INFORMATION", "MATCHING_HEADINGS", "MATCHING_FEATURES", "MATCHING_SENTENCE_ENDINGS")) {
            mvc.perform(post(url).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(template.formatted(variant, false, "B"))).andExpect(status().isCreated());
        }
        mvc.perform(post(url).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(template.formatted("MATCHING_HEADINGS", true, "A"))).andExpect(status().isBadRequest());
        mvc.perform(post(url).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(template.formatted("MATCHING_FEATURES", false, "A"))).andExpect(status().isBadRequest());
        mvc.perform(post(url).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(template.formatted("MATCHING_FEATURES", true, "A"))).andExpect(status().isCreated());
        mvc.perform(get(url).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].candidates[0].label").value("A"))
                .andExpect(jsonPath("$[0].questions[0].answer").value("A")).andExpect(jsonPath("$[4].candidateReuse").value(true));
        long groupId = jdbc.queryForObject("SELECT MIN(question_group_id) FROM question_groups WHERE passage_id = ?", Long.class, passageId);
        long candidateId = jdbc.queryForObject("SELECT matching_question_id FROM matching_questions WHERE question_group_id = ? AND answer_label = 'A'", Long.class, groupId);
        long questionId = jdbc.queryForObject("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ? AND question_number = 1", Long.class, groupId);
        String update = """
                {"taskVariant":"MATCHING_INFORMATION","instructions":"Edited","candidateReuse":false,
                 "candidates":[{"id":%d,"label":"A","text":"Edited candidate"}],
                 "questions":[{"id":%d,"questionText":"One","answer":"A"}]}
                """.formatted(candidateId, questionId);
        mvc.perform(put(url + "/" + groupId).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(update)).andExpect(status().isNoContent());
        assertEquals(candidateId, jdbc.queryForObject("SELECT matching_question_id FROM matching_question_answers WHERE passage_question_id = ?", Long.class, questionId));
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM passage_questions q JOIN question_groups g ON g.question_group_id = q.question_group_id WHERE g.passage_id = ?", Integer.class, passageId));
        mvc.perform(put(url + "/" + groupId).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(update.replace("\"label\":\"A\"", "\"label\":\"C\"").replace("\"answer\":\"A\"", "\"answer\":\"C\""))).andExpect(status().isBadRequest());
        mvc.perform(delete(url + "/" + groupId).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM matching_questions WHERE question_group_id = ?", Integer.class, groupId));
        assertEquals(1, jdbc.queryForObject("SELECT MIN(q.question_number) FROM passage_questions q JOIN question_groups g ON g.question_group_id = q.question_group_id WHERE g.passage_id = ?", Integer.class, passageId));
        String blankForResult = "{\"taskVariant\":\"SENTENCE_COMPLETION\",\"wordLimitMode\":\"TWO_WORDS\",\"questions\":[{\"questionText\":\"A place\",\"blankText\":\".\",\"answer\":\"New York\",\"alternatives\":[\"city\"]}]}";
        mvc.perform(post(url).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content(blankForResult)).andExpect(status().isCreated());
        mvc.perform(post("/api/admin/passages/" + passageId + "/publish").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk());
        var attempt = mvc.perform(post("/api/reading/passages/" + passageId + "/attempts").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk()).andReturn();
        int attemptId = JsonPath.read(attempt.getResponse().getContentAsString(), "$.id");
        long matchingQuestion = jdbc.queryForObject("SELECT q.passage_question_id FROM passage_questions q JOIN question_groups g ON g.question_group_id = q.question_group_id WHERE g.passage_id = ? AND q.question_number = 1", Long.class, passageId);
        String answerUrl = "/api/reading/attempts/" + attemptId + "/answers/" + matchingQuestion;
        mvc.perform(put(answerUrl).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"value\":\"B\"}")).andExpect(status().isNoContent());
        mvc.perform(put(answerUrl).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"value\":\"Z\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reading/attempts/" + attemptId).session(session)).andExpect(jsonPath("$.answers[0].value").value("B"));
        long blankId = jdbc.queryForObject("SELECT q.passage_question_id FROM passage_questions q JOIN question_groups g ON g.question_group_id = q.question_group_id WHERE g.passage_id = ? AND g.task_variant = 'SENTENCE_COMPLETION'", Long.class, passageId);
        mvc.perform(put("/api/reading/attempts/" + attemptId + "/answers/" + blankId).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("{\"value\":\"  CiTy  \"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/reading/attempts/" + attemptId + "/submit").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isOk());
        mvc.perform(get("/api/reading/attempts/" + attemptId + "/result").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(1)).andExpect(jsonPath("$.incorrect").value(1))
                .andExpect(jsonPath("$.breakdown[1].family").value("MATCHING"))
                .andExpect(jsonPath("$.groups[0].questions[0].status").value("INCORRECT"))
                .andExpect(jsonPath("$.groups[4].questions[0].status").value("CORRECT"))
                .andExpect(jsonPath("$.groups[4].questions[0].submittedAnswer").value("  CiTy  "));
    }

    @Test
    void adminEndpointRequiresAdminRole() throws Exception {
        mvc.perform(get("/api/admin/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/passages")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/passages/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/passages/1/question-groups")).andExpect(status().isUnauthorized());
        var tokenResponse = mvc.perform(get("/auth/csrf")).andReturn();
        var session = (MockHttpSession) tokenResponse.getRequest().getSession(false);
        String token = JsonPath.read(tokenResponse.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                        {"email":"admin-role-test@example.com","password":"password123","displayName":"Role test"}
                        """)).andExpect(status().isCreated());
        String credentials = """
                {"email":"admin-role-test@example.com","password":"password123"}
                """;
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(credentials)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/status").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/passages").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/passages/1").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/passages/1/question-groups").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/passages/1/question-groups").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/passages/1/question-groups/1").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/passages/1/question-groups/1").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/passages").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("{\"title\":\"No\",\"body\":\"No\",\"partNumber\":1}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email_normalized = ?", "admin-role-test@example.com");
        token = JsonPath.read(mvc.perform(get("/auth/csrf").session(session)).andReturn()
                .getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(credentials)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/status").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").exists());
        token = JsonPath.read(mvc.perform(get("/auth/csrf").session(session)).andReturn()
                .getResponse().getContentAsString(), "$.token");
        String passage = "{\"title\":\"Test passage\",\"body\":\"Reading text\",\"partNumber\":2}";
        mvc.perform(post("/api/admin/passages").session(session).contentType("application/json").content(passage))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/passages").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("{\"title\":\"  \",\"body\":\"Text\",\"partNumber\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/passages").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("{\"title\":\"Title\",\"body\":\"Text\",\"partNumber\":4}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/passages").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(passage)).andExpect(status().isCreated());
        mvc.perform(get("/api/admin/passages").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Test passage"))
                .andExpect(jsonPath("$[0].partNumber").value(2))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
        assertEquals("Reading text", jdbc.queryForObject("SELECT body FROM passages WHERE title = ?", String.class, "Test passage"));
        long id = jdbc.queryForObject("SELECT passage_id FROM passages WHERE title = ?", Long.class, "Test passage");
        mvc.perform(get("/api/admin/passages/{id}", id).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Reading text"));
        mvc.perform(get("/api/admin/passages/99999").session(session)).andExpect(status().isNotFound());
        String updated = "{\"title\":\"Updated passage\",\"body\":\"New text\",\"partNumber\":3}";
        mvc.perform(patch("/api/admin/passages/{id}", id).session(session).contentType("application/json").content(updated))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/passages/{id}", id).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("{\"title\":\"\",\"body\":\"Text\",\"partNumber\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/admin/passages/99999").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(updated)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/admin/passages/{id}", id).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(updated)).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/passages/{id}", id).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated passage"))
                .andExpect(jsonPath("$.body").value("New text"))
                .andExpect(jsonPath("$.partNumber").value(3));
        String group = """
                {"instructions":"Choose TRUE, FALSE or NOT GIVEN","questions":[
                  {"questionText":"First statement","answer":"TRUE","explanation":"Evidence"},
                  {"questionText":"Second statement","answer":"NOT GIVEN","explanation":""}]}
                """;
        String groupsUrl = "/api/admin/passages/" + id + "/question-groups";
        mvc.perform(post(groupsUrl).session(session).contentType("application/json").content(group)).andExpect(status().isForbidden());
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("{\"instructions\":\"\",\"questions\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                        {"instructions":"Choose","questions":[{"questionText":"A","answer":"INVALID"}]}
                        """)).andExpect(status().isBadRequest());
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(group)).andExpect(status().isCreated());
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskVariant").value("TRUE_FALSE_NOT_GIVEN"))
                .andExpect(jsonPath("$[0].questions[0].answer").value("TRUE"))
                .andExpect(jsonPath("$[0].questions[1].answer").value("NOT GIVEN"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM question_groups WHERE passage_id = ?", Integer.class, id));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM question_answer_options", Integer.class));
        long groupId = jdbc.queryForObject("SELECT question_group_id FROM question_groups WHERE passage_id = ?", Long.class, id);
        long firstId = jdbc.queryForObject("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ? AND question_number = 1", Long.class, groupId);
        long secondId = jdbc.queryForObject("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ? AND question_number = 2", Long.class, groupId);
        String updatedGroup = """
                {"instructions":"Updated instructions","questions":[
                {"id":%d,"questionText":"Updated second","answer":"TRUE"},
                {"id":%d,"questionText":"Updated first","answer":"FALSE","explanation":"New"}]}
                """.formatted(secondId, firstId);
        String editUrl = groupsUrl + "/" + groupId;
        mvc.perform(put(editUrl).session(session).contentType("application/json").content(updatedGroup)).andExpect(status().isForbidden());
        mvc.perform(put(editUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(updatedGroup)).andExpect(status().isNoContent());
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].instructions").value("Updated instructions"))
                .andExpect(jsonPath("$[0].questions[0].id").value(secondId))
                .andExpect(jsonPath("$[0].questions[1].id").value(firstId))
                .andExpect(jsonPath("$[0].questions[1].answer").value("FALSE"));
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                        {"taskVariant":"YES_NO_NOT_GIVEN","instructions":"Another group","questions":[{"questionText":"Other","answer":"YES"}]}
                        """)).andExpect(status().isCreated());
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].taskVariant").value("YES_NO_NOT_GIVEN"))
                .andExpect(jsonPath("$[1].questions[0].answer").value("YES"))
                .andExpect(jsonPath("$[1].questions[0].questionNumber").value(3));
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                        {"taskVariant":"YES_NO_NOT_GIVEN","instructions":"Invalid","questions":[{"questionText":"Other","answer":"TRUE"}]}
                        """)).andExpect(status().isBadRequest());
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                        {"taskVariant":"TRUE_FALSE_NOT_GIVEN","instructions":"Invalid","questions":[{"questionText":"Other","answer":"YES"}]}
                        """)).andExpect(status().isBadRequest());
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                        {"taskVariant":"UNKNOWN","instructions":"Invalid","questions":[{"questionText":"Other","answer":"YES"}]}
                        """)).andExpect(status().isBadRequest());
        long yesGroupId = jdbc.queryForObject("SELECT question_group_id FROM question_groups WHERE passage_id = ? AND task_variant = 'YES_NO_NOT_GIVEN'", Long.class, id);
        long yesQuestionId = jdbc.queryForObject("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ?", Long.class, yesGroupId);
        String yesEditUrl = groupsUrl + "/" + yesGroupId;
        String yesEdit = """
                {"taskVariant":"YES_NO_NOT_GIVEN","instructions":"Edited","questions":[{"id":%d,"questionText":"Other","answer":"NO"}]}
                """.formatted(yesQuestionId);
        mvc.perform(put(yesEditUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(yesEdit)).andExpect(status().isNoContent());
        mvc.perform(put(yesEditUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(yesEdit.replace("YES_NO_NOT_GIVEN", "TRUE_FALSE_NOT_GIVEN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].taskVariant").value("YES_NO_NOT_GIVEN"))
                .andExpect(jsonPath("$[1].questions[0].answer").value("NO"));
        String editedWithRemoval = """
                {"instructions":"Updated instructions","questions":[
                {"id":%d,"questionText":"Updated first","answer":"FALSE"}]}
                """.formatted(firstId);
        mvc.perform(put(editUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(editedWithRemoval)).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM passage_questions WHERE passage_question_id = ?", Integer.class, secondId));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM option_questions", Integer.class));
        mvc.perform(put(editUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(updatedGroup)).andExpect(status().isBadRequest());
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].questions[0].id").value(firstId))
                .andExpect(jsonPath("$[0].questions[0].questionNumber").value(1))
                .andExpect(jsonPath("$[1].questions[0].questionNumber").value(2));
        assertEquals("Updated instructions", jdbc.queryForObject("SELECT instructions FROM question_groups WHERE question_group_id = ?", String.class, groupId));
        mvc.perform(put(groupsUrl + "/99999").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(editedWithRemoval)).andExpect(status().isNotFound());
        String mc = """
                {"taskVariant":"MULTIPLE_CHOICE_SINGLE","instructions":"Choose one","questions":[
                {"questionText":"Which?","answer":"B","options":["First","Second","Third"]}]}
                """;
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(mc)).andExpect(status().isCreated());
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[2].questions[0].questionNumber").value(3))
                .andExpect(jsonPath("$[2].questions[0].answer").value("B"))
                .andExpect(jsonPath("$[2].questions[0].options[1]").value("Second"));
        for (String bad : new String[]{mc.replace("[\"First\",\"Second\",\"Third\"]", "[\"First\"]"),
                mc.replace("Second", " "), mc.replace("\"B\"", "\"D\"")}) {
            mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                    .contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        }
        long mcGroupId = jdbc.queryForObject("SELECT question_group_id FROM question_groups WHERE passage_id = ? AND task_variant = 'MULTIPLE_CHOICE_SINGLE'", Long.class, id);
        long mcQuestionId = jdbc.queryForObject("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ?", Long.class, mcGroupId);
        String mcEdit = """
                {"taskVariant":"MULTIPLE_CHOICE_SINGLE","instructions":"Choose one","questions":[
                {"id":%d,"questionText":"Edited?","answer":"A","options":["New first","New second"]}]}
                """.formatted(mcQuestionId);
        mvc.perform(put(groupsUrl + "/" + mcGroupId).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(mcEdit)).andExpect(status().isNoContent());
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[2].questions[0].answer").value("A"))
                .andExpect(jsonPath("$[2].questions[0].options.length()").value(2))
                .andExpect(jsonPath("$[2].questions[0].options[0]").value("New first"));
        assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM option_questions", Integer.class));
        String blank = """
                {"taskVariant":"SENTENCE_COMPLETION","wordLimitMode":"ONE_WORD","questions":[
                {"questionText":"The colour is","blankText":".","answer":"colour","alternatives":["color"],"explanation":"In the passage"}]}
                """;
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(blank)).andExpect(status().isCreated());
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(blank.replace("[\"color\"]", "[\" COLOUR \" ]"))).andExpect(status().isBadRequest());
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(blank.replace("ONE_WORD", "INVALID"))).andExpect(status().isBadRequest());
        long blankGroupId = jdbc.queryForObject("SELECT question_group_id FROM question_groups WHERE passage_id = ? AND task_variant = 'SENTENCE_COMPLETION'", Long.class, id);
        long blankQuestionId = jdbc.queryForObject("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ?", Long.class, blankGroupId);
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[3].questions[0].questionNumber").value(4))
                .andExpect(jsonPath("$[3].questions[0].blankText").value("."))
                .andExpect(jsonPath("$[3].questions[0].alternatives[0]").value("color"))
                .andExpect(jsonPath("$[3].instructions").value("ONE WORD ONLY"));
        String blankEdit = """
                {"taskVariant":"SENTENCE_COMPLETION","wordLimitMode":"TWO_WORDS","questions":[
                {"id":%d,"questionText":"It is","blankText":"outside.","answer":"bright red","alternatives":["red"],"explanation":"Evidence"}]}
                """.formatted(blankQuestionId);
        mvc.perform(put(groupsUrl + "/" + blankGroupId).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(blankEdit)).andExpect(status().isNoContent());
        mvc.perform(put(groupsUrl + "/" + blankGroupId).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(blankEdit.replace("TWO_WORDS", "ONE_WORD")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("đáp án chính")));
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[3].wordLimitMode").value("TWO_WORDS"))
                .andExpect(jsonPath("$[3].questions[0].answer").value("bright red"))
                .andExpect(jsonPath("$[3].questions[0].alternatives[0]").value("red"));
        mvc.perform(delete(yesEditUrl).session(session)).andExpect(status().isForbidden());
        mvc.perform(delete(groupsUrl + "/99999").session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isNotFound());
        mvc.perform(delete(yesEditUrl).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isNoContent());
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].questions[0].questionNumber").value(1))
                .andExpect(jsonPath("$[1].taskVariant").value("MULTIPLE_CHOICE_SINGLE"))
                .andExpect(jsonPath("$[1].questions[0].questionNumber").value(2))
                .andExpect(jsonPath("$[2].questions[0].questionNumber").value(3));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM option_questions", Integer.class));
        mvc.perform(delete(groupsUrl + "/" + mcGroupId).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isNoContent());
        mvc.perform(get(groupsUrl).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].questions[0].questionNumber").value(2));
        mvc.perform(delete(groupsUrl + "/" + blankGroupId).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM blank_questions", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM blank_answer_variants", Integer.class));
        String[] modes = {"ONE_WORD", "ONE_WORD", "ONE_WORD", "TWO_WORDS", "TWO_WORDS", "TWO_WORDS_AND_ONE_NUMBER", "TWO_WORDS_AND_ONE_NUMBER", "TWO_WORDS_AND_ONE_NUMBER", "TWO_WORDS_AND_ONE_NUMBER", "TWO_WORDS_AND_ONE_NUMBER"};
        String[] values = {"check-in", "New York", "3", "public library", "3 libraries", "3", "3 old books", "3 very old books", "3 4 books", "3.5"};
        boolean[] valid = {true, false, false, true, false, true, true, false, false, false};
        for (int i = 0; i < modes.length; i++) {
            String candidate = """
                    {"taskVariant":"SENTENCE_COMPLETION","wordLimitMode":"%s","questions":[
                    {"questionText":"Example","blankText":".","answer":"%s","alternatives":[]}]}
                    """.formatted(modes[i], values[i]);
            var request = mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                    .contentType("application/json").content(candidate));
            if (valid[i]) {
                request.andExpect(status().isCreated());
                long createdGroup = jdbc.queryForObject("SELECT question_group_id FROM question_groups WHERE passage_id = ? AND task_variant = 'SENTENCE_COMPLETION'", Long.class, id);
                mvc.perform(delete(groupsUrl + "/" + createdGroup).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isNoContent());
            } else request.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        }
        String invalidAlternative = blank.replace("[\"color\"]", "[\"two words\"]");
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(invalidAlternative)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("đáp án thay thế 1")));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM option_questions", Integer.class));
        jdbc.update("UPDATE passages SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE passage_id = ?", id);
        mvc.perform(delete(editUrl).session(session).header("X-CSRF-TOKEN", token)).andExpect(status().isConflict());
        mvc.perform(post(groupsUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(group)).andExpect(status().isConflict());
        mvc.perform(put(editUrl).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(editedWithRemoval)).andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/passages/{id}", id).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(passage)).andExpect(status().isConflict());
        assertEquals("New text", jdbc.queryForObject("SELECT body FROM passages WHERE passage_id = ?", String.class, id));
    }

    @Test
    void migrationCreatesUsersAndEnforcesConstraints() {
        String insert = """
                INSERT INTO users (email, email_normalized, password_hash, display_name, role, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
        jdbc.update(insert, "learner@example.com", "learner@example.com", "hash", "Learner", "LEARNER");
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT is_active FROM users WHERE email_normalized = ?", Boolean.class, "learner@example.com"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert,
                "Learner@example.com", "learner@example.com", "hash", "Duplicate", "LEARNER"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert,
                "other@example.com", "other@example.com", "hash", "Other", "INVALID"));
    }

    @Test
    void registrationLoginSessionAndCsrf() throws Exception {
        var tokenResponse = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) tokenResponse.getRequest().getSession(false);
        String token = JsonPath.read(tokenResponse.getResponse().getContentAsString(), "$.token");
        String registration = """
                {"email":"Session-Test@example.com","password":"password123","displayName":"Test learner"}
                """;
        mvc.perform(post("/auth/register").session(session).contentType("application/json").content(registration))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(registration)).andExpect(status().isCreated());
        mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(registration)).andExpect(status().isConflict());
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                        {"email":"session-test@example.com","password":"wrong"}
                        """)).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                        {"email":"session-test@example.com","password":"password123"}
                        """)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("LEARNER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Test learner"));
        var newTokenResponse = mvc.perform(get("/auth/csrf").session(session)).andReturn();
        String newToken = JsonPath.read(newTokenResponse.getResponse().getContentAsString(), "$.token");
        mvc.perform(delete("/auth/sessions/current").session(session).header("X-CSRF-TOKEN", newToken))
                .andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        mvc.perform(get("/me")).andExpect(status().isUnauthorized());
    }
}
