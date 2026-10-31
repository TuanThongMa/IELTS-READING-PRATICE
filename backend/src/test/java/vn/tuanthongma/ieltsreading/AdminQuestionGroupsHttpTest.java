package vn.tuanthongma.ieltsreading;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminQuestionGroupsHttpTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
    @Transactional
    void adminCanSaveAndReloadAllSupportedQuestionGroupShapes() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        String email = "question-types-" + suffix + "@example.com";
        String title = "Question type contract test " + suffix;
        var csrf = mvc.perform(get("/auth/csrf")).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/register").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content("""
                                {"email":"%s","password":"test-password-123","displayName":"Question Types"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email_normalized = ?", email);
        mvc.perform(post("/auth/sessions").session(session).header("X-CSRF-TOKEN", token)
                        .contentType("application/json").content("""
                                {"email":"%s","password":"test-password-123"}
                                """.formatted(email)))
                .andExpect(status().isOk());
        token = JsonPath.read(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString(), "$.token");
        jdbc.update("""
                INSERT INTO passages (title, body, part_number, status, created_by_user_id)
                VALUES (?, 'Test passage text.', 1, 'DRAFT',
                        (SELECT user_id FROM users WHERE email_normalized = ?))
                """, title, email);
        long passageId = jdbc.queryForObject("SELECT passage_id FROM passages WHERE title = ?", Long.class, title);
        String url = "/api/admin/passages/" + passageId + "/question-groups";

        mvc.perform(post(url).session(session).header("X-CSRF-TOKEN", token).contentType("application/json").content("""
                {"taskVariant":"SENTENCE_COMPLETION","wordLimitMode":"ONE_WORD",
                 "questions":[{"questionText":"","blankText":"","answer":"first","alternatives":[]},
                              {"questionText":"","blankText":"","answer":"second","alternatives":[]}],
                 "presentationTemplate":{"version":1,"kind":"PARAGRAPH","caption":null,"columns":null,"rows":null,"steps":null,
                   "paragraphs":[[{"text":"Start","questionIndex":null},{"text":null,"questionIndex":1},
                                   {"text":" then ","questionIndex":null},{"text":null,"questionIndex":0}]]}}
                """)) .andExpect(status().isBadRequest());

        String[] payloads = {
                """
                {"taskVariant":"TRUE_FALSE_NOT_GIVEN","instructions":"Do the statements agree?","questions":[{"questionText":"Statement one","answer":"TRUE"},{"questionText":"Statement two","answer":"NOT GIVEN"}]}
                """,
                """
                {"taskVariant":"YES_NO_NOT_GIVEN","instructions":"Do the claims agree?","questions":[{"questionText":"Claim one","answer":"NO"}]}
                """,
                """
                {"taskVariant":"MULTIPLE_CHOICE_SINGLE","questions":[{"questionText":"Which answer is correct?","answer":"A","options":["First option","Second option"]}]}
                """,
                """
                {"taskVariant":"MULTIPLE_CHOICE_MULTIPLE","instructions":"Choose TWO letters.","correctAnswerLabels":["A","B"],"questions":[{"questionText":"","answer":"","options":["First option","Second option","Third option"]},{"questionText":"","answer":"","options":["First option","Second option","Third option"]}]}
                """,
                """
                {"taskVariant":"SENTENCE_COMPLETION","wordLimitMode":"TWO_WORDS","questions":[{"questionText":"The first answer is","blankText":".","answer":"New York","alternatives":[]}]}
                """,
                """
                {"taskVariant":"NOTE_COMPLETION","wordLimitMode":"ONE_WORD","questions":[{"questionText":"First note","blankText":"","answer":"reading","alternatives":[]}]}
                """,
                """
                {"taskVariant":"SUMMARY_COMPLETION","answerInputMode":"TEXT_ENTRY","wordLimitMode":"ONE_WORD","questions":[{"questionText":"Summary before","blankText":"after","answer":"reading","alternatives":[]}]}
                """,
                """
                {"taskVariant":"SUMMARY_COMPLETION","instructions":"Complete the summary using the words below.","answerInputMode":"SHARED_CHOICE_POOL","candidateReuse":false,"candidates":[{"label":"A","text":"first choice"},{"label":"B","text":"second choice"}],"questions":[{"questionText":"Summary sentence","answer":"A"}]}
                """,
                """
                {"taskVariant":"TABLE_COMPLETION","instructions":"Complete the table.","wordLimitMode":"ONE_WORD","questions":[{"questionText":"","blankText":"","answer":"north","alternatives":[]}],"presentationTemplate":{"version":1,"kind":"TABLE","caption":"Sample table","columns":["Place","Direction"],"rows":[[[{"text":"Example","questionIndex":null}],[{"text":null,"questionIndex":0}]]],"steps":null}}
                """,
                """
                {"taskVariant":"FLOW_CHART_COMPLETION","instructions":"Complete the flow chart.","wordLimitMode":"ONE_WORD","questions":[{"questionText":"","blankText":"","answer":"north","alternatives":[]}],"presentationTemplate":{"version":1,"kind":"FLOW_CHART","caption":"Sample flow","columns":null,"rows":null,"steps":[[{"text":"Start","questionIndex":null}],[{"text":null,"questionIndex":0}]]}}
                """,
                """
                {"taskVariant":"SHORT_ANSWER","instructions":"Answer using NO MORE THAN TWO WORDS.","wordLimitMode":"TWO_WORDS","questions":[{"questionText":"Where did it happen?","blankText":"","answer":"New York","alternatives":[]}]}
                """,
                matching("MATCHING_INFORMATION"),
                matching("MATCHING_HEADINGS"),
                matching("MATCHING_FEATURES"),
                matching("MATCHING_SENTENCE_ENDINGS"),
                """
                {"taskVariant":"SENTENCE_COMPLETION","wordLimitMode":"ONE_WORD",
                 "questions":[{"questionText":"","blankText":"","answer":"oak","alternatives":[]},
                              {"questionText":"","blankText":"","answer":"flooring","alternatives":[]},
                              {"questionText":"","blankText":"","answer":"keel","alternatives":[]}],
                 "presentationTemplate":{"version":1,"kind":"PARAGRAPH","caption":null,"columns":null,"rows":null,"steps":null,
                 "paragraphs":[[{"text":"For hundreds of years, elm was used for","questionIndex":null},
                                   {"text":null,"questionIndex":0,"sourceText":"oak"},
                                   {"text":". The wood was also used for","questionIndex":null},
                                   {"text":null,"questionIndex":1},
                                   {"text":" and for ship parts such as","questionIndex":null},
                                   {"text":null,"questionIndex":2},
                                   {"text":".","questionIndex":null}]]}}
                """
        };

        for (String payload : payloads) {
            mvc.perform(post(url).session(session).header("X-CSRF-TOKEN", token)
                            .contentType("application/json").content(payload))
                    .andExpect(status().isCreated());
        }

        mvc.perform(get(url).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(payloads.length)))
                .andExpect(jsonPath("$[0].questions", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].questions[1].questionText").value("Statement two"))
                .andExpect(jsonPath("$[2].questions[0].options", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[7].candidates", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[8].presentationTemplate.columns", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[9].presentationTemplate.steps", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[10].questions[0].questionText").value("Where did it happen?"))
                .andExpect(jsonPath("$[15].presentationTemplate.paragraphs", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[15].presentationTemplate.paragraphs[0][1].questionIndex").value(0))
                .andExpect(jsonPath("$[15].presentationTemplate.paragraphs[0][1].sourceText").value("oak"))
                .andExpect(jsonPath("$[15].questions", org.hamcrest.Matchers.hasSize(3)));
        mvc.perform(post("/api/admin/passages/" + passageId + "/publish").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        mvc.perform(get("/api/reading/passages/" + passageId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups", org.hamcrest.Matchers.hasSize(payloads.length)))
                .andExpect(jsonPath("$.groups[0].questions[1].number").value(2))
                .andExpect(jsonPath("$.groups[2].questions[0].choices", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.groups[8].presentationTemplate.rows", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.groups[15].presentationTemplate.paragraphs[0][5].questionIndex").value(2))
                .andExpect(jsonPath("$.groups[15].presentationTemplate.paragraphs[0][1].sourceText").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.groups[15].questions[0].number").value(18))
                .andExpect(jsonPath("$.groups[15].questions[2].number").value(20))
                .andExpect(jsonPath("$.groups[0].questions[0].answer").doesNotExist());
        assertEquals(payloads.length + 4, jdbc.queryForObject("SELECT COUNT(*) FROM passage_questions q JOIN question_groups g ON g.question_group_id = q.question_group_id WHERE g.passage_id = ?", Integer.class, passageId));
    }

    private String matching(String variant) {
        return """
                {"taskVariant":"%s","instructions":"Match each statement.","candidateReuse":false,
                 "candidates":[{"label":"A","text":"First candidate"},{"label":"B","text":"Second candidate"}],
                 "questions":[{"questionText":"Statement to match","answer":"A"}]}
                """.formatted(variant);
    }
}
