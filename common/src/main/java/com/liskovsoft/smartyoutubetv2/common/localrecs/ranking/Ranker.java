package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.List;

/**
 * Gives every candidate a relevance score. Diversity is applied afterwards by {@link RowAssembler},
 * so a different ranker (remote, ML) only has to care about relevance.
 */
public interface Ranker {
    List<ScoredCandidate> score(RankingContext context, List<Candidate> candidates);
}
