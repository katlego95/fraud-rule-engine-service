package com.fraudengine.generator;

import com.fraudengine.domain.TransactionEvent;
import java.util.List;

/**
 * @param background legitimate traffic, against which the false positive rate is measured
 * @param scenarios injected fraud, each labelled with the rule that should catch it
 */
public record Corpus(List<TransactionEvent> background, List<LabelledScenario> scenarios) {

    public List<TransactionEvent> allEvents() {
        return java.util.stream.Stream.concat(
                        background.stream(),
                        scenarios.stream().flatMap(scenario -> scenario.events().stream()))
                .sorted(java.util.Comparator.comparing(TransactionEvent::occurredAt))
                .toList();
    }
}
