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
     * Fold another sighting of the same video into this one.
     */
    public void mergeFrom(Candidate other) {
        relatedSeedIds.addAll(other.relatedSeedIds);
        fromRss |= other.fromRss;
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
