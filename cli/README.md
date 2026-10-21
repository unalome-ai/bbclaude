# bbclaude (terminal)

A terminal Claude client for BlackBerry 10. It is plain C99 plus libcurl, so it
builds with the GCC 4.6 that Berrymuch installs on the phone and runs in Term49.
Command-line programs run there without a signed `.bar`, which sidesteps the
dead BlackBerry signing servers.

It streams replies, keeps the conversation in memory, and sends the assistant's
full content (including thinking blocks) back unchanged on the next turn.

## Build on the Passport

1. Install [Term49](https://github.com/BerryFarm/Term49/releases) and
   [Berrymuch](https://github.com/BerryFarm/berrymuch) (copy `clitools.zip` and
   `install.sh` to the device's `misc/` folder, then `sh install.sh` in Term49).
2. Copy this folder to the phone, e.g. `/accounts/1000/shared/documents/claude-cli`.
3. In Term49:

   ```sh
   cd /accounts/1000/shared/documents/claude-cli
   make CC=gcc
   ./bbclaude --probe
   ```

`--probe` only connects to `https://api.anthropic.com/v1/models`. HTTP 401
means TLS works and you're ready. If it fails with "certificate verify failed",
run:

```sh
./bbclaude --probe --cacert globalsign-root-r1.pem
```

Why: the API's certificate chain ends in a cross-signature from the 1998
GlobalSign Root CA, and the phone's OpenSSL 1.0.1i follows the chain all the way
to that root. `globalsign-root-r1.pem` is that root, downloaded from
GlobalSign (SHA-256 `EB:D4:10:40:E4:BB:3E:C7:42:C9:E3:81:D3:1E:F2:A4:1A:48:B6:68:5C:96:E7:CE:F3:C1:DF:6C:D4:33:1C:99`,
expires 2028-01-28). Verified on Mac by rebuilding the phone's exact OpenSSL and
libcurl in Docker . Use `-v` to see the TLS handshake.

If `make` can't find `curl/curl.h` or `-lcurl`, the Berrymuch install is
incomplete; libcurl ships with Berrymuch v0.4.

## Use

```sh
echo 'sk-ant-...' > ~/.bbclaude_key     # or export ANTHROPIC_API_KEY=...
./bbclaude                               # Claude Opus 5.5, effort low
./bbclaude -m claude-haiku-5-5           # cheaper and faster
./bbclaude --cacert globalsign-root-r1.pem   # if --probe needed it
```

In the chat: `/new` starts over, `/model ID` switches model (and starts over),
`/effort LEVEL` changes effort, `/quit` exits. End a line with `\` to continue
typing on the next line. Ctrl-C stops a reply in progress.

`--base-url URL` points the client at another host, such as a relay you run
yourself if the phone can't talk TLS to the API directly.

## Notes

- Requests opt into server-side fallbacks (`fallbacks: "default"`) on models
  that support it, so a request the main model's safety classifiers decline is
  retried on a fallback model in the same call. Requests that are still
  declined are dropped from the conversation with a note.
- A failed or cancelled turn is removed from the history, so the conversation
  stays valid.
- Build on a Mac with `make` (uses the system libcurl).
