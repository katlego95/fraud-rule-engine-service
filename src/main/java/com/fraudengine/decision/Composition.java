package com.fraudengine.decision;

import com.fraudengine.domain.Verdict;

/**
 * The composed verdict and the working that produced it.
 *
 * <p>Both halves are kept, not just the answer. A final verdict of REVIEW is a different thing
 * when a decisive rule demanded it than when three weak signals accumulated to it, and the audit
 * view has to be able to tell them apart. The bands are carried for the same reason: they are
 * configuration, so a decision made under one set of bands must still be reconstructable after
 * they change.
 */
public record Composition(
        Verdict finalVerdict,
        Verdict decisiveVerdict,
        Verdict scoreVerdict,
        int totalScore,
        ScoreBands bands) {}
