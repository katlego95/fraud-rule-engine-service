package com.fraudengine.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fraudengine.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The decision endpoint over HTTP.
 *
 * <p>`DecisionPipelineIT` covers the pipeline by calling the service directly, which skips bean
 * validation entirely — the constraints on `TransactionEvent` only run behind `@Valid` at the
 * controller. Anything asserting a 400 has to enter here.
 */
@Transactional
class DecisionApiIT extends PostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void anOrdinaryTransactionIsDecided() throws Exception {
        mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content(event("120.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("APPROVE"));
    }

    /**
     * The column stores two decimal places. A third was previously accepted, rounded on write, and
     * left the stored row disagreeing with both the rules that evaluated it and the audit snapshot.
     */
    @Test
    void aThirdDecimalPlaceIsRejectedRatherThanRounded() throws Exception {
        mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content(event("120.999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount")
                        .value(org.hamcrest.Matchers.containsString("two decimal places")));
    }

    @Test
    void twoDecimalPlacesAreAccepted() throws Exception {
        mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content(event("120.99")))
                .andExpect(status().isOk());
    }

    /** A foreign currency is refused at the boundary rather than silently mis-scored. ADR 0004. */
    @Test
    void aForeignCurrencyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content(event("120.00").replace("\"ZAR\"", "\"USD\"")))
                .andExpect(status().isBadRequest());
    }

    private static String event(String amount) {
        return """
                {"eventId":"%s","occurredAt":"2026-08-03T09:00:00Z","accountId":"acct-api",
                 "cardToken":"card-api-%s","amount":"%s","currency":"ZAR","merchantId":"m1",
                 "merchantCategoryCode":"5411","merchantCountry":"ZA","channel":"CARD_PRESENT"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), amount);
    }
}
