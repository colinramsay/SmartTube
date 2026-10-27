package com.liskovsoft.smartyoutubetv2.common.localrecs.cache;

/**
 * Decides whether the cached row can be shown and whether it needs a background refresh.
 */
public class CachePolicy {
    public static final long DEFAULT_TTL_MS = 2 * 60 * 60 * 1000L;

    private final long mTtlMs;

    public CachePolicy(long ttlMs) {
        mTtlMs = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;
    }

    /**
     * A cached row is shown if it belongs to the current user, even when stale.
     */
    public boolean isUsable(CachedRow row, String owner) {
        return row != null && !row.isEmpty() && row.owner.equals(owner != null ? owner : "");
    }

    /**
     * Refresh when there is nothing usable, the TTL has passed, or the clock went backwards.
     */
    public boolean needsRefresh(CachedRow row, String owner, long nowMs) {
        if (!isUsable(row, owner)) {
            return true;
        }

        long age = nowMs - row.savedAtMs;
        return age < 0 || age >= mTtlMs;
    }
}
