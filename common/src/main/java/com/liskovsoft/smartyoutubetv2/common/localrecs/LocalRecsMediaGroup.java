package com.liskovsoft.smartyoutubetv2.common.localrecs;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The "For You (Local)" row. It has no next page, so the browse screen never tries to continue it.
 */
public class LocalRecsMediaGroup implements MediaGroup {
    private final String mTitle;
    private final List<MediaItem> mItems;

    public LocalRecsMediaGroup(String title, List<MediaItem> items) {
        mTitle = title;
        mItems = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    @Override
    public int getType() {
        return TYPE_HOME;
    }

    @Nullable
    @Override
    public List<MediaItem> getMediaItems() {
        return mItems;
    }

    @Override
    public String getTitle() {
        return mTitle;
    }

    @Override
    public String getChannelId() {
        return null;
    }

    @Override
    public String getParams() {
        return null;
    }

    @Override
    public String getReloadPageKey() {
        return null;
    }

    @Override
    public String getNextPageKey() {
        return null;
    }

    @Override
    public String getChannelUrl() {
        return null;
    }

    @Override
    public boolean isEmpty() {
        return mItems.isEmpty();
    }
}
