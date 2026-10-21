/* bbclaude: a terminal Claude client small enough to build with the
 * on-device GCC 4.6 on BlackBerry 10 (Term48 + Berrymuch), and on macOS. */
#include "json.h"

#include <curl/curl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define DEFAULT_BASE_URL "https://api.anthropic.com"
#define FALLBACK_BETA "server-side-fallback-2026-07-01"

static const char *SYSTEM_PROMPT =
    "You are chatting with the user through a terminal on a BlackBerry Passport, "
    "a phone with a small square screen. Answer in plain text. Avoid tables, "
    "wide code blocks and heavy Markdown, and keep answers reasonably short "
    "unless the user asks for detail.";

static char g_model[64] = "claude-opus-5-5";
static char g_effort[16] = "low";
static const char *g_cacert = NULL;
static const char *g_base_url = DEFAULT_BASE_URL;
static int g_verbose = 0;
static char *g_api_key = NULL;
static jnode *g_history = NULL; /* the "messages" array, append-only */
static volatile sig_atomic_t g_cancel = 0;

static void on_sigint(int sig) { (void)sig; g_cancel = 1; }

/* ---- streaming state ---- */

typedef struct {
    CURL *curl;
    long status;
    sbuf pending;   /* SSE bytes not yet split into events */
    sbuf errbody;   /* body of a non-200 response */
    jnode *blocks;  /* content blocks rebuilt from the stream */
    char stop_reason[48];
    char served_model[64];
    char error[512];
    int printed_text;
} stream_t;

static void str_append(jnode *n, const char *more) {
    size_t a = n->str ? strlen(n->str) : 0, b = strlen(more);
    n->str = realloc(n->str, a + b + 1);
    memcpy(n->str + a, more, b + 1);
}

static jnode *block_at(stream_t *st, long index) {
    jnode *b = st->blocks->child;
    while (b && index-- > 0) b = b->next;
    return b;
}

static void handle_event(stream_t *st, const char *data) {
    jnode *ev = json_parse(data);
    const char *type;
    if (!ev) return;
    type = json_get_str(ev, "type");
    if (!type) { json_free(ev); return; }

    if (strcmp(type, "message_start") == 0) {
        const char *m = json_get_str(json_get(ev, "message"), "model");
        if (m) snprintf(st->served_model, sizeof st->served_model, "%s", m);
    } else if (strcmp(type, "content_block_start") == 0) {
        jnode *cb = json_detach(ev, "content_block");
        if (cb) {
            const char *bt = json_get_str(cb, "type");
            free(cb->key);
            cb->key = NULL;
            json_add(st->blocks, NULL, cb);
            if (bt && strcmp(bt, "fallback") == 0)
                fputs("\n[answer continued by a fallback model]\n", stdout);
        }
    } else if (strcmp(type, "content_block_delta") == 0) {
        jnode *idx = json_get(ev, "index");
        jnode *delta = json_get(ev, "delta");
        jnode *blk = idx ? block_at(st, strtol(idx->str, NULL, 10)) : NULL;
        const char *dt = json_get_str(delta, "type");
        if (blk && dt) {
            const char *field = NULL, *val = NULL;
            if (strcmp(dt, "text_delta") == 0) {
                field = "text"; val = json_get_str(delta, "text");
                if (val) { fputs(val, stdout); fflush(stdout); st->printed_text = 1; }
            } else if (strcmp(dt, "thinking_delta") == 0) {
                field = "thinking"; val = json_get_str(delta, "thinking");
            } else if (strcmp(dt, "signature_delta") == 0) {
                /* Signatures are replaced, not appended. */
                val = json_get_str(delta, "signature");
                if (val) json_set(blk, "signature", json_new_string(val));
            }
            if (field && val) {
                jnode *f = json_get(blk, field);
                if (f && f->type == J_STRING) str_append(f, val);
                else json_set(blk, field, json_new_string(val));
            }
        }
    } else if (strcmp(type, "message_delta") == 0) {
        const char *sr = json_get_str(json_get(ev, "delta"), "stop_reason");
        if (sr) snprintf(st->stop_reason, sizeof st->stop_reason, "%s", sr);
    } else if (strcmp(type, "error") == 0) {
        jnode *e = json_get(ev, "error");
        snprintf(st->error, sizeof st->error, "%s: %s",
                 json_get_str(e, "type") ? json_get_str(e, "type") : "error",
                 json_get_str(e, "message") ? json_get_str(e, "message") : "(no message)");
    }
    json_free(ev);
}

