package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import org.junit.Test;

import java.util.ArrayList;
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

public class LengthPreferenceTest {
    private static final long MINUTE = 60_000L;

    @Test
    public void usualLengthIsWhatNineInTenFinishedVideosStayUnder() {
        // Mostly 10 to 38 minutes, one long outlier
        List<Long> finished = new ArrayList<>();
        for (long minutes : new long[] {10, 12, 15, 18, 20, 22, 25, 30, 38, 95}) {
            finished.add(minutes * MINUTE);
        }

        LengthPreference length = LengthPreference.fromFinished(finished);

        assertTrue(length.isKnown());
        assertEquals(38 * MINUTE, length.usualMaxMs());
        assertEquals(76 * MINUTE, length.dropAboveMs());
    }

    @Test
    public void tooFewFinishedVideosMeansNoGuess() {
        LengthPreference length = LengthPreference.fromFinished(Arrays.asList(20 * MINUTE, 30 * MINUTE, 0L, null));

        assertFalse(length.isKnown());
        assertEquals(0, length.dropAboveMs());
        assertEquals(1.0, length.factor(300 * MINUTE), 1e-9);
    }

    @Test
    public void longerThanUsualScoresLessDownToAFloor() {
        LengthPreference length = LengthPreference.fromFinished(Collections.nCopies(10, 40 * MINUTE));

        assertEquals(1.0, length.factor(25 * MINUTE), 1e-9);
        assertEquals(1.0, length.factor(40 * MINUTE), 1e-9);
        assertEquals(0.6, length.factor(60 * MINUTE), 1e-9);
        assertEquals(0.2, length.factor(80 * MINUTE), 1e-9);
        assertEquals(1.0, length.factor(0), 1e-9); // unknown length
    }

    @Test
    public void rankerPrefersAVideoOfUsualLength() {
        RecsConfig config = new RecsConfig();
        List<Seed> seeds = Collections.singletonList(seed("s1", "A", 1));
        RankingContext context = new RankingContext(seeds, ChannelAffinity.compute(seeds, null, config), config, NOW);
        context.length = LengthPreference.fromFinished(Collections.nCopies(10, 30 * MINUTE));

        Candidate usual = related("usual", "X", "s1");
        usual.durationMs = 25 * MINUTE;
        Candidate epic = related("epic", "Y", "s1");
        epic.durationMs = 55 * MINUTE;

        Map<String, Double> scores = new HashMap<>();
        for (ScoredCandidate item : new HeuristicRanker().score(context, Arrays.asList(usual, epic))) {
            scores.put(item.candidate.videoId, item.score);
        }

        assertTrue(scores.get("usual") > scores.get("epic"));
    }
}
