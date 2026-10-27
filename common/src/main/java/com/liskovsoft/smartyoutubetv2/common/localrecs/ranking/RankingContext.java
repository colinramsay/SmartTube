package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a {@link Ranker} may look at besides the candidates themselves.
 */
public class RankingContext {
    public final List<Seed> seeds;
    public final ChannelAffinity affinity;
    public final RecsConfig config;
    public final long nowMs;
    private final Map<String, Double> mSeedWeights = new HashMap<>();

    public RankingContext(List<Seed> seeds, ChannelAffinity affinity, RecsConfig config, long nowMs) {
        this.seeds = seeds;
        this.affinity = affinity;
        this.config = config;
        this.nowMs = nowMs;

        for (int i = 0; i < seeds.size(); i++) {
            Seed seed = seeds.get(i);
            mSeedWeights.put(seed.videoId, Seed.weight(i, seed.completion, config));
        }
    }

    public double seedWeight(String seedVideoId) {
        Double weight = mSeedWeights.get(seedVideoId);
        return weight == null ? 0 : weight;
    }

    /**
     * True when the user has watched or subscribed to this channel.
     */
    public boolean isKnownChannel(Candidate candidate) {
        return affinity.contains(candidate.channelKey());
    }
}
