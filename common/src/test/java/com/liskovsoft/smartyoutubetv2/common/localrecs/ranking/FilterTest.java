package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.related;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.video;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FilterTest {
    private final RecsConfig mConfig = new RecsConfig();

    @Test
    public void removesWatchedShortsLiveAndUpcoming() {
        Candidate watched = video("watched", "A");
        Candidate partlyWatched = video("partly", "A");
        partlyWatched.percentWatched = 50;
        Candidate shorts = video("shorts", "A");
        shorts.isShorts = true;
        Candidate tiny = video("tiny", "A");
        tiny.durationMs = 30_000;
        Candidate live = video("live", "A");
        live.isLive = true;
        Candidate upcoming = video("upcoming", "A");
        upcoming.isUpcoming = true;
        Candidate ok = video("ok", "A");

        CandidateFilter filter = new CandidateFilter(new HashSet<>(Collections.singletonList("watched")), null, mConfig);
        List<Candidate> result = filter.apply(Arrays.asList(watched, partlyWatched, shorts, tiny, live, upcoming, ok));

        assertEquals(Collections.singletonList("ok"), ids(result));
    }

    @Test
    public void mergesDuplicatesKeepingAllSeedLinks() {
        Candidate first = related("v1", "A", "s1");
        Candidate second = related("v1", "A", "s2");
        second.publishedMs = 123;

        List<Candidate> result = new CandidateFilter(null, null, mConfig).apply(Arrays.asList(first, second));

        assertEquals(1, result.size());
        assertEquals(new HashSet<>(Arrays.asList("s1", "s2")), result.get(0).relatedSeedIds);
        assertEquals(123, result.get(0).publishedMs);
    }

    @Test
    public void duplicateSightingCanRevealLiveStatus() {
        Candidate fromSuggestions = video("v1", "A");
        Candidate fromRss = video("v1", "A");
        fromRss.isLive = true;

        List<Candidate> result = new CandidateFilter(null, null, mConfig).apply(Arrays.asList(fromSuggestions, fromRss));

        assertTrue(result.isEmpty());
    }

    @Test
    public void blocklistMatchesChannelIdNameAndTitleKeywords() {
        Blocklist blocklist = new Blocklist()
                .addChannelIds(Collections.singletonList("BLOCKED"))
                .addChannelNames(Collections.singletonList("Channel NAMED"))
                .addKeywords(Blocklist.parseKeywords(" Spoiler , reaction\n"));

        Candidate byId = video("v1", "BLOCKED");
        Candidate byName = video("v2", "NAMED");
        Candidate byKeyword = video("v3", "A");
        byKeyword.title = "Huge SPOILERS inside";
        Candidate byKeyword2 = video("v4", "A");
        byKeyword2.title = "My Reaction to it";
        Candidate ok = video("v5", "A");

        List<Candidate> result = new CandidateFilter(null, blocklist, mConfig)
                .apply(Arrays.asList(byId, byName, byKeyword, byKeyword2, ok));

        assertEquals(Collections.singletonList("v5"), ids(result));
    }

    @Test
    public void parseKeywordsIgnoresBlanks() {
        assertEquals(Arrays.asList("a", "b c"), Blocklist.parseKeywords(" a ,, b c ,\n\n"));
        assertTrue(Blocklist.parseKeywords(null).isEmpty());
    }

    static List<String> ids(List<Candidate> candidates) {
        List<String> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            result.add(candidate.videoId);
        }
        return result;
    }
}