/* Split buffered bytes into SSE events (blank-line separated) and dispatch
 * the concatenated "data:" lines of each one. */
static void drain_events(stream_t *st) {
    char *start = st->pending.buf, *end;
    if (!start) return;
    while ((end = strstr(start, "\n\n")) != NULL) {
        sbuf data;
        char *line = start;
        *end = '\0';
        sb_init(&data);
        while (line && *line) {
            char *nl = strchr(line, '\n');
            if (nl) *nl = '\0';
            if (strncmp(line, "data:", 5) == 0) {
                const char *v = line + 5;
                if (*v == ' ') v++;
                if (data.len) sb_puts(&data, "\n");
                sb_puts(&data, v);
            }
            line = nl ? nl + 1 : NULL;
        }
        if (data.len) handle_event(st, data.buf);
        sb_free(&data);
        start = end + 2;
    }
    /* Keep the unfinished tail. */
    {
        size_t rest = strlen(start);
        memmove(st->pending.buf, start, rest + 1);
        st->pending.len = rest;
    }
}

static size_t on_body(char *ptr, size_t size, size_t nmemb, void *userdata) {
    stream_t *st = userdata;
    size_t n = size * nmemb, i;
    if (g_cancel) return 0; /* aborts the transfer */
    if (!st->status) curl_easy_getinfo(st->curl, CURLINFO_RESPONSE_CODE, &st->status);
    if (st->status != 200) { sb_append(&st->errbody, ptr, n); return n; }
    for (i = 0; i < n; i++)
        if (ptr[i] != '\r') sb_append(&st->pending, ptr + i, 1);
    drain_events(st);
    return n;
}

/* ---- request ---- */

static int model_has_server_fallback(const char *m) {
    return strcmp(m, "claude-opus-5-5") == 0 || strcmp(m, "claude-opus-5") == 0
        || strcmp(m, "claude-fable-5-1") == 0 || strcmp(m, "claude-sonnet-5-5") == 0;
}

static void apply_tls_options(CURL *c, char *errbuf) {
    curl_easy_setopt(c, CURLOPT_ERRORBUFFER, errbuf);
    curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, 30L);
#ifdef CURL_SSLVERSION_TLSv1_2
    curl_easy_setopt(c, CURLOPT_SSLVERSION, (long)CURL_SSLVERSION_TLSv1_2);
#endif
    if (g_cacert) curl_easy_setopt(c, CURLOPT_CAINFO, g_cacert);
    if (g_verbose) curl_easy_setopt(c, CURLOPT_VERBOSE, 1L);
}

static void print_curl_failure(CURLcode rc, const char *errbuf) {
    fprintf(stderr, "\n[network error] %s\n", errbuf[0] ? errbuf : curl_easy_strerror(rc));
    if (rc == CURLE_SSL_CONNECT_ERROR
#ifdef CURLE_PEER_FAILED_VERIFICATION
        || rc == CURLE_PEER_FAILED_VERIFICATION
#endif
#ifdef CURLE_SSL_CACERT
        || rc == CURLE_SSL_CACERT
#endif
        )
        fputs("TLS failed. If the phone's root certificates are too old, pass a\n"
              "current CA bundle with --cacert cacert.pem (see README).\n", stderr);
}

/* Drop the trailing user message so the history stays valid after a failed turn. */
static void pop_last_message(void) {
    jnode **p = &g_history->child;
    if (!*p) return;
    while ((*p)->next) p = &(*p)->next;
    json_free(*p);
    *p = NULL;
}

/* After a fallback, blocks the declined model produced before the last
 * fallback marker must not be echoed back (thinking, tool use). */
