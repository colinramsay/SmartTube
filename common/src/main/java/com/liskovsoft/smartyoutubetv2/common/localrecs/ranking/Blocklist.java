package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Channels and title keywords the user never wants to see in the row.
 */
public class Blocklist {
    private final Set<String> mChannelIds = new HashSet<>();
    private final Set<String> mChannelNames = new HashSet<>();
    private final List<String> mKeywords = new ArrayList<>();

    public Blocklist addChannelIds(Collection<String> channelIds) {
        for (String id : channelIds) {
            if (id != null && !id.isEmpty()) {
                mChannelIds.add(id);
            }
        }
        return this;
    }

    public Blocklist addChannelNames(Collection<String> names) {
        for (String name : names) {
            if (name != null && !name.trim().isEmpty()) {
                mChannelNames.add(normalize(name));
            }
        }
        return this;
    }

    public Blocklist addKeywords(Collection<String> keywords) {
        for (String keyword : keywords) {
            if (keyword != null && !keyword.trim().isEmpty()) {
                mKeywords.add(normalize(keyword));
            }
        }
        return this;
    }

    /**
     * Comma or newline separated keywords, as typed in settings.
     */
    public static List<String> parseKeywords(String text) {
        List<String> result = new ArrayList<>();

        if (text == null) {
            return result;
        }

        for (String part : text.split("[,\\n]")) {
            String keyword = part.trim();
            if (!keyword.isEmpty()) {
                result.add(keyword);
            }
        }

        return result;
    }

    public boolean isBlocked(Candidate candidate) {
        if (candidate.channelId != null && mChannelIds.contains(candidate.channelId)) {
            return true;
        }

        if (candidate.channelName != null && mChannelNames.contains(normalize(candidate.channelName))) {
            return true;
        }

        if (candidate.title != null && !mKeywords.isEmpty()) {
            String title = normalize(candidate.title);
            for (String keyword : mKeywords) {
                if (title.contains(keyword)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT);
    }
}
