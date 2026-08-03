package com.fraudengine.web;

import java.util.List;

/**
 * @param nextCursor null when the result set is exhausted
 *
 * <p>No total count. Counting a filtered set on every page request is a scan the caller did not
 * ask for, and a count that is stale by the time it is read is worse than no count at all.
 */
public record DecisionPage(List<DecisionResponse> items, String nextCursor, int pageSize) {}
