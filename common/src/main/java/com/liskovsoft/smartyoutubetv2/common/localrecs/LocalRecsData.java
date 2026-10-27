package com.liskovsoft.smartyoutubetv2.common.localrecs;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.localrecs.cache.CachePolicy;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

/**
 * Settings and the cached row for the local recommendations feature, stored in the app prefs.
 */
public class LocalRecsData {
    private static final String KEY_ENABLED = "localrecs_enabled";
    private static final String KEY_NEW_CHANNELS_ONLY = "localrecs_new_channels_only";
    private static final String KEY_TTL_MS = "localrecs_ttl_ms";
    private static final String KEY_KEYWORDS = "localrecs_blocked_keywords";
    private static final String KEY_CACHE = "localrecs_cache";
    @SuppressLint("StaticFieldLeak")
    private static LocalRecsData sInstance;
    private final AppPrefs mPrefs;

    private LocalRecsData(Context context) {
        mPrefs = AppPrefs.instance(context);
    }

    public static synchronized LocalRecsData instance(Context context) {
        if (sInstance == null) {
            sInstance = new LocalRecsData(context.getApplicationContext());
        }

        return sInstance;
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.putBoolean(KEY_ENABLED, enabled);
    }

    /**
     * Only recommend channels the user has never watched or subscribed to.
     */
    public boolean isNewChannelsOnly() {
        return mPrefs.getBoolean(KEY_NEW_CHANNELS_ONLY, true);
    }

    public void setNewChannelsOnly(boolean newChannelsOnly) {
        mPrefs.putBoolean(KEY_NEW_CHANNELS_ONLY, newChannelsOnly);
    }

    public long getTtlMs() {
        return mPrefs.getLong(KEY_TTL_MS, CachePolicy.DEFAULT_TTL_MS);
    }

    public void setTtlMs(long ttlMs) {
        mPrefs.putLong(KEY_TTL_MS, ttlMs);
    }

    /**
     * Comma separated title keywords, as typed by the user.
     */
    public String getBlockedKeywords() {
        return mPrefs.getString(KEY_KEYWORDS, "");
    }

    public void setBlockedKeywords(String keywords) {
        mPrefs.putString(KEY_KEYWORDS, keywords != null ? keywords.trim() : "");
    }

    /**
     * The cached row lives in its own file since it is much bigger than a setting.
     */
    public String getCache() {
        return mPrefs.getData(KEY_CACHE);
    }

    public void setCache(String cache) {
        mPrefs.setData(KEY_CACHE, cache);
    }
}
