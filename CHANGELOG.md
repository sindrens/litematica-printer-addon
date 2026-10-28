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

## 26.2-0.17.16
- Lane order: a grown cactus at a lane's end no longer makes the lane beside count as 30 blocks farther. The sweep took every other lane and flew back for the rest (460 blocks of flying to lanes in one layer of your tower, about 130 now).
- Clicks go on a side of a block you can see, on its real shape: string between two cactus is clicked against the cactus facing you. Your server refused 12 % of the string clicks (the side facing away), and those were most of the holes left after the lanes.
- Lanes no longer stand still 3 s at each grown cactus they leave for later.
- A grown column standing on sand that doesn't belong there: the sand is broken (a few ticks with a shovel) and the whole column drops, instead of each cactus from the air (3 s each).
- New setting "Click a side you can see" (on).
- Test farm with grown cactus in a quarter of the string spots: 229 s now, was over 480 s and stuck on its last 2 blocks.

## 26.2-0.17.17
- Fewer and shorter supply trips: what the vaults won't have enough of for the next trip is bought in bulk at the start of a trip, while the inventory is still empty. Bought later in the trip, the whole inventory (29 stacks) went into the vaults to make room and came back out, about 5 s, every third trip.
- A bulk purchase fills the vault room with more than one round of an item (a second purchase when 16 stacks or more are still to go): fewer shop visits.
- Blocks the layer needs only a few of (3 white concrete) are no longer put away to make room: that was a trip of its own a minute later, twice a layer.
- Test runs of the developer no longer send their logs to the report channel.

## 26.2-0.17.18
- A few blocks at the far end of the build (left from before a restart) are placed with the spots after the lanes, on their planned route, not flown to as a lane of their own (a layer ended with a 139 block flight for 3 blocks).
- After each layer: how long the rest of the build takes at this run's speed, in chat and in the build log ("About 6 h 10 min left").

## 26.2-0.17.19
- Stopping covers the cactus first: the sand beside cactus left open on top goes in (90 s at most), so nothing grows while it's stopped. Stop again to stop at once. Setting "Cover cactus before stopping" (on).
- Schedule: stop at a time of day ("Stop at", like 07:00) and never spend below an amount ("Keep at least this much money", read from the sidebar balance). In the settings, or from Discord: /schedule or the ⏰ button in your control room.
- A layer that costs much more than the ones before it: a warning in chat and Discord (a shop price went up?). Every layer's cost is in the build log (LAYER-COST).
- The Discord control room shows more: blocks a minute, money an hour, the layer it's on, the build type and the schedule. Auto break shows its progress and time left there too.
- No presets to choose: the build is looked at once it's counted (cactus farm, crop farm, redstone, sand/gravel, water...) and what matters for it is switched on by itself; it's said in chat and the log (PROFILE).
- Auto break: time left after each band, in chat and the log.
- When something stops the build, the message says what to do about it (in chat and Discord).
- Works with AutoSell 1.1: collectors that are well filled are sold right after a supply trip, on the same stop.

## 26.2-0.17.20
- Platforms: no more 3 s stops every few blocks (lanes now fly at the reach the printer really clicks).
- A schematic bigger than your island: the part past the border is left out (not clicked, bought or flown to) and said once.
- Learns an island edge from refused clicks too, and leaves everything past it.
- Refused clicks on the same spot no longer slow the printer down or shrink the reach.
- Debug info shows the world border.

## 26.2-0.17.21
- AutoSell 1.2 can send its log through the add-on's log channel (rounds that went wrong, and /autosell log).

## 26.2-0.17.22
- A floor that floats as a whole (a farm floor with air under it) no longer gets stuck with "the printer can't place them from where it can stand": one block is air placed and the rest go against it.

## 26.2-0.17.23
- A floor that floats as a whole is really placed now (tested): the blocks with nothing to go against are placed in the air, the rest against them. A floating patch beside a supported part works too.
- New setting "Break blocks not in the schematic" (Breaking, off by default): blocks where the schematic has air, and blocks of yours where it wants something else (dirt or farmland where the floor goes), are broken and the right block goes in. Containers and unbreakable blocks are never broken.
- The debug says when the printer places in the air.

