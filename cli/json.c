#include "json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- string buffer ---- */

void sb_init(sbuf *s) { s->buf = NULL; s->len = s->cap = 0; }

void sb_append(sbuf *s, const char *data, size_t n) {
    if (s->len + n + 1 > s->cap) {
        size_t cap = s->cap ? s->cap : 256;
        while (cap < s->len + n + 1) cap *= 2;
        s->buf = realloc(s->buf, cap);
        if (!s->buf) { fputs("out of memory\n", stderr); exit(1); }
        s->cap = cap;
    }
    memcpy(s->buf + s->len, data, n);
    s->len += n;
    s->buf[s->len] = '\0';
}

void sb_puts(sbuf *s, const char *str) { sb_append(s, str, strlen(str)); }

void sb_free(sbuf *s) { free(s->buf); sb_init(s); }

static char *xstrdup(const char *s) {
    size_t n = strlen(s) + 1;
    char *d = malloc(n);
    if (!d) { fputs("out of memory\n", stderr); exit(1); }
    memcpy(d, s, n);
    return d;
}

/* ---- construction ---- */

jnode *json_new(jtype t) {
    jnode *n = calloc(1, sizeof *n);
    if (!n) { fputs("out of memory\n", stderr); exit(1); }
    n->type = t;
    return n;
}

jnode *json_new_string(const char *s) {
    jnode *n = json_new(J_STRING);
    n->str = xstrdup(s);
    return n;
}

jnode *json_new_number(const char *literal) {
    jnode *n = json_new(J_NUMBER);
    n->str = xstrdup(literal);
    return n;
}

void json_add(jnode *c, const char *key, jnode *v) {
    jnode **p = &c->child;
    if (key) { free(v->key); v->key = xstrdup(key); }
    while (*p) p = &(*p)->next;
    *p = v;
}

jnode *json_get(const jnode *obj, const char *key) {
    jnode *c;
    if (!obj || obj->type != J_OBJECT) return NULL;
    for (c = obj->child; c; c = c->next)
        if (c->key && strcmp(c->key, key) == 0) return c;
    return NULL;
}

const char *json_get_str(const jnode *obj, const char *key) {
    jnode *v = json_get(obj, key);
    return (v && v->type == J_STRING) ? v->str : NULL;
}

jnode *json_detach(jnode *obj, const char *key) {
    jnode **p;
    if (!obj || obj->type != J_OBJECT) return NULL;
    for (p = &obj->child; *p; p = &(*p)->next) {
        if ((*p)->key && strcmp((*p)->key, key) == 0) {
            jnode *found = *p;
            *p = found->next;
            found->next = NULL;
            return found;
        }
    }
    return NULL;
}

void json_set(jnode *obj, const char *key, jnode *v) {
    jnode **p;
    free(v->key);
    v->key = xstrdup(key);
    /* Replace in place so member order (and the serialized bytes) stay stable. */
    for (p = &obj->child; *p; p = &(*p)->next) {
        if ((*p)->key && strcmp((*p)->key, key) == 0) {
            jnode *old = *p;
            v->next = old->next;
            old->next = NULL;
            *p = v;
            json_free(old);
            return;
        }
    }
    *p = v;
}

jnode *json_clone(const jnode *n) {
    jnode *c, *src;
    if (!n) return NULL;
    c = json_new(n->type);
    if (n->key) c->key = xstrdup(n->key);
    if (n->str) c->str = xstrdup(n->str);
    for (src = n->child; src; src = src->next) {
        jnode *copy = json_clone(src);
        jnode **p = &c->child;
        while (*p) p = &(*p)->next;
        *p = copy;
    }
    return c;
}

void json_free(jnode *n) {
    while (n) {
        jnode *next = n->next;
        json_free(n->child);
        free(n->key);
        free(n->str);
        free(n);
        n = next;
    }
}

/* ---- parser ---- */

typedef struct { const char *p; int err; } parser;

