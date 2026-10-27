package com.liskovsoft.smartyoutubetv2.common.localrecs;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.VideoMenuCallback;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.ContextMenuProvider;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.sharedutils.rx.RxHelper;

/**
 * "Remove from For You" in the long-press menu of videos in our row.
 */
public class LocalRecsMenuProvider extends ContextMenuProvider {
    /**
     * Slot in upstream's provider list. Upstream uses 0-2 today; this leaves room for it to grow.
     * The menu item id is derived from it, so it must never change.
     */
    public static final int MENU_INDEX = 10;

    public LocalRecsMenuProvider(Context context, int idx) {
        super(context, idx);
    }

    /**
     * Upstream adds new menu providers switched off. Switch ours on once, then leave it to the user.
     */
    public static void enableByDefault(Context context) {
        LocalRecsData data = LocalRecsData.instance(context);

        if (data.isMenuItemAdded()) {
            return;
        }

        MainUIData.instance(context).setMenuItemEnabled(new LocalRecsMenuProvider(context, MENU_INDEX).getId());
        data.markMenuItemAdded();
    }

    @Override
    public int getTitleResId() {
        return R.string.localrecs_remove;
    }

    @Override
    public void onClicked(Video item, VideoMenuCallback callback) {
        Context context = getContext();

        if (context == null) {
            return;
        }

        // Writes to disk, so keep it off the main thread
        RxHelper.runAsync(() -> LocalRecsEngine.instance(context).removeFromRow(item));

        if (callback != null) {
            callback.onItemAction(item, VideoMenuCallback.ACTION_REMOVE);
        }

        AppDialogPresenter.instance(context).closeDialog();
        MessageHelpers.showMessage(context, R.string.localrecs_removed);
    }

    @Override
    public boolean isEnabled(Video item) {
        return item != null && item.videoId != null && item.getGroup() != null
                && item.getGroup().getMediaGroup() instanceof LocalRecsMediaGroup;
    }

    @Override
    public int getMenuType() {
        return MENU_TYPE_VIDEO;
    }
}
