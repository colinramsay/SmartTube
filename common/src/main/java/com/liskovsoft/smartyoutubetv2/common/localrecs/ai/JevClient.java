package com.liskovsoft.smartyoutubetv2.common.localrecs.ai;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Candidate;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Judgment;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Leap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Asks TypeSafe's Jev model a few narrow questions about each candidate video.
 * One request per video, all questions in it answered together. Any failure just means no judgment
 * for that video, and the ranking carries on without it.
 */
public class JevClient {
    private static final String TAG = JevClient.class.getSimpleName();
    private static final String URL = "https://api.typesafe.ai/v1/systemone";
    private static final String MODEL = "jev-latest";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int CONCURRENCY = 6;
    private final OkHttpClient mClient;
    private final String mApiKey;

    public JevClient(String apiKey) {
        mApiKey = apiKey;
        mClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    /**
     * What the viewer's history looks like, sent as context with every question. Plain titles only.
     */
    public static class Viewer {
        public final List<String> finished;
        public final List<String> dropped;

        public Viewer(List<String> finished, List<String> dropped) {
            this.finished = finished;
            this.dropped = dropped;
        }
    }

    /**
     * Judge main-row candidates: filler, kids' content and taste. Returns judgments by video id.
     */
    public Map<String, Judgment> judgeForMainRow(List<Candidate> candidates, Viewer viewer) {
        return judgeAll(candidates, viewer, null);
    }

    /**
     * Judge Left Field candidates: filler, kids' content, quality and fit with the idea that found them.
     */
    public Map<String, Judgment> judgeForLeftField(List<Candidate> candidates, Viewer viewer, Map<String, Leap> leapByVideoId) {
        return judgeAll(candidates, viewer, leapByVideoId);
    }

    private Map<String, Judgment> judgeAll(List<Candidate> candidates, Viewer viewer, Map<String, Leap> leapByVideoId) {
        try {
            List<Object[]> results = Observable.fromIterable(candidates)
                    .flatMap(candidate -> Observable.fromCallable(() -> {
                                Leap leap = leapByVideoId != null ? leapByVideoId.get(candidate.videoId) : null;
                                Judgment judgment = judge(candidate, viewer, leap);
                                return judgment != null ? Collections.singletonList(new Object[] {candidate.videoId, judgment})
                                        : Collections.<Object[]>emptyList();
                            })
                            .subscribeOn(Schedulers.io())
                            .flatMapIterable(list -> list),
                            CONCURRENCY)
                    .toList()
                    .blockingGet();

            Map<String, Judgment> judgments = new HashMap<>();
            for (Object[] result : results) {
                judgments.put((String) result[0], (Judgment) result[1]);
            }
            return judgments;
        } catch (RuntimeException e) {
            Log.e(TAG, "Judging failed: %s", e);
            return Collections.emptyMap();
        }
    }

    private Judgment judge(Candidate candidate, Viewer viewer, Leap leap) {
        try {
            JSONObject body = new JSONObject()
                    .put("model", MODEL)
                    .put("state", state(candidate, viewer, leap))
                    .put("questions", questions(leap != null));

            Request request = new Request.Builder()
                    .url(URL)
                    .header("Authorization", "Bearer " + mApiKey)
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();

            try (Response response = mClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "Jev returned %s", response.code());
                    return null;
                }

                return parse(new JSONObject(response.body().string()));
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Jev call failed: %s", e);
            return null;
        }
    }

    private static JSONObject state(Candidate candidate, Viewer viewer, Leap leap) throws JSONException {
        JSONObject video = new JSONObject()
                .put("title", candidate.title)
                .put("channel", candidate.channelName);

        if (candidate.durationMs > 0) {
            video.put("length_minutes", candidate.durationMs / 60_000);
        }

        if (candidate.viewCount >= 0) {
            video.put("views", candidate.viewCount);
        }

        JSONObject state = new JSONObject()
                .put("video", video)
                .put("viewer", new JSONObject()
                        .put("finished_watching", new JSONArray(viewer.finished))
                        .put("removed_or_abandoned", new JSONArray(viewer.dropped)));

        if (leap != null) {
            state.put("idea", new JSONObject()
                    .put("topic", leap.label)
                    .put("why_it_might_appeal", leap.reason));
        }

        return state;
    }

    private static JSONObject questions(boolean leftField) throws JSONException {
        JSONObject questions = new JSONObject()
                .put("filler", new JSONObject()
                        .put("type", "noul")
                        .put("instructions", "Is `video` background filler rather than something to sit and watch?")
                        .put("criteria", new JSONObject()
                                .put("true", "Ambient or music mixes, study or sleep loops, long compilations, playlists, livestream VODs left running")
                                .put("false", "A video made to be watched: a story, explainer, review, vlog, show episode or performance")))
                .put("kids", new JSONObject()
                        .put("type", "noul")
                        .put("instructions", "Is `video` made for young children?")
                        .put("criteria", new JSONObject()
                                .put("true", "Aimed at toddlers or young children, such as nursery rhymes or kids' cartoon episodes")
                                .put("false", "Aimed at teenagers or adults, even if light or playful")));

        if (leftField) {
            questions.put("quality", new JSONObject()
                    .put("type", "noul")
                    .put("instructions", "Judging only by `video` title, channel, length and views, does it look well made and worth an adult's time?")
                    .put("criteria", new JSONObject()
                            .put("true", "Looks substantial and made with care: a real story, skill, place or idea at its centre")
                            .put("false", "Looks like clickbait, low-effort reposts, reaction filler, rage bait or spam")));
            questions.put("fit", new JSONObject()
                    .put("type", "noul")
                    .put("instructions", "Is `video` a good example of `idea.topic`, for the reason in `idea.why_it_might_appeal`?")
                    .put("criteria", new JSONObject()
                            .put("true", "Clearly about the idea's topic and likely to deliver what the reason promises")
                            .put("false", "Only matches a search keyword, or is about something else")));
        } else {
            questions.put("taste", new JSONObject()
                    .put("type", "score")
                    .put("instructions", "How likely is this viewer to enjoy `video`, judging from `viewer.finished_watching` (liked) and `viewer.removed_or_abandoned` (disliked)?")
                    .put("criteria", new JSONArray()
                            .put("Very unlikely: close to things they removed or abandoned")
                            .put("Unlikely: little in common with what they finish")
                            .put("Possibly: some overlap in subject, style or tone with what they finish")
                            .put("Likely: shares the subject, style or tone of several things they finish")
                            .put("Very likely: the kind of video they reliably finish")));
        }

        return questions;
    }

    private static Judgment parse(JSONObject response) throws JSONException {
        JSONObject answers = response.getJSONObject("answers");
        Judgment judgment = new Judgment();
        judgment.filler = noul(answers, "filler");
        judgment.kids = noul(answers, "kids");
        judgment.quality = noul(answers, "quality");
        judgment.leapFit = noul(answers, "fit");

        JSONObject taste = answers.optJSONObject("taste");
        if (taste != null && taste.has("score")) {
            // Score is a probability-weighted level, 0..4 here; scale to 0..1
            judgment.taste = Math.max(0, Math.min(1, taste.getDouble("score") / 4.0));
        }

        return judgment;
    }

    private static double noul(JSONObject answers, String name) {
        JSONObject answer = answers.optJSONObject(name);
        return answer != null && answer.has("noul") ? answer.optDouble("noul", -1) : -1;
    }
}
