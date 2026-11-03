package vn.tuanthongma.ieltsreading;

import com.jayway.jsonpath.JsonPath;
import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VocabularyNoteHttpTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @Test
    @Transactional
    @EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
    void repeatSaveReusesNoteAndAddsOnlyNewCategories() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "vocab-dedup-" + suffix + "@example.com";
        var csrf = mvc.perform(get("/auth/csrf")).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                {"email":"%s","password":"test-password-123","displayName":"Vocabulary Test"}
                """.formatted(email))).andExpect(status().isCreated());
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                {"email":"%s","password":"test-password-123"}
                """.formatted(email))).andExpect(status().isOk());
        token = JsonPath.read(mvc.perform(get("/auth/csrf").session(session))
                .andReturn().getResponse().getContentAsString(), "$.token");

        long userId = jdbc.queryForObject("SELECT user_id FROM users WHERE email_normalized = ?", Long.class, email);
        long firstCategory = jdbc.queryForObject("""
                INSERT INTO categories (user_id, name, name_normalized)
                VALUES (?, 'Study', 'study') RETURNING category_id
                """, Long.class, userId);
        long secondCategory = jdbc.queryForObject("""
                INSERT INTO categories (user_id, name, name_normalized)
                VALUES (?, 'Practice', 'practice') RETURNING category_id
                """, Long.class, userId);
        long entryId = jdbc.queryForObject("""
                INSERT INTO vocabulary_entries (word, normalized_word, provider, source_url)
                VALUES (?, ?, 'TEST', 'https://example.com') RETURNING vocabulary_entry_id
                """, Long.class, "astonish-" + suffix, "astonish-" + suffix);
        long meaningId = jdbc.queryForObject("""
                INSERT INTO vocabulary_meanings (definition_text)
                VALUES ('to surprise greatly') RETURNING vocabulary_meaning_id
                """, Long.class);
        long senseId = jdbc.queryForObject("""
                INSERT INTO vocabulary_senses (vocabulary_entry_id, vocabulary_meaning_id, part_of_speech)
                VALUES (?, ?, 'verb') RETURNING vocabulary_sense_id
                """, Long.class, entryId, meaningId);

        var first = mvc.perform(post("/api/vocabulary-notes").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                {"senseId":%d,"selectedText":"astonishing","contextSentence":"","categoryIds":[%d]}
                """.formatted(senseId, firstCategory)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.created").value(true))
                .andReturn();
        int noteId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/vocabulary-notes").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                {"senseId":%d,"selectedText":"astonish","contextSentence":"","categoryIds":[%d]}
                """.formatted(senseId, secondCategory)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(noteId))
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.addedCategoryCount").value(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vocabulary_notes WHERE user_id = ?", Integer.class, userId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM note_categories WHERE user_id = ? AND vocabulary_note_id = ?", Integer.class, userId, noteId));

        mvc.perform(post("/api/vocabulary-notes").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                {"senseId":%d,"selectedText":"astonish","contextSentence":"","categoryIds":[%d]}
                """.formatted(senseId, secondCategory)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.addedCategoryCount").value(0));

        mvc.perform(post("/api/vocabulary-notes").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                {"senseId":%d,"selectedText":"astonish","contextSentence":"","customMeaning":"My new meaning"}
                """.formatted(senseId)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/vocabulary-notes").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                {"senseId":%d,"selectedText":"astonish","contextSentence":"Another sentence."}
                """.formatted(senseId)))
                .andExpect(status().isCreated());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM vocabulary_notes WHERE user_id = ?", Integer.class, userId));

        long otherMeaningId = jdbc.queryForObject("""
                INSERT INTO vocabulary_meanings (definition_text)
                VALUES ('to amaze') RETURNING vocabulary_meaning_id
                """, Long.class);
        long otherSenseId = jdbc.queryForObject("""
                INSERT INTO vocabulary_senses (vocabulary_entry_id, vocabulary_meaning_id, part_of_speech)
                VALUES (?, ?, 'verb') RETURNING vocabulary_sense_id
                """, Long.class, entryId, otherMeaningId);
        mvc.perform(post("/api/vocabulary-notes").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                {"senseId":%d,"selectedText":"astonish","contextSentence":""}
                """.formatted(otherSenseId)))
                .andExpect(status().isCreated());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM vocabulary_notes WHERE user_id = ?", Integer.class, userId));

        mvc.perform(get("/api/vocabulary-notes/summary").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.newCount").value(3))
                .andExpect(jsonPath("$.learningCount").value(0))
                .andExpect(jsonPath("$.masteredCount").value(0));
        mvc.perform(get("/api/vocabulary-notes").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/vocabulary-notes").param("categoryId", String.valueOf(firstCategory)).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(patch("/api/vocabulary-notes/" + noteId).session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("""
                {"selectedText":"astonish","contextSentence":"Another sentence."}
                """))
                .andExpect(status().isConflict());
    }
}
