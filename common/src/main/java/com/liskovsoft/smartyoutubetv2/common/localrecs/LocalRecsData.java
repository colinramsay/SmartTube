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
    private static final String KEY_MAX_DURATION_MS = "localrecs_max_duration_ms";
    private static final String KEY_TTL_MS = "localrecs_ttl_ms";
    private static final String KEY_KEYWORDS = "localrecs_blocked_keywords";
    private static final String KEY_CACHE = "localrecs_cache";
    private static final String KEY_DIAGNOSTICS = "localrecs_debug";
    private static final String KEY_FEEDBACK = "localrecs_feedback";
    private static final String KEY_MENU_ITEM_ADDED = "localrecs_menu_item_added";
    private static final String KEY_OUTCOMES = "localrecs_outcomes";
    private static final String KEY_LEAPS = "localrecs_leaps";
    private static final String KEY_LEFT_FIELD_CACHE = "localrecs_left_field_cache";
    private static final String KEY_TYPESAFE_API_KEY = "localrecs_typesafe_api_key";
    private static final String KEY_ANTHROPIC_API_KEY = "localrecs_anthropic_api_key";
    private static final String KEY_JEV_ENABLED = "localrecs_jev_enabled";
    private static final String KEY_LEFT_FIELD_ENABLED = "localrecs_left_field_enabled";
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

    /**
     * Videos longer than this are left out of the row. 0 means no limit.
     */
    public long getMaxDurationMs() {
        return mPrefs.getLong(KEY_MAX_DURATION_MS, 2 * 60 * 60 * 1000L);
    }

    public void setMaxDurationMs(long maxDurationMs) {
        mPrefs.putLong(KEY_MAX_DURATION_MS, maxDurationMs);
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

    /**
     * API keys live in the app's private storage, so they survive "adb install -r" updates but not
     * an uninstall. Anyone with root or a debugger on the device could read them.
     */
    public String getTypeSafeApiKey() {
        return mPrefs.getString(KEY_TYPESAFE_API_KEY, "");
    }

    public void setTypeSafeApiKey(String key) {
        mPrefs.putString(KEY_TYPESAFE_API_KEY, key != null ? key.trim() : "");
    }

    public String getAnthropicApiKey() {
        return mPrefs.getString(KEY_ANTHROPIC_API_KEY, "");
    }

    public void setAnthropicApiKey(String key) {
        mPrefs.putString(KEY_ANTHROPIC_API_KEY, key != null ? key.trim() : "");
    }

    /**
     * Jev judges candidates only when switched on and a key is set.
     */
    public boolean isJevEnabled() {
        return mPrefs.getBoolean(KEY_JEV_ENABLED, true) && !getTypeSafeApiKey().isEmpty();
    }

    public boolean isJevSwitchOn() {
        return mPrefs.getBoolean(KEY_JEV_ENABLED, true);
    }

    public void setJevEnabled(boolean enabled) {
        mPrefs.putBoolean(KEY_JEV_ENABLED, enabled);
    }

    /**
     * The Left Field row needs Claude for ideas and Jev to vet what the searches find.
     */
    public boolean isLeftFieldEnabled() {
        return mPrefs.getBoolean(KEY_LEFT_FIELD_ENABLED, true) && !getAnthropicApiKey().isEmpty() && isJevEnabled();
    }

    public boolean isLeftFieldSwitchOn() {
        return mPrefs.getBoolean(KEY_LEFT_FIELD_ENABLED, true);
    }

    public void setLeftFieldEnabled(boolean enabled) {
        mPrefs.putBoolean(KEY_LEFT_FIELD_ENABLED, enabled);
    }

    /**
     * Keys can also be dropped into files/localrecs_keys.txt (lines "typesafe=..." and "anthropic=...")
     * over adb, which beats typing them with a TV remote. The file is read once and deleted.
     */
    public void importKeysFile(Context context) {
        java.io.File file = new java.io.File(context.getFilesDir(), "localrecs_keys.txt");
        if (!file.exists()) {
            return;
        }

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String name = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim();
                if ("typesafe".equals(name)) {
                    setTypeSafeApiKey(value);
                } else if ("anthropic".equals(name)) {
                    setAnthropicApiKey(value);
                }
            }
        } catch (java.io.IOException e) {
            // leave the keys as they were
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public String getOutcomes() {
        return mPrefs.getData(KEY_OUTCOMES);
    }

    public void setOutcomes(String outcomes) {
        mPrefs.setData(KEY_OUTCOMES, outcomes);
    }

    public String getLeaps() {
        return mPrefs.getData(KEY_LEAPS);
    }

    public void setLeaps(String leaps) {
        mPrefs.setData(KEY_LEAPS, leaps);
    }

    public String getLeftFieldCache() {
        return mPrefs.getData(KEY_LEFT_FIELD_CACHE);
    }

    public void setLeftFieldCache(String cache) {
        mPrefs.setData(KEY_LEFT_FIELD_CACHE, cache);
    }

    /**
     * Videos removed from the row, see {@code Feedback}. Kept per device, not per account.
     */
    public String getFeedback() {
        return mPrefs.getData(KEY_FEEDBACK);
    }

    public void setFeedback(String feedback) {
        mPrefs.setData(KEY_FEEDBACK, feedback);
    }

    /**
     * Whether we already switched on our long-press menu item once. After that it's the user's choice.
     */
    public boolean isMenuItemAdded() {
        return mPrefs.getBoolean(KEY_MENU_ITEM_ADDED, false);
    }

    public void markMenuItemAdded() {
        mPrefs.putBoolean(KEY_MENU_ITEM_ADDED, true);
    }

    /**
     * Summary of the last refresh, for tuning. Not shown in the app.
     */
    public String getDiagnostics() {
        return mPrefs.getData(KEY_DIAGNOSTICS);
    }

    public void setDiagnostics(String text) {
        mPrefs.setData(KEY_DIAGNOSTICS, text);
    }
}
