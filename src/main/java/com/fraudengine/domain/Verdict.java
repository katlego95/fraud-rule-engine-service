package com.fraudengine.domain;

/**
 * Declared in ascending order of severity. Composition takes the most severe of the decisive
 * verdict and the score band, so the ordering is load-bearing rather than cosmetic.
 */
public enum Verdict {
    APPROVE,
    REVIEW,
    BLOCK;

    public static Verdict mostSevere(Verdict left, Verdict right) {
        return left.compareTo(right) >= 0 ? left : right;
    }
}
