package vn.tuanthongma.ieltsreading;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "pdf-import.worker-enabled=false",
        "pdf-import.storage-dir=${java.io.tmpdir}/ielts-pdf-import-test"
})
@AutoConfigureMockMvc
class PdfImportHttpTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    private record Login(MockHttpSession session, String csrf) {}

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
    void validatesQueuesAndScopesPdfUploads() throws Exception {
        Login admin = login(true);
        Login otherAdmin = login(true);
        Login learner = login(false);
        byte[] pdf = onePagePdf();
        MockMultipartFile valid = new MockMultipartFile("file", "sample.pdf", "application/pdf", pdf);

        mvc.perform(multipart("/api/admin/pdf-imports").file(valid)
                        .param("scope", "PASSAGE").param("partNumber", "2")
                        .session(learner.session()).header("X-CSRF-TOKEN", learner.csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/pdf-imports").file(new MockMultipartFile(
                        "file", "fake.pdf", "application/pdf", "not a PDF".getBytes()))
                        .param("scope", "PASSAGE").param("partNumber", "2")
                        .session(admin.session()).header("X-CSRF-TOKEN", admin.csrf()))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/admin/pdf-imports").file(valid)
                        .param("scope", "PASSAGE").param("partNumber", "4")
                        .session(admin.session()).header("X-CSRF-TOKEN", admin.csrf()))
                .andExpect(status().isBadRequest());

        var first = mvc.perform(multipart("/api/admin/pdf-imports").file(valid)
                        .param("scope", "PASSAGE").param("partNumber", "2")
                        .session(admin.session()).header("X-CSRF-TOKEN", admin.csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.pageCount").value(1)).andReturn();
        int id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/admin/pdf-imports/" + id).session(otherAdmin.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/pdf-imports/" + id + "/source").session(admin.session()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/pdf-imports/" + id + "/review").session(admin.session()))
                .andExpect(status().isNotFound());

        for (int i = 0; i < 2; i++) {
            mvc.perform(multipart("/api/admin/pdf-imports").file(valid)
                            .param("scope", "FULL_TEST")
                            .session(admin.session()).header("X-CSRF-TOKEN", admin.csrf()))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(multipart("/api/admin/pdf-imports").file(valid)
                        .param("scope", "FULL_TEST")
                        .session(admin.session()).header("X-CSRF-TOKEN", admin.csrf()))
                .andExpect(status().isTooManyRequests());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM pdf_import_jobs", Integer.class));

        mvc.perform(delete("/api/admin/pdf-imports/" + id).session(admin.session())
                        .header("X-CSRF-TOKEN", admin.csrf()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/admin/pdf-imports/" + id + "/source").session(admin.session()))
                .andExpect(status().isNotFound());
        long failedId = jdbc.queryForObject("""
                SELECT pdf_import_job_id FROM pdf_import_jobs
                WHERE status = 'QUEUED' ORDER BY pdf_import_job_id LIMIT 1
                """, Long.class);
        jdbc.update("UPDATE pdf_import_jobs SET status = 'FAILED' WHERE pdf_import_job_id = ?", failedId);
        mvc.perform(post("/api/admin/pdf-imports/" + failedId + "/retry")
                        .session(admin.session()).header("X-CSRF-TOKEN", admin.csrf()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));

        for (UUID key : jdbc.query("SELECT source_storage_key FROM pdf_import_jobs",
                (row, index) -> row.getObject(1, UUID.class))) {
            Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"),
                    "ielts-pdf-import-test", key + ".pdf"));
        }
    }

    private Login login(boolean admin) throws Exception {
        String email = "pdf-import-" + UUID.randomUUID() + "@example.com";
        var csrf = mvc.perform(get("/auth/csrf")).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content("""
                        {"email":"%s","password":"test-password-123","displayName":"PDF Tester"}
                        """.formatted(email)))
                .andExpect(status().isCreated());
        if (admin) jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email_normalized = ?", email);
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content("""
                        {"email":"%s","password":"test-password-123"}
                        """.formatted(email)))
                .andExpect(status().isOk());
        token = JsonPath.read(mvc.perform(get("/auth/csrf").session(session))
                .andReturn().getResponse().getContentAsString(), "$.token");
        return new Login(session, token);
    }

    private static byte[] onePagePdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
