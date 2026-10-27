package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

/**
 * A recently watched video used as a starting point for recommendations.
 */
public class Seed {
    public final String videoId;
    public final String channelId;
    public final String channelName;
    /** 0..1, or a negative value when unknown. */
    public final double completion;

    public Seed(String videoId, String channelId, String channelName, double completion) {
        this.videoId = videoId;
        this.channelId = channelId;
        this.channelName = channelName;
        this.completion = completion;
    }

    /**
     * Weight of a seed given its position in history (0 = most recent).
     */
    public static double weight(int rank, double completion, RecsConfig config) {
        double recency = Math.pow(config.recencyDecay, Math.max(rank, 0));
        double known = completion < 0 ? config.unknownCompletion : Math.min(completion, 1);
        double completionFactor = config.minCompletionFactor + (1 - config.minCompletionFactor) * known;
        return recency * completionFactor;
    }
}
