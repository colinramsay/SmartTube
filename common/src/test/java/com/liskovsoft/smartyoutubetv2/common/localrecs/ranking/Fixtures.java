package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.List;

/**
 * Small builders for test data.
 */
class Fixtures {
    static final long NOW = 1_800_000_000_000L;
    static final long DAY = 24 * 60 * 60 * 1000L;

    static Candidate video(String videoId, String channelId) {
        Candidate candidate = new Candidate(videoId, channelId, "Channel " + channelId, "Title " + videoId);
        candidate.durationMs = 10 * 60 * 1000;
        return candidate;
    }

    static Candidate rss(String videoId, String channelId, long ageDays) {
        Candidate candidate = video(videoId, channelId);
        candidate.fromRss = true;
        candidate.publishedMs = NOW - ageDays * DAY;
        return candidate;
    }

    static Candidate related(String videoId, String channelId, String... seedIds) {
        Candidate candidate = video(videoId, channelId);
        for (String seedId : seedIds) {
            candidate.relatedSeedIds.add(seedId);
        }
        return candidate;
    }

    static Seed seed(String videoId, String channelId, double completion) {
        return new Seed(videoId, channelId, "Channel " + channelId, completion);
    }

    /**
     * Pairs each candidate with the score at the same index.
     */
    static List<ScoredCandidate> scored(List<Candidate> candidates, double... scores) {
        if (scores.length != candidates.size()) {
            throw new IllegalArgumentException("Need one score per candidate");
        }

        List<ScoredCandidate> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            result.add(new ScoredCandidate(candidates.get(i), scores[i]));
        }
        return result;
    }
}
