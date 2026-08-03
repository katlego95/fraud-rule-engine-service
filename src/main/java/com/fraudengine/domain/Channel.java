package com.fraudengine.domain;

public enum Channel {
    CARD_PRESENT,
    ECOMMERCE,
    ATM,
    TRANSFER;

    /**
     * Impossible-travel reasoning only holds where the coordinates describe where the cardholder
     * physically was. E-commerce coordinates are derived from an IP address, so pairing them with
     * a card-present location flags a customer buying online while travelling.
     */
    public boolean impliesPhysicalPresence() {
        return this == CARD_PRESENT || this == ATM;
    }
}
