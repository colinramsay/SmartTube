package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

/**
 * Tuning knobs for the local recommendation row. Plain fields so tests can tweak them.
 */
public class RecsConfig {
    // Seeds
    public int maxSeeds = 20;
    /** Weight multiplier per step back in history (0 = most recent). */
    public double recencyDecay = 0.9;
    /** Completion used when we don't know how much of a video was watched. */
    public double unknownCompletion = 0.5;
    /** An abandoned video still counts this much compared to a finished one. */
    public double minCompletionFactor = 0.3;

    // Channel affinity
    /** Base affinity for subscribed channels, smaller than a single fully watched recent seed. */
    public double subscriptionBaseScore = 0.25;

    // Candidates
    public int rssChannels = 8;
    public int relatedSeeds = 4;

    // Scoring
    public double affinityWeight = 0.45;
    public double coOccurrenceWeight = 0.35;
    public double freshnessWeight = 0.20;
    public double freshnessHalfLifeDays = 7;
    public double unknownFreshness = 0.4;

    // Filtering
    /** Videos YouTube reports as at least this % watched are treated as watched. */
    public int watchedPercentThreshold = 20;
    /** Fallback Shorts detection when the item has no Shorts flag. */
    public long shortsMaxDurationMs = 60_000;

    // Diversity
    public int maxPerChannel = 2;
    /** Score multiplier applied once per item already picked from the same channel. */
    public double channelRepeatPenalty = 0.6;
    public int explorationSlots = 2;
    /** Exploration picks must score at least this fraction of the best candidate. */
    public double explorationMinRelativeScore = 0.3;
    /** Where exploration picks are placed in the row (0-based). */
    public int[] explorationPositions = {4, 9};

    // Output
    public int maxItems = 30;
    /** Fewer items than this and the row is hidden. Keeps the browse screen from trying to "continue" it. */
    public int minItems = 10;
}
