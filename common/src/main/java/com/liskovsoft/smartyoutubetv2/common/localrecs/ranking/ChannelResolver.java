package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fills in missing channel ids from the channel name.
 *
 * YouTube's TV responses often leave the channel id off a video tile (history is the usual culprit)
 * while other tiles for the same channel carry it. Without this, one channel can look like two:
 * once by id and once by name, which breaks "have I watched this channel" checks and per-channel caps.
 */
public class ChannelResolver {
    private final Map<String, String> mIdsByName = new HashMap<>();

    /**
     * Remember an id/name pair seen together on any item.
     */
    public void learn(String channelId, String channelName) {
        if (isEmpty(channelId) || isEmpty(channelName)) {
            return;
        }

        mIdsByName.put(normalize(channelName), channelId);
    }

    /**
     * The given id if there is one, otherwise the id learned for this name, otherwise null.
     */
    public String resolveId(String channelId, String channelName) {
        if (!isEmpty(channelId)) {
            return channelId;
        }

        return isEmpty(channelName) ? null : mIdsByName.get(normalize(channelName));
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
