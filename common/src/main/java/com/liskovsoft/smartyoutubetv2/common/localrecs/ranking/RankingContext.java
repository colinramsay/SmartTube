package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a {@link Ranker} may look at besides the candidates themselves.
 */
public class RankingContext {
    public final List<Seed> seeds;
    /** How much the user likes channels they already watch. */
    public final ChannelAffinity affinity;
    /** How promising each new channel looks, see {@link ChannelDiscovery}. */
    public final ChannelAffinity discovery;
    /** Every channel the user has watched or subscribed to, as id and name keys. */
    public final Set<String> knownChannels;
    public final RecsConfig config;
    public final long nowMs;
    /** What the user removed from the row before. Empty unless set. */
    public Feedback feedback;
    /** How long the videos you finish usually are. Unknown unless set. */
    public LengthPreference length = LengthPreference.unknown();
    private final Map<String, Double> mSeedWeights = new HashMap<>();

    /**
     * Without discovery data. Known channels are the ones with affinity.
     */
    public RankingContext(List<Seed> seeds, ChannelAffinity affinity, RecsConfig config, long nowMs) {
        this(seeds, affinity, new ChannelAffinity(), affinity.keys(), config, nowMs);
    }

    public RankingContext(List<Seed> seeds, ChannelAffinity affinity, ChannelAffinity discovery, Set<String> knownChannels,
                          RecsConfig config, long nowMs) {
        this.seeds = seeds;
        this.affinity = affinity;
        this.discovery = discovery;
        this.knownChannels = knownChannels;
        this.config = config;
        this.nowMs = nowMs;
        this.feedback = new Feedback(config);

        for (int i = 0; i < seeds.size(); i++) {
            Seed seed = seeds.get(i);
            mSeedWeights.put(seed.videoId, seed.weightAt(i, config));
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
        return ChannelAffinity.containsChannel(knownChannels, candidate.channelId, candidate.channelName);
    }
}
