package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.FilterTest.ids;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.NOW;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.related;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.rss;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.seed;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.video;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiscoveryTest {
    private final RecsConfig mConfig = new RecsConfig();
    private final List<Seed> mSeeds = Arrays.asList(seed("s1", "A", 1), seed("s2", "B", 1), seed("s3", "C", 1));

    @Test
    public void channelSuggestedByManySeedsBeatsOneSuggestedOftenBySingleSeed() {
        List<Candidate> related = Arrays.asList(
                related("w1", "WIDE", "s1"), related("w2", "WIDE", "s2"), related("w3", "WIDE", "s3"),
                related("n1", "NARROW", "s1"), related("n2", "NARROW", "s1"), related("n3", "NARROW", "s1"));

        ChannelAffinity discovery = ChannelDiscovery.compute(mSeeds, related, known("A", "B", "C"), mConfig);

        assertTrue(discovery.get("WIDE") > discovery.get("NARROW"));
        assertEquals(Seed.weight(0, 1, mConfig), discovery.get("NARROW"), 1e-9);
    }

    @Test
    public void knownChannelsAreNotDiscovered() {
        List<Candidate> related = Arrays.asList(related("a1", "A", "s2"), related("x1", "X", "s1"));

        ChannelAffinity discovery = ChannelDiscovery.compute(mSeeds, related, known("A", "B", "C"), mConfig);

        assertFalse(discovery.contains("A"));
        assertTrue(discovery.contains("X"));
        assertEquals(Collections.singletonList("X"), discovery.topChannelIds(5));
    }

    @Test
    public void knownChannelMatchesByNameWhenIdIsMissing() {
        Set<String> known = new HashSet<>();
        ChannelAffinity.addKeys(known, "A", "Some Channel");

        assertTrue(ChannelAffinity.containsChannel(known, null, "some channel"));
        assertTrue(ChannelAffinity.containsChannel(known, "A", null));
        assertFalse(ChannelAffinity.containsChannel(known, "B", "Other"));
    }

    @Test
    public void filterDropsKnownChannels() {
        CandidateFilter filter = new CandidateFilter(null, null, mConfig).excludeChannels(known("A"));

        List<Candidate> result = filter.apply(Arrays.asList(video("a1", "A"), video("x1", "X")));

        assertEquals(Collections.singletonList("x1"), ids(result));
    }

    @Test
    public void newChannelsOnlyRowContainsNoKnownChannelsAndPrefersWellBackedOnes() {
        Set<String> known = known("A", "B", "C");
        List<Candidate> candidates = new ArrayList<>();

        // Uploads from channels the user already watches must never show up
        for (int i = 0; i < 5; i++) {
            candidates.add(rss("a" + i, "A", 0));
        }

        // WIDE turns up next to all three seeds, the others next to one each
        candidates.add(related("wide1", "WIDE", "s1", "s2", "s3"));
        candidates.add(related("wide2", "WIDE", "s2"));
        for (int i = 0; i < 12; i++) {
            candidates.add(related("one" + i, "ONE" + i, "s" + (1 + i % 3)));
        }

        ChannelAffinity affinity = ChannelAffinity.compute(mSeeds, null, mConfig);
        ChannelAffinity discovery = ChannelDiscovery.compute(mSeeds, candidates, known, mConfig);
        RankingContext context = new RankingContext(mSeeds, affinity, discovery, known, mConfig, NOW);
        CandidateFilter filter = new CandidateFilter(null, null, mConfig).excludeChannels(known);

        List<Candidate> row = new Recommender(new HeuristicRanker(), mConfig).recommend(context, candidates, filter);

        assertFalse(row.isEmpty());
        for (Candidate candidate : row) {
            assertFalse(known.contains(candidate.channelId));
        }
        assertEquals("wide1", row.get(0).videoId);
    }

    private static Set<String> known(String... channelIds) {
        Set<String> result = new HashSet<>();
        for (String id : channelIds) {
            ChannelAffinity.addKeys(result, id, "Channel " + id);
        }
        return result;
    }
}
