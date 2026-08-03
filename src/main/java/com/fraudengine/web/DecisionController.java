package com.fraudengine.web;

import com.fraudengine.decision.Cursor;
import com.fraudengine.decision.DecisionQuery;
import com.fraudengine.decision.DecisionRepository;
import com.fraudengine.decision.DecisionResult;
import com.fraudengine.decision.DecisionService;
import com.fraudengine.domain.Decision;
import com.fraudengine.domain.TransactionEvent;
import com.fraudengine.domain.Verdict;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
class DecisionController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final DecisionService service;
    private final DecisionRepository decisions;
    private final JsonMapper json;

    DecisionController(DecisionService service, DecisionRepository decisions, JsonMapper json) {
        this.service = service;
        this.decisions = decisions;
        this.json = json;
    }

    @PostMapping("/decisions")
    DecisionResponse decide(@Valid @RequestBody TransactionEvent event) {
        DecisionResult result = service.decide(event);
        return DecisionResponse.from(result.decision(), result.replayed(), json);
    }

    /**
     * Evaluates a sequence in event-time order, so a velocity pattern can be fired in one call.
     *
     * <p>Deliberately not atomic: each transaction is decided in its own database transaction, so
     * one rejected item does not discard the decisions already made for the others. That matches
     * the semantics of the single endpoint being called repeatedly, which is what a caller
     * replaying a sequence expects.
     */
    @PostMapping("/decisions/batch")
    List<DecisionResponse> decideBatch(@Valid @RequestBody List<@Valid TransactionEvent> events) {
        return events.stream()
                .sorted(Comparator.comparing(TransactionEvent::occurredAt))
                .map(service::decide)
                .map(result -> DecisionResponse.from(result.decision(), result.replayed(), json))
                .toList();
    }

    @GetMapping("/decisions/{decisionId}")
    DecisionResponse byId(@PathVariable UUID decisionId) {
        Decision decision = decisions.findById(decisionId)
                .orElseThrow(() -> new DecisionNotFoundException("No decision with id " + decisionId));
        return DecisionResponse.from(decision, false, json);
    }

    /** Resolve a decision by the caller's own event identifier, which is what a consumer holds. */
    @GetMapping("/transactions/{eventId}/decision")
    DecisionResponse byEventId(@PathVariable UUID eventId) {
        Decision decision = decisions.findByEventId(eventId)
                .orElseThrow(() -> new DecisionNotFoundException("No decision for event " + eventId));
        return DecisionResponse.from(decision, false, json);
    }

    @GetMapping("/decisions")
    DecisionPage search(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String cardToken,
            @RequestParam(required = false) List<Verdict> verdict,
            @RequestParam(required = false) String ruleCode,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize) {

        int size = resolvePageSize(pageSize);
        DecisionQuery query = new DecisionQuery(accountId, cardToken, verdict, ruleCode, minScore,
                from, to, cursor == null ? null : Cursor.decode(cursor));

        // One more than the page so exhaustion is known without a second query or a count.
        List<Decision> found = decisions.search(query, size + 1);
        boolean hasMore = found.size() > size;
        List<Decision> page = hasMore ? found.subList(0, size) : found;

        String nextCursor = hasMore
                ? new Cursor(page.getLast().evaluatedAt(), page.getLast().decisionId()).encode()
                : null;

        return new DecisionPage(
                page.stream().map(decision -> DecisionResponse.from(decision, false, json)).toList(),
                nextCursor,
                size);
    }

    /**
     * Rejects an oversized page rather than silently capping it: a client that asked for 1000 and
     * received 200 without being told believes it has seen everything.
     */
    private static int resolvePageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (requested < 1 || requested > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and %d, was %d".formatted(MAX_PAGE_SIZE, requested));
        }
        return requested;
    }
}
