package com.fraudengine.web;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.Verdict;
import com.fraudengine.rules.RuleRepository;
import com.fraudengine.rules.RuleValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rules")
class RuleController {

    private final RuleRepository rules;
    private final RuleValidator validator;

    RuleController(RuleRepository rules, RuleValidator validator) {
        this.rules = rules;
        this.validator = validator;
    }

    @GetMapping
    List<RuleResponse> current() {
        return rules.findCurrent().stream().map(RuleResponse::from).toList();
    }

    @GetMapping("/{code}/history")
    List<RuleResponse> history(@PathVariable String code) {
        List<Rule> versions = rules.findHistory(code);
        if (versions.isEmpty()) {
            throw new DecisionNotFoundException("No rule with code " + code);
        }
        return versions.stream().map(RuleResponse::from).toList();
    }

    /**
     * Creates a rule, or the next version of an existing code. Never mutates a prior version.
     *
     * <p>Parameters are parsed before the insert. The same parse happens at decision time, so
     * skipping it here only defers the failure to the next transaction, where it is a 500 for a
     * caller who did nothing wrong.
     */
    @PostMapping
    RuleResponse create(@Valid @RequestBody CreateRule request) {
        Rule definition = Rule.definition(request.code(), request.type(), request.mode(),
                request.nature(), request.verdict(), request.weight(), request.parameters(),
                request.description(), request.typology());

        validator.validate(definition);
        return RuleResponse.from(rules.insertNextVersion(definition));
    }

    /**
     * Moves a rule between ACTIVE, SHADOW and DISABLED. Mode is operational state rather than rule
     * definition, so it mutates in place — and the transition is recorded, because "when did this
     * rule leave shadow?" has to be answerable.
     */
    @PatchMapping("/{id}/mode")
    RuleResponse changeMode(@PathVariable UUID id, @Valid @RequestBody ChangeMode request) {
        return RuleResponse.from(rules.changeMode(id, request.mode()));
    }

    record CreateRule(
            @NotBlank String code,
            @NotNull RuleType type,
            @NotNull RuleMode mode,
            @NotNull RuleNature nature,
            Verdict verdict,
            Integer weight,
            @NotBlank String parameters,
            @NotBlank String description,
            @NotBlank String typology) {}

    record ChangeMode(@NotNull RuleMode mode) {}

    record RuleResponse(UUID id, String code, int version, RuleType type, RuleMode mode,
            RuleNature nature, Verdict verdict, Integer weight, String parameters,
            String description, String typology, Instant createdAt, Instant supersededAt) {

        static RuleResponse from(Rule rule) {
            return new RuleResponse(rule.id(), rule.code(), rule.version(), rule.type(), rule.mode(),
                    rule.nature(), rule.verdict(), rule.weight(), rule.parameters(),
                    rule.description(), rule.typology(), rule.createdAt(), rule.supersededAt());
        }
    }
}
