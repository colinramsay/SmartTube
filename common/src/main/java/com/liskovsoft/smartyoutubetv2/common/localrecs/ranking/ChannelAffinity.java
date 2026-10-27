package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much the user seems to like each channel, keyed by {@link Candidate#channelKey()} style keys.
 */
public class ChannelAffinity {
    private final Map<String, Double> mScores = new HashMap<>();

    /**
     * @param seeds most recent first
     * @param subscribedChannelIds subscriptions, most recently viewed first
     */
    public static ChannelAffinity compute(List<Seed> seeds, List<String> subscribedChannelIds, RecsConfig config) {
        ChannelAffinity affinity = new ChannelAffinity();

        if (seeds != null) {
            for (int i = 0; i < seeds.size(); i++) {
                Seed seed = seeds.get(i);
                String key = keyOf(seed.channelId, seed.channelName);

                if (key != null) {
                    affinity.add(key, Seed.weight(i, seed.completion, config));
                }
            }
        }

        if (subscribedChannelIds != null) {
            int size = subscribedChannelIds.size();

            for (int i = 0; i < size; i++) {
                String key = keyOf(subscribedChannelIds.get(i), null);

                if (key != null) {
                    // Recently viewed subscriptions get the full base, the rest taper to half of it
                    double taper = size > 1 ? 1 - 0.5 * i / (size - 1) : 1;
                    affinity.add(key, config.subscriptionBaseScore * taper);
                }
            }
        }

        return affinity;
    }

    static String keyOf(String channelId, String channelName) {
        if (channelId != null && !channelId.isEmpty()) {
            return channelId;
        }

        if (channelName != null && !channelName.isEmpty()) {
            return "name:" + channelName.toLowerCase();
        }

        return null;
    }

    public void add(String channelKey, double score) {
        Double current = mScores.get(channelKey);
        mScores.put(channelKey, current == null ? score : current + score);
    }

    public double get(String channelKey) {
        Double score = mScores.get(channelKey);
        return score == null ? 0 : score;
    }

    public boolean contains(String channelKey) {
        return mScores.containsKey(channelKey);
    }

    public double max() {
        return mScores.isEmpty() ? 0 : Collections.max(mScores.values());
    }

    /**
     * Channel ids (not name keys) by descending affinity.
     */
    public List<String> topChannelIds(int limit) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(mScores.entrySet());
        Collections.sort(entries, (a, b) -> {
            int result = Double.compare(b.getValue(), a.getValue());
            return result != 0 ? result : a.getKey().compareTo(b.getKey());
        });

        List<String> result = new ArrayList<>();

        for (Map.Entry<String, Double> entry : entries) {
            if (result.size() >= limit) {
                break;
            }

            if (!entry.getKey().startsWith("name:")) {
                result.add(entry.getKey());
            }
        }

        return result;
    }
}
