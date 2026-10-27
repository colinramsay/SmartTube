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
 * Builds the Left Field row: takes turns between ideas so no single idea dominates, one video per
 * channel, best first within each idea.
 */
public class LeftFieldAssembler {
    public static List<Candidate> assemble(List<ScoredCandidate> scored, int maxPerLeap, int maxItems) {
        Map<String, List<ScoredCandidate>> byLeap = new LinkedHashMap<>();

        for (ScoredCandidate item : scored) {
            if (item.candidate.leapId == null || item.score < 0) {
                continue;
            }

            List<ScoredCandidate> list = byLeap.get(item.candidate.leapId);
            if (list == null) {
                list = new ArrayList<>();
                byLeap.put(item.candidate.leapId, list);
            }
            list.add(item);
        }

        for (List<ScoredCandidate> list : byLeap.values()) {
            Collections.sort(list, (a, b) -> Double.compare(b.score, a.score));
        }

        List<Candidate> row = new ArrayList<>();
        Set<String> channels = new HashSet<>();
        Set<String> videos = new HashSet<>();
        Map<String, Integer> taken = new HashMap<>();
        boolean added = true;

        // Round robin: each idea gets its next-best video per pass
        while (added && row.size() < maxItems) {
            added = false;

            for (Map.Entry<String, List<ScoredCandidate>> entry : byLeap.entrySet()) {
                if (row.size() >= maxItems) {
                    break;
                }

                Integer count = taken.get(entry.getKey());
                if (count != null && count >= maxPerLeap) {
                    continue;
                }

                for (ScoredCandidate item : entry.getValue()) {
                    Candidate candidate = item.candidate;
                    if (videos.contains(candidate.videoId) || channels.contains(candidate.channelKey())) {
                        continue;
                    }

                    row.add(candidate);
                    videos.add(candidate.videoId);
                    channels.add(candidate.channelKey());
                    taken.put(entry.getKey(), count == null ? 1 : count + 1);
                    added = true;
                    break;
                }
            }
        }

        return row;
    }
}
