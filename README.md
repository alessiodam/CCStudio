# CC: Studio

A NeoForge 1.21.1 addon for CC: Tweaked that opens any computer in a full VS Code editor running in your browser.

Type `code` on a computer (or turtle, or pocket computer). The mod prints a link and, if you are looking at the screen, sends it to you in chat as well. The first time a browser opens the link it shows a code such as `code trust 7KQ4-M2XP`; run that on the computer to approve the browser. After that the link gives you:

- the computer's files in the VS Code explorer, plus a read-only `/rom`
- a live terminal that mirrors the computer's screen in colour, with keyboard, paste and mouse input
- **Run** (F5), **Terminate** (Shift+F5), **Reboot** (Ctrl+Shift+F5), **Shut Down** and **Turn On**
- completions, hover docs and signature help for the whole CC: Tweaked API, plus live Lua syntax errors
- Quick Open (Ctrl+P) and Find in Files

`code trust` lists browsers waiting for approval, `code status` shows the current link, and `code stop` ends the session and revokes every approved browser. A session also closes after `idleTimeoutMinutes` with no browser connected, and after `maxLifetimeHours` in any case.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1.250+, CC: Tweaked 1.120.2+
- The server needs internet access once. It downloads the official VS Code Web build (about 53 MB), checks its SHA-256 and caches it in `<game dir>/ccstudio/vscode-web`.
- Only the server needs the mod. Clients without it can still join.

## Configuration

Settings live in `config/ccstudio-common.toml`.

| Setting | Default | Notes |
| --- | --- | --- |
| `web.bindAddress` | `127.0.0.1` | Use `0.0.0.0` so other machines can connect |
| `web.port` | `8765` | |
| `web.publicUrl` | empty | Base URL used in links, e.g. `https://studio.example.com` |
| `web.maxConnections` | `256` | Maximum simultaneous HTTP and WebSocket connections |
| `tls.enabled` | `false` | Serve HTTPS directly |
| `tls.certificate` / `tls.privateKey` / `tls.password` | empty | PEM chain + PKCS#8 key, or a `.p12`/`.pfx` keystore |
| `tunnel.cloudflareToken` | empty | Cloudflare Tunnel token. When set, the server runs `cloudflared` for you |
| `tunnel.cloudflaredPath` | empty | Use an installed `cloudflared` instead of the bundled download |
| `sessions.*` | | Session limits, idle timeout, chat links, `/rom` visibility |
| `sessions.maxLifetimeHours` | `12` | Sessions end after this long even with a browser connected |
| `sessions.allowCommandComputers` | `false` | Command computers can run server commands, so they are off by default |
| `vscode.downloadUrl` / `vscode.sha256` | pinned 1.139.1 | Change these to use another VS Code Web build |
| `vscode.openVsx` | `true` | Lets the editor install web extensions from open-vsx.org |

### Access modes

- **Singleplayer:** the defaults work as they are. Links point to `http://localhost:8765`.
- **Plain HTTP on a server:** set `bindAddress = "0.0.0.0"` and set `publicUrl` to `http://your-host:8765`. Everything works; only the browser's clipboard integration is limited on non-HTTPS origins.
- **Built-in Cloudflare Tunnel:** see below. The mod stays on `127.0.0.1` and no port needs to be opened.
- **Reverse proxy** (Caddy, nginx): keep the mod on `127.0.0.1`, proxy HTTP and WebSocket traffic to it, and set `publicUrl` to the proxied URL. Sub-paths work.
- **Built-in HTTPS:** set `tls.enabled = true` and point it at a certificate. Links then use `https://`.

### Cloudflare Tunnel

1. In the Cloudflare Zero Trust dashboard, create a tunnel under **Networks > Tunnels** and copy its token (the long value after `--token` in the install command).
2. Add a **Public Hostname** to the tunnel, e.g. `studio.example.com`, with the service `http://localhost:8765` (use your `web.port`).
3. Put the token in `tunnel.cloudflareToken` and keep `web.bindAddress = "127.0.0.1"`.

When the server starts, the mod downloads `cloudflared` 2026.9.3 for your platform from Cloudflare's GitHub releases, checks it against a pinned SHA-256, and runs it. It restarts `cloudflared` if it exits and stops it with the server. Links automatically use the tunnel's hostname unless `publicUrl` is set. The token is passed to `cloudflared` through an environment variable, so it never shows up in process lists or logs. The config file is not synced to players.

