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

## 26.2-0.17.7
- Works together with the new AutoSell mod: a build pauses while it sells your collectors, and goes on by itself after.
- The Discord control room says why a build is paused.

## 26.2-0.17.8
- A layer no longer stands still when nothing left on it can be placed ("can't place them from where it can stand"): those blocks wait until the end and the build goes on.
- After a restart, a few blocks left far below where the build is (one column of a cactus tower) no longer pull the build all the way back down; a layer that was stopped half way is still finished first.
- Buys the cheapest way: seeds or pumpkins, ingots or blocks (or blocks and the rest as ingots), dust or blocks, diamonds or blocks, rails or what they're made of, sticks or logs. The choice is in the build log (PRICE).

## 26.2-0.17.9
- No more creeping through a part of the build that never loads (one block at a time, nothing placed): after 5 seconds right there that part is left alone.
- Starting again without saved progress no longer flies down to the bottom of a tall build that's already standing.

## 26.2-0.17.10
- Players who don't stop it: friends and island members can come close without stopping the build.
- A normal game close is no longer sent as a crash with reports.
- Clearer text for the cheapest-buying setting.

## 26.2-0.17.11
- Out of something the shop doesn't sell (string): it waits where it is instead of flying empty lanes, tells you (on screen and in Discord) how many the layer still needs, and goes on by itself once you put some in your inventory.
- Cactus flowers are only broken when they're in the way of the next block it can place: no more going round breaking flowers that keep coming back on a big farm, and none at all while it waits for items.
- Auto break no longer stops for every block that goes with one hit (string): it keeps flying along, much faster on string layers.
- A schematic placed outside your island's border isn't started (it flew to the edge and down towards the void); parts of a build outside the border are left alone at once.
- Bulk buying no longer puts stacks into a vault and takes them straight back out.
- Blocks put into a vault to make room are checked: when the vault doesn't take them, the log says so.
- "Bring these" shows the numbers with commas, and the cactus warning shows once.

## 26.2-0.17.12
- A cactus flower right beside a block still to go in is broken (string and sand waited for good beside flowers nobody broke, and the layer was called done without them). Flowers that aren't in the way are still left alone.
- Blocks left over far below (at the bottom of a tower) are only done at the very end, not flown down to after every layer.

## 26.2-0.17.13
- "Storage full" says what fills it: mostly the build's own string, sand and cactus bought ahead is fine (nothing to do), and it only asks you to empty some when other things take up the room.
- The build log shows the route: which lane it flies to next and why, how far, the next best choice, and between layers where the last layer ended and where the next one starts.
- Blocks a lane leaves say why: sand with nothing under it yet (its string isn't in) instead of "out of reach".
- Auto break does 3 levels at a time in one sweep instead of one level per sweep (a string level of a cactus tower, then the next, each across the whole area).
- Auto break no longer hovers doing nothing when the blocks left on a level can't be got to: on down, back for them at the end. It also tries spots right beside a block, not only above it.
- A grown cactus to break while the lanes are flown waits until the sand around it is in and it can stand there (5 times faster than breaking from the air): breaking from the air took most of a 24 minute run.
- Stopping for damage says what the damage was in the log.

## 26.2-0.17.14
- Auto break on string (anything that goes with one hit): lanes 7 wide flown along their middle, everything in reach broken on the way, instead of 3-wide lanes with a stop at each block. About twice fewer sweeps over the area.
- Auto break's 3 levels at a time start at a level that has something on it: on a cactus farm (string every other level) that's 2 string levels in one sweep, not 1.
- The build log says how each Auto break band is laid out (how many blocks, lanes, how wide).

## 26.2-0.17.15
- A layer saved as done whose blocks are gone again (the sand falls when the string under it is broken) is built again together with the layer under it: string, then the sand on it in the same lanes. Before, the string layer was built alone and the next layers flew lanes with nothing under them (the sand stripes in your farm).
- The layer right above a finished one is checked at once, not only in the background.
- How many layers go together is worked out each time: the next layer only when it stands on the one below, leaves no cactus open, and the narrower lanes it needs still do more a sweep. With your reach that's 2 (string and cactus, then the sand on the string); the log says why ("3 layers ...? no").
- Auto break: blocks that go with one hit (string) are broken every 2 ticks instead of every 5.
