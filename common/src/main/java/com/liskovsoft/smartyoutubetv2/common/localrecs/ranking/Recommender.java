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
        return recommend(new RankingContext(seeds, affinity, mConfig, nowMs), candidates, filter);
    }

    public List<Candidate> recommend(RankingContext context, Collection<Candidate> candidates, CandidateFilter filter) {
        List<Candidate> allowed = filter.apply(candidates);
        return RowAssembler.assemble(mRanker.score(context, allowed), context);
    }
}
