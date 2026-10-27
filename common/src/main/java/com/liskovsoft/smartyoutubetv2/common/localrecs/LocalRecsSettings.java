package com.liskovsoft.smartyoutubetv2.common.localrecs;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Leap;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings entry for the local recommendations row. Added to the General settings dialog.
 */
public class LocalRecsSettings {
    private static final long MINUTE_MS = 60 * 1000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;
    private static final long[] TTL_OPTIONS_MS = {30 * MINUTE_MS, HOUR_MS, 2 * HOUR_MS, 4 * HOUR_MS, 12 * HOUR_MS};
    private static final long[] MAX_LENGTH_OPTIONS_MS = {30 * MINUTE_MS, HOUR_MS, 2 * HOUR_MS, 3 * HOUR_MS, 0};

    /**
     * Adds a single button that opens the feature's own settings dialog.
     */
    public static void append(Context context, AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(context.getString(R.string.localrecs_title),
                option -> showDialog(context)));
    }

    /**
     * API keys, the model switches, and the current Left Field ideas.
     */
    private static void appendAiOptions(Context context, AppDialogPresenter dialog, LocalRecsData data, LocalRecsEngine engine) {
        dialog.appendSingleButton(UiOptionItem.from(keyTitle(context, R.string.localrecs_typesafe_key, data.getTypeSafeApiKey()),
                option -> SimpleEditDialog.showPassword(context, context.getString(R.string.localrecs_typesafe_key),
                        data.getTypeSafeApiKey(), newValue -> {
                            data.setTypeSafeApiKey(newValue);
                            MessageHelpers.showMessage(context, R.string.localrecs_key_saved);
                            return true;
                        })));

        dialog.appendSingleButton(UiOptionItem.from(keyTitle(context, R.string.localrecs_anthropic_key, data.getAnthropicApiKey()),
                option -> SimpleEditDialog.showPassword(context, context.getString(R.string.localrecs_anthropic_key),
                        data.getAnthropicApiKey(), newValue -> {
                            data.setAnthropicApiKey(newValue);
                            MessageHelpers.showMessage(context, R.string.localrecs_key_saved);
                            return true;
                        })));

        dialog.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.localrecs_jev_enabled),
                option -> data.setJevEnabled(option.isSelected()), data.isJevSwitchOn()));

        dialog.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.localrecs_left_field_enabled),
                option -> data.setLeftFieldEnabled(option.isSelected()), data.isLeftFieldSwitchOn()));

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.localrecs_new_ideas), option -> {
            if (!data.isLeftFieldEnabled()) {
                MessageHelpers.showMessage(context, R.string.localrecs_left_field_needs_keys);
                return;
            }
            engine.requestNewLeaps();
            MessageHelpers.showMessage(context, R.string.localrecs_new_ideas_started);
        }));

        List<Leap> leaps = engine.getCurrentLeaps();
        if (!leaps.isEmpty()) {
            List<OptionItem> ideas = new ArrayList<>();
            for (Leap leap : leaps) {
                ideas.add(UiOptionItem.from(leap.label + ": " + leap.reason));
            }
            dialog.appendStringsCategory(context.getString(R.string.localrecs_current_ideas), ideas);
        }
    }

    private static String aiState(LocalRecsData data) {
        return data.isJevEnabled() + "|" + data.isLeftFieldEnabled() + "|" + data.getTypeSafeApiKey().hashCode() + "|" + data.getAnthropicApiKey().hashCode();
    }

    private static String keyTitle(Context context, int titleResId, String key) {
        return context.getString(titleResId) + ": " +
                context.getString(key == null || key.isEmpty() ? R.string.localrecs_key_not_set : R.string.localrecs_key_set);
    }

    private static String formatLength(Context context, long ms) {
        if (ms < HOUR_MS) {
            return context.getString(R.string.localrecs_minutes, ms / MINUTE_MS);
        }

        return ms == HOUR_MS ? context.getString(R.string.localrecs_one_hour) : context.getString(R.string.localrecs_hours, ms / HOUR_MS);
    }

    private static void showDialog(Context context) {
        LocalRecsData data = LocalRecsData.instance(context);
        LocalRecsEngine engine = LocalRecsEngine.instance(context);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        boolean wasEnabled = data.isEnabled();
        boolean wasNewChannelsOnly = data.isNewChannelsOnly();
        String aiStateBefore = aiState(data);

        dialog.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.localrecs_enable),
                option -> data.setEnabled(option.isSelected()), data.isEnabled()));

        dialog.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.localrecs_new_channels_only),
                option -> data.setNewChannelsOnly(option.isSelected()), data.isNewChannelsOnly()));

        List<OptionItem> ttlOptions = new ArrayList<>();
        for (long ttlMs : TTL_OPTIONS_MS) {
            String title = formatLength(context, ttlMs);
            ttlOptions.add(UiOptionItem.from(title, option -> data.setTtlMs(ttlMs), data.getTtlMs() == ttlMs));
        }
        dialog.appendRadioCategory(context.getString(R.string.localrecs_refresh_interval), ttlOptions);

        List<OptionItem> lengthOptions = new ArrayList<>();
        for (long maxMs : MAX_LENGTH_OPTIONS_MS) {
            String title = maxMs == 0 ? context.getString(R.string.localrecs_no_limit) : formatLength(context, maxMs);
            lengthOptions.add(UiOptionItem.from(title, option -> data.setMaxDurationMs(maxMs), data.getMaxDurationMs() == maxMs));
        }
        dialog.appendRadioCategory(context.getString(R.string.localrecs_max_length), lengthOptions);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.localrecs_blocked_keywords),
                option -> SimpleEditDialog.show(context,
                        context.getString(R.string.localrecs_blocked_keywords),
                        context.getString(R.string.localrecs_blocked_keywords_hint),
                        data.getBlockedKeywords(),
                        newValue -> {
                            data.setBlockedKeywords(newValue);
                            return true;
                        })));

        appendAiOptions(context, dialog, data, engine);

        int removals = engine.getRemovalCount();
        if (removals > 0) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.localrecs_forget_removals, removals), option -> {
                engine.forgetRemovals();
                MessageHelpers.showMessage(context, R.string.localrecs_removals_forgotten);
            }));
        }

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.localrecs_refresh_now), option -> {
            engine.refreshFromSettings();
            MessageHelpers.showMessage(context, R.string.localrecs_refresh_started);
        }));

        dialog.showDialog(context.getString(R.string.localrecs_title), () -> {
            // Show or hide the row straight away if the user is looking at Home
            BrowsePresenter browsePresenter = BrowsePresenter.instance(context);
            boolean changed = wasEnabled != data.isEnabled() || wasNewChannelsOnly != data.isNewChannelsOnly();
            if (changed && browsePresenter.isHomeSection()) {
                browsePresenter.refresh(false);
            }

            // New keys or switches: rebuild in the background so the next Home load uses them
            if (!aiStateBefore.equals(aiState(data))) {
                engine.refreshNow();
            }
        });
    }
}
