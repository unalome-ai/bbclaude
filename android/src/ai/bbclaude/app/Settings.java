package ai.bbclaude.app;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings kept in SharedPreferences (private to the app). */
public class Settings {
    static final String DEFAULT_MODEL = "claude-opus-5-5";

    static final String[] MODEL_IDS = {"claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"};
    static final String[] MODEL_NAMES = {"Opus 5.5", "Sonnet 5.5", "Haiku 5.5"};
    static final String[] MODEL_NOTES = {
            "Most capable", "Fast and capable for everyday tasks", "Fastest and cheapest"};
    static final String[] EFFORTS = {"low", "medium", "high"};

    private final SharedPreferences p;

    Settings(Context ctx) {
        p = ctx.getSharedPreferences("bbclaude", Context.MODE_PRIVATE);
    }

    String apiKey() { return p.getString("api_key", "").trim(); }
    void setApiKey(String k) { p.edit().putString("api_key", k.trim()).commit(); }

    String model() { return p.getString("model", DEFAULT_MODEL); }
    void setModel(String m) { p.edit().putString("model", m).commit(); }

    String effort() { return p.getString("effort", "low"); }
    void setEffort(String e) { p.edit().putString("effort", e).commit(); }

    int textSize() { return p.getInt("text_size", 19); }
    void setTextSize(int sp) { p.edit().putInt("text_size", sp).commit(); }

    String lastConversation() { return p.getString("last_conversation", null); }
    void setLastConversation(String id) { p.edit().putString("last_conversation", id).commit(); }

    static String modelName(String id) {
        for (int i = 0; i < MODEL_IDS.length; i++) if (MODEL_IDS[i].equals(id)) return MODEL_NAMES[i];
        return id;
    }
}
