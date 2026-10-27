package com.liskovsoft.smartyoutubetv2.common.localrecs.ranking;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelResolverTest {
    @Test
    public void fillsMissingIdFromAnotherSighting() {
        ChannelResolver resolver = new ChannelResolver();
        resolver.learn("UC123", "Some Channel");

        assertEquals("UC123", resolver.resolveId(null, " some channel "));
        assertEquals("UC999", resolver.resolveId("UC999", "Some Channel"));
        assertNull(resolver.resolveId(null, "Unknown"));
        assertNull(resolver.resolveId(null, null));
    }

    @Test
    public void historyTileWithoutIdStillMakesTheChannelKnown() {
        // History tile came without an id, a related video from the same channel came with one
        ChannelResolver resolver = new ChannelResolver();
        resolver.learn("UC123", "Some Channel");

        Set<String> known = new HashSet<>();
        ChannelAffinity.addKeys(known, resolver.resolveId(null, "Some Channel"), "Some Channel");

        assertTrue(ChannelAffinity.containsChannel(known, "UC123", null));
        assertTrue(ChannelAffinity.containsChannel(known, null, "Some Channel"));
    }
}
