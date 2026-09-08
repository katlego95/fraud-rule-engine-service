package com.fraudengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Shedding needs two requests in flight at once, which a sequential MockMvc test cannot produce.
 * Here one request is held inside the filter on a latch while a second arrives.
 */
class AdmissionControlFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AdmissionControlFilter filter = new AdmissionControlFilter(1, 1, registry);

    @Test
    void aRequestArrivingWithNoPermitLeftIsRefusedRatherThanQueued() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try {
                filter.doFilter(decisionRequest(), new MockHttpServletResponse(), (req, res) -> {
                    inside.countDown();
                    await(release);
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        holder.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse shed = new MockHttpServletResponse();
        filter.doFilter(decisionRequest(), shed, (req, res) -> {
            throw new AssertionError("the second request should not have reached the chain");
        });

        assertThat(shed.getStatus()).isEqualTo(429);
        assertThat(shed.getHeader("Retry-After")).isEqualTo("1");
        assertThat(shed.getContentAsString()).contains("Too many decisions in flight");
        assertThat(registry.counter("fraud.admission.shed").count()).isEqualTo(1);

        release.countDown();
        holder.join(5000);
    }

    /** A permit held by a request that threw is a permit gone for the life of the process. */
    @Test
    void aPermitIsReleasedWhenTheRequestInsideItThrows() throws Exception {
        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("failed while deciding");
        };

        try {
            filter.doFilter(decisionRequest(), new MockHttpServletResponse(), boom);
        } catch (Exception expected) {
            // the point is what happens next
        }

        MockHttpServletResponse next = new MockHttpServletResponse();
        filter.doFilter(decisionRequest(), next, (req, res) -> next.setStatus(200));

        assertThat(next.getStatus()).isEqualTo(200);
    }

    @Test
    void readsAndRuleAdministrationAreNotShed() throws Exception {
        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/v1/decisions");
        MockHttpServletRequest admin = new MockHttpServletRequest("POST", "/api/v1/rules");

        assertThat(filter.shouldNotFilter(read)).isTrue();
        assertThat(filter.shouldNotFilter(admin)).isTrue();
        assertThat(filter.shouldNotFilter(decisionRequest())).isFalse();
    }

    private static MockHttpServletRequest decisionRequest() {
        return new MockHttpServletRequest("POST", "/api/v1/decisions");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
