package com.liskovsoft.smartyoutubetv2.common.localrecs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Pair;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.SignInService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.localrecs.cache.CachePolicy;
import com.liskovsoft.smartyoutubetv2.common.localrecs.cache.CachedRow;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Blocklist;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Candidate;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.CandidateFilter;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.ChannelAffinity;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.HeuristicRanker;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Ranker;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Recommender;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.RecsConfig;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Seed;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * Builds the "For You (Local)" Home row from the user's own history, subscriptions and YouTube's
 * related videos, ranked on the device.
 *
 * Home never waits on a network refresh: the cached row is shown at once and refreshed in the
 * background when older than the TTL. Only the very first run (no cache yet) waits, and only for a
 * few seconds. Signed-out users get no row, as there is no history to work from.
 */
public class LocalRecsEngine {
    private static final String TAG = LocalRecsEngine.class.getSimpleName();
    private static final long CALL_TIMEOUT_MS = 10_000;
    private static final long REFRESH_TIMEOUT_MS = 60_000;
    private static final long FIRST_RUN_WAIT_MS = 6_000;
    /** Don't hammer YouTube when refreshes keep producing nothing (e.g. empty history). */
    private static final long MIN_REFRESH_INTERVAL_MS = 10 * 60 * 1000;
    private static final int MAX_CALLS_PER_REFRESH = 12;
    /** RSS feeds for a batch are fetched in parallel by the service, so this bounds concurrency too. */
    private static final int RSS_BATCH_SIZE = 4;
    private static final int METADATA_CONCURRENCY = 2;
    private static final int MAX_SUGGESTIONS_PER_SEED = 40;
    @SuppressLint("StaticFieldLeak")
    private static LocalRecsEngine sInstance;
    private final Context mContext;
    private final LocalRecsData mData;
    private final RecsConfig mConfig = new RecsConfig();
    private final Ranker mRanker = new HeuristicRanker();
    private Observable<List<MediaGroup>> mRefresh;
    private long mLastRefreshStartMs;

    private LocalRecsEngine(Context context) {
        mContext = context.getApplicationContext();
        mData = LocalRecsData.instance(context);
    }

    public static synchronized LocalRecsEngine instance(Context context) {
        if (sInstance == null) {
            sInstance = new LocalRecsEngine(context);
        }

        return sInstance;
    }

