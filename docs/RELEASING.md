# Releasing Printer Addon

More than one person (and more than one Claude session) works on this repo. To keep versions from clashing:

1. **Pull first.** `git pull` before you change anything.
2. **Take the next free version.** Look at `mod_version` in `gradle.properties` and the newest `## 26.2-x.y.z` in
   `CHANGELOG.md` on `origin/main`. Your version is one higher. Never reuse a number that's on GitHub already.
3. **One commit for the bump:** `mod_version` in `gradle.properties` and a `## <minecraft>-<version>` section with
   `- ` lines in `CHANGELOG.md`, in the same commit. Push it right away, so nobody else takes the number.
4. **That's all.** The release is automatic: the developer's Mac (`discord-bot/sync.py`) checks GitHub every 5
   minutes, builds the new version in a clean copy, adds `src/main/resources/printer_addon/log-channel.txt` (never
   in git), checks the jar and hands it to the Discord bot. The bot posts it in #changelog and #downloads (with a
   release card: `branding/discord/release-<version>.png` if it's in git, else the bot draws one).

## Who works where

- Logs and reports (`LogShare`, `ReportIndex`, `DebugInfo`, `RunLog`, `tools/reports.py`): the logs session.
- Discord (`discord/`), auto break (`AreaBreaker`, the errand walk in `Mover`), the schematic library: the Discord bot
  session.
- Anything else: pull, keep the change small, push soon.

## The Discord bot

The bot lives in https://github.com/sindrens/autoprinter-bot and runs on the droplet; it updates itself from GitHub.
Its API for the game is in that repo's `API.md` (base `http://139.59.156.198/api/v1`).
