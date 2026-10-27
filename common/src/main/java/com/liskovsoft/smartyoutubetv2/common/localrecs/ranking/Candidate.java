package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A video that might go into the row. {@link #payload} carries the original item through ranking untouched.
 */
public class Candidate {
    public String videoId;
    public String channelId;
    public String channelName;
    public String title;
    /** Upload time in ms, 0 when unknown. */
    public long publishedMs;
    public long durationMs;
    public boolean isLive;
    public boolean isUpcoming;
    public boolean isShorts;
    public int percentWatched;
    /** -1 when unknown. */
    public long viewCount = -1;
    public boolean isMovie;
    public boolean fromRss;
    /** 1 for related to something you watched, 2 for related to one of those (see {@link RecsConfig#secondHopFactor}). */
    public int hop = 1;
    /** For second-hop videos, the video whose related list they came from. */
    public String hopParentId;
    /** For Left Field videos, the idea whose search found them. */
    public String leapId;
    /** Ids of the seeds this video showed up as related to. */
    public final Set<String> relatedSeedIds = new LinkedHashSet<>();
    public Object payload;

    public Candidate(String videoId, String channelId, String channelName, String title) {
        this.videoId = videoId;
        this.channelId = channelId;
        this.channelName = channelName;
        this.title = title;
    }

    /**
     * Key used for per-channel caps. Falls back to the name, then to the video itself.
     */
    public String channelKey() {
        String key = ChannelAffinity.keyOf(channelId, channelName);
        return key != null ? key : "video:" + videoId;
    }

    /**
     * Every key this channel could go by: its id, its name, and for collabs ("A and B") each
     * channel in the name. Used so one channel can't take several slots under different keys.
     */
    public java.util.Set<String> channelKeys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        ChannelAffinity.addKeys(keys, channelId, channelName);
        if (keys.isEmpty()) {
            keys.add(channelKey());
        }
        return keys;
    }

    /**
     * Fold another sighting of the same video into this one.
     */
    /**
     * How much this video's links to seeds count.
     */
    public double hopFactor(RecsConfig config) {
        return hop > 1 ? config.secondHopFactor : 1;
    }

    public void mergeFrom(Candidate other) {
        relatedSeedIds.addAll(other.relatedSeedIds);
        fromRss |= other.fromRss;
        hop = Math.min(hop, other.hop);
        isLive |= other.isLive;
        isUpcoming |= other.isUpcoming;
        isShorts |= other.isShorts;
        isMovie |= other.isMovie;
        viewCount = Math.max(viewCount, other.viewCount);
        percentWatched = Math.max(percentWatched, other.percentWatched);

        if (publishedMs <= 0) {
            publishedMs = other.publishedMs;
        }

        if (durationMs <= 0) {
            durationMs = other.durationMs;
        }

        if (channelId == null) {
            channelId = other.channelId;
        }

        if (channelName == null) {
            channelName = other.channelName;
        }
    }
}