    /**
     * Rows to show before YouTube's Home rows. Emits at most once, on the main thread, and never errors.
     * Settings are read on subscribe, so toggling the feature takes effect on the next Home load.
     */
    public static Observable<List<MediaGroup>> observe(Context context) {
        LocalRecsEngine engine = instance(context);

        return Observable.defer(() -> engine.mData.isEnabled() ? Observable.fromCallable(engine::loadForDisplay) : Observable.<List<MediaGroup>>empty())
                .subscribeOn(Schedulers.io())
                .filter(groups -> !groups.isEmpty())
                .onErrorResumeNext(error -> {
                    Log.e(TAG, "Local row failed, Home continues without it: %s", error);
                    return Observable.empty();
                })
                .observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * Recompute the row now (e.g. from settings). Result goes to the cache.
     */
    public void refreshNow() {
        mLastRefreshStartMs = 0;
        startRefresh();
    }

    private List<MediaGroup> loadForDisplay() {
        if (!isSignedIn() || !isOnline()) {
            return Collections.emptyList();
        }

        String owner = getOwner();
        CachePolicy policy = new CachePolicy(mData.getTtlMs());
        CachedRow cached = CachedRow.decode(mData.getCache());

        if (policy.isUsable(cached, owner)) {
            if (policy.needsRefresh(cached, owner, System.currentTimeMillis())) {
                startRefresh();
            }

            return fromCache(cached);
        }

        // First run: give the refresh a short head start, then let Home carry on regardless.
        // The refresh keeps going in the background and fills the cache for next time.
        List<MediaGroup> result = startRefresh()
                .timeout(FIRST_RUN_WAIT_MS, TimeUnit.MILLISECONDS)
                .onErrorReturnItem(Collections.emptyList())
                .blockingFirst(Collections.emptyList());

        return result;
    }

    private synchronized Observable<List<MediaGroup>> startRefresh() {
        if (mRefresh != null) {
            return mRefresh;
        }

        long now = System.currentTimeMillis();

        if (now - mLastRefreshStartMs < MIN_REFRESH_INTERVAL_MS) {
            return Observable.empty();
        }

        mLastRefreshStartMs = now;

        Observable<List<MediaGroup>> refresh = Observable.fromCallable(this::computeAndStore)
                .subscribeOn(Schedulers.io())
                .timeout(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .doOnError(error -> Log.e(TAG, "Refresh failed: %s", error))
                .onErrorReturnItem(Collections.emptyList())
                .doFinally(this::onRefreshDone)
                .cache();

        mRefresh = refresh;
        // Runs to completion even if whoever asked stops waiting
        refresh.subscribe(groups -> {}, error -> {});

        return refresh;
    }

    private synchronized void onRefreshDone() {
        mRefresh = null;
    }

    private List<MediaGroup> computeAndStore() {
        long startMs = System.currentTimeMillis();
        String owner = getOwner();
        List<MediaItem> row = compute();

        if (row.isEmpty()) {
            Log.d(TAG, "Nothing to recommend yet");
            return Collections.emptyList();
        }

        List<CachedRow.Item> items = new ArrayList<>();
        for (MediaItem item : row) {
            items.add(CachedMediaItem.toCacheItem(item));
        }
        mData.setCache(new CachedRow(System.currentTimeMillis(), owner, items).encode());

        Log.d(TAG, "Refreshed %s items in %s ms", row.size(), System.currentTimeMillis() - startMs);

        return Collections.singletonList(new LocalRecsMediaGroup(mContext.getString(R.string.localrecs_title), row));
    }

    private List<MediaItem> compute() {
        ContentService contentService = YouTubeServiceManager.instance().getContentService();
        MediaItemService itemService = YouTubeServiceManager.instance().getMediaItemService();
        AtomicInteger budget = new AtomicInteger(MAX_CALLS_PER_REFRESH);
        VideoStateService stateService = VideoStateService.instance(mContext);

        // 1. Seeds from history
        MediaGroup history = call(budget, contentService::getHistory);
        List<MediaItem> historyItems = videosOf(history);
        Set<String> watchedIds = new HashSet<>();
        List<Seed> seeds = new ArrayList<>();

        for (MediaItem item : historyItems) {
            watchedIds.add(item.getVideoId());

            if (seeds.size() < mConfig.maxSeeds) {
                seeds.add(new Seed(item.getVideoId(), item.getChannelId(), item.getAuthor(), completionOf(item, stateService)));
            }
        }

        watchedIds.addAll(localWatchedIds(stateService));

        // 2. Channel affinity, with subscriptions as a weaker signal
        MediaGroup subscriptions = call(budget, contentService::getSubscribedChannelsByLastViewed);
        List<String> subscribedIds = new ArrayList<>();
        if (subscriptions != null && subscriptions.getMediaItems() != null) {
            for (MediaItem channel : subscriptions.getMediaItems()) {
                if (channel != null && channel.getChannelId() != null) {
                    subscribedIds.add(channel.getChannelId());
                }
            }
        }

        if (seeds.isEmpty() && subscribedIds.isEmpty()) {
            return Collections.emptyList();
        }

        ChannelAffinity affinity = ChannelAffinity.compute(seeds, subscribedIds, mConfig);

        // 3. Candidates: latest uploads from top channels, related videos for the strongest seeds
        List<Candidate> candidates = new ArrayList<>();
        List<String> topChannels = affinity.topChannelIds(mConfig.rssChannels);

        for (int i = 0; i < topChannels.size(); i += RSS_BATCH_SIZE) {
            String[] batch = topChannels.subList(i, Math.min(i + RSS_BATCH_SIZE, topChannels.size())).toArray(new String[0]);
            for (MediaItem item : videosOf(call(budget, () -> contentService.getRssFeed(batch)))) {
                Candidate candidate = toCandidate(item);
                candidate.fromRss = true;
                candidates.add(candidate);
            }
        }

        for (Pair<Seed, MediaItemMetadata> result : fetchRelated(itemService, strongestSeeds(seeds), budget)) {
            int added = 0;
            for (MediaGroup group : nonNull(result.second.getSuggestions())) {
                for (MediaItem item : videosOf(group)) {
                    if (added++ >= MAX_SUGGESTIONS_PER_SEED) {
                        break;
                    }
                    Candidate candidate = toCandidate(item);
                    candidate.relatedSeedIds.add(result.first.videoId);
                    candidates.add(candidate);
                }
            }
        }

        // 4-7. Filter, score, diversify
        CandidateFilter filter = new CandidateFilter(watchedIds, createBlocklist(), mConfig);
        List<Candidate> ranked = new Recommender(mRanker, mConfig)
                .recommend(seeds, affinity, candidates, filter, System.currentTimeMillis());

        List<MediaItem> result = new ArrayList<>();
        for (Candidate candidate : ranked) {
            result.add((MediaItem) candidate.payload);
        }

        return result;
    }

    private List<Seed> strongestSeeds(List<Seed> seeds) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            order.add(i);
        }

        Collections.sort(order, (a, b) -> Double.compare(
                Seed.weight(b, seeds.get(b).completion, mConfig),
                Seed.weight(a, seeds.get(a).completion, mConfig)));

        List<Seed> result = new ArrayList<>();
        for (int i = 0; i < Math.min(mConfig.relatedSeeds, order.size()); i++) {
            result.add(seeds.get(order.get(i)));
        }

        return result;
    }