static void skip_ws(parser *ps) {
    while (*ps->p == ' ' || *ps->p == '\t' || *ps->p == '\n' || *ps->p == '\r') ps->p++;
}

static void put_utf8(sbuf *s, unsigned long cp) {
    char b[4];
    if (cp < 0x80) { b[0] = (char)cp; sb_append(s, b, 1); }
    else if (cp < 0x800) {
        b[0] = (char)(0xC0 | (cp >> 6)); b[1] = (char)(0x80 | (cp & 0x3F));
        sb_append(s, b, 2);
    } else if (cp < 0x10000) {
        b[0] = (char)(0xE0 | (cp >> 12)); b[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        b[2] = (char)(0x80 | (cp & 0x3F));
        sb_append(s, b, 3);
    } else {
        b[0] = (char)(0xF0 | (cp >> 18)); b[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
        b[2] = (char)(0x80 | ((cp >> 6) & 0x3F)); b[3] = (char)(0x80 | (cp & 0x3F));
        sb_append(s, b, 4);
    }
}

static int hex4(const char *p, unsigned long *out) {
    int i;
    unsigned long v = 0;
    for (i = 0; i < 4; i++) {
        char c = p[i];
        v <<= 4;
        if (c >= '0' && c <= '9') v |= (unsigned long)(c - '0');
        else if (c >= 'a' && c <= 'f') v |= (unsigned long)(c - 'a' + 10);
        else if (c >= 'A' && c <= 'F') v |= (unsigned long)(c - 'A' + 10);
        else return 0;
    }
    *out = v;
    return 1;
}

static char *parse_string_raw(parser *ps) {
    sbuf s;
    sb_init(&s);
    sb_append(&s, "", 0);
    ps->p++; /* opening quote */
    while (*ps->p && *ps->p != '"') {
        if (*ps->p == '\\') {
            char e = *++ps->p;
            switch (e) {
            case '"': sb_append(&s, "\"", 1); break;
            case '\\': sb_append(&s, "\\", 1); break;
            case '/': sb_append(&s, "/", 1); break;
            case 'b': sb_append(&s, "\b", 1); break;
            case 'f': sb_append(&s, "\f", 1); break;
            case 'n': sb_append(&s, "\n", 1); break;
            case 'r': sb_append(&s, "\r", 1); break;
            case 't': sb_append(&s, "\t", 1); break;
            case 'u': {
                unsigned long cp, lo;
                if (!hex4(ps->p + 1, &cp)) { ps->err = 1; sb_free(&s); return NULL; }
                ps->p += 4;
                if (cp >= 0xD800 && cp <= 0xDBFF && ps->p[1] == '\\' && ps->p[2] == 'u'
                    && hex4(ps->p + 3, &lo) && lo >= 0xDC00 && lo <= 0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    ps->p += 6;
                }
                put_utf8(&s, cp);
                break;
            }
            default: ps->err = 1; sb_free(&s); return NULL;
            }
            ps->p++;
        } else {
            sb_append(&s, ps->p, 1);
            ps->p++;
        }
    }
    if (*ps->p != '"') { ps->err = 1; sb_free(&s); return NULL; }
    ps->p++;
    return s.buf;
}

static jnode *parse_value(parser *ps, int depth);

static jnode *parse_container(parser *ps, int depth, int is_obj) {
    jnode *n = json_new(is_obj ? J_OBJECT : J_ARRAY);
    char close = is_obj ? '}' : ']';
    ps->p++;
    skip_ws(ps);
    if (*ps->p == close) { ps->p++; return n; }
    for (;;) {
        char *key = NULL;
        jnode *v;
        skip_ws(ps);
        if (is_obj) {
            if (*ps->p != '"') { ps->err = 1; break; }
            key = parse_string_raw(ps);
            if (!key) break;
            skip_ws(ps);
            if (*ps->p != ':') { free(key); ps->err = 1; break; }
            ps->p++;
        }
        v = parse_value(ps, depth + 1);
        if (!v) { free(key); break; }
        v->key = key;
        json_add(n, NULL, v);
        skip_ws(ps);
        if (*ps->p == ',') { ps->p++; continue; }
        if (*ps->p == close) { ps->p++; return n; }
        ps->err = 1;
        break;
    }
    json_free(n);
    return NULL;
}

static jnode *parse_value(parser *ps, int depth) {
    skip_ws(ps);
    if (depth > 64) { ps->err = 1; return NULL; }
    switch (*ps->p) {
    case '{': return parse_container(ps, depth, 1);
    case '[': return parse_container(ps, depth, 0);
    case '"': {
        char *s = parse_string_raw(ps);
        jnode *n;
        if (!s) return NULL;
        n = json_new(J_STRING);
        n->str = s;
        return n;
    }
    case 't':
        if (strncmp(ps->p, "true", 4) == 0) { ps->p += 4; return json_new(J_TRUE); }
        break;
    case 'f':
        if (strncmp(ps->p, "false", 5) == 0) { ps->p += 5; return json_new(J_FALSE); }
        break;
    case 'n':
        if (strncmp(ps->p, "null", 4) == 0) { ps->p += 4; return json_new(J_NULL); }
        break;
    default: {
        const char *start = ps->p;
        if (*ps->p == '-') ps->p++;
        while ((*ps->p >= '0' && *ps->p <= '9') || *ps->p == '.' || *ps->p == 'e'
               || *ps->p == 'E' || *ps->p == '+' || *ps->p == '-')
            ps->p++;
        if (ps->p > start) {
            jnode *n = json_new(J_NUMBER);
            size_t len = (size_t)(ps->p - start);
            n->str = malloc(len + 1);
            memcpy(n->str, start, len);
            n->str[len] = '\0';
            return n;
        }
    }
    }
    ps->err = 1;
    return NULL;
}

jnode *json_parse(const char *text) {
    parser ps;
    jnode *n;
    ps.p = text;
    ps.err = 0;
    n = parse_value(&ps, 0);
    if (!n) return NULL;
    skip_ws(&ps);
    if (ps.err || *ps.p) { json_free(n); return NULL; }
    return n;
}

/* ---- serializer ---- */

static void put_escaped(sbuf *out, const char *s) {
    sb_append(out, "\"", 1);
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        switch (c) {
        case '"': sb_append(out, "\\\"", 2); break;
        case '\\': sb_append(out, "\\\\", 2); break;
        case '\n': sb_append(out, "\\n", 2); break;
        case '\r': sb_append(out, "\\r", 2); break;
        case '\t': sb_append(out, "\\t", 2); break;
        default:
            if (c < 0x20) {
                char esc[8];
                sprintf(esc, "\\u%04x", c);
                sb_puts(out, esc);
            } else {
                sb_append(out, s, 1); /* UTF-8 bytes pass through */
            }
        }
    }
    sb_append(out, "\"", 1);
}

void json_serialize(const jnode *n, sbuf *out) {
    const jnode *c;
    switch (n->type) {
    case J_NULL: sb_puts(out, "null"); break;
    case J_FALSE: sb_puts(out, "false"); break;
    case J_TRUE: sb_puts(out, "true"); break;
    case J_NUMBER: sb_puts(out, n->str); break;
    case J_STRING: put_escaped(out, n->str); break;
    case J_ARRAY:
    case J_OBJECT:
        sb_puts(out, n->type == J_ARRAY ? "[" : "{");
        for (c = n->child; c; c = c->next) {
            if (c != n->child) sb_puts(out, ",");
            if (n->type == J_OBJECT) { put_escaped(out, c->key); sb_puts(out, ":"); }
            json_serialize(c, out);
        }
        sb_puts(out, n->type == J_ARRAY ? "]" : "}");
        break;
    }
}
