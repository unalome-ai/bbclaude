package ai.bbclaude.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** bbclaude: a Claude chat app for the BlackBerry Passport's Android runtime. */
public class MainActivity extends Activity {
    private static final int CHAT = 0, CHATS = 1, SETTINGS = 2;

    private Settings settings;
    private Store store;
    private Store.Conversation conv;
    private final ClaudeClient client = new ClaudeClient();
    private final Handler ui = new Handler();
    private float density;

    private FrameLayout root;
    private View chatScreen, chatsScreen, settingsScreen;
    private EditText settingsKey;
    private int screen = CHAT;

    // chat screen
    private TextView titleView, modelView;
    private ScrollView scroll;
    private LinearLayout messagesBox;
    private EditText composer;
    private Theme.Icon sendIcon;
    private View sendButton;

    // chats screen
    private ListView chatsList;
    private List<Store.Conversation> chats;

    // streaming state
    private boolean streaming;
    private TextView streamingView;
    private final StringBuilder streamed = new StringBuilder();
    private boolean renderPending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        store = new Store(this);
        density = getResources().getDisplayMetrics().density;
        getWindow().getDecorView().setBackgroundColor(Theme.BG);

        root = new FrameLayout(this);
        chatScreen = buildChat();
        root.addView(chatScreen);
        setContentView(root);

        String last = settings.lastConversation();
        conv = last != null ? store.load(last) : null;
        if (conv == null) conv = store.create(settings.model());
        renderConversation();
        if (settings.apiKey().length() == 0) {
            show(SETTINGS);
            toast("Paste your Anthropic API key to get started.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (screen == SETTINGS && settingsKey != null) settings.setApiKey(settingsKey.getText().toString());
        store.save(conv);
        settings.setLastConversation(conv.messages.length() > 0 ? conv.id : null);
    }

    @Override
    protected void onDestroy() {
        client.cancel();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (screen != CHAT) show(CHAT);
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- helpers

    private int dp(float v) { return (int) (v * density + 0.5f); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private View iconButton(int kind, int color, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(this);
        Theme.Icon icon = new Theme.Icon(this, kind, color);
        f.addView(icon, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        f.setOnClickListener(click);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        return f;
    }

    private LinearLayout topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Theme.BAR);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));
        return bar;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Theme.DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private void show(int which) {
        if (screen == SETTINGS && settingsKey != null) settings.setApiKey(settingsKey.getText().toString());
        if (which == CHATS) {
            if (chatsScreen == null) { chatsScreen = buildChats(); root.addView(chatsScreen); }
            refreshChats();
        }
        if (which == SETTINGS) {
            if (settingsScreen != null) root.removeView(settingsScreen);
            settingsScreen = buildSettings();
            root.addView(settingsScreen);
        }
        chatScreen.setVisibility(which == CHAT ? View.VISIBLE : View.GONE);
        if (chatsScreen != null) chatsScreen.setVisibility(which == CHATS ? View.VISIBLE : View.GONE);
        if (settingsScreen != null) settingsScreen.setVisibility(which == SETTINGS ? View.VISIBLE : View.GONE);
        screen = which;
        if (which == CHAT) renderConversation();
    }

    // ---------------------------------------------------------------- chat screen

    private View buildChat() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Theme.BG);

