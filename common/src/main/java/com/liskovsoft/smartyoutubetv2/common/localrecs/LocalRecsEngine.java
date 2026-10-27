package com.liskovsoft.smartyoutubetv2.common.localrecs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.TextUtils;
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
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ai.JevClient;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ai.LeapGenerator;
import com.liskovsoft.smartyoutubetv2.common.localrecs.cache.CachePolicy;
import com.liskovsoft.smartyoutubetv2.common.localrecs.cache.CachedRow;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Blocklist;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Candidate;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.CandidateFilter;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.ChannelAffinity;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.ChannelDiscovery;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.ChannelResolver;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Feedback;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.HeuristicRanker;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Judgment;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Leap;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.LeftFieldAssembler;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Outcomes;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Ranker;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.RankingContext;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Recommender;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.RecsConfig;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.RowAssembler;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.ScoredCandidate;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Seed;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.ViewCounts;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final long REFRESH_TIMEOUT_MS = 180_000;
    /** How long Home waits for the quick first pass when nothing is saved yet. */
    private static final long QUICK_PASS_WAIT_MS = 7_000;
    private static final int QUICK_PASS_CALLS = 8;
    private static final int QUICK_PASS_SEEDS = 6;
    /** Don't hammer YouTube when refreshes keep producing nothing (e.g. empty history). */
    private static final long MIN_REFRESH_INTERVAL_MS = 10 * 60 * 1000;
    private static final int MAX_CALLS_PER_REFRESH = 45;
    /** RSS feeds for a batch are fetched in parallel by the service, so this bounds concurrency too. */
    private static final int RSS_BATCH_SIZE = 4;
    private static final int METADATA_CONCURRENCY = 3;
    private static final int MAX_SUGGESTIONS_PER_SEED = 40;
    /** Paid films and shows sometimes turn up in related videos. */
    private static final String YOUTUBE_MOVIES_CHANNEL_ID = "UCuVPpxrm2VAgpH3Ktln4HXg";
    /** Jev judges the best this many main-row candidates; the rest are dropped when it's on. */
    private static final int JEV_MAIN_LIMIT = 150;
    private static final int LEFT_FIELD_MAX = 20;
    /** Below this the Left Field row is hidden (and the browse screen won't try to "continue" it). */
    private static final int LEFT_FIELD_MIN = 6;
    private static final int LEFT_FIELD_PER_LEAP = 1;
    private static final int LEAPS_SEARCHED = 20;
    private static final int SEARCH_RESULTS_PER_LEAP = 6;
    private static final long LEAPS_TTL_MS = 24 * 60 * 60 * 1000L;
    /** Don't ask Claude again sooner than this after a failed or recent attempt. */
    private static final long LEAP_RETRY_MS = 6 * 60 * 60 * 1000L;
    /** Shown in this many refreshes without being played: treated as a pass and dropped. */
    private static final int IGNORED_AFTER_SHOWS = 4;
    private static final int EXAMPLES = 15;
    private static final long DISPLAY_RECORD_INTERVAL_MS = 2 * 60 * 60 * 1000L;
    @SuppressLint("StaticFieldLeak")
    private static LocalRecsEngine sInstance;
    private final java.util.concurrent.atomic.AtomicBoolean mGeneratingLeaps = new java.util.concurrent.atomic.AtomicBoolean();
    private long mLastLeapAttemptMs;
    private boolean mForceNewLeaps;
    private boolean mRefreshPending;
    private final Context mContext;
    private final LocalRecsData mData;
    private final RecsConfig mConfig = new RecsConfig();
    private final Ranker mRanker = new HeuristicRanker();
    private Observable<List<MediaGroup>> mRefresh;
    private long mLastRefreshStartMs;
    /** How the last quick first pass went, for the diagnostics. */
    private volatile String mQuickPassNote = "not run";
    private volatile String mQuickPassRunNote = "";
    private long mLastDisplayRecordMs;

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
        LocalRecsMenuProvider.enableByDefault(context);

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

    /**
     * The user removed this video from the row. Remember it, and what led to it, for the next refresh.
     */
    public synchronized void removeFromRow(Video video) {
        if (video == null || video.videoId == null) {
            return;
        }

        // Keep the saved rows in step with what's on screen
        CachedRow.Item cachedItem = removeCached(video.videoId, mData.getCache(), false);
        CachedRow.Item leftFieldItem = removeCached(video.videoId, mData.getLeftFieldCache(), true);
        if (cachedItem == null) {
            cachedItem = leftFieldItem;
        }

        String channelName = video.getAuthor();
        String channelId = video.channelId != null ? video.channelId : cachedItem != null ? cachedItem.channelId : null;
        // For Left Field videos this holds "leap:<id>", which is how a removal counts against that idea
        List<String> seedIds = cachedItem != null && cachedItem.seedIds != null ?
                Arrays.asList(cachedItem.seedIds.split(",")) : Collections.emptyList();

        Feedback feedback = loadFeedback();
        feedback.addRemoval(new Feedback.Removal(video.videoId, channelId, channelName, seedIds, video.getTitle()));
        mData.setFeedback(feedback.encode());

        Log.d(TAG, "Removed %s (%s), %s removals stored", video.videoId, channelName, feedback.size());
    }

    /**
     * Drops the video from one saved row and returns its saved entry, or null if it wasn't there.
     */
    private CachedRow.Item removeCached(String videoId, String data, boolean leftField) {
        CachedRow cached = CachedRow.decode(data);
        if (cached == null) {
            return null;
        }

        CachedRow.Item found = null;
        List<CachedRow.Item> remaining = new ArrayList<>();
        for (CachedRow.Item item : cached.items) {
            if (videoId.equals(item.videoId)) {
                found = item;
            } else {
                remaining.add(item);
            }
        }

        if (found != null) {
            String encoded = new CachedRow(cached.savedAtMs, cached.owner, remaining).encode();
            if (leftField) {
                mData.setLeftFieldCache(encoded);
            } else {
                mData.setCache(encoded);
            }
        }

        return found;
    }

    public int getRemovalCount() {
        return loadFeedback().size();
    }

    public void forgetRemovals() {
        mData.setFeedback("");
    }

    private Feedback loadFeedback() {
        return Feedback.decode(mData.getFeedback(), mConfig);
    }

    /**
     * Ask Claude for fresh Left Field ideas on the next refresh, and start that refresh now.
     */
    public void requestNewLeaps() {
        mForceNewLeaps = true;
        mLastLeapAttemptMs = 0;
        refreshNow();
    }

    /**
     * Current Left Field ideas, for the settings screen.
     */
    public List<Leap> getCurrentLeaps() {
        Leap.Batch batch = Leap.Batch.decode(mData.getLeaps());
        return batch != null ? batch.leaps : Collections.<Leap>emptyList();
    }

    private List<MediaGroup> loadForDisplay() {
        mData.importKeysFile(mContext);

        if (!isSignedIn() || !isOnline()) {
            return Collections.emptyList();
        }

        String owner = getOwner();
        CachePolicy policy = new CachePolicy(mData.getTtlMs());
        CachedRow cached = CachedRow.decode(mData.getCache());

        if (policy.isUsable(cached, owner)) {
            List<MediaGroup> rows = fromCache(cached);
            recordDisplayed(rows);

            // Also refresh when removals or newly watched videos have left too few items to show
            if (rows.isEmpty() || policy.needsRefresh(cached, owner, System.currentTimeMillis())) {
                startRefresh();
            }

            return rows;
        }

        // Nothing saved yet (first launch, or a new account or mode). Rows never pop in after Home has
        // loaded, so run a quick pass now and let our row arrive together with YouTube's. If even that
        // is too slow, Home carries on without it. The full refresh follows in the background for next time.
        long quickStartMs = System.currentTimeMillis();
        mQuickPassRunNote = "still running";
        // Runs to the end even if Home stops waiting; the full refresh starts once it's done
        Observable<List<MediaGroup>> quickPass = Observable.fromCallable(() -> computeAndStore(true))
                .subscribeOn(Schedulers.io())
                .onErrorReturnItem(Collections.<MediaGroup>emptyList())
                .doFinally(this::refreshNow)
                .cache();
        quickPass.subscribe(rows -> {}, error -> {});

        List<MediaGroup> result = quickPass
                .timeout(QUICK_PASS_WAIT_MS, TimeUnit.MILLISECONDS)
                .onErrorReturnItem(Collections.<MediaGroup>emptyList())
                .blockingFirst(Collections.<MediaGroup>emptyList());
        mQuickPassNote = (result.isEmpty() ? "no row for Home" : result.get(0).getMediaItems().size() + " videos shown with Home")
                + " after " + (System.currentTimeMillis() - quickStartMs) + " ms";

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

        Observable<List<MediaGroup>> refresh = Observable.fromCallable(() -> computeAndStore(false))
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

        if (mRefreshPending) {
            mRefreshPending = false;
            mLastRefreshStartMs = 0;
            Schedulers.io().scheduleDirect(this::startRefresh);
        }
    }

    /**
     * Refresh now, or straight after the refresh that's running.
     */
    private synchronized void refreshSoon() {
        if (mRefresh != null) {
            mRefreshPending = true;
        } else {
            refreshNow();
        }
    }

    /**
     * Builds the rows and saves them. They're shown the next time Home loads, never swapped in while
     * you're looking at it.
     */
    private List<MediaGroup> computeAndStore(boolean quick) {
        long startMs = System.currentTimeMillis();
        String owner = getOwner();
        Rows result = compute(quick);

        if (result.main.isEmpty()) {
            Log.d(TAG, "Nothing to recommend yet");
            if (quick) {
                mQuickPassRunNote = "finished in " + (System.currentTimeMillis() - startMs) + " ms with too few videos";
            }
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        List<MediaItem> main = toItems(result.main);
        mData.setCache(new CachedRow(now, owner, toCacheItems(result.main)).encode());

        List<MediaGroup> rows = new ArrayList<>();
        rows.add(new LocalRecsMediaGroup(mContext.getString(R.string.localrecs_title), main));

        // A thin Left Field result keeps the previous row rather than making it disappear
        if (result.leftField.size() >= LEFT_FIELD_MIN) {
            mData.setLeftFieldCache(new CachedRow(now, owner, toCacheItems(result.leftField)).encode());
            rows.add(new LocalRecsMediaGroup(mContext.getString(R.string.localrecs_left_field_title), toItems(result.leftField)));
        }

        Log.d(TAG, "Refreshed %s + %s items in %s ms%s", main.size(), result.leftField.size(), System.currentTimeMillis() - startMs, quick ? " (quick pass)" : "");
        if (quick) {
            mQuickPassRunNote = "finished in " + (System.currentTimeMillis() - startMs) + " ms with " + main.size() + " videos";
        }

        return rows;
    }

    /**
     * Count the videos Home is showing as seen, at most once every couple of hours, so a video you
     * keep scrolling past eventually makes way. Refreshes don't count, only what reaches the screen.
     */
    private synchronized void recordDisplayed(List<MediaGroup> rows) {
        long now = System.currentTimeMillis();
        if (rows.isEmpty() || now - mLastDisplayRecordMs < DISPLAY_RECORD_INTERVAL_MS) {
            return;
        }
        mLastDisplayRecordMs = now;

        Outcomes outcomes = Outcomes.decode(mData.getOutcomes());
        for (MediaGroup group : rows) {
            for (MediaItem item : nonNull(group.getMediaItems())) {
                String leapId = null;
                if (item instanceof CachedMediaItem) {
                    String links = ((CachedMediaItem) item).getCacheItem().seedIds;
                    for (String link : links != null ? links.split(",") : new String[0]) {
                        if (link.startsWith(Feedback.LEAP_PREFIX)) {
                            leapId = link.substring(Feedback.LEAP_PREFIX.length());
                        }
                    }
                }
                outcomes.recordShown(item.getVideoId(), item.getTitle(), channelName(item), leapId);
            }
        }
        mData.setOutcomes(outcomes.encode());
    }

    private synchronized void savePlayback(Map<String, Double> playback) {
        Outcomes outcomes = Outcomes.decode(mData.getOutcomes());
        outcomes.recordPlayback(playback);
        mData.setOutcomes(outcomes.encode());
    }

    private static class Rows {
        List<Candidate> main = Collections.emptyList();
        List<Candidate> leftField = Collections.emptyList();
    }

    private static List<MediaItem> toItems(List<Candidate> candidates) {
        List<MediaItem> items = new ArrayList<>();
        for (Candidate candidate : candidates) {
            items.add((MediaItem) candidate.payload);
        }
        return items;
    }

    private static List<CachedRow.Item> toCacheItems(List<Candidate> candidates) {
        List<CachedRow.Item> items = new ArrayList<>();
        for (Candidate candidate : candidates) {
            CachedRow.Item item = CachedMediaItem.toCacheItem((MediaItem) candidate.payload);
            List<String> links = new ArrayList<>(candidate.relatedSeedIds);
            if (candidate.leapId != null) {
                links.add(Feedback.LEAP_PREFIX + candidate.leapId);
            }
            item.seedIds = TextUtils.join(",", links);
            items.add(item);
        }
        return items;
    }

    /**
     * @param quick a fast first pass for when nothing is saved yet: history, subscriptions and related
     *              videos for a few watches only. No second hop, uploads, models or Left Field.
     */
    private Rows compute(boolean quick) {
        ContentService contentService = YouTubeServiceManager.instance().getContentService();
        MediaItemService itemService = YouTubeServiceManager.instance().getMediaItemService();
        AtomicInteger budget = new AtomicInteger(quick ? QUICK_PASS_CALLS : MAX_CALLS_PER_REFRESH);
        VideoStateService stateService = VideoStateService.instance(mContext);
        ChannelResolver resolver = new ChannelResolver();
        mConfig.newChannelsOnly = mData.isNewChannelsOnly();
        mConfig.maxDurationMs = mData.getMaxDurationMs();
        Feedback feedback = loadFeedback();

        // Fetch everything first, so channel ids seen on one item can fill gaps on another (see ChannelResolver)
        MediaGroup historyPage = call(budget, contentService::getHistory);
        List<MediaItem> historyItems = videosOf(historyPage);
        // A second page widens the set of channels we treat as already watched
        MediaGroup historyPage2 = historyPage != null && !quick ? call(budget, () -> contentService.continueGroup(historyPage)) : null;
        List<MediaItem> olderHistoryItems = videosOf(historyPage2);
        MediaGroup subscriptions = call(budget, contentService::getSubscribedChannelsByLastViewed);
        List<MediaGroup> home = quick ? null : call(budget, contentService::getHome);

        if (historyItems.isEmpty()) {
            return new Rows();
        }

        // The first history page is often short, so seeds can come from the second page too
        List<MediaItem> allHistory = new ArrayList<>(historyItems);
        allHistory.addAll(olderHistoryItems);

        // What happened to videos the rows showed before: finished, abandoned or passed over
        Outcomes outcomes = Outcomes.decode(mData.getOutcomes());
        outcomes.recordPlayback(playbackOf(stateService, allHistory));

        // Related videos only need seed video ids, so fetch them before resolving channels
        List<Seed> rawSeeds = new ArrayList<>();
        for (MediaItem item : allHistory) {
            if (rawSeeds.size() >= mConfig.maxSeeds) {
                break;
            }
            Seed seed = new Seed(item.getVideoId(), null, null, completionOf(item, stateService));
            seed.feedbackFactor = feedback.seedMultiplier(seed.videoId);
            rawSeeds.add(seed);
        }
        List<Pair<Seed, MediaItemMetadata>> related = fetchRelated(itemService, strongestSeeds(rawSeeds, quick ? QUICK_PASS_SEEDS : mConfig.relatedSeeds), budget);

        learnChannels(resolver, historyItems, olderHistoryItems, subscriptions, home, related);

        // 1. Seeds, and everything watched or already on YouTube's Home
        Set<String> excludedIds = new HashSet<>(localWatchedIds(stateService));
        Set<String> knownChannels = new HashSet<>();
        List<Seed> seeds = new ArrayList<>();

        for (MediaItem item : allHistory) {
            String name = channelName(item);
            String channelId = resolver.resolveId(item.getChannelId(), name);
            excludedIds.add(item.getVideoId());
            ChannelAffinity.addKeys(knownChannels, channelId, name);

            if (seeds.size() < mConfig.maxSeeds) {
                Seed seed = new Seed(item.getVideoId(), channelId, name, completionOf(item, stateService));
                seed.title = item.getTitle();
                seed.feedbackFactor = feedback.seedMultiplier(seed.videoId);
                seeds.add(seed);
            }
        }

        addLocalChannels(knownChannels, stateService, resolver);

        for (MediaGroup group : nonNull(home)) {
            for (MediaItem item : videosOf(group)) {
                excludedIds.add(item.getVideoId());
            }
        }

        // 2. Channel affinity, with subscriptions as a weaker signal
        // The TV subscriptions list has channel names but no ids, so match by name and borrow ids where we can
        List<String> subscribedKeys = new ArrayList<>();
        for (MediaItem channel : itemsOf(subscriptions)) {
            String channelId = resolver.resolveId(channel.getChannelId(), channel.getTitle());
            String key = ChannelAffinity.keyOf(channelId, channel.getTitle());

            if (key != null) {
                subscribedKeys.add(key);
                ChannelAffinity.addKeys(knownChannels, channelId, channel.getTitle());
            }
        }

        ChannelAffinity affinity = ChannelAffinity.compute(seeds, subscribedKeys, mConfig);

        // 3. Related videos. New channels that keep turning up here are the discoveries.
        List<Candidate> candidates = new ArrayList<>();

        for (Pair<Seed, MediaItemMetadata> result : related) {
            int added = 0;
            for (MediaGroup group : nonNull(result.second.getSuggestions())) {
                for (MediaItem item : videosOf(group)) {
                    if (added++ >= MAX_SUGGESTIONS_PER_SEED) {
                        break;
                    }
                    Candidate candidate = toCandidate(item, resolver);
                    candidate.relatedSeedIds.add(result.first.videoId);
                    candidates.add(candidate);
                }
            }
        }

        // Channels the user keeps removing are never "discovered" again
        Set<String> removedChannels = feedback.blockedChannelKeys();
        Set<String> skipForDiscovery = new HashSet<>(knownChannels);
        skipForDiscovery.addAll(removedChannels);
        ChannelAffinity discovery = ChannelDiscovery.compute(seeds, candidates, skipForDiscovery, mConfig);

        if (!quick) {
            expandSecondHop(itemService, discovery, candidates, resolver, budget);
            discovery = ChannelDiscovery.compute(seeds, candidates, skipForDiscovery, mConfig);

            // Latest uploads from the best new channels
            addRssCandidates(contentService, discoveredChannelIds(discovery, resolver), candidates, budget, resolver);
        }

        // Plus from favourite channels when those are allowed
        if (!quick && !mConfig.newChannelsOnly) {
            addRssCandidates(contentService, affinity.topChannelIds(mConfig.rssChannels), candidates, budget, resolver);
        }

        // Now that more ids are known, give every candidate one consistent channel key for the per-channel caps
        for (Candidate candidate : candidates) {
            if (candidate.channelId == null) {
                candidate.channelId = resolver.resolveId(null, candidate.channelName);
            }
        }

        // 4-7. Filter, score, diversify
        excludedIds.addAll(feedback.removedVideoIds());
        excludedIds.addAll(outcomes.ignoredVideoIds(IGNORED_AFTER_SHOWS));
        CandidateFilter filter = new CandidateFilter(excludedIds, createBlocklist(), mConfig)
                .excludeChannels(mConfig.newChannelsOnly ? skipForDiscovery : removedChannels);

        RankingContext context = new RankingContext(seeds, affinity, discovery, knownChannels, mConfig, System.currentTimeMillis());
        context.feedback = feedback;
        List<ScoredCandidate> scored = mRanker.score(context, filter.apply(candidates));

        // Model step 1: Jev reads the best candidates against examples of what you finish and drop
        JevClient jev = mData.isJevEnabled() && !quick ? new JevClient(mData.getTypeSafeApiKey()) : null;
        JevClient.Viewer viewer = viewerExamples(seeds, feedback, outcomes);
        Map<String, Judgment> judgments = Collections.emptyMap();

        if (jev != null) {
            List<ScoredCandidate> top = bestPerChannel(scored, JEV_MAIN_LIMIT);
            judgments = jev.judgeForMainRow(candidatesOf(top), viewer);
            // Only trust the judged set when most calls worked; otherwise rank as before
            if (judgments.size() >= top.size() / 2) {
                scored = Judgment.applyToMainRow(top, judgments);
            }
        }

        Rows rows = new Rows();
        rows.main = RowAssembler.assemble(scored, context);

        // Model step 2: Left Field, surprises from Claude's ideas, vetted by Jev
        Set<String> leftFieldSkipChannels = new HashSet<>(skipForDiscovery);
        Set<String> leftFieldExcluded = new HashSet<>(excludedIds);
        for (Candidate candidate : rows.main) {
            leftFieldExcluded.add(candidate.videoId);
            // A channel already in the main row doesn't get a second slot in Left Field
            ChannelAffinity.addKeys(leftFieldSkipChannels, candidate.channelId, candidate.channelName);
        }

        Leap.Batch leaps = null;
        if (mData.isLeftFieldEnabled() && !quick) {
            leaps = activeLeaps(feedback, outcomes);
            if (leaps != null) {
                rows.leftField = buildLeftField(contentService, jev, viewer, leaps, leftFieldExcluded, leftFieldSkipChannels, resolver);
            }
            maybeGenerateLeaps(seeds, feedback, outcomes, subscriptions, leaps);
        }

        if (quick) {
            // The full refresh straight after records what it shows; counting both would look like ignored repeats
            return rows;
        }

        // Shows are recorded when Home displays the rows (see recordDisplayed), not here
        savePlayback(playbackOf(stateService, allHistory));

        Log.d(TAG, "Seeds %s, known channel keys %s, new channels %s, candidates %s, picked %s + %s",
                seeds.size(), knownChannels.size(), discovery.keys().size(), candidates.size(), rows.main.size(), rows.leftField.size());
        writeDiagnostics(seeds, subscribedKeys, knownChannels, discovery, candidates.size(), rows.main);
        appendModelDiagnostics(judgments, viewer, leaps, rows.leftField, outcomes);

        return rows;
    }

    /**
     * Examples for the models: titles you finished and titles you removed or gave up on.
     */
    private JevClient.Viewer viewerExamples(List<Seed> seeds, Feedback feedback, Outcomes outcomes) {
        List<String> finished = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        for (Outcomes.Entry entry : outcomes.withState(Outcomes.STATE_FINISHED, EXAMPLES)) {
            addExample(finished, entry.title, entry.channelName);
        }
        for (Seed seed : seeds) {
            if (finished.size() < EXAMPLES && seed.completion >= Outcomes.FINISHED) {
                addExample(finished, seed.title, seed.channelName);
            }
        }

        for (Feedback.Removal removal : feedback.recentRemovals(EXAMPLES)) {
            addExample(dropped, removal.title, removal.channelName);
        }
        for (Outcomes.Entry entry : outcomes.withState(Outcomes.STATE_ABANDONED, EXAMPLES)) {
            if (dropped.size() < EXAMPLES) {
                addExample(dropped, entry.title, entry.channelName);
            }
        }
        for (Seed seed : seeds) {
            if (dropped.size() < EXAMPLES && seed.completion >= 0 && seed.completion < Outcomes.ABANDONED) {
                addExample(dropped, seed.title, seed.channelName);
            }
        }

        return new JevClient.Viewer(finished, dropped);
    }

    private static void addExample(List<String> examples, String title, String channel) {
        if (title == null || title.trim().isEmpty()) {
            return;
        }

        String example = channel != null ? title.trim() + " (" + channel + ")" : title.trim();
        if (!examples.contains(example)) {
            examples.add(example);
        }
    }

    /**
     * Current ideas minus the ones that have failed, or null if there are none to use.
     */
    private Leap.Batch activeLeaps(Feedback feedback, Outcomes outcomes) {
        Leap.Batch batch = Leap.Batch.decode(mData.getLeaps());
        if (batch == null) {
            return null;
        }

        Map<String, Integer> hits = outcomes.leapHits();
        Map<String, Integer> misses = feedback.leapMisses();
        List<Leap> active = new ArrayList<>();

        for (Leap leap : batch.leaps) {
            if (!Leap.hasFailed(leap.id, hits, misses)) {
                active.add(leap);
            }
        }

        return active.isEmpty() ? null : new Leap.Batch(batch.createdAtMs, active, batch.rejected);
    }

    /**
     * Search YouTube for each idea, keep only channels you've never watched, let Jev vet quality and fit,
     * then take turns between ideas.
     */
    private List<Candidate> buildLeftField(ContentService contentService, JevClient jev, JevClient.Viewer viewer, Leap.Batch leaps,
                                           Set<String> excludedIds, Set<String> skipChannels, ChannelResolver resolver) {
        if (jev == null) {
            return Collections.emptyList();
        }

        AtomicInteger budget = new AtomicInteger(LEAPS_SEARCHED);
        List<Candidate> found = new ArrayList<>();
        Map<String, Leap> leapByVideoId = new java.util.HashMap<>();
        Map<String, List<MediaGroup>> results = searchAll(contentService, leaps.leaps, budget);

        for (Leap leap : leaps.leaps) {
            int added = 0;
            for (MediaGroup group : nonNull(results.get(leap.id))) {
                for (MediaItem item : videosOf(group)) {
                    // Shorts in search results often aren't flagged, but they come without a length
                    if (added >= SEARCH_RESULTS_PER_LEAP || leapByVideoId.containsKey(item.getVideoId()) || item.getDurationMs() <= 0) {
                        continue;
                    }
                    Candidate candidate = toCandidate(item, resolver);
                    candidate.leapId = leap.id;
                    found.add(candidate);
                    leapByVideoId.put(candidate.videoId, leap);
                    added++;
                }
            }
        }

        // Surprises must come from channels you don't know, and never repeat what's watched, removed or in the main row
        List<Candidate> allowed = new CandidateFilter(excludedIds, createBlocklist(), mConfig)
                .excludeChannels(skipChannels)
                .apply(found);

        Map<String, Judgment> judgments = jev.judgeForLeftField(allowed, viewer, leapByVideoId);
        List<ScoredCandidate> scored = new ArrayList<>();
        for (Candidate candidate : allowed) {
            scored.add(new ScoredCandidate(candidate, Judgment.leftFieldScore(judgments.get(candidate.videoId))));
        }

        return LeftFieldAssembler.assemble(scored, LEFT_FIELD_PER_LEAP, LEFT_FIELD_MAX);
    }

    /**
     * One YouTube search per idea, three at a time. Results by idea id; failed searches are missing.
     */
    private Map<String, List<MediaGroup>> searchAll(ContentService contentService, List<Leap> leaps, AtomicInteger budget) {
        Map<String, List<MediaGroup>> results = new java.util.concurrent.ConcurrentHashMap<>();

        try {
            Observable.fromIterable(leaps)
                    .flatMap(leap -> Observable.fromCallable(() -> {
                                List<MediaGroup> groups = call(budget, () -> contentService.getSearch(leap.query));
                                if (groups != null) {
                                    results.put(leap.id, groups);
                                }
                                return leap.id;
                            })
                            .subscribeOn(Schedulers.io()),
                            METADATA_CONCURRENCY)
                    .toList()
                    .blockingGet();
        } catch (RuntimeException e) {
            Log.e(TAG, "Searches failed: %s", e);
        }

        return results;
    }

    /**
     * Ask Claude for new ideas in the background when they're a day old, mostly used up, or the user
     * asked. When they arrive, refresh so the row appears straight away.
     */
    private void maybeGenerateLeaps(List<Seed> seeds, Feedback feedback, Outcomes outcomes, MediaGroup subscriptions, Leap.Batch active) {
        long now = System.currentTimeMillis();
        Leap.Batch stored = Leap.Batch.decode(mData.getLeaps());
        boolean stale = stored == null || now - stored.createdAtMs >= LEAPS_TTL_MS || active == null || active.leaps.size() < LeapGenerator.IDEAS / 2;

        if (!(mForceNewLeaps || stale) || now - mLastLeapAttemptMs < LEAP_RETRY_MS || !mGeneratingLeaps.compareAndSet(false, true)) {
            return;
        }

        mForceNewLeaps = false;
        mLastLeapAttemptMs = now;

        LeapGenerator.Evidence evidence = new LeapGenerator.Evidence();
        JevClient.Viewer viewer = viewerExamples(seeds, feedback, outcomes);
        evidence.finished.addAll(viewer.finished);
        evidence.dropped.addAll(viewer.dropped);
        for (MediaItem channel : itemsOf(subscriptions)) {
            if (channel.getTitle() != null) {
                evidence.subscriptions.add(channel.getTitle().trim());
            }
        }

        // Carry forward what we've learned about earlier ideas
        Map<String, Integer> hits = outcomes.leapHits();
        Map<String, Integer> misses = feedback.leapMisses();
        List<String> rejected = new ArrayList<>(stored != null ? stored.rejected : Collections.<String>emptyList());
        if (stored != null) {
            for (Leap leap : stored.leaps) {
                if (Leap.hasLanded(leap.id, hits)) {
                    evidence.landed.add(leap.label);
                } else if (Leap.hasFailed(leap.id, hits, misses) && !rejected.contains(leap.label)) {
                    rejected.add(leap.label);
                }
            }
        }
        while (rejected.size() > 30) {
            rejected.remove(0);
        }
        evidence.rejected.addAll(rejected);

        String apiKey = mData.getAnthropicApiKey();
        Schedulers.io().scheduleDirect(() -> {
            try {
                List<Leap> leaps = new LeapGenerator(apiKey).generate(evidence, System.currentTimeMillis());

                if (leaps != null && !leaps.isEmpty()) {
                    mData.setLeaps(new Leap.Batch(System.currentTimeMillis(), leaps, rejected).encode());
                    Log.d(TAG, "Got %s new Left Field ideas", leaps.size());
                    mGeneratingLeaps.set(false);
                    refreshSoon();
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Generating ideas failed: %s", e);
            } finally {
                mGeneratingLeaps.set(false);
            }
        });
    }

    private static Map<String, Double> playbackOf(VideoStateService stateService, List<MediaItem> history) {
        Map<String, Double> result = new java.util.HashMap<>();

        for (MediaItem item : history) {
            if (item.getPercentWatched() > 0) {
                result.put(item.getVideoId(), item.getPercentWatched() / 100.0);
            }
        }

        if (stateService != null) {
            for (VideoStateService.State state : new ArrayList<>(stateService.getStates())) {
                if (state != null && state.video != null && state.video.videoId != null && state.durationMs > 0) {
                    result.put(state.video.videoId, Math.min(1.0, (double) state.positionMs / state.durationMs));
                }
            }
        }

        return result;
    }

    /**
     * The best video from each channel, up to the limit. The row only takes one per channel, so
     * judging several from the same channel would waste the limit.
     */
    private static List<ScoredCandidate> bestPerChannel(List<ScoredCandidate> scored, int limit) {
        List<ScoredCandidate> result = new ArrayList<>();
        Set<String> channels = new HashSet<>();

        for (ScoredCandidate item : best(scored, Integer.MAX_VALUE)) {
            if (result.size() >= limit) {
                break;
            }

            Set<String> keys = item.candidate.channelKeys();
            if (Collections.disjoint(channels, keys)) {
                channels.addAll(keys);
                result.add(item);
            }
        }

        return result;
    }

    private static List<ScoredCandidate> best(List<ScoredCandidate> scored, int limit) {
        List<ScoredCandidate> sorted = new ArrayList<>(scored);
        Collections.sort(sorted, (a, b) -> Double.compare(b.score, a.score));
        return new ArrayList<>(sorted.subList(0, Math.min(limit, sorted.size())));
    }

    private static List<Candidate> candidatesOf(List<ScoredCandidate> scored) {
        List<Candidate> result = new ArrayList<>();
        for (ScoredCandidate item : scored) {
            result.add(item.candidate);
        }
        return result;
    }

    /**
     * Adds what the models did to the diagnostics written by {@link #writeDiagnostics}.
     */
    private void appendModelDiagnostics(Map<String, Judgment> judgments, JevClient.Viewer viewer, Leap.Batch leaps,
                                        List<Candidate> leftField, Outcomes outcomes) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\nOutcomes tracked: ").append(outcomes.size())
                    .append(", finished ").append(outcomes.withState(Outcomes.STATE_FINISHED, 1000).size())
                    .append(", abandoned ").append(outcomes.withState(Outcomes.STATE_ABANDONED, 1000).size())
                    .append(", passed over ").append(outcomes.ignoredVideoIds(IGNORED_AFTER_SHOWS).size()).append('\n');

            sb.append("\nJev: ").append(mData.isJevEnabled() ? judgments.size() + " judged" : "off").append('\n');
            sb.append("  Finished examples: ").append(viewer.finished).append('\n');
            sb.append("  Dropped examples: ").append(viewer.dropped).append('\n');
            int dropped = 0;
            for (Judgment judgment : judgments.values()) {
                if (judgment.isUnwanted()) {
                    dropped++;
                }
            }
            sb.append("  Dropped as filler or kids: ").append(dropped).append('\n');

            sb.append("\nLeft Field: ").append(mData.isLeftFieldEnabled() ? "on" : "off")
                    .append(", generating: ").append(mGeneratingLeaps.get()).append('\n');
            if (leaps != null) {
                sb.append("  Ideas from ").append(new java.util.Date(leaps.createdAtMs)).append(":\n");
                for (Leap leap : leaps.leaps) {
                    sb.append("    ").append(leap.id).append(" ").append(leap.label).append(" | ").append(leap.query)
                            .append(" | ").append(leap.reason).append('\n');
                }
                sb.append("  Rejected before: ").append(leaps.rejected).append('\n');
            }
            sb.append("  Row (").append(leftField.size()).append("):\n");
            for (Candidate candidate : leftField) {
                sb.append("    [").append(candidate.leapId).append("] ").append(candidate.channelName)
                        .append(" | ").append(candidate.title).append('\n');
            }

            String existing = mData.getDiagnostics();
            mData.setDiagnostics((existing != null ? existing : "") + sb);
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't write model diagnostics: %s", e);
        }
    }

    /**
     * Plain-text summary of the last refresh, for tuning. Read it with:
     * adb shell run-as org.smarttube.localrecs cat files/app_prefs/localrecs_debug
     */
    private void writeDiagnostics(List<Seed> seeds, List<String> subscribedKeys, Set<String> knownChannels,
                                  ChannelAffinity discovery, int candidateCount, List<Candidate> row) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Refreshed ").append(new java.util.Date()).append(", mode ")
                    .append(mConfig.newChannelsOnly ? "new channels only" : "mixed").append('\n');
            sb.append("Quick first pass: ").append(mQuickPassNote).append("; ").append(mQuickPassRunNote).append('\n');

            sb.append("\nSeeds (").append(seeds.size()).append("):\n");
            for (Seed seed : seeds) {
                sb.append("  ").append(seed.videoId).append(" ").append(seed.channelName).append(" completion ")
                        .append(String.format(Locale.US, "%.2f", seed.completion)).append(" feedback ")
                        .append(String.format(Locale.US, "%.2f", seed.feedbackFactor)).append('\n');
            }

            sb.append("\nSubscriptions (").append(subscribedKeys.size()).append("): ").append(subscribedKeys).append('\n');

            List<String> known = new ArrayList<>(knownChannels);
            Collections.sort(known);
            sb.append("\nKnown channel keys (").append(known.size()).append("): ").append(known).append('\n');

            sb.append("\nTop new channels:\n");
            for (String channel : discovery.topKeys(20)) {
                sb.append("  ").append(channel).append(String.format(Locale.US, " %.2f", discovery.get(channel))).append('\n');
            }

            sb.append("\nRemoved videos remembered: ").append(loadFeedback().size()).append('\n');

            sb.append("\nRow (").append(row.size()).append(" of ").append(candidateCount).append(" candidates):\n");
            for (Candidate candidate : row) {
                sb.append("  ").append(candidate.channelName).append(" [").append(candidate.channelId).append("] views ")
                        .append(candidate.viewCount).append(", ").append(candidate.durationMs / 60_000).append(" min, seeds ").append(candidate.relatedSeedIds.size())
                        .append(candidate.fromRss ? ", rss" : "").append(candidate.hop > 1 ? ", hop 2" : "").append(" | ").append(candidate.title).append('\n');
            }

            mData.setDiagnostics(sb.toString());
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't write diagnostics: %s", e);
        }
    }

    private static void learnChannels(ChannelResolver resolver, List<MediaItem> history, List<MediaItem> olderHistory,
                                      MediaGroup subscriptions, List<MediaGroup> home, List<Pair<Seed, MediaItemMetadata>> related) {
        List<MediaItem> items = new ArrayList<>(history);
        items.addAll(olderHistory);

        for (MediaGroup group : nonNull(home)) {
            items.addAll(videosOf(group));
        }

        for (Pair<Seed, MediaItemMetadata> result : related) {
            // The seed's own page always knows its channel
            resolver.learn(result.second.getChannelId(), result.second.getAuthor());

            for (MediaGroup group : nonNull(result.second.getSuggestions())) {
                items.addAll(videosOf(group));
            }
        }

        for (MediaItem item : items) {
            resolver.learn(item.getChannelId(), channelName(item));
        }

        for (MediaItem channel : itemsOf(subscriptions)) {
            resolver.learn(channel.getChannelId(), channel.getTitle());
        }
    }

    /**
     * Second hop: open the best video of each top new channel. Its page gives the channel id (which
     * RSS needs and TV tiles rarely carry) and its own related videos, which reach channels one step
     * further from what you watched. Those count for less, see {@link RecsConfig#secondHopFactor}.
     */
    private void expandSecondHop(MediaItemService itemService, ChannelAffinity discovery, List<Candidate> candidates,
                                 ChannelResolver resolver, AtomicInteger budget) {
        List<Candidate> parents = new ArrayList<>();

        for (String key : discovery.topKeys(mConfig.hopChannels)) {
            Candidate best = null;

            for (Candidate candidate : candidates) {
                if (candidate.hop == 1 && key.equals(candidate.channelKey())
                        && (best == null || candidate.relatedSeedIds.size() > best.relatedSeedIds.size())) {
                    best = candidate;
                }
            }

            if (best != null) {
                parents.add(best);
            }
        }

        List<Pair<Candidate, MediaItemMetadata>> pages = Observable.fromIterable(parents)
                .flatMap(parent -> Observable.fromCallable(() -> {
                            MediaItemMetadata metadata = call(budget, () -> itemService.getMetadata(parent.videoId));
                            return metadata != null ? Collections.singletonList(new Pair<>(parent, metadata)) : Collections.<Pair<Candidate, MediaItemMetadata>>emptyList();
                        })
                        .subscribeOn(Schedulers.io())
                        .flatMapIterable(list -> list),
                        METADATA_CONCURRENCY)
                .toList()
                .onErrorReturnItem(Collections.emptyList())
                .blockingGet();

        for (Pair<Candidate, MediaItemMetadata> page : pages) {
            resolver.learn(page.second.getChannelId(), page.second.getAuthor());
            resolver.learn(page.second.getChannelId(), page.first.channelName);

            int added = 0;
            for (MediaGroup group : nonNull(page.second.getSuggestions())) {
                for (MediaItem item : videosOf(group)) {
                    if (added++ >= MAX_SUGGESTIONS_PER_SEED) {
                        break;
                    }
                    Candidate candidate = toCandidate(item, resolver);
                    candidate.hop = 2;
                    candidate.hopParentId = page.first.videoId;
                    // Credit the watched videos that led to the parent, so removals still teach the right seeds
                    candidate.relatedSeedIds.addAll(page.first.relatedSeedIds);
                    candidates.add(candidate);
                }
            }
        }
    }

    /**
     * Channel ids for the best new channels, as far as we know them.
     */
    private List<String> discoveredChannelIds(ChannelAffinity discovery, ChannelResolver resolver) {
        Set<String> result = new java.util.LinkedHashSet<>();

        for (String key : discovery.topKeys(mConfig.discoveredChannels)) {
            String channelId = key.startsWith("name:") ? resolver.resolveId(null, key.substring("name:".length())) : key;

            if (channelId != null) {
                result.add(channelId);
            }
        }

        return new ArrayList<>(result);
    }

    private void addRssCandidates(ContentService contentService, List<String> channelIds, List<Candidate> candidates,
                                  AtomicInteger budget, ChannelResolver resolver) {
        for (int i = 0; i < channelIds.size(); i += RSS_BATCH_SIZE) {
            String[] batch = channelIds.subList(i, Math.min(i + RSS_BATCH_SIZE, channelIds.size())).toArray(new String[0]);
            for (MediaItem item : videosOf(call(budget, () -> contentService.getRssFeed(batch)))) {
                Candidate candidate = toCandidate(item, resolver);
                candidate.fromRss = true;
                candidates.add(candidate);
            }
        }
    }

    private static void addLocalChannels(Set<String> knownChannels, VideoStateService stateService, ChannelResolver resolver) {
        if (stateService == null) {
            return;
        }

        for (VideoStateService.State state : new ArrayList<>(stateService.getStates())) {
            if (state != null && state.video != null) {
                String name = state.video.getAuthor();
                ChannelAffinity.addKeys(knownChannels, resolver.resolveId(state.video.channelId, name), name);
            }
        }
    }

    private List<Seed> strongestSeeds(List<Seed> seeds, int limit) {
        // Videos abandoned early say little about taste, so they don't lead to related videos
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            double completion = seeds.get(i).completion;
            if (completion < 0 || completion >= mConfig.minRelatedSeedCompletion) {
                order.add(i);
            }
        }

        if (order.isEmpty()) {
            for (int i = 0; i < seeds.size(); i++) {
                order.add(i);
            }
        }

        Collections.sort(order, (a, b) -> Double.compare(
                seeds.get(b).weightAt(b, mConfig),
                seeds.get(a).weightAt(a, mConfig)));

        List<Seed> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, order.size()); i++) {
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
    /**
     * The saved rows, minus anything watched or removed since. The main row comes first; without
     * it nothing is shown, since the Left Field row would otherwise be the only local row.
     */
    private List<MediaGroup> fromCache(CachedRow cached) {
        mConfig.maxDurationMs = mData.getMaxDurationMs();
        Set<String> watchedIds = localWatchedIds(VideoStateService.instance(mContext));
        watchedIds.addAll(loadFeedback().removedVideoIds());
        CandidateFilter filter = new CandidateFilter(watchedIds, createBlocklist(), mConfig);

        List<MediaItem> main = filterCached(cached, filter);
        if (main.size() < mConfig.minItems) {
            return Collections.emptyList();
        }

        List<MediaGroup> rows = new ArrayList<>();
        rows.add(new LocalRecsMediaGroup(mContext.getString(R.string.localrecs_title), main));

        CachedRow leftField = mData.isLeftFieldEnabled() ? CachedRow.decode(mData.getLeftFieldCache()) : null;
        if (leftField != null && leftField.owner.equals(cached.owner)) {
            List<MediaItem> items = filterCached(leftField, filter);
            if (items.size() >= LEFT_FIELD_MIN) {
                rows.add(new LocalRecsMediaGroup(mContext.getString(R.string.localrecs_left_field_title), items));
            }
        }

        return rows;
    }

    private static List<MediaItem> filterCached(CachedRow cached, CandidateFilter filter) {
        List<MediaItem> items = new ArrayList<>();

        for (CachedRow.Item item : cached.items) {
            CachedMediaItem mediaItem = new CachedMediaItem(item);
            if (filter.isAllowed(toCandidate(mediaItem, null))) {
                items.add(mediaItem);
            }
        }

        return items;
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

    /**
     * @param resolver fills in a missing channel id, may be null
     */
    private static Candidate toCandidate(MediaItem item, ChannelResolver resolver) {
        String name = channelName(item);
        String channelId = resolver != null ? resolver.resolveId(item.getChannelId(), name) : item.getChannelId();
        Candidate candidate = new Candidate(item.getVideoId(), channelId, name, item.getTitle());
        candidate.publishedMs = item.getPublishedDate();
        candidate.durationMs = item.getDurationMs();
        candidate.isLive = item.isLive();
        candidate.isUpcoming = item.isUpcoming();
        candidate.isShorts = item.isShorts();
        candidate.percentWatched = item.getPercentWatched();
        candidate.viewCount = ViewCounts.parse(item.getSecondTitle());
        candidate.isMovie = item.isMovie() || YOUTUBE_MOVIES_CHANNEL_ID.equals(channelId);
        candidate.payload = item;
        return candidate;
    }

    /**
     * The plain channel name. MediaItem.getAuthor() is the whole second line on TV tiles
     * ("Channel • 1.2M views • 3 days ago"), so reuse the app's own parsing of it.
     */
    private static String channelName(MediaItem item) {
        try {
            return Video.from(item).getAuthor();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<MediaItem> itemsOf(MediaGroup group) {
        List<MediaItem> result = new ArrayList<>();

        if (group != null && group.getMediaItems() != null) {
            for (MediaItem item : group.getMediaItems()) {
                if (item != null) {
                    result.add(item);
                }
            }
        }

        return result;
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

    /**
     * Who the cached row belongs to. Includes the discovery mode, so switching modes builds a fresh row.
     */
    private String getOwner() {
        SignInService signInService = YouTubeServiceManager.instance().getSignInService();
        Account account = signInService != null ? signInService.getSelectedAccount() : null;

        if (account == null) {
            return "";
        }

        // Only used to tell profiles apart, so a hash is enough
        return Arrays.hashCode(new Object[] {account.getEmail(), account.getName()}) + (mData.isNewChannelsOnly() ? ":new" : ":mixed");
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
