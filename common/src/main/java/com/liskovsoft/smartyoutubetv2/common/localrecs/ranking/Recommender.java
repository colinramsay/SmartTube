package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.Collection;
import java.util.List;

/**
 * The pure part of the pipeline: filter, score, assemble. No Android or network code in here.
 */
public class Recommender {
    private final Ranker mRanker;
    private final RecsConfig mConfig;

    public Recommender(Ranker ranker, RecsConfig config) {
        mRanker = ranker;
        mConfig = config;
    }

    public List<Candidate> recommend(List<Seed> seeds, ChannelAffinity affinity, Collection<Candidate> candidates,
                                     CandidateFilter filter, long nowMs) {
        RankingContext context = new RankingContext(seeds, affinity, mConfig, nowMs);
        List<Candidate> allowed = filter.apply(candidates);
        return RowAssembler.assemble(mRanker.score(context, allowed), context);
    }
}
