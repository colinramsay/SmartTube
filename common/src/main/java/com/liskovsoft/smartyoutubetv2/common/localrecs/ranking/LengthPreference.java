package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * How long the videos you actually finish tend to be, learned from history. Videos longer than
 * that score less, and ones far longer are dropped.
 */
public class LengthPreference {
    /** Fewer finished videos than this and we don't guess. */
    static final int MIN_SAMPLES = 5;
    /** "Usual" means this share of finished videos are this long or shorter. */
    static final double PERCENTILE = 0.9;
    /** Longer than this many times the usual length: dropped. */
    public static final double DROP_RATIO = 2.0;

    private final long mUsualMaxMs;
    private final int mSamples;

    private LengthPreference(long usualMaxMs, int samples) {
        mUsualMaxMs = usualMaxMs;
        mSamples = samples;
    }

    public static LengthPreference unknown() {
        return new LengthPreference(-1, 0);
    }

    /**
     * @param finishedDurationsMs lengths of videos the user watched to the end
     */
    public static LengthPreference fromFinished(Collection<Long> finishedDurationsMs) {
        List<Long> durations = new ArrayList<>();
        for (Long duration : finishedDurationsMs) {
            if (duration != null && duration > 0) {
                durations.add(duration);
            }
        }

        if (durations.size() < MIN_SAMPLES) {
            return new LengthPreference(-1, durations.size());
        }

        Collections.sort(durations);
        int index = (int) Math.ceil(PERCENTILE * durations.size()) - 1;
        return new LengthPreference(durations.get(Math.max(0, index)), durations.size());
    }

    public boolean isKnown() {
        return mUsualMaxMs > 0;
    }

    public long usualMaxMs() {
        return mUsualMaxMs;
    }

    public int samples() {
        return mSamples;
    }

    /**
     * Longest video worth showing at all, or 0 when we don't know.
     */
    public long dropAboveMs() {
        return isKnown() ? (long) (mUsualMaxMs * DROP_RATIO) : 0;
    }

    /**
     * Score multiplier: 1 up to the usual length, falling to 0.2 at twice it. Unknown lengths get 1.
     */
    public double factor(long durationMs) {
        if (!isKnown() || durationMs <= 0 || durationMs <= mUsualMaxMs) {
            return 1;
        }

        double over = (double) durationMs / mUsualMaxMs - 1; // 0..1 between usual and twice usual
        return Math.max(0.2, 1 - 0.8 * over);
    }
}
