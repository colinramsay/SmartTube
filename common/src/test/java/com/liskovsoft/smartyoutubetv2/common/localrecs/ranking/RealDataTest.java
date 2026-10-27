package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.FilterTest.ids;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Cases taken from a real row on the Fire TV.
 */
public class RealDataTest {
    private final RecsConfig mConfig = new RecsConfig();

    @Test
    public void parsesViewCounts() {
        assertEquals(1_400_000, ViewCounts.parse("RAINBOLT • 1.4m views • 13 days ago"));
        assertEquals(665_000, ViewCounts.parse("Best Ever Food Clips • 665k views • 6 days ago"));
        assertEquals(4_200, ViewCounts.parse("CMAT • 4.2k views • 1 hour ago"));
        assertEquals(66, ViewCounts.parse("Untitled.mov • 66 views • 1 day ago"));
        assertEquals(1, ViewCounts.parse("Someone • 1 view • 1 day ago"));
        assertEquals(1_234, ViewCounts.parse("Someone • 1,234 views"));
        assertEquals(-1, ViewCounts.parse("Animation • 2006"));
        assertEquals(-1, ViewCounts.parse(null));
    }

    @Test
    public void dropsObscureVideosAndMovies() {
        Candidate obscure = new Candidate("v1", null, "Untitled.mov", "Marks went on holiday");
        obscure.viewCount = 66;
        Candidate movie = new Candidate("v2", "UCmovies", "Animation", "Barnyard");
        movie.isMovie = true;
        Candidate unknownViews = new Candidate("v3", null, "Tom Scott", "Strawberries");
        Candidate popular = new Candidate("v4", null, "Mark Rober", "Board games");
        popular.viewCount = 10_000_000;

        List<Candidate> result = new CandidateFilter(null, null, mConfig)
                .apply(Arrays.asList(obscure, movie, unknownViews, popular));

        assertEquals(Arrays.asList("v3", "v4"), ids(result));
    }

    @Test
    public void paddedSubscriptionNameStillMatches() {
        Set<String> known = new HashSet<>();
        ChannelAffinity.addKeys(known, null, "Joshua Weissman Recipes ");

        assertTrue(ChannelAffinity.containsChannel(known, null, "Joshua Weissman Recipes"));
    }

    @Test
    public void dropsBlankLookingTitles() {
        assertFalse(CandidateFilter.hasVisibleTitle("ㅤㅤㅤㅤ ㅤㅤ"));
        assertFalse(CandidateFilter.hasVisibleTitle("⠀⠀ ."));
        assertFalse(CandidateFilter.hasVisibleTitle(null));
        assertTrue(CandidateFilter.hasVisibleTitle("CMAT: Tiny Desk Concert"));
        assertTrue(CandidateFilter.hasVisibleTitle("교토의 작은 찻집"));
    }

    @Test
    public void dropsVideosOverTheLengthLimit() {
        Candidate ambience = new Candidate("v1", null, "Brumbleberry", "A Tiny Spider Knits");
        ambience.durationMs = 183 * 60_000L;
        Candidate documentary = new Candidate("v2", null, "RAINBOLT", "solving a 4 year internet mystery");
        documentary.durationMs = 86 * 60_000L;

        assertEquals(Collections.singletonList("v2"),
                ids(new CandidateFilter(null, null, mConfig).apply(Arrays.asList(ambience, documentary))));

        mConfig.maxDurationMs = 0;
        assertEquals(2, new CandidateFilter(null, null, mConfig).apply(Arrays.asList(ambience, documentary)).size());
    }

    @Test
    public void collabCountsAsKnownWhenAnyChannelInItIsKnown() {
        Set<String> known = new HashSet<>();
        ChannelAffinity.addKeys(known, null, "JackSucksAtGeography");

        assertTrue(ChannelAffinity.containsChannel(known, null, "JackSucksAtGeography and Jack & Oscar"));
        assertFalse(ChannelAffinity.containsChannel(known, null, "Jack & Oscar"));
    }

    @Test
    public void knownCollabMakesEachMemberKnown() {
        Set<String> known = new HashSet<>();
        ChannelAffinity.addKeys(known, null, "Robbie Knox and bambinobecky");

        assertTrue(ChannelAffinity.containsChannel(known, null, "bambinobecky"));
        assertEquals(Collections.singletonList("Tom Scott"), ChannelAffinity.nameVariants("Tom Scott"));
    }
}
