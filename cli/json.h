/* Tiny JSON DOM: just enough to build Messages API requests and read
 * streamed events. C99, no dependencies, builds with GCC 4.6 on BB10. */
#ifndef BBCLAUDE_JSON_H
#define BBCLAUDE_JSON_H

#include <stddef.h>

typedef enum { J_NULL, J_FALSE, J_TRUE, J_NUMBER, J_STRING, J_ARRAY, J_OBJECT } jtype;

typedef struct jnode {
    jtype type;
    char *key;            /* set when this node is an object member */
    char *str;            /* J_STRING text (UTF-8) or J_NUMBER literal */
    struct jnode *child;  /* first element/member of an array/object */
    struct jnode *next;   /* next sibling */
} jnode;

/* Dynamic string buffer. */
typedef struct { char *buf; size_t len, cap; } sbuf;
void sb_init(sbuf *s);
void sb_append(sbuf *s, const char *data, size_t n);
void sb_puts(sbuf *s, const char *str);
void sb_free(sbuf *s);

jnode *json_parse(const char *text);          /* NULL on syntax error */
void json_serialize(const jnode *n, sbuf *out);
void json_free(jnode *n);

jnode *json_new(jtype t);
jnode *json_new_string(const char *s);
jnode *json_new_number(const char *literal);
void json_add(jnode *container, const char *key, jnode *value); /* key NULL for arrays */
void json_set(jnode *obj, const char *key, jnode *value);       /* replace or add */
jnode *json_detach(jnode *obj, const char *key);
jnode *json_get(const jnode *obj, const char *key);
const char *json_get_str(const jnode *obj, const char *key);    /* NULL unless string */
jnode *json_clone(const jnode *n);

#endif
