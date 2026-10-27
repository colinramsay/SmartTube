package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds channels the user hasn't watched that keep turning up next to the videos they did watch.
 *
 * A new channel scores the sum of the weights of the distinct seeds whose related videos included it.
 * So a channel that shows up next to five different videos you liked beats one that showed up
 * five times next to the same video.
 */
public class ChannelDiscovery {
    public static ChannelAffinity compute(List<Seed> seeds, Collection<Candidate> related, Set<String> knownChannels,
                                          RecsConfig config) {
        Map<String, Double> seedWeights = new HashMap<>();
        for (int i = 0; i < seeds.size(); i++) {
            seedWeights.put(seeds.get(i).videoId, Seed.weight(i, seeds.get(i).completion, config));
        }

        ChannelAffinity result = new ChannelAffinity();
        Set<String> counted = new HashSet<>();

        for (Candidate candidate : related) {
            if (candidate.relatedSeedIds.isEmpty()
                    || ChannelAffinity.containsChannel(knownChannels, candidate.channelId, candidate.channelName)) {
                continue;
            }

            String channel = ChannelAffinity.keyOf(candidate.channelId, candidate.channelName);

            if (channel == null) {
                continue;
            }

            for (String seedId : candidate.relatedSeedIds) {
                // Each seed vouches for a channel once, however many of its videos it suggested
                Double weight = seedWeights.get(seedId);
                if (weight != null && counted.add(channel + "|" + seedId)) {
                    result.add(channel, weight);
                }
            }
        }

        return result;
    }
}
