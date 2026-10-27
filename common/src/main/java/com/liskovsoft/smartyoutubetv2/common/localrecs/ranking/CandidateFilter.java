package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drops anything that should never be recommended and merges duplicate sightings.
 */
public class CandidateFilter {
    private final Set<String> mWatchedIds;
    private final Blocklist mBlocklist;
    private final RecsConfig mConfig;
    private Set<String> mExcludedChannels = Collections.emptySet();

    public CandidateFilter(Set<String> watchedIds, Blocklist blocklist, RecsConfig config) {
        mWatchedIds = watchedIds != null ? watchedIds : Collections.emptySet();
        mBlocklist = blocklist != null ? blocklist : new Blocklist();
        mConfig = config != null ? config : new RecsConfig();
    }

    /**
     * Also drop every video from these channels (id or name keys, see {@link ChannelAffinity#addKeys}).
     */
    public CandidateFilter excludeChannels(Set<String> channelKeys) {
        mExcludedChannels = channelKeys != null ? channelKeys : Collections.emptySet();
        return this;
    }

    /**
     * Merge sightings of the same video (keeping the first one's position), then filter.
     */
    public List<Candidate> apply(Collection<Candidate> candidates) {
        Map<String, Candidate> unique = new LinkedHashMap<>();

        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.videoId == null || candidate.videoId.isEmpty()) {
                continue;
            }

            Candidate existing = unique.get(candidate.videoId);

            if (existing != null) {
                existing.mergeFrom(candidate);
            } else {
                unique.put(candidate.videoId, candidate);
            }
        }

        List<Candidate> result = new ArrayList<>();

        for (Candidate candidate : unique.values()) {
            if (isAllowed(candidate)) {
                result.add(candidate);
            }
        }

        return result;
    }

    public boolean isAllowed(Candidate candidate) {
        return !isWatched(candidate) && !isShorts(candidate) && !candidate.isLive && !candidate.isUpcoming
                && !mBlocklist.isBlocked(candidate)
                && !ChannelAffinity.containsChannel(mExcludedChannels, candidate.channelId, candidate.channelName);
    }

    private boolean isWatched(Candidate candidate) {
        return mWatchedIds.contains(candidate.videoId) || candidate.percentWatched >= mConfig.watchedPercentThreshold;
    }

    private boolean isShorts(Candidate candidate) {
        return candidate.isShorts || (candidate.durationMs > 0 && candidate.durationMs <= mConfig.shortsMaxDurationMs);
    }
}
