# Changelog

## 26.2-0.14.1
- Rejoins the build after "Timed out".
- No more lanes stuck waiting.
- Hotbar and purchases fit better.

## 26.2-0.14.2
- Fixed a lane freeze on redstone layers.
- Two layers at once is back on.
- Fewer bumps into walls and buildings.

## 26.2-0.14.3
- Floating string is placed in the air.
- Cactus farms build two layers at a time.
- Stays inside the island border.

## 26.2-0.14.4
- Interval 2 with a speed guard.
- Bulk buying into vaults.
- Sand waits for its string.
- Always-break list (cactus flowers).

## 26.2-0.14.5
- Never left standing in a cactus.
- No false speed guard stalls.
- Lanes in order, smarter bulk buying.
- Remembers vault contents.
- Full run log with a final report.

## 26.2-0.14.6
- Grown cactus drop by themselves.
- Cactus are built with the sand above them.

## 26.2-0.14.7
- Grown cactus in the way get broken.
- Lanes fly beside grown cactus.
- No more hanging next to a cactus.
- Fewer double clicks on slow servers.

## 26.2-0.14.8
- Lands to break cactus: 3 times faster.
- Cactus flowers thrown out, more room to buy.
- Bulk buying works again after a full vault.
- Waits out server lag, retries purchases.
- Leftovers on one short route.
- Logs 90% smaller, with decimal points.

## 26.2-0.14.9
- Lanes 10% faster again (0.14.8 waited too much).
- One log file (.jsonl), plain text optional.
- Run report ends with the top 3 time costs.

## 26.2-0.14.10
- Farmland tilled right away, stems planted in the same pass.
- Dirt the printer missed gets tilled by the add-on.
- Lanes wait a moment instead of leaving holes.
- Finished layers checked for untilled dirt at start.

## 26.2-0.14.11
- Seeds wait one tick after switching to them.
- No more "no hoe for tilling" with your own hoe on the hotbar.

## 26.2-0.15.0
- Auto break: breaks a Litematica area selection.
- Skip any block type in the Auto break tab.
- Best tool per block, drops put away or ignored.
- Share last log (J+B) sends it to the developer's Discord.

## 26.2-0.15.1
- Every item waits a tick after switching to it (pistons too).
- Fewer item switches: the item in hand goes first.
- Green START and red STOP buttons (J+B).
- Clearer build report: holes, wrong blocks, your own blocks.
- Log files named by date, what ran and version.
- Share last log goes to the developer's Discord.

## 26.2-0.15.2
- Report / share log (J+B): write what went wrong, click what fits, say what you built.
- Your F2 screenshot can go with it.
- Full debug with it: mods, settings, PC, game log (no chat), crash report.
- The log of the run going on is sent, not the last one.

## 26.2-0.15.3
- Reports reach the developer again (new #player-reports channel).
- New logo, also shown in Mod Menu.

## 26.2-0.15.4
- After every run it asks to send the log: Send, Always send logs, Write what happened, Not now.
- "Always send logs" sends every run's log by itself (setting of the same name).
- Every report is listed for the developer, so none get lost.
- Report titles say what happened: problem, stopped, finished, stopped by you.

## 26.2-0.15.5
- Auto break mines in straight lanes, standing on the next lane: no circles, no gaps.
- Smoother flying on tall builds: the movement scan only reads the layers being built.
- A layer picked by hand keeps its cactus capped (built with the sand layer above).
- Warns when it stops with cactus open on top (they grow while stopped).

## 26.2-0.16.0
- Link the game with Discord (J+L): your own control room on the Printer Addon Discord.
- Live status there: progress, layer and time left.
- Pause, resume and stop your build from Discord.
- A message when the build is done or needs you (you choose what).
- Replies from the developer show up in game.
- Tells you when a new version is out.

## 26.2-0.16.1
- Linking with Discord works for everyone (J+L).

## 26.2-0.16.2
- Sending a log or report works again (J+B → Report / share log).

## 26.2-0.17.0
- Discord tab in the J+B menu: link, unlink, sync on or off, messages.
- Time-lapse: a GIF of your build going up, in your control room when it's done.
- Schematic library: browse and download builds from Discord, straight into Litematica.
- Alerts sent while offline arrive once the connection is back.
- Less traffic when nothing is building.

## 26.2-0.17.1
- Fewer trips to the vaults and the shop: a lane starts unless an item is nearly gone, and it's topped up where it runs out.
- One more inventory slot for building blocks.

## 26.2-0.17.3
- Every run's log goes to the developer by itself (on by default, told once in chat; off: "Always send logs").
- No log gets lost: they wait in an outbox and are sent again after a failed send or a restart.
- Disconnects, a closed game and crashes during a run send their log too (a crash on the next start).
- Closing the game during a build ends it properly: your printer settings come back, and it goes on next time.
- Every report shows the version that wrote the log, and the developer sorts them by it.
- Every log you send counts for the Logger role, however the run ended.

## 26.2-0.17.4
- Linking with Discord works on more networks (the bot is reached on the normal web port).

## 26.2-0.17.5
- Auto break walks on the ground wherever it can (blocks break 5 times faster than in the air), also with movement set to "Fly only".
- Auto break never stops as "done" while blocks are left: it goes over the whole area again.
- A clear message when the Discord link is lost, and what to do.
- Schematic library: stars, the cost at your server's shop prices, and how to build a downloaded schematic.
- Your shop prices (from J+S) go to Discord, so the library can show what a build costs.

## 26.2-0.17.6
- Observers saved "powered" in the schematic are placed (the printer skipped them, so layers were left with observers missing).
- After a restart, a layer that was stopped half way is finished before the next one.
- Waterlogged leaves get their water as the lane passes: the bucket gets its own turn and the lane holds still a moment.
- A missed bucket is tried again after 1 second instead of 30.
- Ordinary water buckets: as many are fetched or bought as the layer's water needs, instead of 1 at a time with 3 minutes of waiting in between.
