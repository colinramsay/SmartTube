package com.liskovsoft.smartyoutubetv2.common.localrecs.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The last computed row as stored on disk, plus the plain-text format used to store it.
 * Pure Java so it can be unit tested.
 */
public class CachedRow {
    // Bump when an old cached row should be thrown away (v4: rows built before the view count and movie filters)
    private static final String VERSION = "localrecs-v4";
    // ASCII unit and record separators never show up in titles, so no escaping is needed once stripped
    private static final char FIELD_SEP = '\u001F';
    private static final char RECORD_SEP = '\u001E';
    private static final int FIELD_COUNT = 9;

    public final long savedAtMs;
    /** Who the row was computed for, so a profile switch doesn't show someone else's row. */
    public final String owner;
    public final List<Item> items;

    public static class Item {
        public String videoId;
        public String channelId;
        public String title;
        public String author;
        public String cardImageUrl;
        public String secondTitle;
        public String badgeText;
        public long durationMs;
        public long publishedMs;
    }

    public CachedRow(long savedAtMs, String owner, List<Item> items) {
        this.savedAtMs = savedAtMs;
        this.owner = owner != null ? owner : "";
        this.items = items != null ? items : Collections.emptyList();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(VERSION).append(FIELD_SEP).append(savedAtMs).append(FIELD_SEP).append(clean(owner));

        for (Item item : items) {
            sb.append(RECORD_SEP)
                    .append(clean(item.videoId)).append(FIELD_SEP)
                    .append(clean(item.channelId)).append(FIELD_SEP)
                    .append(clean(item.title)).append(FIELD_SEP)
                    .append(clean(item.author)).append(FIELD_SEP)
                    .append(clean(item.cardImageUrl)).append(FIELD_SEP)
                    .append(clean(item.secondTitle)).append(FIELD_SEP)
                    .append(clean(item.badgeText)).append(FIELD_SEP)
                    .append(item.durationMs).append(FIELD_SEP)
                    .append(item.publishedMs);
        }

        return sb.toString();
    }

    /**
     * @return null for missing, corrupt or old-format data
     */
    public static CachedRow decode(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }

        try {
            String[] records = data.split(String.valueOf(RECORD_SEP), -1);
            String[] header = records[0].split(String.valueOf(FIELD_SEP), -1);

            if (header.length != 3 || !VERSION.equals(header[0])) {
                return null;
            }

            List<Item> items = new ArrayList<>();

            for (int i = 1; i < records.length; i++) {
                String[] fields = records[i].split(String.valueOf(FIELD_SEP), -1);

                if (fields.length != FIELD_COUNT || fields[0].isEmpty()) {
                    continue;
                }

                Item item = new Item();
                item.videoId = fields[0];
                item.channelId = nullIfEmpty(fields[1]);
                item.title = nullIfEmpty(fields[2]);
                item.author = nullIfEmpty(fields[3]);
                item.cardImageUrl = nullIfEmpty(fields[4]);
                item.secondTitle = nullIfEmpty(fields[5]);
                item.badgeText = nullIfEmpty(fields[6]);
                item.durationMs = Long.parseLong(fields[7]);
                item.publishedMs = Long.parseLong(fields[8]);
                items.add(item);
            }

            return new CachedRow(Long.parseLong(header[1]), header[2], items);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }

        return value.replace(FIELD_SEP, ' ').replace(RECORD_SEP, ' ');
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }
}
