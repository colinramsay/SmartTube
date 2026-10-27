package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How much the user seems to like each channel, keyed by {@link Candidate#channelKey()} style keys.
 */
public class ChannelAffinity {
    private final Map<String, Double> mScores = new HashMap<>();

    /**
     * @param seeds most recent first
     * @param subscribedChannels subscriptions as ids or {@link #keyOf} keys, most recently viewed first
     */
    public static ChannelAffinity compute(List<Seed> seeds, List<String> subscribedChannels, RecsConfig config) {
        ChannelAffinity affinity = new ChannelAffinity();

        if (seeds != null) {
            for (int i = 0; i < seeds.size(); i++) {
                Seed seed = seeds.get(i);
                String key = keyOf(seed.channelId, seed.channelName);

                if (key != null) {
                    affinity.add(key, seed.weightAt(i, config));
                }
            }
        }

        if (subscribedChannels != null) {
            int size = subscribedChannels.size();

            for (int i = 0; i < size; i++) {
                String key = subscribedChannels.get(i);

                if (key != null && !key.isEmpty()) {
                    // Recently viewed subscriptions get the full base, the rest taper to half of it
                    double taper = size > 1 ? 1 - 0.5 * i / (size - 1) : 1;
                    affinity.add(key, config.subscriptionBaseScore * taper);
                }
            }
        }

        return affinity;
    }

    public static String keyOf(String channelId, String channelName) {
        if (channelId != null && !channelId.trim().isEmpty()) {
            return channelId.trim();
        }

        // Trimmed because YouTube sometimes pads names, e.g. "Joshua Weissman Recipes " in subscriptions
        if (channelName != null && !channelName.trim().isEmpty()) {
            return "name:" + channelName.trim().toLowerCase(Locale.ROOT);
        }

        return null;
    }

    /**
     * Adds both the id key and the name key, so a later lookup by either one matches.
     */
    public static void addKeys(Set<String> keys, String channelId, String channelName) {
        String idKey = keyOf(channelId, null);

        if (idKey != null) {
            keys.add(idKey);
        }

        for (String name : nameVariants(channelName)) {
            keys.add(keyOf(null, name));
        }
    }

    public static boolean containsChannel(Set<String> keys, String channelId, String channelName) {
        String idKey = keyOf(channelId, null);

        if (idKey != null && keys.contains(idKey)) {
            return true;
        }

        for (String name : nameVariants(channelName)) {
            if (keys.contains(keyOf(null, name))) {
                return true;
            }
        }

        return false;
    }

    /**
     * The name itself plus, for collaborations ("Tom Scott and Matt Gray"), each channel in it.
     * So a collab counts as known if you know any of the channels involved.
     */
    static List<String> nameVariants(String channelName) {
        List<String> result = new ArrayList<>();

        if (channelName == null || channelName.trim().isEmpty()) {
            return result;
        }

        result.add(channelName);

        for (String part : channelName.split(" and |, ")) {
            if (!part.trim().isEmpty() && !part.trim().equalsIgnoreCase(channelName.trim())) {
                result.add(part);
            }
        }

        return result;
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(mScores.keySet());
    }

    public void add(String channelKey, double score) {
        Double current = mScores.get(channelKey);
        mScores.put(channelKey, current == null ? score : current + score);
    }

    /**
     * Score for a channel stored under either its id or its name, whichever is higher.
     */
    public double getFor(String channelId, String channelName) {
        double byId = channelId != null ? get(keyOf(channelId, null)) : 0;
        double byName = channelName != null ? get(keyOf(null, channelName)) : 0;
        return Math.max(byId, byName);
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
        List<String> result = new ArrayList<>();

        for (String key : topKeys(Integer.MAX_VALUE)) {
            if (result.size() >= limit) {
                break;
            }

            if (!key.startsWith("name:")) {
                result.add(key);
            }
        }

        return result;
    }

    /**
     * All keys (ids and name keys) by descending score.
     */
    public List<String> topKeys(int limit) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(mScores.entrySet());
        Collections.sort(entries, (a, b) -> {
            int result = Double.compare(b.getValue(), a.getValue());
            return result != 0 ? result : a.getKey().compareTo(b.getKey());
        });

        List<String> result = new ArrayList<>();

        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            result.add(entries.get(i).getKey());
        }

        return result;
    }
}
