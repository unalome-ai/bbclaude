package ai.bbclaude.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Conversations saved as one JSON file each in the app's private storage. */
public class Store {
    public static class Conversation {
        public String id;
        public String title = "New chat";
        public String model;
        public long updated;
        /** Messages API history, append-only. */
        public JSONArray messages = new JSONArray();

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("title", title).put("model", model)
                    .put("updated", updated).put("messages", messages);
        }

        static Conversation fromJson(JSONObject o) {
            Conversation c = new Conversation();
            c.id = o.optString("id");
            c.title = o.optString("title", "New chat");
            c.model = o.optString("model", Settings.DEFAULT_MODEL);
            c.updated = o.optLong("updated");
            JSONArray m = o.optJSONArray("messages");
            if (m != null) c.messages = m;
            return c;
        }
    }

    private final File dir;

    public Store(Context ctx) {
        dir = new File(ctx.getFilesDir(), "conversations");
        dir.mkdirs();
    }

    public Conversation create(String model) {
        Conversation c = new Conversation();
        c.id = Long.toString(System.currentTimeMillis(), 36);
        c.model = model;
        c.updated = System.currentTimeMillis();
        return c;
    }

    public void save(Conversation c) {
        if (c.messages.length() == 0) return; // don't keep empty chats
        c.updated = System.currentTimeMillis();
        File tmp = new File(dir, c.id + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(c.toJson().toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            tmp.renameTo(new File(dir, c.id + ".json"));
        } catch (Exception e) {
            tmp.delete();
        }
    }

    public Conversation load(String id) {
        try {
            return Conversation.fromJson(new JSONObject(read(new File(dir, id + ".json"))));
        } catch (Exception e) {
            return null;
        }
    }

    public void delete(String id) {
        new File(dir, id + ".json").delete();
    }

    /** Newest first. Loads every file, which is fine for a phone's worth of chats. */
    public List<Conversation> list() {
        List<Conversation> out = new ArrayList<Conversation>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    out.add(Conversation.fromJson(new JSONObject(read(f))));
                } catch (Exception ignored) {
                }
            }
        }
        Collections.sort(out, new Comparator<Conversation>() {
            public int compare(Conversation a, Conversation b) {
                return a.updated == b.updated ? 0 : (a.updated > b.updated ? -1 : 1);
            }
        });
        return out;
    }

    private static String read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int off = 0, n;
        while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
        in.close();
        return new String(buf, 0, off, "UTF-8");
    }
}
