package com.liskovsoft.smartyoutubetv2.common.localrecs.cache;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CacheTest {
    private static final long HOUR = 60 * 60 * 1000L;
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void roundTripsItems() {
        CachedRow.Item item = new CachedRow.Item();
        item.videoId = "abc";
        item.channelId = "UC1";
        item.title = "Title with \u001F odd \u001E chars";
        item.author = "Author";
        item.durationMs = 1234;
        item.publishedMs = 5678;

        CachedRow.Item bare = new CachedRow.Item();
        bare.videoId = "def";

        CachedRow decoded = CachedRow.decode(new CachedRow(NOW, "me@example.com", Arrays.asList(item, bare)).encode());

        assertEquals(NOW, decoded.savedAtMs);
        assertEquals("me@example.com", decoded.owner);
        assertEquals(2, decoded.items.size());
        assertEquals("abc", decoded.items.get(0).videoId);
        assertEquals("Title with   odd   chars", decoded.items.get(0).title);
        assertEquals(1234, decoded.items.get(0).durationMs);
        assertEquals(5678, decoded.items.get(0).publishedMs);
        assertNull(decoded.items.get(1).title);
    }

    @Test
    public void rejectsGarbage() {
        assertNull(CachedRow.decode(null));
        assertNull(CachedRow.decode(""));
        assertNull(CachedRow.decode("something else"));
        assertNull(CachedRow.decode("localrecs-v3\u001Fnot-a-number\u001Fme"));
    }

    @Test
    public void freshCacheNeedsNoRefresh() {
        CachePolicy policy = new CachePolicy(2 * HOUR);
        CachedRow row = rowSavedAt(NOW - HOUR);

        assertTrue(policy.isUsable(row, "me"));
        assertFalse(policy.needsRefresh(row, "me", NOW));
    }

    @Test
    public void staleCacheIsStillShownButRefreshed() {
        CachePolicy policy = new CachePolicy(2 * HOUR);
        CachedRow row = rowSavedAt(NOW - 3 * HOUR);

        assertTrue(policy.isUsable(row, "me"));
        assertTrue(policy.needsRefresh(row, "me", NOW));
    }

    @Test
    public void exactlyAtTtlIsStale() {
        assertTrue(new CachePolicy(2 * HOUR).needsRefresh(rowSavedAt(NOW - 2 * HOUR), "me", NOW));
    }

    @Test
    public void clockGoingBackwardsForcesRefresh() {
        assertTrue(new CachePolicy(2 * HOUR).needsRefresh(rowSavedAt(NOW + HOUR), "me", NOW));
    }

    @Test
    public void otherUsersOrEmptyRowsAreNotUsable() {
        CachePolicy policy = new CachePolicy(2 * HOUR);

        assertFalse(policy.isUsable(rowSavedAt(NOW), "someone else"));
        assertFalse(policy.isUsable(new CachedRow(NOW, "me", Collections.emptyList()), "me"));
        assertFalse(policy.isUsable(null, "me"));
        assertTrue(policy.needsRefresh(null, "me", NOW));
    }

    @Test
    public void invalidTtlFallsBackToDefault() {
        CachePolicy policy = new CachePolicy(0);

        assertFalse(policy.needsRefresh(rowSavedAt(NOW - HOUR), "me", NOW));
        assertTrue(policy.needsRefresh(rowSavedAt(NOW - CachePolicy.DEFAULT_TTL_MS), "me", NOW));
    }

    private static CachedRow rowSavedAt(long savedAtMs) {
        CachedRow.Item item = new CachedRow.Item();
        item.videoId = "v";
        return new CachedRow(savedAtMs, "me", Collections.singletonList(item));
    }
}