## 26.2-0.17.24
- Breaking stands on the ground far more often: a spot to stand is looked for all around the block (up to 4 blocks), not only right beside the player. Test with wrong cactus: 65 of 76 breaks standing instead of 19 of 82, 3 times faster per block, layer done in 175 s instead of not done after 240 s.

## 26.2-0.17.25
- Keys: J+G starts / stops auto build, J+Z pauses it and goes on again (it keeps its place and spending limit). Change them in the settings (Hotkeys).
- Changing a setting while auto build runs (auto eat...) no longer stops the build.

## 26.2-0.17.26
- This is the Mythian build (the jar ends in +mythian): every speed setting is yours to choose. A separate official build with vanilla limits is coming for Modrinth.
- Changing one of the custom speeds switches the speed preset to Custom by itself (before, it did nothing while another preset was picked), and picking Custom starts from the preset you had.
- New speed settings: ticks between placements, and a placements-per-second cap (both off by default here).

## 26.2-0.17.27
- "Break blocks not in the schematic" no longer breaks cactus that grew above the layer one by one (it drops by itself when the sand beside it goes in). With it on, a tower layer placed 96 blocks in 3.5 minutes.
- Auto break stays standing between blocks it can reach from the same spot, instead of flying up and landing again for every block.
- Never flies or steps into a nether portal, end portal or end gateway.
- Auto break flies between spots instead of walking on the layers it breaks (it went a block every 2 seconds and fell through its own holes).
- "Can't fly here" is said once, not many times a second.

## 26.2-0.17.28
- New "Controls" tab in the settings: Start, Stop, Pause / Go on, Stop after this layer, Stop everything, and layer up / down as buttons, with the build's state, and their keys listed below to change right there.
- "Stop after this layer" (J+N): auto build stops by itself when the layer it's building is done (press again to keep going). Kept through a pause or a lost connection.
- Separate keys for Start only and Stop only (no key by default, set them in Controls or Hotkeys); the old start/stop key (J+G) still toggles.
- The on-screen status says "(stops after this layer)" while that's on.

## 26.2-0.18.0
One version for everyone now (the one going to Modrinth). Updating keeps all your settings: the server commands you set
(fly, shop, vaults...) are kept for the first server you join.

Plays like a player:
- Moves with the movement keys at a player's speed on servers, and looks where it goes, turning smoothly ("Look where it goes").
- Walks along straight lines where nothing's in the way, instead of zig-zagging over every block.
- Walk and /fly both work: "Walk, fly when needed" flies only where you may and walks everywhere else. "Fly only" walks too when flying isn't allowed. A /fly that turned flying off is sent once more.
- Opens the inventory to move items, and holds the sneak key for chest and hopper clicks.
- Your camera stays yours. "Turn my camera to blocks" makes it turn to each block clicked, if you want that.
- Reach is never more than vanilla. Placement and break speed are your choice, with a warning in chat when they're faster than a player.

New:
- Temporary blocks for floating parts: blocks of the schematic with nothing to place them against are reached with temporary blocks put from the nearest solid block, and broken again after. Uses the first of dirt, cobblestone, cobbled deepslate or netherrack you have (your choice in the Layers settings). On by default when air place is off.
- Server commands are kept per server, in the "Commands (this server)" tab, together with the movement mode.
- Servers can switch it off (MOTD tag [printer_addon:disable] or the plugin channel printer_addon:policy) or allow faster movement ([printer_addon:allow-fast-movement]). See docs/SERVER_ADMINS.md.
- Safety stops: lava, the void, low health, a full inventory with nothing placed for 90 s. Never breaks outside the schematic or Auto break's area.
- "Why did it stop?" in the Controls tab and /printeraddon why. "Report a problem..." button and /printeraddon report.
- Tests tab: 14 test builds and an Auto break test run in a singleplayer world, with PASS/FAIL and speed (config/printer_addon/test-results.txt).
- Basic and Advanced settings views, settings in Norwegian, version checks for Litematica / Printer / MaLiLib, warnings for mods that also move the player.
- Big layers are checked a little each tick (no stutter on 200 x 200).

Fixed:
- Builds stopped for "you took damage (unknown)" when a little health went with nothing hitting you.
- Log sharing is off until you turn it on or press Send after a run.