static jnode *assistant_content(jnode *blocks) {
    jnode *out = json_new(J_ARRAY), *b, *last_fb = NULL;
    for (b = blocks->child; b; b = b->next) {
        const char *t = json_get_str(b, "type");
        if (t && strcmp(t, "fallback") == 0) last_fb = b;
    }
    for (b = blocks->child; b; b = b->next) {
        const char *t = json_get_str(b, "type");
        int before_fb = 0;
        jnode *s;
        if (!t) continue;
        if (last_fb) {
            for (s = b; s; s = s->next) if (s == last_fb) { before_fb = 1; break; }
        }
        if (strcmp(t, "fallback") == 0) continue;
        if (before_fb && strcmp(t, "text") != 0) continue;
        json_add(out, NULL, json_clone(b));
    }
    return out;
}

static void send_turn(void) {
    CURL *c = curl_easy_init();
    struct curl_slist *hdr = NULL;
    sbuf body, keyhdr;
    stream_t st;
    char errbuf[CURL_ERROR_SIZE] = "";
    CURLcode rc;
    jnode *tmp;

    if (!c) { fputs("curl init failed\n", stderr); pop_last_message(); return; }

    /* Request body. */
    sb_init(&body);
    sb_puts(&body, "{\"model\":");
    tmp = json_new_string(g_model); json_serialize(tmp, &body); json_free(tmp);
    sb_puts(&body, ",\"max_tokens\":64000,\"stream\":true,\"output_config\":{\"effort\":");
    tmp = json_new_string(g_effort); json_serialize(tmp, &body); json_free(tmp);
    sb_puts(&body, "}");
    if (model_has_server_fallback(g_model)) sb_puts(&body, ",\"fallbacks\":\"default\"");
    sb_puts(&body, ",\"system\":");
    tmp = json_new_string(SYSTEM_PROMPT); json_serialize(tmp, &body); json_free(tmp);
    sb_puts(&body, ",\"messages\":");
    json_serialize(g_history, &body);
    sb_puts(&body, "}");

    sb_init(&keyhdr);
    sb_puts(&keyhdr, "x-api-key: ");
    sb_puts(&keyhdr, g_api_key);
    hdr = curl_slist_append(hdr, keyhdr.buf);
    hdr = curl_slist_append(hdr, "anthropic-version: 2023-06-01");
    hdr = curl_slist_append(hdr, "content-type: application/json");
    hdr = curl_slist_append(hdr, "accept: text/event-stream");
    if (model_has_server_fallback(g_model))
        hdr = curl_slist_append(hdr, "anthropic-beta: " FALLBACK_BETA);

    memset(&st, 0, sizeof st);
    st.curl = c;
    sb_init(&st.pending);
    sb_init(&st.errbody);
    st.blocks = json_new(J_ARRAY);

    {
        char url[512];
        snprintf(url, sizeof url, "%s/v1/messages", g_base_url);
        curl_easy_setopt(c, CURLOPT_URL, url);
    }
    curl_easy_setopt(c, CURLOPT_HTTPHEADER, hdr);
    curl_easy_setopt(c, CURLOPT_POSTFIELDS, body.buf);
    curl_easy_setopt(c, CURLOPT_POSTFIELDSIZE, (long)body.len);
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION, on_body);
    curl_easy_setopt(c, CURLOPT_WRITEDATA, &st);
    /* Give up only if the stream stalls for 5 minutes. */
    curl_easy_setopt(c, CURLOPT_LOW_SPEED_LIMIT, 1L);
    curl_easy_setopt(c, CURLOPT_LOW_SPEED_TIME, 300L);
    apply_tls_options(c, errbuf);

    g_cancel = 0;
    rc = curl_easy_perform(c);
    if (!st.status) curl_easy_getinfo(c, CURLINFO_RESPONSE_CODE, &st.status);

    if (g_cancel) {
        fputs("\n[cancelled]\n", stdout);
        pop_last_message();
    } else if (rc != CURLE_OK) {
        print_curl_failure(rc, errbuf);
        pop_last_message();
    } else if (st.status != 200) {
        jnode *err = st.errbody.buf ? json_parse(st.errbody.buf) : NULL;
        jnode *e = json_get(err, "error");
        fprintf(stderr, "[HTTP %ld] %s: %s\n", st.status,
                json_get_str(e, "type") ? json_get_str(e, "type") : "error",
                json_get_str(e, "message") ? json_get_str(e, "message")
                                           : (st.errbody.buf ? st.errbody.buf : ""));
        json_free(err);
        pop_last_message();
    } else if (st.error[0]) {
        fprintf(stderr, "\n[stream error] %s\n", st.error);
        pop_last_message();
    } else if (strcmp(st.stop_reason, "refusal") == 0) {
        fputs("\n[Claude declined this request. It was removed from the conversation.]\n",
              stdout);
        pop_last_message();
    } else {
        jnode *content = assistant_content(st.blocks);
        if (!content->child) {
            json_free(content);
            fputs("\n[empty reply]\n", stdout);
            pop_last_message();
        } else {
            jnode *msg = json_new(J_OBJECT);
            json_add(msg, "role", json_new_string("assistant"));
            json_add(msg, "content", content);
            json_add(g_history, NULL, msg);
            if (strcmp(st.stop_reason, "max_tokens") == 0)
                fputs("\n[reply hit the length limit]", stdout);
        }
        if (g_verbose && strcmp(st.served_model, g_model) != 0)
            fprintf(stderr, "\n[served by %s]", st.served_model);
        fputs("\n", stdout);
    }

    json_free(st.blocks);
    sb_free(&st.pending);
    sb_free(&st.errbody);
    sb_free(&body);
    sb_free(&keyhdr);
    curl_slist_free_all(hdr);
    curl_easy_cleanup(c);
}

