package com.fraudengine.rules;

import java.util.UUID;

public class RuleNotFoundException extends RuntimeException {

    public RuleNotFoundException(UUID id) {
        super("No rule with id " + id);
    }
}
