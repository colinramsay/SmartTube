package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.NOW;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.DAY;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.related;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.rss;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.seed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScoringTest {
    private final RecsConfig mConfig = new RecsConfig();

    @Test
    public void recentSeedsWeighMoreThanOldOnes() {
        assertTrue(Seed.weight(0, 1, mConfig) > Seed.weight(5, 1, mConfig));
    }

    @Test
    public void finishedSeedsWeighMoreThanAbandonedOnes() {
        double finished = Seed.weight(0, 1.0, mConfig);
        double abandoned = Seed.weight(0, 0.1, mConfig);
        double unknown = Seed.weight(0, -1, mConfig);

        assertTrue(finished > unknown);
        assertTrue(unknown > abandoned);
        assertEquals(1.0, finished, 1e-9);
    }

    @Test
    public void channelAffinitySumsSeedsAndAddsSmallerSubscriptionBase() {
        List<Seed> seeds = Arrays.asList(seed("s1", "A", 1), seed("s2", "A", 1), seed("s3", "B", 1));
        ChannelAffinity affinity = ChannelAffinity.compute(seeds, Arrays.asList("C", "A"), mConfig);

        double a = affinity.get("A");
        double b = affinity.get("B");
        double c = affinity.get("C");

        assertTrue("two seeds beat one", a > b);
        assertTrue("watched channel beats subscription only", b > c);
        assertTrue("subscription gives some affinity", c > 0);
        assertEquals(Arrays.asList("A", "B", "C"), affinity.topChannelIds(3));
    }

    @Test
    public void freshnessHalvesEveryHalfLife() {
        double fresh = HeuristicRanker.freshness(NOW, NOW, mConfig);
        double weekOld = HeuristicRanker.freshness(NOW - 7 * DAY, NOW, mConfig);

        assertEquals(1.0, fresh, 1e-9);
        assertEquals(0.5, weekOld, 1e-9);
        assertEquals(mConfig.unknownFreshness, HeuristicRanker.freshness(0, NOW, mConfig), 1e-9);
    }

    @Test
    public void coOccurrenceAcrossSeveralSeedsBeatsASingleSeed() {
        List<Seed> seeds = Arrays.asList(seed("s1", "A", 1), seed("s2", "B", 1), seed("s3", "C", 1));
        ChannelAffinity affinity = ChannelAffinity.compute(seeds, null, mConfig);
        RankingContext context = new RankingContext(seeds, affinity, mConfig, NOW);

        Candidate many = related("v1", "X", "s1", "s2", "s3");
        Candidate one = related("v2", "Y", "s1");

        Map<String, Double> scores = scoreMap(context, Arrays.asList(many, one));

        assertTrue(scores.get("v1") > scores.get("v2"));
    }

    @Test
    public void favouriteChannelUploadOutranksUnknownChannelOfSameAge() {
        List<Seed> seeds = Collections.singletonList(seed("s1", "A", 1));
        ChannelAffinity affinity = ChannelAffinity.compute(seeds, null, mConfig);
        RankingContext context = new RankingContext(seeds, affinity, mConfig, NOW);

        Map<String, Double> scores = scoreMap(context, Arrays.asList(rss("fav", "A", 1), rss("other", "Z", 1)));

        assertTrue(scores.get("fav") > scores.get("other"));
    }

    @Test
    public void newerUploadOutranksOlderFromSameChannel() {
        List<Seed> seeds = Collections.singletonList(seed("s1", "A", 1));
        RankingContext context = new RankingContext(seeds, ChannelAffinity.compute(seeds, null, mConfig), mConfig, NOW);

        Map<String, Double> scores = scoreMap(context, Arrays.asList(rss("new", "A", 0), rss("old", "A", 30)));

        assertTrue(scores.get("new") > scores.get("old"));
    }

    private static Map<String, Double> scoreMap(RankingContext context, List<Candidate> candidates) {
        Map<String, Double> result = new HashMap<>();
        for (ScoredCandidate item : new HeuristicRanker().score(context, candidates)) {
            result.put(item.candidate.videoId, item.score);
        }
        return result;
    }
}
