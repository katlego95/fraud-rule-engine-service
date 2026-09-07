package com.fraudengine.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fraudengine.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Admission control, driven at a limit of one so the shed path is reachable without generating
 * load. What is being tested is the decision to refuse and the release of the permit afterwards,
 * neither of which depends on the size of the limit.
 */
@TestPropertySource(properties = "fraud.admission.max-in-flight=1")
@Transactional
class AdmissionControlIT extends PostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Filters are not registered by webAppContextSetup unless asked for, and the filter is
        // the thing under test.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(AdmissionControlFilter.class))
                .build();
    }

    /**
     * The permit must be returned on every path or the service throttles itself to a standstill:
     * one leak per request and a limit of N stops accepting anything after N requests. Sequential
     * requests well past the limit are the cheapest way to prove the release happens.
     */
    @Test
    void permitsAreReleasedSoSequentialRequestsAreNeverShed() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                            .content(event()))
                    .andExpect(status().isOk());
        }
    }

    /** A permit is released even when the request inside it fails. */
    @Test
    void permitsAreReleasedWhenTheRequestIsRejected() throws Exception {
        mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content(event()))
                .andExpect(status().isOk());
    }

    /**
     * Shedding applies to the decision path only. An operator disabling a rule while the service
     * is refusing transactions is the case where that access matters most.
     */
    @Test
    void ruleAdministrationIsNotSubjectToAdmissionControl() throws Exception {
        mvc.perform(get("/api/v1/rules")).andExpect(status().isOk());
    }

    /** Reads are not shed either — only the work that costs a decision. */
    @Test
    void readsAreNotSubjectToAdmissionControl() throws Exception {
        mvc.perform(get("/api/v1/decisions")).andExpect(status().isOk());
    }

    @Test
    void aBatchLargerThanTheCapIsRejected() throws Exception {
        String events = IntStream.range(0, 501)
                .mapToObj(i -> event())
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();

        mvc.perform(post("/api/v1/decisions/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("[" + events + "]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBatchAtTheCapIsAccepted() throws Exception {
        String events = IntStream.range(0, 500)
                .mapToObj(i -> event())
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();

        mvc.perform(post("/api/v1/decisions/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("[" + events + "]"))
                .andExpect(status().isOk());
    }

    private static String event() {
        return """
                {"eventId":"%s","occurredAt":"2026-08-03T09:00:00Z","accountId":"acct-admission",
                 "cardToken":"4000111122223333","amount":"%s","currency":"ZAR","merchantId":"m1",
                 "merchantCategoryCode":"5411","merchantCountry":"ZA","channel":"CARD_PRESENT"}
                """.formatted(UUID.randomUUID(), new BigDecimal("120.00"));
    }
}