    private List<Pair<Seed, MediaItemMetadata>> fetchRelated(MediaItemService itemService, List<Seed> seeds, AtomicInteger budget) {
        try {
            return Observable.fromIterable(seeds)
                    .flatMap(seed -> Observable.fromCallable(() -> {
                                MediaItemMetadata metadata = call(budget, () -> itemService.getMetadata(seed.videoId));
                                return metadata != null ? Collections.singletonList(new Pair<>(seed, metadata)) : Collections.<Pair<Seed, MediaItemMetadata>>emptyList();
                            })
                            .subscribeOn(Schedulers.io())
                            .flatMapIterable(list -> list),
                            METADATA_CONCURRENCY)
                    .toList()
                    .blockingGet();
        } catch (Throwable e) {
            Log.e(TAG, "Related videos failed: %s", e);
            return Collections.emptyList();
        }
    }

    /**
     * One network call, counted against the budget, with a timeout. Returns null on any failure.
     */
    private static <T> T call(AtomicInteger budget, Callable<T> callable) {
        if (budget.getAndDecrement() <= 0) {
            Log.w(TAG, "Request budget used up, skipping a call");
            return null;
        }

        try {
            return Observable.fromCallable(callable)
                    .subscribeOn(Schedulers.io())
                    .timeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .blockingFirst();
        } catch (Throwable e) {
            // Includes the NPE RxJava throws when the service returns null
            Log.e(TAG, "Call failed: %s", e);
            return null;
        }
    }

    private Blocklist createBlocklist() {
        Blocklist blocklist = new Blocklist().addKeywords(Blocklist.parseKeywords(mData.getBlockedKeywords()));
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (Pair<String, String> channel : BlockedChannelData.instance(mContext).getChannelIdsWithNames()) {
            ids.add(channel.first);
            names.add(channel.second);
        }

        return blocklist.addChannelIds(ids).addChannelNames(names);
    }

    /**
     * Cached items, minus anything watched or blocked since the row was built.
     */
    private List<MediaGroup> fromCache(CachedRow cached) {
        Set<String> watchedIds = localWatchedIds(VideoStateService.instance(mContext));
        CandidateFilter filter = new CandidateFilter(watchedIds, createBlocklist(), mConfig);
        List<MediaItem> items = new ArrayList<>();

        for (CachedRow.Item item : cached.items) {
            CachedMediaItem mediaItem = new CachedMediaItem(item);
            if (filter.isAllowed(toCandidate(mediaItem))) {
                items.add(mediaItem);
            }
        }

        if (items.size() < mConfig.minItems) {
            return Collections.emptyList();
        }

        return Collections.singletonList(new LocalRecsMediaGroup(mContext.getString(R.string.localrecs_title), items));
    }

    private static Set<String> localWatchedIds(VideoStateService stateService) {
        Set<String> result = new HashSet<>();

        if (stateService == null) {
            return result;
        }

        for (VideoStateService.State state : new ArrayList<>(stateService.getStates())) {
            if (state != null && state.video != null && state.video.videoId != null) {
                result.add(state.video.videoId);
            }
        }

        return result;
    }

    private static Candidate toCandidate(MediaItem item) {
        Candidate candidate = new Candidate(item.getVideoId(), item.getChannelId(), item.getAuthor(), item.getTitle());
        candidate.publishedMs = item.getPublishedDate();
        candidate.durationMs = item.getDurationMs();
        candidate.isLive = item.isLive();
        candidate.isUpcoming = item.isUpcoming();
        candidate.isShorts = item.isShorts();
        candidate.percentWatched = item.getPercentWatched();
        candidate.payload = item;
        return candidate;
    }

    /**
     * Local progress wins over YouTube's, as it is exact. Negative means unknown.
     */
    private static double completionOf(MediaItem item, VideoStateService stateService) {
        VideoStateService.State state = stateService != null ? stateService.getByVideoId(item.getVideoId()) : null;

        if (state != null && state.durationMs > 0) {
            return Math.min(1.0, (double) state.positionMs / state.durationMs);
        }

        if (item.getPercentWatched() > 0) {
            return item.getPercentWatched() / 100.0;
        }

        return -1;
    }

    private static List<MediaItem> videosOf(MediaGroup group) {
        List<MediaItem> result = new ArrayList<>();

        if (group == null || group.getMediaItems() == null) {
            return result;
        }

        for (MediaItem item : group.getMediaItems()) {
            if (item != null && item.getVideoId() != null && item.getType() != MediaItem.TYPE_CHANNEL
                    && item.getType() != MediaItem.TYPE_PLAYLIST) {
                result.add(item);
            }
        }

        return result;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }

    private static boolean isSignedIn() {
        SignInService signInService = YouTubeServiceManager.instance().getSignInService();
        return signInService != null && signInService.isSigned();
    }

    private static String getOwner() {
        SignInService signInService = YouTubeServiceManager.instance().getSignInService();
        Account account = signInService != null ? signInService.getSelectedAccount() : null;

        if (account == null) {
            return "";
        }

        // Only used to tell profiles apart, so a hash is enough
        return String.valueOf(Arrays.hashCode(new Object[] {account.getEmail(), account.getName()}));
    }

    private boolean isOnline() {
        try {
            ConnectivityManager manager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = manager != null ? manager.getActiveNetworkInfo() : null;
            return info != null && info.isConnected();
        } catch (SecurityException e) {
            return true; // can't tell, assume online
        }
    }
}