## Security

- A link alone grants nothing. Every browser must be approved on the computer with `code trust <code>`, which gives only that browser an HttpOnly, SameSite=Strict cookie scoped to the session. Players looking at the computer are told in chat when a browser asks, with its address and browser. `code stop` ends the session and revokes every approved browser.
- A session only reaches its own computer's files, its terminal and its power controls. Editor writes go through the computer's own mount so CC's disk quota stays exact. There is no shell, no host file access and no server command access. Command computers are refused unless `sessions.allowCommandComputers` is enabled.
- The editor's static files are only served to browsers that opened a valid session, through a separate HttpOnly, SameSite=Strict cookie.
- The web server rejects request bodies and pipelining, caps connections, closes idle connections, limits WebSocket message sizes per session, rate-limits requests and input, applies write backpressure, and never compresses on the fly. Keyboard and control input is queued and handled a bounded amount per tick, so it cannot lag the server.
- Downloads (VS Code Web and `cloudflared`) are pinned by SHA-256 and size-limited, and archives are extracted without symlinks or path traversal.
- Web extensions that players install from Open VSX run in their own browser with access to their session. Disable `vscode.openVsx` if you do not want that.

## Development

Open the project in IntelliJ IDEA as a Gradle project with a Java 21 JDK. Gradle downloads any other JDK a platform needs.

### Layout

| Folder | What lives there |
| --- | --- |
| `common/` | Everything that does not depend on Minecraft: the web server, sessions, browser trust, VS Code Web, the Cloudflare Tunnel, the `code` program and the web pages. |
| `platforms/<loader>-<minecraft>/` | A thin adapter for one Minecraft version: the mod entrypoint, the config and the CC: Tweaked glue. Its `gradle.properties` holds every version for that platform. |
| `extension/` | The browser extension (TypeScript). Gradle builds it with bun, or npm if bun is missing. `extension/data/cc-api.json` holds the IntelliSense data. |
| `build-logic/` | Shared Gradle conventions, so each platform's `build.gradle` is a single line. |
| `scripts/versions.py` | Adds new Minecraft versions and keeps dependency versions current. |

`common` talks to Minecraft only through the interfaces in `common/src/main/java/dev/alessiodam/mcmods/ccstudio/platform`. Everything else in `common` is shared by every Minecraft version unchanged.

### Commands

- `./gradlew build` builds every platform. Jars end up in `platforms/<platform>/build/libs/`.
- `./gradlew :neoforge-1.21.1:runClient` and `:neoforge-1.21.1:runServer` start a development game with CC: Tweaked installed.
- `python3 scripts/versions.py check` lists newer Minecraft versions that NeoForge and CC: Tweaked already support.
- `python3 scripts/versions.py update` bumps NeoForge, CC: Tweaked, Parchment and ModDevGradle to their latest patch releases.

### Supporting a new Minecraft version

1. Run `python3 scripts/versions.py new <minecraft version>`, for example `new 26.3`. It copies the newest platform folder and fills in the matching NeoForge, CC: Tweaked, Parchment, Java and Netty versions.
2. Run `./gradlew :neoforge-<minecraft version>:build`.
3. Fix the compile errors, which only ever appear in the new platform folder. They come from Minecraft or CC: Tweaked API changes, usually in `ChatMessages.java` or `CCComputer.java`.
4. Test it with `./gradlew :neoforge-<minecraft version>:runClient`.

Old platforms keep building alongside new ones, so one commit can ship every supported version.

### GitHub Actions

- **Build** runs on every push to `main` and on pull requests, and uploads the jars.
- **Release** runs when you push a tag like `v1.0.1` that matches `mod_version`, and attaches every jar to a GitHub release.
- **Versions** runs weekly. It opens a pull request with dependency bumps once they build, and keeps an issue up to date listing newer Minecraft versions that can be supported.

## License

CC: Studio is licensed under the PolyForm Noncommercial License 1.0.0 with additional terms; the full text is in `LICENSE`. You may use, study, modify and improve it, share modified versions, and contribute improvements back, as long as you credit alessiodam and keep the license with every copy. Commercial use of any kind is not allowed, including selling it, putting it in paid modpacks or behind a paywall, and offering paid hosting or services built on it. Using it for malware, account theft, attacks on servers or players, or anything illegal or against the Minecraft EULA is also not allowed.