/* ---- TLS probe: does HTTPS to the API work from this device at all? ---- */

static size_t discard(char *p, size_t s, size_t n, void *u) { (void)p; (void)u; return s * n; }

static int run_probe(void) {
    CURL *c = curl_easy_init();
    char errbuf[CURL_ERROR_SIZE] = "";
    curl_version_info_data *vi = curl_version_info(CURLVERSION_NOW);
    long status = 0;
    CURLcode rc;

    printf("libcurl %s, TLS library: %s\n", vi->version, vi->ssl_version ? vi->ssl_version : "none");
    {
        char url[512];
        snprintf(url, sizeof url, "%s/v1/models", g_base_url);
        curl_easy_setopt(c, CURLOPT_URL, url);
    }
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION, discard);
    apply_tls_options(c, errbuf);
    rc = curl_easy_perform(c);
    if (rc != CURLE_OK) {
        print_curl_failure(rc, errbuf);
        curl_easy_cleanup(c);
        return 1;
    }
    curl_easy_getinfo(c, CURLINFO_RESPONSE_CODE, &status);
    printf("Connection OK, %s answered HTTP %ld%s\n", g_base_url, status,
           status == 401 ? " (expected without an API key)" : "");
    curl_easy_cleanup(c);
    return 0;
}

/* ---- setup and REPL ---- */

static char *read_key_file(const char *path) {
    FILE *f = fopen(path, "r");
    char line[512];
    char *nl;
    if (!f) return NULL;
    if (!fgets(line, sizeof line, f)) { fclose(f); return NULL; }
    fclose(f);
    nl = strpbrk(line, "\r\n");
    if (nl) *nl = '\0';
    if (!line[0]) return NULL;
    return strcpy(malloc(strlen(line) + 1), line);
}

static char *find_api_key(const char *key_file) {
    const char *env = getenv("ANTHROPIC_API_KEY");
    const char *home = getenv("HOME");
    char path[1024];
    if (key_file) return read_key_file(key_file);
    if (env && *env) return strcpy(malloc(strlen(env) + 1), env);
    if (home) {
        snprintf(path, sizeof path, "%s/.bbclaude_key", home);
        return read_key_file(path);
    }
    return NULL;
}

