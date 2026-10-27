package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A Left Field idea: something the viewer hasn't watched that shares a reason they like what they do.
 * Ideas come from a model; how they're tracked and dropped is plain code.
 */
public class Leap {
    private static final String VERSION = "leaps-v1";
    private static final char FIELD_SEP = '\u001F';
    private static final char RECORD_SEP = '\u001E';

    public final String id;
    /** Short label, e.g. "canal boat restoration". */
    public final String label;
    /** Why it might appeal, in terms of what the viewer already likes. */
    public final String reason;
    /** YouTube search used to find videos for it. */
    public final String query;

    public Leap(String id, String label, String reason, String query) {
        this.id = id;
        this.label = label;
        this.reason = reason;
        this.query = query;
    }

    /**
     * A set of ideas and when they were generated.
     */
    public static class Batch {
        public final long createdAtMs;
        public final List<Leap> leaps;
        /** Labels of earlier ideas that didn't land, so the model won't suggest them again. */
        public final List<String> rejected;

        public Batch(long createdAtMs, List<Leap> leaps, List<String> rejected) {
            this.createdAtMs = createdAtMs;
            this.leaps = leaps != null ? leaps : Collections.emptyList();
            this.rejected = rejected != null ? rejected : Collections.emptyList();
        }

        public boolean isEmpty() {
            return leaps.isEmpty();
        }

        public Leap find(String id) {
            for (Leap leap : leaps) {
                if (leap.id.equals(id)) {
                    return leap;
                }
            }
            return null;
        }

        public String encode() {
            StringBuilder sb = new StringBuilder(VERSION).append(FIELD_SEP).append(createdAtMs);

            for (String label : rejected) {
                sb.append(FIELD_SEP).append(clean(label));
            }

            for (Leap leap : leaps) {
                sb.append(RECORD_SEP)
                        .append(clean(leap.id)).append(FIELD_SEP)
                        .append(clean(leap.label)).append(FIELD_SEP)
                        .append(clean(leap.reason)).append(FIELD_SEP)
                        .append(clean(leap.query));
            }

            return sb.toString();
        }

        public static Batch decode(String data) {
            if (data == null || data.isEmpty()) {
                return null;
            }

            String[] records = data.split(String.valueOf(RECORD_SEP), -1);
            String[] header = records[0].split(String.valueOf(FIELD_SEP), -1);

            if (header.length < 2 || !VERSION.equals(header[0])) {
                return null;
            }

            try {
                List<String> rejected = new ArrayList<>();
                for (int i = 2; i < header.length; i++) {
                    if (!header[i].isEmpty()) {
                        rejected.add(header[i]);
                    }
                }

                List<Leap> leaps = new ArrayList<>();
                for (int i = 1; i < records.length; i++) {
                    String[] fields = records[i].split(String.valueOf(FIELD_SEP), -1);
                    if (fields.length == 4 && !fields[0].isEmpty() && !fields[3].isEmpty()) {
                        leaps.add(new Leap(fields[0], fields[1], fields[2], fields[3]));
                    }
                }

                return new Batch(Long.parseLong(header[1]), leaps, rejected);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * An idea has failed when you removed more of its videos than you finished, after at least two
     * verdicts. Failed ideas are dropped and reported back so the model doesn't repeat them.
     */
    public static boolean hasFailed(String leapId, Map<String, Integer> hits, Map<String, Integer> misses) {
        int hit = count(hits, leapId);
        int miss = count(misses, leapId);
        return miss >= 2 && miss > hit;
    }

    /**
     * An idea has landed when you finished at least two of its videos.
     */
    public static boolean hasLanded(String leapId, Map<String, Integer> hits) {
        return count(hits, leapId) >= 2;
    }

    private static int count(Map<String, Integer> counts, String key) {
        Integer value = counts.get(key);
        return value == null ? 0 : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(FIELD_SEP, ' ').replace(RECORD_SEP, ' ');
    }
}
