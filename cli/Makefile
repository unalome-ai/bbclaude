# macOS: make            BlackBerry 10 (Term49 + Berrymuch GCC): make CC=gcc
CC ?= cc
CFLAGS ?= -O2 -std=c99 -D_POSIX_C_SOURCE=200809L -Wall -Wextra
LDLIBS ?= -lcurl

bbclaude: claude.c json.c json.h
	$(CC) $(CFLAGS) -o $@ claude.c json.c $(LDLIBS)

clean:
	rm -f bbclaude
.PHONY: clean
