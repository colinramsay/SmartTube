package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.List;

/**
 * Default on-device ranker: a weighted mix of a channel score, co-occurrence with seeds and freshness.
 * Each part is scaled to 0..1 before mixing. The channel score is affinity for channels the user
 * knows and the discovery score for new ones.
 */
public class HeuristicRanker implements Ranker {
    private static final double DAY_MS = 24 * 60 * 60 * 1000.0;

    @Override
    public List<ScoredCandidate> score(RankingContext context, List<Candidate> candidates) {
        RecsConfig config = context.config;
        double maxAffinity = context.affinity.max();
        double maxDiscovery = context.discovery.max();
        double maxCoOccurrence = 0;

        for (Candidate candidate : candidates) {
            maxCoOccurrence = Math.max(maxCoOccurrence, coOccurrence(context, candidate));
        }

        List<ScoredCandidate> result = new ArrayList<>(candidates.size());

        for (Candidate candidate : candidates) {
            double channel = context.isKnownChannel(candidate) ?
                    scaled(context.affinity.getFor(candidate.channelId, candidate.channelName), maxAffinity) :
                    scaled(context.discovery.getFor(candidate.channelId, candidate.channelName), maxDiscovery) * config.newChannelScoreFactor;
            double coOccurrence = maxCoOccurrence > 0 ? coOccurrence(context, candidate) / maxCoOccurrence : 0;
            double freshness = freshness(candidate.publishedMs, context.nowMs, config);

            double score = (config.affinityWeight * channel
                    + config.coOccurrenceWeight * coOccurrence
                    + config.freshnessWeight * freshness)
                    * context.feedback.channelMultiplier(candidate.channelId, candidate.channelName)
                    * context.length.factor(candidate.durationMs);

            result.add(new ScoredCandidate(candidate, score));
        }

        return result;
    }

    private static double scaled(double value, double max) {
        return max > 0 ? value / max : 0;
    }

    /**
     * Sum of the weights of the seeds this video was related to.
     */
    static double coOccurrence(RankingContext context, Candidate candidate) {
        double sum = 0;

        for (String seedId : candidate.relatedSeedIds) {
            sum += context.seedWeight(seedId);
        }

        return sum * candidate.hopFactor(context.config);
    }

    /**
     * 1 for a brand new upload, halving every {@link RecsConfig#freshnessHalfLifeDays}.
     */
    static double freshness(long publishedMs, long nowMs, RecsConfig config) {
        if (publishedMs <= 0) {
            return config.unknownFreshness;
        }

        double ageDays = Math.max(0, nowMs - publishedMs) / DAY_MS;
        return Math.pow(0.5, ageDays / config.freshnessHalfLifeDays);
    }
}
