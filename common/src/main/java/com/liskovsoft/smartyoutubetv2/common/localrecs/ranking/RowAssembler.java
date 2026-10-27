package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns scored candidates into the final row: caps items per channel, penalises channels that
 * already appear, and reserves a few slots for channels the user hasn't watched before.
 */
public class RowAssembler {
    /**
     * @return best first, or an empty list when there aren't enough items to fill a row
     */
    public static List<Candidate> assemble(List<ScoredCandidate> scored, RankingContext context) {
        RecsConfig config = context.config;
        List<ScoredCandidate> sorted = new ArrayList<>(scored);
        Collections.sort(sorted, (a, b) -> {
            int result = Double.compare(b.score, a.score);
            return result != 0 ? result : a.candidate.videoId.compareTo(b.candidate.videoId);
        });

        Map<String, Integer> perChannel = new HashMap<>();
        Set<String> picked = new HashSet<>();

        List<Candidate> exploration = pickExploration(sorted, context, perChannel, picked);
        List<Candidate> main = pickMain(sorted, config, config.maxItems - exploration.size(), perChannel, picked);

        List<Candidate> row = new ArrayList<>(main);

        for (int i = 0; i < exploration.size(); i++) {
            int position = i < config.explorationPositions.length ? config.explorationPositions[i] : row.size();
            row.add(Math.min(position, row.size()), exploration.get(i));
        }

        if (row.size() < config.minItems) {
            return Collections.emptyList();
        }

        return row;
    }

    private static List<Candidate> pickExploration(List<ScoredCandidate> sorted, RankingContext context,
                                                   Map<String, Integer> perChannel, Set<String> picked) {
        RecsConfig config = context.config;
        List<Candidate> result = new ArrayList<>();

        // Every channel is new when known ones are filtered out, so there is nothing to reserve slots for
        if (sorted.isEmpty() || config.explorationSlots <= 0 || config.newChannelsOnly) {
            return result;
        }

        double minScore = sorted.get(0).score * config.explorationMinRelativeScore;

        for (ScoredCandidate item : sorted) {
            if (result.size() >= config.explorationSlots || item.score < minScore) {
                break;
            }

            String channel = item.candidate.channelKey();

            if (context.isKnownChannel(item.candidate) || perChannel.containsKey(channel)) {
                continue;
            }

            result.add(item.candidate);
            picked.add(item.candidate.videoId);
            perChannel.put(channel, 1);
        }

        return result;
    }

    /**
     * Greedy pick: each round takes the best score after the repeat penalty for its channel.
     */
    private static List<Candidate> pickMain(List<ScoredCandidate> sorted, RecsConfig config, int limit,
                                            Map<String, Integer> perChannel, Set<String> picked) {
        List<Candidate> result = new ArrayList<>();

        while (result.size() < limit) {
            ScoredCandidate best = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (ScoredCandidate item : sorted) {
                if (picked.contains(item.candidate.videoId)) {
                    continue;
                }

                int count = count(perChannel, item.candidate.channelKey());

                if (count >= config.maxPerChannel) {
                    continue;
                }

                double effective = item.score * Math.pow(config.channelRepeatPenalty, count);

                if (effective > bestScore) {
                    best = item;
                    bestScore = effective;
                }
            }

            if (best == null) {
                break;
            }

            result.add(best.candidate);
            picked.add(best.candidate.videoId);
            String channel = best.candidate.channelKey();
            perChannel.put(channel, count(perChannel, channel) + 1);
        }

        return result;
    }

    private static int count(Map<String, Integer> perChannel, String channel) {
        Integer count = perChannel.get(channel);
        return count == null ? 0 : count;
    }
}
