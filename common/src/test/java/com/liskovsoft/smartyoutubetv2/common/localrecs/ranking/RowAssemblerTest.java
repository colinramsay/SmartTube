package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.FilterTest.ids;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.NOW;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.related;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.rss;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.scored;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.seed;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.video;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RowAssemblerTest {
    @Test
    public void capsItemsPerChannel() {
        RecsConfig config = smallConfig();
        config.explorationSlots = 0;
        config.maxPerChannelFill = config.maxPerChannel;

        List<Candidate> candidates = new ArrayList<>();
        double[] scores = new double[12];
        for (int i = 0; i < 6; i++) {
            candidates.add(video("a" + i, "A"));
            scores[i] = 1.0 - i * 0.01; // channel A dominates the raw scores
        }
        for (int i = 0; i < 6; i++) {
            candidates.add(video("k" + i, "K" + i));
            scores[6 + i] = 0.2;
        }

        List<Candidate> row = RowAssembler.assemble(scored(candidates, scores), contextKnowing(config, "A"));

        assertEquals(2, countChannel(row, "A"));
        assertEquals(8, row.size());
    }

    @Test
    public void fillPassAllowsAThirdItemWhenTheRowIsShort() {
        RecsConfig config = smallConfig();
        config.explorationSlots = 0;
        config.maxItems = 7;

        List<Candidate> candidates = new ArrayList<>();
        double[] scores = new double[8];
        for (int i = 0; i < 4; i++) {
            candidates.add(video("a" + i, "A"));
            candidates.add(video("b" + i, "B"));
            scores[i * 2] = 1.0 - i * 0.1;
            scores[i * 2 + 1] = 0.9 - i * 0.1;
        }

        List<Candidate> row = RowAssembler.assemble(scored(candidates, scores), contextKnowing(config, "A", "B"));

        // Two each in the main pass, then a third each to fill; never a fourth
        assertEquals(6, row.size());
        assertEquals(3, countChannel(row, "A"));
        assertEquals(Arrays.asList("a0", "b0", "a1", "b1"), ids(row).subList(0, 4));
    }

    @Test
    public void oneHopParentCannotFloodTheRow() {
        RecsConfig config = smallConfig();
        config.minItems = 1;

        // One music video's related list: lots of different artists, all scoring well
        List<Candidate> candidates = new ArrayList<>();
        double[] scores = new double[8];
        for (int i = 0; i < 6; i++) {
            Candidate artist = video("m" + i, "ARTIST" + i);
            artist.hop = 2;
            artist.hopParentId = "tinydesk";
            candidates.add(artist);
            scores[i] = 1.0;
        }
        candidates.add(video("x", "X"));
        candidates.add(video("y", "Y"));
        scores[6] = 0.5;
        scores[7] = 0.5;

        List<Candidate> row = RowAssembler.assemble(scored(candidates, scores), contextKnowing(config));

        assertEquals(config.maxPerHopParent + 2, row.size());
        assertTrue(ids(row).contains("x"));
        assertTrue(ids(row).contains("y"));
    }

    @Test
    public void repeatPenaltyInterleavesChannels() {
        RecsConfig config = smallConfig();
        config.explorationSlots = 0;
        config.minItems = 1;

        List<Candidate> candidates = Arrays.asList(video("a1", "A"), video("a2", "A"), video("b1", "B"));
        // b1 scores below a2, but a2 is penalised for being the second A video
        List<Candidate> row = RowAssembler.assemble(scored(candidates, 1.0, 0.9, 0.7), contextKnowing(config, "A", "B"));

        assertEquals(Arrays.asList("a1", "b1", "a2"), ids(row));
    }

    @Test
    public void reservesExplorationSlotsForUnknownChannels() {
        RecsConfig config = smallConfig();
        config.newChannelsOnly = false;
        config.explorationSlots = 2;
        config.explorationPositions = new int[] {1, 3};
        config.maxPerChannel = 10;

        List<Candidate> candidates = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(video("known" + i, "K" + (i % 5)));
            scores.add(1.0 - i * 0.01);
        }
        candidates.add(video("new1", "N1"));
        scores.add(0.5);
        candidates.add(video("new2", "N2"));
        scores.add(0.45);
        candidates.add(video("weak", "N3"));
        scores.add(0.01); // below the exploration threshold

        List<Candidate> row = RowAssembler.assemble(scored(candidates, toArray(scores)),
                contextKnowing(config, "K0", "K1", "K2", "K3", "K4"));

        assertEquals("new1", row.get(1).videoId);
        assertEquals("new2", row.get(3).videoId);
        assertTrue(!ids(row).contains("weak"));
    }

    @Test
    public void hidesRowWhenTooFewItems() {
        RecsConfig config = smallConfig();
        config.minItems = 5;

        List<Candidate> candidates = Arrays.asList(video("a", "A"), video("b", "B"));
        List<Candidate> row = RowAssembler.assemble(scored(candidates, 1, 1), contextKnowing(config, "A", "B"));

        assertTrue(row.isEmpty());
    }

    @Test
    public void endToEndPipelineProducesDiverseUnwatchedRow() {
        RecsConfig config = new RecsConfig();
        config.newChannelsOnly = false;
        config.minItems = 5;

        List<Seed> seeds = Arrays.asList(seed("s1", "A", 1), seed("s2", "B", 0.9), seed("s3", "A", 0.2));
        ChannelAffinity affinity = ChannelAffinity.compute(seeds, Collections.singletonList("C"), config);

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates.add(rss("a" + i, "A", i));
            candidates.add(rss("b" + i, "B", i));
            candidates.add(rss("c" + i, "C", i));
        }
        candidates.add(related("x1", "X", "s1", "s2"));
        candidates.add(related("s2", "B", "s1")); // a seed is already watched

        CandidateFilter filter = new CandidateFilter(new java.util.HashSet<>(Arrays.asList("s1", "s2", "s3")), null, config);
        List<Candidate> row = new Recommender(new HeuristicRanker(), config).recommend(seeds, affinity, candidates, filter, NOW);

        assertTrue(row.size() >= 5);
        assertTrue(!ids(row).contains("s2"));
        assertTrue("related video from new channel gets in", ids(row).contains("x1"));
        for (String channel : Arrays.asList("A", "B", "C")) {
            assertTrue(countChannel(row, channel) <= config.maxPerChannelFill);
        }
    }

    private static RecsConfig smallConfig() {
        RecsConfig config = new RecsConfig();
        config.maxItems = 10;
        config.minItems = 3;
        return config;
    }

    private static RankingContext contextKnowing(RecsConfig config, String... channels) {
        List<Seed> seeds = new ArrayList<>();
        for (String channel : channels) {
            seeds.add(seed("seed-" + channel, channel, 1));
        }
        return new RankingContext(seeds, ChannelAffinity.compute(seeds, null, config), config, NOW);
    }

    private static int countChannel(List<Candidate> row, String channelId) {
        Map<String, Integer> counts = new HashMap<>();
        for (Candidate candidate : row) {
            counts.put(candidate.channelId, counts.containsKey(candidate.channelId) ? counts.get(candidate.channelId) + 1 : 1);
        }
        return counts.containsKey(channelId) ? counts.get(channelId) : 0;
    }

    private static double[] toArray(List<Double> values) {
        double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}
