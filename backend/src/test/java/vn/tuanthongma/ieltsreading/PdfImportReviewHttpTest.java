package vn.tuanthongma.ieltsreading;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"pdf-import.worker-enabled=false",
        "pdf-import.storage-dir=${java.io.tmpdir}/ielts-pdf-review-test"})
@AutoConfigureMockMvc
class PdfImportReviewHttpTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    private record Login(MockHttpSession session, String csrf, long userId) {}

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
    void proposesConservativelyAndSavesOnlyOwnedVersionedDraft() throws Exception {
        Login admin = login();
        Login anotherAdmin = login();
        String raw = """
                {"schemaVersion":1,"scope":"PASSAGE","partNumber":2,"pages":[
                  {"number":1,"extractionMode":"TEXT","warnings":[],"blocks":[
                    {"type":"heading","text":"Reading Passage 2"},
                    {"type":"text","text":"The elm has a long history."},
                    {"type":"heading","text":"Questions 14-16"},
                    {"type":"text","text":"Reading Passage 2 has seven sections, A-G."},
                    {"type":"text","text":"14. Which section describes the elm?"}
                  ]}
                ]}
                """;
        long jobId = jdbc.queryForObject("""
                INSERT INTO pdf_import_jobs (created_by_user_id, scope, part_number,
                    source_storage_key, original_filename, page_count, processed_pages,
                    status, review_data, review_version)
                VALUES (?, 'PASSAGE', 2, ?, 'sample.pdf', 1, 1,
                    'REVIEW_READY', ?::jsonb, 1) RETURNING pdf_import_job_id
                """, Long.class, admin.userId(), UUID.randomUUID(), raw);

        mvc.perform(get("/api/admin/pdf-imports/" + jobId + "/review").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.draft.passages[0].partNumber").value(2))
                .andExpect(jsonPath("$.draft.passages[0].body").value("The elm has a long history."))
                .andExpect(jsonPath("$.draft.questions[0].number").value(14))
                .andExpect(jsonPath("$.draft.questions[0].taskVariant").isEmpty())
                .andExpect(jsonPath("$.draft.answers[0].confirmed").value(false))
                .andExpect(jsonPath("$.draft.unclassifiedBlocks.length()").value(5));
        mvc.perform(get("/api/admin/pdf-imports/" + jobId + "/review")
                        .session(anotherAdmin.session()))
                .andExpect(status().isNotFound());

        String edited = """
                {"version":1,"draft":{
                  "passages":[{"partNumber":2,"title":"The elm","body":"Revised passage",
                    "sourcePages":[1],"warnings":[]}],
                  "questions":[{"number":14,"text":"Which section describes the elm?",
                    "taskVariant":"MATCHING_INFORMATION","sourcePage":1}],
                  "answers":[{"number":14,"value":"B","confirmed":true,"sourcePage":1}],
                  "unclassifiedBlocks":[{"pageNumber":1,"blockIndex":4,"text":"Review this block"}]
                }}
                """;
        mvc.perform(put("/api/admin/pdf-imports/" + jobId + "/review")
                        .session(admin.session()).header("X-CSRF-TOKEN", admin.csrf())
                        .contentType("application/json").content(edited))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.draft.answers[0].value").value("B"));
        mvc.perform(put("/api/admin/pdf-imports/" + jobId + "/review")
                        .session(admin.session()).header("X-CSRF-TOKEN", admin.csrf())
                        .contentType("application/json").content(edited))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/admin/pdf-imports/" + jobId + "/review")
                        .session(anotherAdmin.session()).header("X-CSRF-TOKEN", anotherAdmin.csrf())
                        .contentType("application/json").content(edited))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/pdf-imports/" + jobId + "/review")
                        .session(admin.session()).header("X-CSRF-TOKEN", admin.csrf())
                        .contentType("application/json")
                        .content(edited.replace("\"version\":1", "\"version\":2")
                                .replace("\"value\":\"B\"", "\"value\":\"\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/pdf-imports/" + jobId + "/review").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.source.pages[0].blocks[4].text")
                        .value("14. Which section describes the elm?"));
    }

    private Login login() throws Exception {
        String email = "pdf-review-" + UUID.randomUUID() + "@example.com";
        var csrf = mvc.perform(get("/auth/csrf")).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content("""
                        {"email":"%s","password":"test-password-123","displayName":"PDF Reviewer"}
                        """.formatted(email))).andExpect(status().isCreated());
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email_normalized = ?", email);
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content("""
                        {"email":"%s","password":"test-password-123"}
                        """.formatted(email))).andExpect(status().isOk());
        token = JsonPath.read(mvc.perform(get("/auth/csrf").session(session))
                .andReturn().getResponse().getContentAsString(), "$.token");
        Long id = jdbc.queryForObject("SELECT user_id FROM users WHERE email_normalized = ?", Long.class, email);
        return new Login(session, token, id);
    }
}
