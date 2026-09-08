package com.fraudengine.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fraudengine.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The rules API over HTTP.
 *
 * <p>Every other test in this suite enters at the service or repository layer, which leaves the
 * status codes and the exception-to-response mapping unexercised — and that is where the four
 * defects this branch fixes were living. Status codes exist only here, so only a test at this
 * level can hold them.
 */
@Transactional
class RuleApiIT extends PostgresIntegrationTest {

    // Built from the context rather than @AutoConfigureMockMvc: Spring Boot 4 moved that
    // annotation out of the test starter, and this needs no dependency the project lacks.
    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void creatingARuleReturns201AndTheStoredVersion() throws Exception {
        mvc.perform(post("/api/v1/rules").contentType(MediaType.APPLICATION_JSON)
                        .content(rule("NEW_RULE", "{\\\"threshold\\\":\\\"8000.00\\\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("NEW_RULE"))
                .andExpect(jsonPath("$.version").value(1));
    }

    /** The defect: this was accepted, then failed on the next transaction as a 500. */
    @Test
    void aMisspelledParameterIsRejectedOnTheWriteAndNamesTheField() throws Exception {
        mvc.perform(post("/api/v1/rules").contentType(MediaType.APPLICATION_JSON)
                        .content(rule("TYPO_RULE", "{\\\"treshold\\\":\\\"8000.00\\\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("threshold")));
    }

    @Test
    void aMissingRequiredFieldIsRejected() throws Exception {
        mvc.perform(post("/api/v1/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NO_TYPE","mode":"SHADOW","nature":"CONTRIBUTORY",
                                 "weight":10,"parameters":"{}","description":"d","typology":"t"}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** The defect: an unknown id left RuleNotFoundException uncaught, which answered 500. */
    @Test
    void changingTheModeOfAnUnknownRuleReturns404() throws Exception {
        mvc.perform(patch("/api/v1/rules/{id}/mode", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DISABLED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyForAnUnknownCodeReturns404() throws Exception {
        mvc.perform(get("/api/v1/rules/{code}/history", "NO_SUCH_RULE"))
                .andExpect(status().isNotFound());
    }

    /** Editing a rule is a new version, never a mutation — the prior one stays readable. */
    @Test
    void postingAnExistingCodeAddsAVersionRatherThanReplacingIt() throws Exception {
        mvc.perform(post("/api/v1/rules").contentType(MediaType.APPLICATION_JSON)
                        .content(rule("HIGH_AMOUNT", "{\\\"threshold\\\":\\\"7500.00\\\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/v1/rules/{code}/history", "HIGH_AMOUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private static String rule(String code, String parameters) {
        return """
                {"code":"%s","type":"AMOUNT_THRESHOLD","mode":"SHADOW","nature":"CONTRIBUTORY",
                 "weight":10,"parameters":"%s","description":"A rule under test.",
                 "typology":"Testing"}
                """.formatted(code, parameters);
    }
}