static void usage(void) {
    puts("usage: bbclaude [options]\n"
         "  -m MODEL      model id (default claude-opus-5-5)\n"
         "  -e EFFORT     low | medium | high | xhigh | max (default low)\n"
         "  -k FILE       read the API key from FILE\n"
         "  --base-url URL  API host (default https://api.anthropic.com), e.g. a relay\n"
         "  --cacert PEM  CA bundle to trust (for devices with outdated roots)\n"
         "  --probe       only test the HTTPS connection, then exit\n"
         "  -v            verbose: print curl/TLS details\n"
         "API key: ANTHROPIC_API_KEY, or the first line of ~/.bbclaude_key");
}

static void help(void) {
    puts("Commands: /new (start over)  /model ID (also starts over)  /effort LEVEL  /quit\n"
         "End a line with \\ to keep typing on the next line. Ctrl-C stops a reply.");
}

int main(int argc, char **argv) {
    const char *key_file = NULL;
    int probe = 0, i;
    sbuf input;
    char line[4096];

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-m") == 0 && i + 1 < argc) snprintf(g_model, sizeof g_model, "%s", argv[++i]);
        else if (strcmp(argv[i], "-e") == 0 && i + 1 < argc) snprintf(g_effort, sizeof g_effort, "%s", argv[++i]);
        else if (strcmp(argv[i], "-k") == 0 && i + 1 < argc) key_file = argv[++i];
        else if (strcmp(argv[i], "--cacert") == 0 && i + 1 < argc) g_cacert = argv[++i];
        else if (strcmp(argv[i], "--base-url") == 0 && i + 1 < argc) g_base_url = argv[++i];
        else if (strcmp(argv[i], "--probe") == 0) probe = 1;
        else if (strcmp(argv[i], "-v") == 0) g_verbose = 1;
        else { usage(); return strcmp(argv[i], "-h") == 0 ? 0 : 2; }
    }

    curl_global_init(CURL_GLOBAL_DEFAULT);
    if (probe) return run_probe();

    g_api_key = find_api_key(key_file);
    if (!g_api_key) {
        fputs("No API key. Set ANTHROPIC_API_KEY or put it in ~/.bbclaude_key\n", stderr);
        return 1;
    }

    signal(SIGINT, on_sigint);
    g_history = json_new(J_ARRAY);
    printf("bbclaude - %s, effort %s. /help for commands.\n", g_model, g_effort);

    sb_init(&input);
    for (;;) {
        size_t len;
        fputs(input.len ? "... " : "\nyou> ", stdout);
        fflush(stdout);
        if (!fgets(line, sizeof line, stdin)) {
            if (g_cancel) { g_cancel = 0; clearerr(stdin); sb_free(&input); continue; }
            break;
        }
        len = strlen(line);
        while (len && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = '\0';
        if (len && line[len - 1] == '\\') {
            line[len - 1] = '\n';
            sb_puts(&input, line);
            continue;
        }
        sb_puts(&input, line);

        if (strcmp(input.buf, "/quit") == 0 || strcmp(input.buf, "/exit") == 0) break;
        if (strcmp(input.buf, "/help") == 0) help();
        else if (strcmp(input.buf, "/new") == 0) {
            json_free(g_history);
            g_history = json_new(J_ARRAY);
            puts("[new conversation]");
        } else if (strncmp(input.buf, "/model ", 7) == 0) {
            /* Thinking blocks belong to the model that wrote them, so switch
             * models on a fresh conversation. */
            snprintf(g_model, sizeof g_model, "%s", input.buf + 7);
            json_free(g_history);
            g_history = json_new(J_ARRAY);
            printf("[model %s, new conversation]\n", g_model);
        } else if (strncmp(input.buf, "/effort ", 8) == 0) {
            snprintf(g_effort, sizeof g_effort, "%s", input.buf + 8);
            printf("[effort %s]\n", g_effort);
        } else if (input.buf[0]) {
            jnode *msg = json_new(J_OBJECT);
            json_add(msg, "role", json_new_string("user"));
            json_add(msg, "content", json_new_string(input.buf));
            json_add(g_history, NULL, msg);
            fputs("\nclaude> ", stdout);
            fflush(stdout);
            send_turn();
        }
        sb_free(&input);
    }

    sb_free(&input);
    json_free(g_history);
    free(g_api_key);
    curl_global_cleanup();
    return 0;
}
