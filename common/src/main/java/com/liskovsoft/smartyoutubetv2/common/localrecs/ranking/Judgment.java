package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A model's read on one video, plus the rules for using it. The model only supplies the numbers;
 * how they change the row is decided here, in plain code.
 */
public class Judgment {
    /** Probability it's background filler: ambient mixes, compilations, loops, "music for studying". */
    public double filler = -1;
    /** Probability it's made for young children. */
    public double kids = -1;
    /** 0..1, how well it fits what the viewer finished versus what they dropped. */
    public double taste = -1;
    /** Probability it's well made and worth someone's time (not clickbait, not low effort). */
    public double quality = -1;
    /** Probability it really fits the Left Field idea it was found for. */
    public double leapFit = -1;

    public static final double DROP_THRESHOLD = 0.7;
    public static final double MIN_QUALITY = 0.5;
    public static final double MIN_LEAP_FIT = 0.4;

    public boolean isUnwanted() {
        return filler >= DROP_THRESHOLD || kids >= DROP_THRESHOLD;
    }

    /**
     * Main row: drop unwanted videos and scale the rest by taste. Videos without a judgment keep
     * their score, so a failed or skipped call never empties the row.
     */
    public static List<ScoredCandidate> applyToMainRow(List<ScoredCandidate> scored, Map<String, Judgment> judgments) {
        List<ScoredCandidate> result = new ArrayList<>(scored.size());

        for (ScoredCandidate item : scored) {
            Judgment judgment = judgments.get(item.candidate.videoId);

            if (judgment == null) {
                result.add(item);
                continue;
            }

            if (judgment.isUnwanted()) {
                continue;
            }

            double factor = judgment.taste >= 0 ? 0.4 + 0.6 * judgment.taste : 1;
            result.add(new ScoredCandidate(item.candidate, item.score * factor));
        }

        return result;
    }

    /**
     * Left Field: surprises are judged on quality and fit, not similarity to what you watch.
     * Without a judgment we can't vouch for a surprise, so it's left out.
     */
    public static double leftFieldScore(Judgment judgment) {
        if (judgment == null || judgment.isUnwanted()) {
            return -1;
        }

        double quality = judgment.quality >= 0 ? judgment.quality : 0;
        double fit = judgment.leapFit >= 0 ? judgment.leapFit : 0;

        if (quality < MIN_QUALITY || fit < MIN_LEAP_FIT) {
            return -1;
        }

        return quality * fit;
    }
}
