package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.NOW;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.related;
import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.seed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeedbackTest {
    private final RecsConfig mConfig = new RecsConfig();

    @Test
    public void roundTrips() {
        Feedback feedback = new Feedback(mConfig);
        feedback.addRemoval(new Feedback.Removal("v1", "UC1", "Some Channel", Arrays.asList("s1", "s2")));
        feedback.addRemoval(new Feedback.Removal("v2", null, null, null));

        Feedback decoded = Feedback.decode(feedback.encode(), mConfig);

        assertEquals(2, decoded.size());
        assertEquals(1, decoded.channelRemovals("UC1", null));
        assertEquals(1, decoded.channelRemovals(null, "some channel"));
        assertTrue(decoded.removedVideoIds().contains("v2"));
        assertEquals(mConfig.seedRemovalPenalty, decoded.seedMultiplier("s2"), 1e-9);
    }

    @Test
    public void unreadableDataGivesEmptyFeedback() {
        assertTrue(Feedback.decode(null, mConfig).isEmpty());
        assertTrue(Feedback.decode("", mConfig).isEmpty());
        assertTrue(Feedback.decode("garbage", mConfig).isEmpty());
    }

    @Test
    public void keepsOnlyMostRecentRemovals() {
        mConfig.maxRemovals = 3;
        Feedback feedback = new Feedback(mConfig);

        for (int i = 0; i < 5; i++) {
            feedback.addRemoval(new Feedback.Removal("v" + i, "UC" + i, null, null));
        }

        assertEquals(3, feedback.size());
        assertFalse(feedback.removedVideoIds().contains("v0"));
        assertTrue(feedback.removedVideoIds().contains("v4"));
    }

    @Test
    public void channelIsPenalisedThenBlocked() {
        Feedback feedback = new Feedback(mConfig);
        feedback.addRemoval(new Feedback.Removal("v1", "UC1", "Chan", null));

        assertEquals(0.5, feedback.channelMultiplier("UC1", null), 1e-9);
        assertEquals(1.0, feedback.channelMultiplier("UC2", null), 1e-9);
        assertFalse(ChannelAffinity.containsChannel(feedback.blockedChannelKeys(), "UC1", null));

        feedback.addRemoval(new Feedback.Removal("v2", null, "Chan", null));

        assertTrue(ChannelAffinity.containsChannel(feedback.blockedChannelKeys(), "UC1", null));
        assertTrue(ChannelAffinity.containsChannel(feedback.blockedChannelKeys(), null, "Chan"));
    }

    @Test
    public void removalLowersScoreOfThatChannelOnly() {
        List<Seed> seeds = Collections.singletonList(seed("s1", "A", 1));
        RankingContext context = new RankingContext(seeds, ChannelAffinity.compute(seeds, null, mConfig), mConfig, NOW);
        context.feedback.addRemoval(new Feedback.Removal("old", "X", null, null));

        Map<String, Double> scores = new HashMap<>();
        for (ScoredCandidate item : new HeuristicRanker().score(context,
                Arrays.asList(related("x1", "X", "s1"), related("y1", "Y", "s1")))) {
            scores.put(item.candidate.videoId, item.score);
        }

        assertEquals(scores.get("y1") * 0.5, scores.get("x1"), 1e-9);
    }

    @Test
    public void seedThatLedToRemovalsCountsLess() {
        Seed liked = seed("s1", "A", 1);
        Seed disliked = seed("s2", "B", 1);
        Feedback feedback = new Feedback(mConfig);
        feedback.addRemoval(new Feedback.Removal("v1", "X", null, Collections.singletonList("s2")));
        feedback.addRemoval(new Feedback.Removal("v2", "Y", null, Collections.singletonList("s2")));
        disliked.feedbackFactor = feedback.seedMultiplier("s2");

        List<Candidate> related = Arrays.asList(related("a", "NEW1", "s1"), related("b", "NEW2", "s2"));
        ChannelAffinity discovery = ChannelDiscovery.compute(Arrays.asList(liked, disliked), related,
                Collections.<String>emptySet(), mConfig);

        // Two removals: 0.7 * 0.7 of its normal weight
        assertEquals(Seed.weight(1, 1, mConfig) * 0.49, disliked.weightAt(1, mConfig), 1e-9);
        assertTrue(discovery.get("NEW1") > discovery.get("NEW2"));
    }
}