        LinearLayout bar = topBar();
        bar.addView(iconButton(Theme.Icon.MENU, Theme.TEXT, new View.OnClickListener() {
            public void onClick(View v) { show(CHATS); }
        }));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER);
        titleView = text("New chat", 18, Theme.TEXT);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setGravity(Gravity.CENTER);
        modelView = text("", 13, Theme.TEXT_DIM);
        modelView.setGravity(Gravity.CENTER);
        modelView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickModel(); }
        });
        titles.addView(titleView);
        titles.addView(modelView);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(iconButton(Theme.Icon.PLUS, Theme.TEXT, new View.OnClickListener() {
            public void onClick(View v) { newChat(); }
        }));
        bar.addView(iconButton(Theme.Icon.MORE, Theme.TEXT, new View.OnClickListener() {
            public void onClick(View v) { show(SETTINGS); }
        }));
        col.addView(bar);
        col.addView(divider());

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        messagesBox.setPadding(dp(14), dp(10), dp(14), dp(16));
        scroll.addView(messagesBox);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        col.addView(divider());
        LinearLayout comp = new LinearLayout(this);
        comp.setOrientation(LinearLayout.HORIZONTAL);
        comp.setGravity(Gravity.CENTER_VERTICAL);
        comp.setBackgroundColor(Theme.BAR);
        comp.setPadding(dp(10), dp(8), dp(8), dp(8));
        composer = new EditText(this);
        composer.setHint("Reply to Claude…");
        composer.setHintTextColor(Theme.TEXT_DIM);
        composer.setTextColor(Theme.TEXT);
        composer.setBackground(Theme.rounded(Theme.SURFACE, dp(20)));
        composer.setPadding(dp(16), dp(10), dp(16), dp(10));
        composer.setMaxLines(6);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composer.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent ev) {
                // Enter sends; Shift+Enter (or Alt+Enter) adds a new line.
                if (code == KeyEvent.KEYCODE_ENTER && !ev.isShiftPressed() && !ev.isAltPressed()) {
                    if (ev.getAction() == KeyEvent.ACTION_DOWN) send();
                    return true;
                }
                return false;
            }
        });
        comp.addView(composer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        FrameLayout sb = new FrameLayout(this);
        sb.setBackground(Theme.rounded(Theme.ACCENT, dp(22)));
        sendIcon = new Theme.Icon(this, Theme.Icon.SEND, 0xFFFFFFFF);
        sb.addView(sendIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        sb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (streaming) client.cancel();
                else send();
            }
        });
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(dp(44), dp(44));
        sbl.leftMargin = dp(8);
        comp.addView(sb, sbl);
        sendButton = sb;
        col.addView(comp);
        return col;
    }

    private void renderConversation() {
        if (titleView == null) return;
        titleView.setText(conv.title);
        modelView.setText(Settings.modelName(conv.model) + " ▾");
        messagesBox.removeAllViews();
        if (conv.messages.length() == 0) {
            addEmptyState();
            return;
        }
        for (int i = 0; i < conv.messages.length(); i++) {
            JSONObject m = conv.messages.optJSONObject(i);
            if (m == null) continue;
            if (m.optString("role").equals("user")) {
                addUserBubble(m.optString("content"));
            } else {
                JSONArray content = m.optJSONArray("content");
                addAssistant(content != null ? ClaudeClient.visibleText(content) : m.optString("content"));
            }
        }
        scrollToBottom(true);
    }

    private void addEmptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        Theme.Icon spark = new Theme.Icon(this, Theme.Icon.BERRY, Theme.CLAUDE);
        box.addView(spark, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView hello = text("How can I help you today?", settings.textSize() + 5, Theme.TEXT);
        hello.setGravity(Gravity.CENTER);
        hello.setPadding(dp(16), dp(16), dp(16), dp(6));
        box.addView(hello);
        TextView hint = text(Settings.modelName(conv.model) + " · tap the model name to change it",
                13, Theme.TEXT_DIM);
        hint.setGravity(Gravity.CENTER);
        box.addView(hint);
        messagesBox.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void addUserBubble(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.RIGHT);
        TextView t = text(s, settings.textSize(), Theme.TEXT);
        t.setBackground(Theme.rounded(Theme.USER_BUBBLE, dp(18)));
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setTextIsSelectable(true);
        t.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
        row.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        messagesBox.addView(row, lp);
    }

    private TextView addAssistant(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Theme.Icon spark = new Theme.Icon(this, Theme.Icon.BERRY, Theme.CLAUDE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(20), dp(20));
        sl.topMargin = dp(4);
        sl.rightMargin = dp(10);
        row.addView(spark, sl);
        TextView t = text("", settings.textSize(), Theme.TEXT);
        t.setText(Markdown.render(s, density));
        t.setLineSpacing(dp(3), 1f);
        t.setTextIsSelectable(true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        messagesBox.addView(row, lp);
        return t;
    }

    private void addNote(String s, int color) {
        TextView t = text(s, settings.textSize() - 3, color);
        t.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        t.setPadding(dp(30), dp(8), dp(8), dp(4));
        messagesBox.addView(t);
        scrollToBottom(true);
    }

    private boolean nearBottom() {
        View child = scroll.getChildAt(0);
        return child == null || scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - dp(120);
    }

    private void scrollToBottom(boolean force) {
        if (!force && !nearBottom()) return;
        scroll.post(new Runnable() {
            public void run() { scroll.fullScroll(View.FOCUS_DOWN); composer.requestFocus(); }
        });
    }

    private void setStreaming(boolean on) {
        streaming = on;
        sendIcon.setVisibility(View.GONE);
        FrameLayout f = (FrameLayout) sendButton;
        f.removeAllViews();
        sendIcon = new Theme.Icon(this, on ? Theme.Icon.STOP : Theme.Icon.SEND, 0xFFFFFFFF);
        f.addView(sendIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
    }

    private void newChat() {
        if (streaming) return;
        store.save(conv);
        conv = store.create(settings.model());
        renderConversation();
        composer.requestFocus();
    }

    private void pickModel() {
        if (streaming) return;
        int current = 0;
        for (int i = 0; i < Settings.MODEL_IDS.length; i++) if (Settings.MODEL_IDS[i].equals(conv.model)) current = i;
        String[] items = new String[Settings.MODEL_IDS.length];
        for (int i = 0; i < items.length; i++) items[i] = Settings.MODEL_NAMES[i] + " — " + Settings.MODEL_NOTES[i];
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("Model")
                .setSingleChoiceItems(items, current, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        String id = Settings.MODEL_IDS[which];
                        settings.setModel(id);
                        if (id.equals(conv.model)) return;
                        if (conv.messages.length() == 0) {
                            conv.model = id; // empty chat: just switch
                        } else {
                            // Thinking blocks belong to the model that wrote them, so a
                            // different model gets a fresh conversation.
                            store.save(conv);
                            conv = store.create(id);
                            toast("New chat with " + Settings.modelName(id));
                        }
                        renderConversation();
                    }
                })
                .show();
    }

    private void send() {
        if (streaming) return;
        final String text = composer.getText().toString().trim();
        if (text.length() == 0) return;
        final String key = settings.apiKey();
        if (key.length() == 0) {
            toast("Add your API key in Settings first.");
            show(SETTINGS);
            return;
        }
        try {
            conv.messages.put(new JSONObject().put("role", "user").put("content", text));
        } catch (JSONException e) {
            return;
        }
        if (conv.messages.length() == 1) {
            conv.title = text.length() > 40 ? text.substring(0, 40).trim() + "…" : text;
            messagesBox.removeAllViews();
            titleView.setText(conv.title);
        }
        composer.setText("");
        addUserBubble(text);
        streamed.setLength(0);
        streamingView = addAssistant("");
        streamingView.setText("…");
        streamingView.setTextColor(Theme.TEXT_DIM);
        scrollToBottom(true);
        setStreaming(true);

        final String model = conv.model;
        final String effort = settings.effort();
        final JSONArray history = conv.messages;
        final Store.Conversation target = conv;
        new Thread(new Runnable() {
            public void run() {
                client.stream(key, model, effort, history, new ClaudeClient.Listener() {
                    public void onText(final String delta) {
                        ui.post(new Runnable() {
                            public void run() {
                                streamed.append(delta);
                                scheduleRender();
                            }
                        });
                    }

                    public void onFallback() {
                        ui.post(new Runnable() {
                            public void run() { streamed.append("\n\n_(continued by a fallback model)_\n\n"); }
                        });
                    }

                    public void onDone(final JSONArray content, final String stopReason, String served) {
                        ui.post(new Runnable() {
                            public void run() { finishTurn(target, content, stopReason); }
                        });
                    }

                    public void onError(final String message) {
                        ui.post(new Runnable() {
                            public void run() { failTurn(target, text, message); }
                        });
                    }
                });
            }
        }).start();
    }

    /** Re-render the streaming reply at most every 150 ms; Markdown on every token is too slow. */
    private void scheduleRender() {
        if (renderPending) return;
        renderPending = true;
        ui.postDelayed(new Runnable() {
            public void run() {
                renderPending = false;
                if (streamingView == null) return;
                boolean bottom = nearBottom();
                streamingView.setTextColor(Theme.TEXT);
                streamingView.setText(Markdown.render(streamed.toString(), density));
                if (bottom) scrollToBottom(true);
            }
        }, 150);
    }

    private void finishTurn(Store.Conversation target, JSONArray content, String stopReason) {
        try {
            target.messages.put(new JSONObject().put("role", "assistant").put("content", content));
        } catch (JSONException ignored) {
        }
        store.save(target);
        setStreaming(false);
        if (target == conv && streamingView != null) {
            streamingView.setTextColor(Theme.TEXT);
            streamingView.setText(Markdown.render(ClaudeClient.visibleText(content), density));
            if ("max_tokens".equals(stopReason)) addNote("Reply hit the length limit.", Theme.TEXT_DIM);
            scrollToBottom(false);
        }
        streamingView = null;
    }

    /** Remove the unanswered user message so the history stays valid, and give the text back. */
    private void failTurn(Store.Conversation target, String text, String message) {
        JSONArray kept = new JSONArray();
        for (int i = 0; i < target.messages.length() - 1; i++) kept.put(target.messages.opt(i));
        target.messages = kept;
        setStreaming(false);
        streamingView = null;
        if (target != conv) return;
        if (kept.length() == 0) conv.title = "New chat";
        String partial = streamed.toString();
        renderConversation();
        if (composer.getText().length() == 0) {
            composer.setText(text);
            composer.setSelection(text.length());
        }
        if (message == null) {
            if (partial.length() > 0) addNote("Stopped. Partial reply (not saved):\n" + partial, Theme.TEXT_DIM);
            else addNote("Stopped.", Theme.TEXT_DIM);
        } else {
            addNote(message + "\nYour message is back in the box; send it again to retry.", Theme.ERROR);
        }
    }

    // ---------------------------------------------------------------- chats screen

    private View buildChats() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Theme.BG);
        LinearLayout bar = topBar();
        bar.addView(iconButton(Theme.Icon.BACK, Theme.TEXT, new View.OnClickListener() {
            public void onClick(View v) { show(CHAT); }
        }));
        TextView title = text("Chats", 20, Theme.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(iconButton(Theme.Icon.PLUS, Theme.ACCENT, new View.OnClickListener() {
            public void onClick(View v) { newChat(); show(CHAT); }
        }));
        col.addView(bar);
        col.addView(divider());

        chatsList = new ListView(this);
        chatsList.setDivider(new android.graphics.drawable.ColorDrawable(Theme.DIVIDER));
        chatsList.setDividerHeight(1);
        chatsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (streaming) { toast("Wait for the reply to finish."); return; }
                Store.Conversation c = store.load(chats.get(pos).id);
                if (c != null) { store.save(conv); conv = c; }
                show(CHAT);
            }
        });
        chatsList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                final Store.Conversation c = chats.get(pos);
                new AlertDialog.Builder(MainActivity.this, AlertDialog.THEME_HOLO_DARK)
                        .setTitle("Delete chat?")
                        .setMessage(c.title)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                store.delete(c.id);
                                if (c.id.equals(conv.id) && !streaming) conv = store.create(settings.model());
                                refreshChats();
                            }
                        })
                        .show();
                return true;
            }
        });
        col.addView(chatsList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    private void refreshChats() {
        store.save(conv);
        chats = store.list();
        chatsList.setAdapter(new BaseAdapter() {
            public int getCount() { return chats.size(); }
            public Object getItem(int i) { return chats.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View old, ViewGroup parent) {
                Store.Conversation c = chats.get(i);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(18), dp(14), dp(18), dp(14));
                TextView t = text(c.title, settings.textSize(), c.id.equals(conv.id) ? Theme.ACCENT : Theme.TEXT);
                t.setSingleLine(true);
                row.addView(t);
                CharSequence when = DateUtils.getRelativeTimeSpanString(c.updated);
                row.addView(text(when + " · " + Settings.modelName(c.model), 13, Theme.TEXT_DIM));
                return row;
            }
        });
        if (chats.isEmpty()) toast("No saved chats yet.");
    }

    // ---------------------------------------------------------------- settings screen

    private TextView section(LinearLayout col, String label) {
        TextView t = text(label.toUpperCase(), 13, Theme.ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(22), 0, dp(6));
        col.addView(t);
        return t;
    }

    private View buildSettings() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Theme.BG);
        LinearLayout bar = topBar();
        bar.addView(iconButton(Theme.Icon.BACK, Theme.TEXT, new View.OnClickListener() {
            public void onClick(View v) { show(CHAT); }
        }));
        TextView title = text("Settings", 20, Theme.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        outer.addView(bar);
        outer.addView(divider());

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), 0, dp(18), dp(24));
        sv.addView(col);
        outer.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // API key
        section(col, "Anthropic API key");
        final EditText key = new EditText(this);
        settingsKey = key;
        key.setText(settings.apiKey());
        key.setHint("sk-ant-…");
        key.setTextColor(Theme.TEXT);
        key.setHintTextColor(Theme.TEXT_DIM);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) { if (!has) settings.setApiKey(key.getText().toString()); }
        });
        col.addView(key);
        CheckBox showKey = new CheckBox(this);
        showKey.setText("Show key");
        showKey.setTextColor(Theme.TEXT_DIM);
        showKey.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                key.setInputType(InputType.TYPE_CLASS_TEXT | (on
                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                key.setSelection(key.getText().length());
            }
        });
        col.addView(showKey);
        final TextView keyStatus = text("Stored only on this phone. Get a key at console.anthropic.com.",
                13, Theme.TEXT_DIM);
        Button test = new Button(this);
        test.setText("Save and test key");
        test.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String k = key.getText().toString().trim();
                settings.setApiKey(k);
                keyStatus.setTextColor(Theme.TEXT_DIM);
                keyStatus.setText("Testing…");
                new Thread(new Runnable() {
                    public void run() {
                        final String err = ClaudeClient.testKey(k);
                        ui.post(new Runnable() {
                            public void run() {
                                keyStatus.setTextColor(err == null ? 0xFF6CC56C : Theme.ERROR);
                                keyStatus.setText(err == null ? "Key works. You're ready to chat." : err);
                            }
                        });
                    }
                }).start();
            }
        });
        col.addView(test);
        col.addView(keyStatus);

        // Default model
        section(col, "Model for new chats");
        RadioGroup models = new RadioGroup(this);
        for (int i = 0; i < Settings.MODEL_IDS.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(1000 + i);
            r.setText(Settings.MODEL_NAMES[i] + "  —  " + Settings.MODEL_NOTES[i]);
            r.setTextColor(Theme.TEXT);
            r.setTextSize(16);
            models.addView(r);
            if (Settings.MODEL_IDS[i].equals(settings.model())) r.setChecked(true);
        }
        models.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) { settings.setModel(Settings.MODEL_IDS[id - 1000]); }
        });
        col.addView(models);

        // Effort
        section(col, "Effort");
        col.addView(text("Low is quick and cheap; high thinks longer on hard questions.", 13, Theme.TEXT_DIM));
        RadioGroup efforts = new RadioGroup(this);
        efforts.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < Settings.EFFORTS.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(2000 + i);
            r.setText(Settings.EFFORTS[i]);
            r.setTextColor(Theme.TEXT);
            r.setTextSize(16);
            r.setPadding(0, 0, dp(18), 0);
            efforts.addView(r);
            if (Settings.EFFORTS[i].equals(settings.effort())) r.setChecked(true);
        }
        efforts.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) { settings.setEffort(Settings.EFFORTS[id - 2000]); }
        });
        col.addView(efforts);

        // Text size
        section(col, "Text size");
        final TextView preview = text("Claude's replies will look like this.", settings.textSize(), Theme.TEXT);
        SeekBar size = new SeekBar(this);
        size.setMax(16); // 14..30 sp
        size.setProgress(settings.textSize() - 14);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                settings.setTextSize(14 + p);
                preview.setTextSize(14 + p);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        col.addView(size);
        col.addView(preview);

        section(col, "About");
        col.addView(text("bbclaude 1.0 — an unofficial, open-source Claude client for BlackBerry 10. "
                + "Not affiliated with Anthropic or BlackBerry. You use your own API key; "
                + "it never leaves this phone except to call api.anthropic.com.\n"
                + "Enter sends, Shift+Enter starts a new line. Long-press a chat in the list to delete it.",
                14, Theme.TEXT_DIM));
        return outer;
    }
}
