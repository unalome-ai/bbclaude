package ai.bbclaude.app;

import org.conscrypt.Conscrypt;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.security.Provider;
import java.security.Security;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * Streams replies from the Messages API. The BB10 Android runtime only speaks
 * TLS 1.0, so every request goes through the bundled Conscrypt (BoringSSL).
 */
public class ClaudeClient {
    static final String BASE_URL = "https://api.anthropic.com";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    static final String SYSTEM_PROMPT =
            "You are Claude, chatting with the user in the bbclaude app on a BlackBerry "
            + "Passport, a phone with a small square screen and a physical keyboard. The app "
            + "renders simple Markdown: bold, italics, inline code, code blocks, headings and "
            + "lists. Avoid tables and very wide code lines. Keep answers reasonably concise "
            + "unless the user asks for detail.";

    /** Callbacks arrive on the network thread. */
    public interface Listener {
        void onText(String delta);
        void onFallback();
        /** Finished normally; content is the assistant message content to append to history. */
        void onDone(JSONArray content, String stopReason, String servedModel);
        /** Failed or declined; nothing should be appended to history. */
        void onError(String message);
    }

    private static SSLSocketFactory tls;
    private volatile HttpsURLConnection active;
    private volatile boolean cancelled;

    static synchronized SSLSocketFactory tls() throws Exception {
        if (tls == null) {
            Provider p = Conscrypt.newProvider();
            Security.insertProviderAt(p, 1);
            SSLContext ctx = SSLContext.getInstance("TLS", p);
            ctx.init(null, null, null);
            tls = ctx.getSocketFactory();
        }
        return tls;
    }

    static boolean hasServerFallback(String model) {
        return model.equals("claude-opus-5-5") || model.equals("claude-opus-5")
                || model.equals("claude-fable-5-1") || model.equals("claude-sonnet-5-5");
    }

