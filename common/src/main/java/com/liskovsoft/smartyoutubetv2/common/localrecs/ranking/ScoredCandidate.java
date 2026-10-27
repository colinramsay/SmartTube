package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

public class ScoredCandidate {
    public final Candidate candidate;
    public final double score;

    public ScoredCandidate(Candidate candidate, double score) {
        this.candidate = candidate;
        this.score = score;
    }
}
