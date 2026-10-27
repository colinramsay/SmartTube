package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Videos the user removed from the row, and what the ranking learns from them:
 *
 * - the video itself never comes back
 * - its channel scores lower for each removal, and is dropped after a few
 * - the watched videos that led to it count for less, so fewer videos like it turn up
 *
 * Only the most recent removals are kept, so old dislikes fade out over time.
 */
public class Feedback {
    private static final String VERSION = "feedback-v1";
    private static final char FIELD_SEP = '\u001F';
    private static final char RECORD_SEP = '\u001E';
    private static final String LIST_SEP = ",";
    private final List<Removal> mRemovals = new ArrayList<>();
    private final RecsConfig mConfig;

    public static class Removal {
        public final String videoId;
        public final String channelId;
        public final String channelName;
        public final Set<String> seedIds;

        public Removal(String videoId, String channelId, String channelName, Collection<String> seedIds) {
            this.videoId = videoId;
            this.channelId = channelId;
            this.channelName = channelName;
            this.seedIds = seedIds != null ? new LinkedHashSet<>(seedIds) : Collections.emptySet();
        }
    }

    public Feedback(RecsConfig config) {
        mConfig = config;
    }

    public void addRemoval(Removal removal) {
        if (removal == null || removal.videoId == null || removal.videoId.isEmpty()) {
            return;
        }

        mRemovals.add(removal);

        while (mRemovals.size() > mConfig.maxRemovals) {
            mRemovals.remove(0);
        }
    }

    public boolean isEmpty() {
        return mRemovals.isEmpty();
    }

    public int size() {
        return mRemovals.size();
    }

    public Set<String> removedVideoIds() {
        Set<String> result = new HashSet<>();
        for (Removal removal : mRemovals) {
            result.add(removal.videoId);
        }
        return result;
    }

    /**
     * How many removed videos came from this channel. Matches by id or name, like known channels.
     */
    public int channelRemovals(String channelId, String channelName) {
        Set<String> keys = new HashSet<>();
        ChannelAffinity.addKeys(keys, channelId, channelName);
        int count = 0;

        for (Removal removal : mRemovals) {
            if (ChannelAffinity.containsChannel(keys, removal.channelId, removal.channelName)) {
                count++;
            }
        }

        return count;
    }

    /**
     * Score multiplier for a channel: halves (by default) with each removal.
     */
    public double channelMultiplier(String channelId, String channelName) {
        return Math.pow(mConfig.channelRemovalPenalty, channelRemovals(channelId, channelName));
    }

    /**
     * Channels removed often enough to drop entirely, as id and name keys.
     */
    public Set<String> blockedChannelKeys() {
        Set<String> result = new HashSet<>();

        for (Removal removal : mRemovals) {
            if (channelRemovals(removal.channelId, removal.channelName) >= mConfig.channelRemovalsToBlock) {
                ChannelAffinity.addKeys(result, removal.channelId, removal.channelName);
            }
        }

        return result;
    }

    /**
     * Weight multiplier for a seed whose related videos the user keeps removing.
     */
    public double seedMultiplier(String seedVideoId) {
        int count = 0;

        for (Removal removal : mRemovals) {
            if (removal.seedIds.contains(seedVideoId)) {
                count++;
            }
        }

        return Math.pow(mConfig.seedRemovalPenalty, count);
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(VERSION);

        for (Removal removal : mRemovals) {
            sb.append(RECORD_SEP)
                    .append(clean(removal.videoId)).append(FIELD_SEP)
                    .append(clean(removal.channelId)).append(FIELD_SEP)
                    .append(clean(removal.channelName)).append(FIELD_SEP)
                    .append(clean(join(removal.seedIds)));
        }

        return sb.toString();
    }

    /**
     * @return empty feedback for missing or unreadable data
     */
    public static Feedback decode(String data, RecsConfig config) {
        Feedback feedback = new Feedback(config);

        if (data == null) {
            return feedback;
        }

        String[] records = data.split(String.valueOf(RECORD_SEP), -1);

        if (!VERSION.equals(records[0])) {
            return feedback;
        }

        for (int i = 1; i < records.length; i++) {
            String[] fields = records[i].split(String.valueOf(FIELD_SEP), -1);

            if (fields.length != 4) {
                continue;
            }

            List<String> seedIds = fields[3].isEmpty() ? Collections.emptyList() : Arrays.asList(fields[3].split(LIST_SEP));
            feedback.addRemoval(new Removal(fields[0], emptyToNull(fields[1]), emptyToNull(fields[2]), seedIds));
        }

        return feedback;
    }

    // String.join needs API 26, older Fire TVs are on 25
    private static String join(Collection<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(LIST_SEP);
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(FIELD_SEP, ' ').replace(RECORD_SEP, ' ');
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
