package com.liskovsoft.smartyoutubetv2.common.localrecs;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
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

    /**
     * Adds a single button that opens the feature's own settings dialog.
     */
    public static void append(Context context, AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(context.getString(R.string.localrecs_title),
                option -> showDialog(context)));
    }

    private static void showDialog(Context context) {
        LocalRecsData data = LocalRecsData.instance(context);
        LocalRecsEngine engine = LocalRecsEngine.instance(context);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        boolean wasEnabled = data.isEnabled();
        boolean wasNewChannelsOnly = data.isNewChannelsOnly();

        dialog.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.localrecs_enable),
                option -> data.setEnabled(option.isSelected()), data.isEnabled()));

        dialog.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.localrecs_new_channels_only),
                option -> data.setNewChannelsOnly(option.isSelected()), data.isNewChannelsOnly()));

        List<OptionItem> ttlOptions = new ArrayList<>();
        for (long ttlMs : TTL_OPTIONS_MS) {
            String title = ttlMs < HOUR_MS ?
                    context.getString(R.string.localrecs_minutes, ttlMs / MINUTE_MS) :
                    context.getString(R.string.localrecs_hours, ttlMs / HOUR_MS);
            ttlOptions.add(UiOptionItem.from(title, option -> data.setTtlMs(ttlMs), data.getTtlMs() == ttlMs));
        }
        dialog.appendRadioCategory(context.getString(R.string.localrecs_refresh_interval), ttlOptions);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.localrecs_blocked_keywords),
                option -> SimpleEditDialog.show(context,
                        context.getString(R.string.localrecs_blocked_keywords),
                        context.getString(R.string.localrecs_blocked_keywords_hint),
                        data.getBlockedKeywords(),
                        newValue -> {
                            data.setBlockedKeywords(newValue);
                            return true;
                        })));

        int removals = engine.getRemovalCount();
        if (removals > 0) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.localrecs_forget_removals, removals), option -> {
                engine.forgetRemovals();
                MessageHelpers.showMessage(context, R.string.localrecs_removals_forgotten);
            }));
        }

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.localrecs_refresh_now), option -> {
            engine.refreshNow();
            MessageHelpers.showMessage(context, R.string.localrecs_refresh_started);
        }));

        dialog.showDialog(context.getString(R.string.localrecs_title), () -> {
            // Show or hide the row straight away if the user is looking at Home
            BrowsePresenter browsePresenter = BrowsePresenter.instance(context);
            boolean changed = wasEnabled != data.isEnabled() || wasNewChannelsOnly != data.isNewChannelsOnly();
            if (changed && browsePresenter.isHomeSection()) {
                browsePresenter.refresh(false);
            }
        });
    }
}
