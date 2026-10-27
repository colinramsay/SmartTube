package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What happened to the videos the rows showed: finished, abandoned, or shown again and again
 * without ever being played. These are the "good" and "bad" examples the rest of the engine
 * learns from, alongside explicit removals in {@link Feedback}.
 */
public class Outcomes {
    private static final String VERSION = "outcomes-v1";
    private static final char FIELD_SEP = '\u001F';
    private static final char RECORD_SEP = '\u001E';
    /** Played at least this far: liked it. */
    public static final double FINISHED = 0.8;
    /** Started but stopped before this: didn't like it. */
    public static final double ABANDONED = 0.3;
    private static final int MAX_ENTRIES = 400;

    public static final int STATE_SHOWN = 0;
    public static final int STATE_FINISHED = 1;
    public static final int STATE_ABANDONED = 2;
    public static final int STATE_PLAYED = 3;

    private final Map<String, Entry> mEntries = new LinkedHashMap<>();

    public static class Entry {
        public final String videoId;
        public String title;
        public String channelName;
        /** The Left Field idea that found it, or null for the main row. */
        public String leapId;
        /** Refreshes this video was in a row for. */
        public int timesShown;
        public int state = STATE_SHOWN;

        Entry(String videoId) {
            this.videoId = videoId;
        }
    }

    /**
     * Call once per refresh for every video put in a row.
     */
    public void recordShown(String videoId, String title, String channelName, String leapId) {
        if (videoId == null) {
            return;
        }

        Entry entry = mEntries.remove(videoId);
        if (entry == null) {
            entry = new Entry(videoId);
        }

        entry.title = title != null ? title : entry.title;
        entry.channelName = channelName != null ? channelName : entry.channelName;
        entry.leapId = leapId != null ? leapId : entry.leapId;
        entry.timesShown++;
        mEntries.put(videoId, entry); // most recent last

        while (mEntries.size() > MAX_ENTRIES) {
            mEntries.remove(mEntries.keySet().iterator().next());
        }
    }

    /**
     * Update with how far each shown video has been played (0..1).
     */
    public void recordPlayback(Map<String, Double> completionByVideoId) {
        for (Entry entry : mEntries.values()) {
            Double completion = completionByVideoId.get(entry.videoId);

            if (completion == null) {
                continue;
            }

            if (completion >= FINISHED) {
                entry.state = STATE_FINISHED;
            } else if (completion < ABANDONED && entry.state != STATE_FINISHED) {
                entry.state = STATE_ABANDONED;
            } else if (entry.state == STATE_SHOWN) {
                entry.state = STATE_PLAYED;
            }
        }
    }

    /**
     * Shown in at least this many refreshes and never played: you've seen it and passed.
     */
    public Set<String> ignoredVideoIds(int minTimesShown) {
        Set<String> result = new HashSet<>();
        for (Entry entry : mEntries.values()) {
            if (entry.state == STATE_SHOWN && entry.timesShown >= minTimesShown) {
                result.add(entry.videoId);
            }
        }
        return result;
    }

    /**
     * Most recent first.
     */
    public List<Entry> withState(int state, int limit) {
        List<Entry> result = new ArrayList<>();
        List<Entry> all = new ArrayList<>(mEntries.values());
        Collections.reverse(all);

        for (Entry entry : all) {
            if (result.size() >= limit) {
                break;
            }
            if (entry.state == state) {
                result.add(entry);
            }
        }

        return result;
    }

    /**
     * Finished videos per Left Field idea.
     */
    public Map<String, Integer> leapHits() {
        Map<String, Integer> result = new HashMap<>();
        for (Entry entry : mEntries.values()) {
            if (entry.leapId != null && entry.state == STATE_FINISHED) {
                Integer count = result.get(entry.leapId);
                result.put(entry.leapId, count == null ? 1 : count + 1);
            }
        }
        return result;
    }

    /**
     * Videos from each Left Field idea that were shown at all, for hit rates.
     */
    public Map<String, Integer> leapShown() {
        Map<String, Integer> result = new HashMap<>();
        for (Entry entry : mEntries.values()) {
            if (entry.leapId != null) {
                Integer count = result.get(entry.leapId);
                result.put(entry.leapId, count == null ? 1 : count + 1);
            }
        }
        return result;
    }

    public int size() {
        return mEntries.size();
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(VERSION);
        for (Entry entry : mEntries.values()) {
            sb.append(RECORD_SEP)
                    .append(clean(entry.videoId)).append(FIELD_SEP)
                    .append(clean(entry.title)).append(FIELD_SEP)
                    .append(clean(entry.channelName)).append(FIELD_SEP)
                    .append(clean(entry.leapId)).append(FIELD_SEP)
                    .append(entry.timesShown).append(FIELD_SEP)
                    .append(entry.state);
        }
        return sb.toString();
    }

    public static Outcomes decode(String data) {
        Outcomes outcomes = new Outcomes();

        if (data == null) {
            return outcomes;
        }

        String[] records = data.split(String.valueOf(RECORD_SEP), -1);
        if (!VERSION.equals(records[0])) {
            return outcomes;
        }

        for (int i = 1; i < records.length; i++) {
            String[] fields = records[i].split(String.valueOf(FIELD_SEP), -1);
            if (fields.length != 6 || fields[0].isEmpty()) {
                continue;
            }

            try {
                Entry entry = new Entry(fields[0]);
                entry.title = emptyToNull(fields[1]);
                entry.channelName = emptyToNull(fields[2]);
                entry.leapId = emptyToNull(fields[3]);
                entry.timesShown = Integer.parseInt(fields[4]);
                entry.state = Integer.parseInt(fields[5]);
                outcomes.mEntries.put(entry.videoId, entry);
            } catch (NumberFormatException e) {
                // skip the bad record
            }
        }

        return outcomes;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(FIELD_SEP, ' ').replace(RECORD_SEP, ' ');
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