    private static HttpsURLConnection open(String path, String apiKey) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(BASE_URL + path).openConnection();
        c.setSSLSocketFactory(tls());
        c.setConnectTimeout(30000);
        c.setReadTimeout(300000);
        c.setRequestProperty("x-api-key", apiKey);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        return c;
    }

    /**
     * Android 4.3 throws "No authentication challenges found" from getResponseCode()
     * when a 401 arrives without a WWW-Authenticate header.
     */
    private static int responseCode(HttpsURLConnection c) throws IOException {
        try {
            return c.getResponseCode();
        } catch (IOException e) {
            String m = e.getMessage();
            if (m != null && m.contains("authentication challenges")) return 401;
            throw e;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    static String describeError(int code, String body) {
        if (code == 401) return "The API key was rejected (401). Check it in Settings.";
        String msg = null;
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e != null) msg = e.optString("type") + ": " + e.optString("message");
        } catch (JSONException ignored) {
        }
        if (code == 429) return "Rate limited (429). Wait a moment and try again." + (msg != null ? "\n" + msg : "");
        if (code == 529) return "Claude is overloaded right now (529). Try again shortly.";
        return "HTTP " + code + (msg != null ? ": " + msg : "");
    }

    /** Checks the key with GET /v1/models. Returns null when it works, else a message. */
    public static String testKey(String apiKey) {
        try {
            HttpsURLConnection c = open("/v1/models", apiKey);
            int code = responseCode(c);
            if (code == 200) return null;
            String body = code == 401 ? "" : readAll(c.getErrorStream());
            return describeError(code, body);
        } catch (Exception e) {
            return "Connection failed: " + e;
        }
    }

    public void cancel() {
        cancelled = true;
        HttpsURLConnection c = active;
        if (c != null) c.disconnect();
    }

    /** Blocking; call from a background thread. */
    public void stream(String apiKey, String model, String effort, JSONArray history, Listener l) {
        cancelled = false;
        HttpsURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 64000);
            body.put("stream", true);
            body.put("output_config", new JSONObject().put("effort", effort));
            if (hasServerFallback(model)) body.put("fallbacks", "default");
            body.put("system", SYSTEM_PROMPT);
            body.put("messages", history);
            byte[] bytes = body.toString().getBytes("UTF-8");

            c = open("/v1/messages", apiKey);
            active = c;
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("accept", "text/event-stream");
            if (hasServerFallback(model)) c.setRequestProperty("anthropic-beta", FALLBACK_BETA);
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = c.getOutputStream();
            out.write(bytes);
            out.close();

            int code = responseCode(c);
            if (code != 200) {
                String err = code == 401 ? "" : readAll(c.getErrorStream());
                l.onError(describeError(code, err));
                return;
            }
            readEvents(c.getInputStream(), l);
        } catch (Exception e) {
            if (cancelled) l.onError(null);
            else l.onError("Connection failed: " + e.getMessage());
        } finally {
            active = null;
            if (c != null) c.disconnect();
        }
    }

    private void readEvents(InputStream in, Listener l) throws IOException, JSONException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
        JSONArray blocks = new JSONArray();
        String stopReason = "";
        String servedModel = "";
        String error = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (cancelled) throw new IOException("cancelled");
            if (line.length() == 0) {
                if (data.length() == 0) continue;
                JSONObject ev = new JSONObject(data.toString());
                data.setLength(0);
                String type = ev.optString("type");
                if (type.equals("message_start")) {
                    JSONObject m = ev.optJSONObject("message");
                    if (m != null) servedModel = m.optString("model");
                } else if (type.equals("content_block_start")) {
                    JSONObject cb = ev.getJSONObject("content_block");
                    blocks.put(cb);
                    if (cb.optString("type").equals("fallback")) l.onFallback();
                } else if (type.equals("content_block_delta")) {
                    JSONObject blk = blocks.optJSONObject(ev.optInt("index", -1));
                    JSONObject d = ev.getJSONObject("delta");
                    String dt = d.optString("type");
                    if (blk == null) continue;
                    if (dt.equals("text_delta")) {
                        String t = d.optString("text");
                        blk.put("text", blk.optString("text") + t);
                        l.onText(t);
                    } else if (dt.equals("thinking_delta")) {
                        blk.put("thinking", blk.optString("thinking") + d.optString("thinking"));
                    } else if (dt.equals("signature_delta")) {
                        blk.put("signature", d.optString("signature"));
                    }
                } else if (type.equals("message_delta")) {
                    JSONObject d = ev.optJSONObject("delta");
                    if (d != null && !d.isNull("stop_reason")) stopReason = d.optString("stop_reason");
                } else if (type.equals("error")) {
                    JSONObject e = ev.optJSONObject("error");
                    error = e != null ? e.optString("type") + ": " + e.optString("message") : "stream error";
                }
                continue;
            }
            if (line.startsWith("data:")) {
                String v = line.substring(5);
                if (v.startsWith(" ")) v = v.substring(1);
                if (data.length() > 0) data.append('\n');
                data.append(v);
            }
        }
        if (error != null) { l.onError(error); return; }
        if (stopReason.equals("refusal")) {
            l.onError("Claude declined this request.");
            return;
        }
        JSONArray content = assistantContent(blocks);
        if (content.length() == 0) { l.onError("Empty reply."); return; }
        l.onDone(content, stopReason, servedModel);
    }

    /**
     * After a mid-reply fallback, only text blocks from before the last fallback
     * marker may be sent back; the marker itself is dropped.
     */
    static JSONArray assistantContent(JSONArray blocks) {
        int lastFallback = -1;
        for (int i = 0; i < blocks.length(); i++) {
            if (blocks.optJSONObject(i).optString("type").equals("fallback")) lastFallback = i;
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            String t = b.optString("type");
            if (t.equals("fallback")) continue;
            if (i < lastFallback && !t.equals("text")) continue;
            out.put(b);
        }
        return out;
    }

    /** The visible text of an assistant message's content. */
    static String visibleText(JSONArray content) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b != null && b.optString("type").equals("text")) sb.append(b.optString("text"));
        }
        return sb.toString();
    }
}
