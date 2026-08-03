package com.fraudengine.web;

public class DecisionNotFoundException extends RuntimeException {

    public DecisionNotFoundException(String message) {
        super(message);
    }
}
