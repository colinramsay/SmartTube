package com.liskovsoft.smartyoutubetv2.common.localrecs.ai;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.localrecs.ranking.Leap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Asks Claude for Left Field ideas: things the viewer hasn't watched that share the reasons they
 * like what they do. Called about once a day.
 *
 * Raw HTTP rather than the Anthropic Java SDK: the SDK needs a newer OkHttp than the one this app
 * pins for Android 4 support, so it can't be added without changing upstream's dependency setup.
 */
public class LeapGenerator {
    private static final String TAG = LeapGenerator.class.getSimpleName();
    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-5";
    private static final MediaType JSON = MediaType.parse("application/json");
    public static final int IDEAS = 20;
    private final OkHttpClient mClient;
    private final String mApiKey;

    public LeapGenerator(String apiKey) {
        mApiKey = apiKey;
        // Thinking can take a while; this runs in the background, so be patient
        mClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Everything Claude sees. Titles and channel names only, no account details.
     */
    public static class Evidence {
        public List<String> finished = new ArrayList<>();
        public List<String> dropped = new ArrayList<>();
        public List<String> subscriptions = new ArrayList<>();
        /** Earlier ideas the viewer took to; build on these. */
        public List<String> landed = new ArrayList<>();
        /** Earlier ideas that didn't land; don't suggest them or close variants. */
        public List<String> rejected = new ArrayList<>();
    }

    /**
     * @return new ideas, or null if the call failed (the old ideas are kept in that case)
     */
    public List<Leap> generate(Evidence evidence, long nowMs) {
        try {
            JSONObject body = new JSONObject()
                    .put("model", MODEL)
                    .put("max_tokens", 16000)
                    .put("thinking", new JSONObject().put("type", "adaptive"))
                    .put("fallbacks", "default")
                    .put("output_config", new JSONObject().put("format", new JSONObject()
                            .put("type", "json_schema")
                            .put("schema", schema())))
                    .put("messages", new JSONArray().put(new JSONObject()
                            .put("role", "user")
                            .put("content", prompt(evidence))));

            Request request = new Request.Builder()
                    .url(URL)
                    .header("x-api-key", mApiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("anthropic-beta", "server-side-fallback-2026-07-01")
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();

            try (Response response = mClient.newCall(request).execute()) {
                String text = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    Log.e(TAG, "Claude returned %s: %s", response.code(), text);
                    return null;
                }

                return parse(new JSONObject(text), nowMs);
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Claude call failed: %s", e);
            return null;
        }
    }

    private static String prompt(Evidence evidence) {
        return "You suggest surprising YouTube viewing for one person, shown as a \"Left Field\" row on their TV. "
                + "Another system already recommends videos close to what they watch. Your job is the opposite: "
                + "find topics they have never watched that they would still enjoy.\n\n"
                + "Work out why they like what they finish, in terms of underlying appeal rather than subject "
                + "(for example: patient craftsmanship, a strong sense of place, an expert's enthusiasm, clever "
                + "puzzles, gentle humour). Then propose " + IDEAS + " topics that share those reasons but none "
                + "of the subjects in their history. Each topic gets one video on screen, so make every one clearly "
                + "different from the others, in subject and in mood, and a little unexpected. Avoid "
                + "anything they dropped, anything in the rejected list and close variants of it, background or "
                + "ambient filler, and children's content.\n\n"
                + "For each topic give a short label, one sentence on why it might appeal to this viewer "
                + "specifically, and a YouTube search query (3 to 7 words) likely to find good, well-made "
                + "videos on it. Write queries a person would type, not a list of keywords.\n\n"
                + "Videos they finished:\n" + bullets(evidence.finished)
                + "\nVideos they removed or abandoned:\n" + bullets(evidence.dropped)
                + "\nChannels they subscribe to:\n" + bullets(evidence.subscriptions)
                + "\nEarlier ideas that landed (build on what made these work, without repeating them):\n" + bullets(evidence.landed)
                + "\nEarlier ideas they rejected:\n" + bullets(evidence.rejected);
    }

    private static String bullets(List<String> items) {
        if (items.isEmpty()) {
            return "- (none)\n";
        }

        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append("- ").append(item).append('\n');
        }
        return sb.toString();
    }

    private static JSONObject schema() throws JSONException {
        JSONObject idea = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("label", new JSONObject().put("type", "string"))
                        .put("reason", new JSONObject().put("type", "string"))
                        .put("query", new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("label").put("reason").put("query"))
                .put("additionalProperties", false);

        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("ideas", new JSONObject().put("type", "array").put("items", idea)))
                .put("required", new JSONArray().put("ideas"))
                .put("additionalProperties", false);
    }

    private static List<Leap> parse(JSONObject response, long nowMs) throws JSONException {
        String stopReason = response.optString("stop_reason");

        if ("refusal".equals(stopReason) || "max_tokens".equals(stopReason)) {
            Log.e(TAG, "Claude stopped early: %s", stopReason);
            return null;
        }

        JSONArray content = response.getJSONArray("content");
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.getJSONObject(i);

            if (!"text".equals(block.optString("type"))) {
                continue; // thinking and fallback blocks
            }

            JSONArray ideas = new JSONObject(block.getString("text")).getJSONArray("ideas");
            List<Leap> leaps = new ArrayList<>();

            for (int j = 0; j < ideas.length(); j++) {
                JSONObject idea = ideas.getJSONObject(j);
                String query = idea.optString("query").trim();

                if (!query.isEmpty()) {
                    leaps.add(new Leap(Long.toString(nowMs, 36) + "-" + j,
                            idea.optString("label").trim(), idea.optString("reason").trim(), query));
                }
            }

            return leaps;
        }

        return null;
    }
}
