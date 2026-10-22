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
                        {"instructions":"Another group","questions":[{"questionText":"Other","answer":"TRUE"}]}
                        """)).andExpect(status().isCreated());
        mvc.perform(get(groupsUrl).session(session)).andExpect(jsonPath("$[1].questions[0].questionNumber").value(3));
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
        jdbc.update("UPDATE passages SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE passage_id = ?", id);
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
