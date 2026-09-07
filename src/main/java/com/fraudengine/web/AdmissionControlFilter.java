package com.fraudengine.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps decisions in flight and refuses the rest rather than queueing them.
 *
 * <p>The service does not fail under load, it slows down: p99 measured 128ms at 100 concurrent and
 * 179ms at 150, against a 150ms budget, with no errors at any level. What is prevented is not a
 * crash but the budget being quietly missed for everyone. See docs/load-test.md.
 *
 * <p>Decisions only — an operator must still be able to disable a rule while transactions are
 * being shed.
 */
@Component
public class AdmissionControlFilter extends OncePerRequestFilter {

    private final Semaphore permits;
    private final int maxInFlight;
    private final int retryAfterSeconds;
    private final MeterRegistry registry;

    AdmissionControlFilter(
            @Value("${fraud.admission.max-in-flight}") int maxInFlight,
            @Value("${fraud.admission.retry-after-seconds}") int retryAfterSeconds,
            MeterRegistry registry) {
        // Unfair by design: ordering a queue this short costs more than it is worth.
        this.permits = new Semaphore(maxInFlight);
        this.maxInFlight = maxInFlight;
        this.retryAfterSeconds = retryAfterSeconds;
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/decisions"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        // tryAcquire, not acquire: waiting for a permit is the queueing this exists to avoid.
        if (!permits.tryAcquire()) {
            registry.counter("fraud.admission.shed").increment();
            shed(response);
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // A permit leaked on an exception path is gone for the life of the process.
            permits.release();
        }
    }

    private void shed(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.getWriter().write("""
                {"type":"about:blank","title":"Too many decisions in flight",\
                "status":429,\
                "detail":"The service is already evaluating %d transactions. \
                Retry in %d second(s)."}"""
                .formatted(maxInFlight, retryAfterSeconds));
    }
}
