package com.fraudengine.rules;

import java.util.UUID;

public class RuleNotFoundException extends RuntimeException {

    public RuleNotFoundException(UUID id) {
        super("No rule with id " + id);
    }

    /** Rules are addressed by id when changing one and by code when reading its history. */
    public RuleNotFoundException(String code) {
        super("No rule with code " + code);
    }
}
