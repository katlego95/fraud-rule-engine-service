package com.fraudengine.web;

import com.fraudengine.decision.IdempotencyConflictException;
import com.fraudengine.decision.InvalidCursorException;
import com.fraudengine.rules.InvalidRuleDefinitionException;
import com.fraudengine.rules.InvalidRuleParametersException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors as RFC 9457 problem details. No stack traces and no raw exception messages reach the
 * caller; the correlation identifier is how a reported problem is traced back to the logs.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The transaction failed validation");
        problem.setTitle("Invalid transaction");
        problem.setProperty("errors", fields);
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail onIdempotencyConflict(IdempotencyConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Event identifier reused with a different payload");
        return problem;
    }

    @ExceptionHandler(InvalidCursorException.class)
    ProblemDetail onInvalidCursor(InvalidCursorException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The supplied page cursor could not be read");
        problem.setTitle("Invalid cursor");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request parameter");
        return problem;
    }

    @ExceptionHandler(DecisionNotFoundException.class)
    ProblemDetail onDecisionNotFound(DecisionNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Decision not found");
        return problem;
    }

    /** Rejected on the write, so the caller who made the mistake is the one who hears about it. */
    @ExceptionHandler(InvalidRuleDefinitionException.class)
    ProblemDetail onInvalidRuleDefinition(InvalidRuleDefinitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid rule definition");
        return problem;
    }

    /**
     * A rule already in the database could not be read while deciding. By the time this is seen
     * the bad rule is ours, not the caller's, so it is a 500 — and validating on write is what
     * keeps it unreachable.
     */
    @ExceptionHandler(InvalidRuleParametersException.class)
    ProblemDetail onInvalidRuleParameters(InvalidRuleParametersException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "A configured rule could not be evaluated. The decision was not made.");
        problem.setTitle("Rule configuration error");
        return problem;
    }
}
