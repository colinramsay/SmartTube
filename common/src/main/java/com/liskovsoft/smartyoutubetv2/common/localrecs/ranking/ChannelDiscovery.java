package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.Collection;
import java.util.HashMap;
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
            seedWeights.put(seeds.get(i).videoId, seeds.get(i).weightAt(i, config));
        }

        // Strongest link per channel and seed: a first-hop sighting beats a second-hop one
        Map<String, Double> links = new HashMap<>();
        Map<String, String> channelOfLink = new HashMap<>();

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
                if (weight == null) {
                    continue;
                }

                String link = channel + "|" + seedId;
                double value = weight * candidate.hopFactor(config);
                Double current = links.get(link);
                if (current == null || value > current) {
                    links.put(link, value);
                    channelOfLink.put(link, channel);
                }
            }
        }

        ChannelAffinity result = new ChannelAffinity();
        for (Map.Entry<String, Double> link : links.entrySet()) {
            result.add(channelOfLink.get(link.getKey()), link.getValue());
        }

        return result;
    }
}
