package com.fraudengine.decision;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String cursor, Throwable cause) {
        super("Cursor '%s' is not a valid page cursor".formatted(cursor), cause);
    }
}
