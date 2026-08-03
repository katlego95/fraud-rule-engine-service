package com.fraudengine.web;

import com.fraudengine.decision.DecisionService;
import com.fraudengine.generator.LabelledScenario;
import com.fraudengine.generator.SyntheticCorpus;
import com.fraudengine.domain.TransactionEvent;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fires a labelled typology sequence, behind a profile that is inactive by default.
 *
 * <p>The rules carrying the most engineering depth are precisely the ones a reviewer cannot
 * trigger by hand: a velocity rule cannot fire from a single request, and a geo rule cannot fire
 * against an empty database. A reviewer with fifteen minutes needs an affordance a production
 * deployment does not.
 *
 * <p>It is a wrapper over the same path {@code POST /decisions/batch} uses — the same service, the
 * same event-time ordering, the same idempotency — so it demonstrates the engine rather than
 * standing in for it. The sequence is rebased to now on each call so a reviewer can fire it more
 * than once.
 */
@RestController
@RequestMapping("/api/v1/demo")
@Profile("demo")
class ScenarioController {

    private final SyntheticCorpus corpus;
    private final DecisionService service;
    private final JsonMapper json;
    private final Clock clock;

    ScenarioController(SyntheticCorpus corpus, DecisionService service, JsonMapper json, Clock clock) {
        this.corpus = corpus;
        this.service = service;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping("/scenarios/{name}")
    ScenarioRun fire(@PathVariable String name) {
        LabelledScenario scenario = corpus.generate().scenarios().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new DecisionNotFoundException(
                        "No scenario named '%s'. Available: %s".formatted(name, available())))
                .rebasedTo(clock.instant());

        List<DecisionResponse> decisions = scenario.events().stream()
                .sorted(Comparator.comparing(TransactionEvent::occurredAt))
                .map(service::decide)
                .map(result -> DecisionResponse.from(result.decision(), result.replayed(), json))
                .toList();

        return new ScenarioRun(scenario.name(), scenario.typology(), scenario.expectedRuleCode(),
                scenario.description(), decisions);
    }

    private String available() {
        return corpus.generate().scenarios().stream().map(LabelledScenario::name).toList().toString();
    }

    record ScenarioRun(String scenario, String typology, String expectedRule, String description,
            List<DecisionResponse> decisions) {}
}
