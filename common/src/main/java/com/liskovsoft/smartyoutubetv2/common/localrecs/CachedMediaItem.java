package com.liskovsoft.smartyoutubetv2.common.localrecs;

import com.liskovsoft.mediaserviceinterfaces.data.FeedbackEndpoint;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.localrecs.cache.CachedRow;

/**
 * A video restored from the cached row. Only holds what a card needs; the player loads the rest
 * from YouTube by video id, just like it does for any other card.
 */
public class CachedMediaItem implements MediaItem {
    private final CachedRow.Item mItem;

    public CachedMediaItem(CachedRow.Item item) {
        mItem = item;
    }

    public static CachedRow.Item toCacheItem(MediaItem item) {
        CachedRow.Item result = new CachedRow.Item();
        result.videoId = item.getVideoId();
        result.channelId = item.getChannelId();
        result.title = item.getTitle();
        result.author = item.getAuthor();
        result.cardImageUrl = item.getCardImageUrl();
        result.secondTitle = item.getSecondTitle() != null ? item.getSecondTitle().toString() : null;
        result.badgeText = item.getBadgeText();
        result.durationMs = item.getDurationMs();
        result.publishedMs = item.getPublishedDate();
        return result;
    }

    @Override
    public int getType() {
        return TYPE_VIDEO;
    }

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public boolean isUpcoming() {
        return false;
    }

    @Override
    public boolean isShorts() {
        return false;
    }

    @Override
    public int getPercentWatched() {
        return 0;
    }

    @Override
    public int getStartTimeSeconds() {
        return 0;
    }

    @Override
    public String getAuthor() {
        return mItem.author;
    }

    @Override
    public String getFeedbackToken() {
        return null;
    }

    @Override
    public String getFeedbackToken2() {
        return null;
    }

    @Override
    public FeedbackEndpoint getFeedbackEndpoint() {
        return null;
    }

    @Override
    public String getPlaylistId() {
        return null;
    }

    @Override
    public int getPlaylistIndex() {
        return -1;
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
    public boolean hasNewContent() {
        return false;
    }

    @Override
    public int getId() {
        return mItem.videoId.hashCode();
    }

    @Override
    public String getTitle() {
        return mItem.title;
    }

    @Override
    public CharSequence getSecondTitle() {
        return mItem.secondTitle;
    }

    @Override
    public String getVideoId() {
        return mItem.videoId;
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public long getDurationMs() {
        return mItem.durationMs;
    }

    @Override
    public String getBadgeText() {
        return mItem.badgeText;
    }

    @Override
    public String getProductionDate() {
        return null;
    }

    @Override
    public long getPublishedDate() {
        return mItem.publishedMs;
    }

    @Override
    public String getCardImageUrl() {
        return mItem.cardImageUrl;
    }

    @Override
    public String getBackgroundImageUrl() {
        return mItem.cardImageUrl;
    }

    @Override
    public int getWidth() {
        return 0;
    }

    @Override
    public int getHeight() {
        return 0;
    }

    @Override
    public String getChannelId() {
        return mItem.channelId;
    }

    @Override
    public String getVideoPreviewUrl() {
        return null;
    }

    @Override
    public String getAudioChannelConfig() {
        return null;
    }

    @Override
    public String getPurchasePrice() {
        return null;
    }

    @Override
    public String getRentalPrice() {
        return null;
    }

    @Override
    public int getRatingStyle() {
        return 0;
    }

    @Override
    public double getRatingScore() {
        return 0;
    }

    @Override
    public boolean isMovie() {
        return false;
    }

    @Override
    public boolean hasUploads() {
        return false;
    }

    @Override
    public String getClickTrackingParams() {
        return null;
    }

    @Override
    public String getSearchQuery() {
        return null;
    }
}
