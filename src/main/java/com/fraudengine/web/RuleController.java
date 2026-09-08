package com.fraudengine.web;

import com.fraudengine.decision.ShadowReport;
import com.fraudengine.decision.ShadowReportRepository;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.Verdict;
import com.fraudengine.rules.RuleNotFoundException;
import com.fraudengine.rules.RuleRepository;
import com.fraudengine.rules.RuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rules")
class RuleController {

    private final RuleRepository rules;
    private final RuleService ruleService;
    private final ShadowReportRepository shadowReports;
    private final Clock clock;

    RuleController(RuleRepository rules, RuleService ruleService,
            ShadowReportRepository shadowReports, Clock clock) {
        this.rules = rules;
        this.ruleService = ruleService;
        this.shadowReports = shadowReports;
        this.clock = clock;
    }

    @GetMapping
    List<RuleResponse> current() {
        return rules.findCurrent().stream().map(RuleResponse::from).toList();
    }

    @GetMapping("/{code}/history")
    List<RuleResponse> history(@PathVariable String code) {
        List<Rule> versions = rules.findHistory(code);
        if (versions.isEmpty()) {
            throw new RuleNotFoundException(code);
        }
        return versions.stream().map(RuleResponse::from).toList();
    }

    /**
     * What a rule running in shadow would have done over a window of decisions already made.
     *
     * <p>ADR 0008 ships new rules in shadow so a threshold can be tuned against observed behaviour
     * rather than against customers. That argument is only worth anything if the observation can
     * be read back, which until now it could not: every read of an outcome was scoped to a single
     * decision.
     */
    @GetMapping("/{code}/shadow-report")
    ShadowReport shadowReport(@PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (rules.findHistory(code).isEmpty()) {
            throw new RuleNotFoundException(code);
        }
        return shadowReports.reportFor(code,
                from == null ? Instant.EPOCH : from,
                to == null ? clock.instant() : to);
    }

    /**
     * Creates a rule, or the next version of an existing code. Never mutates a prior version.
     *
     * <p>Parameters are parsed before the insert. The same parse happens at decision time, so
     * skipping it here only defers the failure to the next transaction, where it is a 500 for a
     * caller who did nothing wrong.
     *
     * <p>Mode is constrained too — a new rule must start in SHADOW, and a new version must keep
     * the mode in force. See {@link RuleService}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RuleResponse create(@Valid @RequestBody CreateRule request) {
        Rule definition = Rule.definition(request.code(), request.type(), request.mode(),
                request.nature(), request.verdict(), request.weight(), request.parameters(),
                request.description(), request.typology());

        return RuleResponse.from(ruleService.create(definition));
    }

    /**
     * Moves a rule between ACTIVE, SHADOW and DISABLED. Mode is operational state rather than rule
     * definition, so it mutates in place — and the transition is recorded, because "when did this
     * rule leave shadow?" has to be answerable.
     */
    @PatchMapping("/{id}/mode")
    RuleResponse changeMode(@PathVariable UUID id, @Valid @RequestBody ChangeMode request) {
        return RuleResponse.from(ruleService.changeMode(id, request.mode()));
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
