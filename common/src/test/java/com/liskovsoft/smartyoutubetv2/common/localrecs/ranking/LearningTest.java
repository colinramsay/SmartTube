package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Fixtures.video;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Outcomes, model judgments and Left Field ideas.
 */
public class LearningTest {
    private final RecsConfig mConfig = new RecsConfig();

    @Test
    public void outcomesTrackFinishedAbandonedAndIgnored() {
        Outcomes outcomes = new Outcomes();
        for (int refresh = 0; refresh < 4; refresh++) {
            outcomes.recordShown("finished", "Good one", "Chan A", null);
            outcomes.recordShown("abandoned", "Meh", "Chan B", "leap1");
            outcomes.recordShown("ignored", "Never clicked", "Chan C", null);
        }

        Map<String, Double> playback = new HashMap<>();
        playback.put("finished", 0.95);
        playback.put("abandoned", 0.1);
        outcomes.recordPlayback(playback);

        assertEquals("Good one", outcomes.withState(Outcomes.STATE_FINISHED, 10).get(0).title);
        assertEquals("Meh", outcomes.withState(Outcomes.STATE_ABANDONED, 10).get(0).title);
        assertEquals(Collections.singleton("ignored"), outcomes.ignoredVideoIds(4));
        assertTrue(outcomes.ignoredVideoIds(5).isEmpty());
    }

    @Test
    public void finishedStaysFinishedAndOutcomesRoundTrip() {
        Outcomes outcomes = new Outcomes();
        outcomes.recordShown("v1", "Title", "Chan", "leapX");
        outcomes.recordPlayback(Collections.singletonMap("v1", 1.0));
        // A later rewatch that stopped early doesn't undo a finish
        outcomes.recordPlayback(Collections.singletonMap("v1", 0.05));

        Outcomes decoded = Outcomes.decode(outcomes.encode());

        assertEquals(1, decoded.withState(Outcomes.STATE_FINISHED, 10).size());
        assertEquals(Integer.valueOf(1), decoded.leapHits().get("leapX"));
        assertEquals(0, Outcomes.decode("junk").size());
    }

    @Test
    public void judgmentsDropUnwantedAndScaleByTaste() {
        Candidate filler = video("filler", "A");
        Candidate liked = video("liked", "B");
        Candidate disliked = video("disliked", "C");
        Candidate unjudged = video("unjudged", "D");
        List<ScoredCandidate> scored = Fixtures.scored(Arrays.asList(filler, liked, disliked, unjudged), 1, 1, 1, 1);

        Map<String, Judgment> judgments = new HashMap<>();
        judgments.put("filler", judgment(0.9, 0.1, 1.0));
        judgments.put("liked", judgment(0.1, 0.1, 1.0));
        judgments.put("disliked", judgment(0.1, 0.1, 0.0));

        Map<String, Double> result = new HashMap<>();
        for (ScoredCandidate item : Judgment.applyToMainRow(scored, judgments)) {
            result.put(item.candidate.videoId, item.score);
        }

        assertFalse(result.containsKey("filler"));
        assertEquals(1.0, result.get("liked"), 1e-9);
        assertEquals(0.4, result.get("disliked"), 1e-9);
        assertEquals(1.0, result.get("unjudged"), 1e-9);
    }

    @Test
    public void leftFieldNeedsQualityAndFit() {
        Judgment good = judgment(0.1, 0.1, -1);
        good.quality = 0.9;
        good.leapFit = 0.8;
        Judgment offTopic = judgment(0.1, 0.1, -1);
        offTopic.quality = 0.9;
        offTopic.leapFit = 0.2;
        Judgment lowEffort = judgment(0.1, 0.1, -1);
        lowEffort.quality = 0.3;
        lowEffort.leapFit = 0.9;

        assertEquals(0.72, Judgment.leftFieldScore(good), 1e-9);
        assertTrue(Judgment.leftFieldScore(offTopic) < 0);
        assertTrue(Judgment.leftFieldScore(lowEffort) < 0);
        assertTrue(Judgment.leftFieldScore(null) < 0);
    }

    @Test
    public void leftFieldRowTakesTurnsBetweenIdeasWithOneVideoPerChannel() {
        List<ScoredCandidate> scored = new java.util.ArrayList<>();
        // Idea A finds four strong videos, idea B two weaker ones, one sharing a channel with A
        scored.add(leftField("a1", "CH1", "A", 0.9));
        scored.add(leftField("a2", "CH2", "A", 0.8));
        scored.add(leftField("a3", "CH3", "A", 0.7));
        scored.add(leftField("a4", "CH4", "A", 0.6));
        scored.add(leftField("b1", "CH1", "B", 0.5));
        scored.add(leftField("b2", "CH5", "B", 0.4));
        scored.add(leftField("rejected", "CH6", "B", -1));

        List<Candidate> row = LeftFieldAssembler.assemble(scored, 3, 10);

        assertEquals(Arrays.asList("a1", "b2", "a2", "a3"), FilterTest.ids(row));
    }

    private static ScoredCandidate leftField(String videoId, String channel, String leapId, double score) {
        Candidate candidate = video(videoId, channel);
        candidate.leapId = leapId;
        return new ScoredCandidate(candidate, score);
    }

    @Test
    public void leapBatchRoundTrips() {
        Leap.Batch batch = new Leap.Batch(123, Arrays.asList(
                new Leap("l1", "canal boat restoration", "patient craft, a sense of place", "canal boat restoration vlog"),
                new Leap("l2", "linguistics explainers", "you like guessing countries", "why languages sound different")),
                Collections.singletonList("ambient jazz"));

        Leap.Batch decoded = Leap.Batch.decode(batch.encode());

        assertEquals(123, decoded.createdAtMs);
        assertEquals(2, decoded.leaps.size());
        assertEquals("why languages sound different", decoded.find("l2").query);
        assertEquals(Collections.singletonList("ambient jazz"), decoded.rejected);
        assertNull(Leap.Batch.decode("nope"));
    }

    @Test
    public void leapsFailOrLandOnVerdicts() {
        Map<String, Integer> hits = new HashMap<>();
        Map<String, Integer> misses = new HashMap<>();
        misses.put("bad", 2);
        misses.put("mixed", 2);
        hits.put("mixed", 2);
        hits.put("good", 2);

        assertTrue(Leap.hasFailed("bad", hits, misses));
        assertFalse(Leap.hasFailed("mixed", hits, misses));
        assertFalse(Leap.hasFailed("new", hits, misses));
        assertTrue(Leap.hasLanded("good", hits));
    }

    @Test
    public void feedbackKeepsTitlesReadsOldFormatAndCountsLeapMisses() {
        Feedback feedback = new Feedback(mConfig);
        feedback.addRemoval(new Feedback.Removal("v1", null, "Chan", Arrays.asList("s1", Feedback.LEAP_PREFIX + "l1"), "Ambient mix"));

        Feedback decoded = Feedback.decode(feedback.encode(), mConfig);

        assertEquals("Ambient mix", decoded.recentRemovals(5).get(0).title);
        assertEquals(Integer.valueOf(1), decoded.leapMisses().get("l1"));

        Feedback old = Feedback.decode("feedback-v1\u001Ev9\u001F\u001FChan\u001Fs1", mConfig);
        assertEquals(1, old.size());
        assertNull(old.recentRemovals(1).get(0).title);
    }

    private static Judgment judgment(double filler, double kids, double taste) {
        Judgment judgment = new Judgment();
        judgment.filler = filler;
        judgment.kids = kids;
        judgment.taste = taste;
        return judgment;
    }
}
