package vn.tuanthongma.ieltsreading;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reading_data_load_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
class ReadingDataLoadHttpTest {
    private static final String PASSWORD = "load-test-password";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @Transactional
    void learnerAndAdminCanLoadPublishedPassageAndFullTest() throws Exception {
        long ownerId = createUser("content-owner@example.com", "ADMIN");
        long passage1 = createPassage(ownerId, "Load check passage 1", 1, "Passage one body.");
        long passage2 = createPassage(ownerId, "Load check passage 2", 2, "Passage two body.");
        long passage3 = createPassage(ownerId, "Load check passage 3", 3, "Passage three body.");
        long testId = createFullTest(ownerId, passage1, passage2, passage3);

        for (String role : new String[]{"LEARNER", "ADMIN"}) {
            String email = role.toLowerCase() + "-reader@example.com";
            createUser(email, role);
            Login login = login(email);

            var passageList = mvc.perform(get("/api/reading/passages").session(login.session()))
                    .andExpect(status().isOk()).andReturn();
            assertTrue(JsonPath.<java.util.List<Integer>>read(passageList.getResponse().getContentAsString(), "$[*].id")
                    .contains((int) passage1));

            mvc.perform(get("/api/reading/passages/{id}", passage1).session(login.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Load check passage 1"))
                    .andExpect(jsonPath("$.body").value("Passage one body."))
                    .andExpect(jsonPath("$.groups[0].questions[0].number").value(1))
                    .andExpect(jsonPath("$.groups[0].questions[0].answer").doesNotExist());

            var passageAttempt = mvc.perform(post("/api/reading/passages/{id}/attempts", passage1)
                            .session(login.session()).header("X-CSRF-TOKEN", login.csrfToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.answers.length()").value(0))
                    .andReturn();
            assertNotNull(JsonPath.read(passageAttempt.getResponse().getContentAsString(), "$.id"));

            mvc.perform(get("/api/reading/tests").session(login.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value((int) testId))
                    .andExpect(jsonPath("$[0].questionCount").value(40));

            mvc.perform(get("/api/reading/tests/{id}", testId).session(login.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Load check full test"))
                    .andExpect(jsonPath("$.passages.length()").value(3))
                    .andExpect(jsonPath("$.passages[0].groups[0].questions[0].number").value(1))
                    .andExpect(jsonPath("$.passages[1].groups[0].questions[0].number").value(14))
                    .andExpect(jsonPath("$.passages[2].groups[0].questions[0].number").value(27));

            var testAttempt = mvc.perform(post("/api/reading/tests/{id}/attempts", testId)
                            .session(login.session()).header("X-CSRF-TOKEN", login.csrfToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.elapsedSeconds").value(0))
                    .andExpect(jsonPath("$.answers.length()").value(0))
                    .andReturn();
            assertNotNull(JsonPath.read(testAttempt.getResponse().getContentAsString(), "$.id"));
        }
    }

    private long createUser(String email, String role) {
        jdbc.update("""
                INSERT INTO users (email, email_normalized, password_hash, display_name, role, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, email, email, passwordEncoder.encode(PASSWORD), role + " Reader", role);
        return jdbc.queryForObject("SELECT user_id FROM users WHERE email_normalized = ?", Long.class, email);
    }

    private long createPassage(long ownerId, String title, int partNumber, String body) {
        jdbc.update("""
                INSERT INTO passages (title, body, part_number, status, created_by_user_id, published_at)
                VALUES (?, ?, ?, 'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, title, body, partNumber, ownerId);
        long passageId = jdbc.queryForObject("SELECT passage_id FROM passages WHERE title = ?", Long.class, title);
        jdbc.update("""
                INSERT INTO question_groups (passage_id, display_order, question_type, task_variant, answer_input_mode, instructions)
                VALUES (?, 1, 'OPTION', 'MULTIPLE_CHOICE_SINGLE', 'PER_QUESTION_CHOICE', 'Choose the correct answer.')
                """, passageId);
        long groupId = jdbc.queryForObject("SELECT question_group_id FROM question_groups WHERE passage_id = ?", Long.class, passageId);
        jdbc.update("INSERT INTO passage_questions (question_group_id, question_number, question_text) VALUES (?, 1, 'What is the answer?')", groupId);
        long questionId = jdbc.queryForObject("SELECT passage_question_id FROM passage_questions WHERE question_group_id = ?", Long.class, groupId);
        jdbc.update("INSERT INTO option_questions (answer_label, option_text) VALUES ('A', 'Choice A')");
        long optionA = jdbc.queryForObject("SELECT option_question_id FROM option_questions ORDER BY option_question_id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO option_questions (answer_label, option_text) VALUES ('B', 'Choice B')");
        long optionB = jdbc.queryForObject("SELECT option_question_id FROM option_questions ORDER BY option_question_id DESC LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO question_answer_options (passage_question_id, option_question_id, is_correct, display_order)
                VALUES (?, ?, true, 1), (?, ?, false, 2)
                """, questionId, optionA, questionId, optionB);
        return passageId;
    }

    private long createFullTest(long ownerId, long passage1, long passage2, long passage3) {
        jdbc.update("INSERT INTO reading_tests (title, status, created_by_user_id, published_at) VALUES ('Load check full test', 'PUBLISHED', ?, CURRENT_TIMESTAMP)", ownerId);
        long testId = jdbc.queryForObject("SELECT reading_test_id FROM reading_tests WHERE title = 'Load check full test'", Long.class);
        jdbc.update("""
                INSERT INTO test_passages (reading_test_id, passage_id, display_order, question_start_number, question_end_number)
                VALUES (?, ?, 1, 1, 13), (?, ?, 2, 14, 26), (?, ?, 3, 27, 40)
                """, testId, passage1, testId, passage2, testId, passage3);
        return testId;
    }

    private Login login(String email) throws Exception {
        MvcResult csrfResult = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        MvcResult refreshedCsrf = mvc.perform(get("/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        String refreshedToken = JsonPath.read(refreshedCsrf.getResponse().getContentAsString(), "$.token");
        return new Login(session, refreshedToken);
    }

    private record Login(MockHttpSession session, String csrfToken) {}
}
