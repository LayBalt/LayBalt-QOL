<!-- markdownlint-disable-file MD033 -->
# Changelog

Release notes for LayBalt QOL, newest first. They start at 3.2.0; 3.2.1 has no notes of its own.

---

<h3 align="center">3.7.1: Auto Enchanting stops at the stake's max clicks, Auto Kick works</h3>
<p align="center">
Auto Enchanting used fixed round targets minus a manual serum count, so it could stop before the last Superpairs click. It now reads the stake you pick, the way SkyHanni does.
</p>

- **Stop At Max Clicks** (on by default). The stake you click in the Chronomatron or Ultrasequencer stakes menu says how many rounds give the most Superpairs clicks: its last "Chain of N" or "Series of N" line, with Metaphysical Serums already counted in. Chronomatron stops once N rounds are answered, at the moment SkyHanni alerts. Ultrasequencer stops once the Series of N is answered, as soon as the next sequence shows, without waiting for its timer. Only the Metaphysical stake pays the third click.
- **Ultrasequencer Early Stop** (off by default). SkyHanni's rule closes as soon as the Series of N shows, one round earlier, because Hypixel paid the full series for one round less in 2025. That is not confirmed for today's game, so check the Experiment Over rewards once before turning it on.
- **Target Alert** (on by default). A sound and a chat line say when every click is yours, then Auto Close closes the game.
- **Fallback.** Serums Consumed and the round sliders under Advanced apply only when the stake could not be read, in practice mode, or with Stop At Max Clicks off. Get Max XP still goes on to 15 and 20 for the extra enchanting XP.
- **Party Finder Auto Kick and join stats work again.** They looked players up on the NoammAddons stats server, which LayBalt QOL never had, so every check failed ("Couldn't check X, not kicking"). They now read the same stats server as <code>/lbq cata</code> and Secrets Counter (Secrets Counter > API URL, Odin's public one by default): S+ PB per floor, secrets and Magical Power. The dungeon map's run summary also shows your teammates' secrets again. Auto Kick only kicks as party leader. A group you list yourself now makes you leader when Hypixel sends both "Queueing your party..." and "queued in the dungeon finder" (members of other groups get only the second line). When LayBalt cannot tell who leads, Auto Kick says so instead of staying silent (<code>/pl</code> fixes it).

---

<h3 align="center">3.7.0: Auto Melody and NoammAddons sync</h3>
<p align="center">
Synced with NoammAddons up to f24f889a (November 11) and with Odin's Blaze solver fix. Checked by unit tests and in the terminal simulator, not yet in a live Hypixel run.
</p>

- **Auto Melody** (Auto Terminal). It clicks the Melody button when the green pane reaches the marked column. It waits for the first-click protection and works in the terminal simulator. **Melody Skip** (Edges or All, optionally on the first row) also clicks the next rows' buttons a tick apart. It is on by default with the other terminals; while it is on, your own clicks in Melody are blocked, as for every auto terminal.
- **Terminal delays as ranges.**
  - Auto Terminal's Click Delay is one range slider. Two different ends give a random delay between them; equal ends give a fixed one.
  - The Terminal Solver's First Click Protection is a range too, rolled once for each terminal you open.
  - Your old values carry over.
  - Drag either knob, or click the value and type `120-150`.
- **Party.**
  - The new Party HUD shows your members, their roles, whether they are online, and pending invites.
  - Party tracking is more accurate: no false leader when someone joins, and solo runs are no party.
  - Party Finder Auto Kick can follow the group you build at Mort (floor, Master Mode, the time in the note).
  - Auto Kick says when a profile lookup fails.
  - `!reinvite` invites the player back once they actually leave.
  - `!t 3` works for Kuudra tiers.
- **Dungeons.**
  - Star Mob ESP skips the dummy minibosses in Duncan, Balcony, Wizard, Pit, Waterfall, Atlas, Chambers, Layers, Perch, Golden Oasis and upper Stairs.
  - Score Calculator has **Hide in Boss**, and Blood Camp has **Mage Only**.
  - Room Alerts has its own sound settings.
  - Ice Fill finds its path faster.
  - The fairy room's door is counted once as a wither door.
  - The Blaze solver no longer boxes a blaze twice when its name tag is sent again (Odin 9ce2c78).
- **Floor 7.** The crystal, wither and lightning titles are one movable HUD element, "F7 Titles".
- **Other.**
  - Time Changer only changes the sky and fog (no sky jitter) and has a Custom time.
  - Info Display has CPS label options and tighter boxes.
  - Mask Timers hides masks off cooldown and has **Always Show**.
  - Protect Item blocks salvaging protected items.
  - The Explosive Shot damage message has its own toggle.
  - Etherwarp outline colours can be transparent.
  - Gyro Helper aims like Hypixel.
  - Settings search also matches section names.
- **Under the hood.**
  - World boxes and lines use Fabric's render phases.
  - Glow checks run once per tick.
  - Block Overlay works with Lunar Client.
  - Text layout is cached.
  - The jar no longer bundles the Kotlin libraries that Fabric Language Kotlin ships (about 4 MB smaller), so Fabric Language Kotlin 1.13.13+kotlin.2.4.10 or newer is required.

---

<h3 align="center">3.6.0: Auto Mob Killer and Auto Fishing move like your hand</h3>
<p align="center">
Turns, waits and looks now come from your recordings at Jerry's Workshop, the Backwater Bayou and the Crimson Isle, not from fixed delays. Checked by replaying your own fights against the bot and in singleplayer with faked catches, not yet in a live Hypixel run.
</p>

- **Turns.** Every move of the view is a stroke like yours. Its length sets its time, it speeds up and slows down, overshoots now and then, sometimes needs a second stroke, and moves in your mouse's steps. The fast, sharp turns to the creature are gone.
- **Turning only when needed.** The killer turns only when the weapon needs aim. A creature in front of you, one the bobber deals with, or an area attack gets no turn. The way back to the water comes at your times: sometimes late, and not at all when the view still casts onto the water.
- **Timing.** Reactions to a spawn, a death, a swap, the first hit, the recast and the cast are drawn from your own waits, with your slow streaks and rhythm. Nothing waits a fixed time, and nothing reacts in the same tick as what it reacts to.
- **Misses.** Melee clicks miss about as often as yours do, about 1 in 5.
- **Look Around** (Auto Fishing) looks around and back as you do while you wait and after a kill, once your recordings have taught it. **Shake Head** nudges the view like a resting hand.
- **Casts stay on the water.** Before a cast, the bobber's flight is worked out with Hypixel's 1.8 physics. A view that would cast off the water turns back first. A cast that would hit another player's creature or a player waits a moment (at most 1.5 s) or re-aims a little.
- **Where you fish stays yours.** Only your own mouse turns between fights change where you fish, never a move of the killer or of Auto Fishing. With the killer off the view is yours, fight or not: where you rest it is where Auto Fishing fishes, and it never pulls the view off your creature.
- **Safety.** At most one click per tick. Every attack and use is checked against what it would hit, so it never hits another player's creature, a player, a block that breaks or a block that opens. Held keys are let go at your times. Nothing is swapped behind a menu, and nothing is clicked behind one unless you turned Pause In Menus off.
- **Trace** (Auto Mob Killer setting) writes what the killer did every tick to <code>config/LayBaltQOL/fishing/traces/</code>, to compare with your recordings.
- **Learning.** The learner (version 13) relearns your profile once, the first time you join a world. Relearn From Recordings and <code>/lbq fishing relearn</code> do it by hand.
- **Not yet copied.** In weapon fights you re-aim at a moving creature about twice per fight, the killer about once at most, because it does not follow moving creatures.

---

<h3 align="center">3.5.0: Auto Mob Killer picks the weapon like you do</h3>
<p align="center">
Learned from a 41-minute recording at the Backwater Bayou and the Crimson Isle lava. Checked against both recordings and in singleplayer with faked catches, not yet in a live Hypixel run.
</p>

- **Learned is the new default action.** Creatures without a rule of their own get one. Before every fight, and while it lasts, the killer picks what you would pick in that situation from your recordings:
  - **the weapon and click:** rod melee, a held right click or a single right click;
  - **from:** the distance, the height difference, how tough the creature is, and what you used on that creature and on its location's creatures.

  Your own rules still win, and rare catches still stop by default.
- **Flaming Flay and other rod-type weapons.** An item counts as a fishing rod only when it casts. The Flay is a fishing-rod item that never casts, so it is learned as a weapon, used by holding right click, with the reach and aim you used it with (to about 10 m). Its presses no longer spoil your cast and reel timings. Auto Fishing never fishes with it.
- **Hyperion and other teleport blades.** These are used as area attacks, looking down as you do, and never at a shallow pitch that would teleport you off your spot. Aspect of the Void, Grappling Hook and tools are never used to attack.
- **Escalation.** When the current weapon does no damage for 8 uses, it switches to the next one you use. For example, a Bayou Sludge the rod barely hurts gets the Flay. When nothing works, the creature is parked.
- **Out of reach.** A creature nothing you have can reach is parked, and Auto Fishing goes on. Examples are a lava creature sunk 25 blocks down or a squid far out. The killer picks it up again once it comes back in reach.
- **Split creatures.** Creatures that come out of yours when it dies are yours too, and get fought. Examples are the Bayou Sludgelings out of a Bayou Sludge and the Baby Magma Slugs out of a Magma Slug.
- **Fight Messages** (setting) now also say which weapon was picked, what was parked and when a weapon did nothing.
- **Recordings** also note your hotbar and which creature a split one came from. The learner (version 3) relearns your profile once.

---

<h3 align="center">3.4.1: Auto Mob Killer learned from a real recording</h3>
<p align="center">
Tuned from a 33-minute recording of hand fishing at a Jerry's Workshop hotspot. It is checked against that recording and in singleplayer, not yet in a live Hypixel run.
</p>

- **Recast first.** At a hotspot most creatures die to the bobber of the recast you make right after the catch. Rod rules now have three modes: <b>Learned</b>, <b>Melee only</b> and <b>Recast first</b>. With Recast first, and with Learned when your recordings show you recasting through your catches, Auto Fishing recasts about 4 ticks after the catch, as you do, and the killer waits for that bobber to do its work. A creature the bobber kills is never turned to and Auto Fishing never waits for it. A creature that survives gets its first rod hit at your own time after a missed bobber, and any turn starts just early enough for that hit. A rod fight with your view still on the water no longer holds Auto Fishing.
- **Aim.** There is no turn when the crosshair is already on the creature. Standing right under your bobber, a turn moves only the pitch.
- **Your own creatures.** A creature only counts as yours when its nametag appears in the column above your bobber, and it is no longer guessed from where the recast landed. Corrupted creatures are recognised despite their scrambled names.
- **Weapon fights.** The first swing comes 100-300 ms after the weapon swap, as in the recording. The loadout menu goes up to 3 pages (36 loadouts) and waits for Hypixel's "You equipped ...!". When a loadout click goes unconfirmed, the Fishing Loadout is still put back after the fight.
- **Learning.** The learner now counts bobber kills and sweep kills. It keeps your rod hits and weapon swings apart, along with your recast after a catch, the gap from swap to loadout and your way back to the rod. Posture artefacts from standing in the water are dropped. The rules window suggests Recast first for creatures your bobber killed, and a weapon and loadout for the ones you fought with one. A profile learned with 3.4.0 is relearned from your recordings once, the first time you join a world.
- **LayBalt Recorder** (separate jar for other players). <code>./gradlew jarRecorder</code> builds <code>build/libs/LayBaltRecorder-1.1.0-26.2.jar</code>, about 150 KB. It contains only the Fishing Recorder: the sea creature tracker, the event bus and small stand-ins, with no other feature. It needs Fabric API and Fabric Language Kotlin and refuses to load next to LayBaltQOL.
  - **Use.** <code>/lbrec</code> starts and stops a recording, <code>/lbrec folder</code> opens the folder, and Controls has an unbound "Start/Stop Recording" key. Recordings go to <code>config/LayBaltRecorder/fishing/recordings/</code> and are written in the same format.
  - **Privacy.** Of the chat, only "You equipped ...!" and "It's a Double Hook!" are kept, since other server lines can name other players.
  - **Not your profile.** The session line is marked <code>"build": "recorder"</code>, and Auto Mob Killer never learns such a file into your own profile, even when it sits in your recordings folder.
  - **Checks.** <code>verifyRecorderJar</code> runs after every build and fails if the jar holds a class or file outside the recorder, refers to a LayBaltQOL class it does not ship, or uses a bundled library. <code>./gradlew runRecorderProduction</code> starts the jar in a plain Fabric client.

---

<h3 align="center">3.4.0: Auto Mob Killer and Fishing Recorder</h3>
<p align="center">
Auto Fishing can now kill your sea creatures, and it learns how by watching you. Nothing below has been checked in a live Hypixel run yet.
</p>

- **Auto Mob Killer** is no longer work in progress. While Auto Fishing runs it kills <i>your own</i> sea creatures. It knows them from their catch message, "It's a Double Hook!" and where they appear next to your bobber, and it never touches other players' creatures. What it does with each creature is set in the new <b>Sea Creature Rules</b> window (the button in its settings), which lists all 90 sea creatures by location. Each one gets <b>Rod</b> (killed with the rod you hold, nothing is swapped), <b>Weapon</b> (equips a chosen loadout through <code>/loadout</code> and takes a weapon from your hotbar), <b>Ignore</b> or <b>Stop</b>. Stop turns Auto Fishing off with a title and a sound, and is the default for legendary and mythic catches.
  - **A fight.** Auto Fishing waits from the catch message on. Then the killer reacts after your reaction time, turns to the creature like a hand on a mouse and hits at your click speed. Melee hits only go out while the crosshair is on that creature. Afterwards your rod and Fishing Loadout come back, your view turns back to the water and Auto Fishing casts again.
  - **Taking over.** Moving, holding a mouse button or turning the mouse yourself (also with Lock Mouse, which then lets you turn) hands the fight back to you. Auto Fishing waits until those creatures are dead, then the rod and your view go back to the water. A creature that takes no damage for <b>Give Up After</b> seconds is left alone.
- **Fishing Recorder** (new). Turn it on, fish and kill sea creatures yourself for 30-40 minutes, then turn it off. It records casts, bites and reels, catches, every click and its target, hotbar and loadout changes, and your rotation every tick of a fight. Each recording is one file in <code>config/LayBaltQOL/fishing/recordings/</code>, and nothing leaves your PC. Other players' chat is never recorded.
  - **What it learns.** When a recording stops, Auto Mob Killer learns from all your recordings: your reaction and click timings, the shape and speed of your turns (overshoot included), where you aim, how fast you use the loadout menu, and per creature which weapon, click and loadout you used.
  - **Using it.** The rules window shows what was learned per creature, and <b>Apply learned</b> turns it into rules. Auto Fishing's new <b>Use Recorded Timings</b> (on by default) reels in and recasts with your own recorded delays once there are enough of them. While you record, Auto Fishing waits for you to finish your fights, and Auto Mob Killer does nothing.
- **Data.** The sea creature table (names, locations, rarities, catch messages) comes from SkyHanni-REPO (MIT, see THIRD_PARTY_NOTICES). The old Auto Mob Killer settings (Attack Delay, Sword/Rod Slot, Detection Radius) are replaced by the rules and are no longer imported from the legacy config.

---

<h3 align="center">3.3.0: Odin 0.4.0 and NoammAddons 1.3.4 sync</h3>
<p align="center">
Brings in the changes from Odin 0.4.0 (and later Odin commits) and NoammAddons 1.3.4. Nothing below has been checked in a live Hypixel run yet.
</p>

- **Terminals.** Terminal Solver has a <b>Max CPS</b> limit (15 by default), so holding the drop key or a hotbar key no longer sends a burst of clicks that breaks the terminal, and a <b>Rainbow Border</b> option (with speed) for the Custom GUI. Lag Protection Ticks set to 0 also turns the extra Melody wait off. Terminal ESP has a <b>Line Width</b> slider. Auto Terminal has <b>Off Tick Clicks</b> (checks every frame, still at most one click per server tick), works in the Terminal Simulator in singleplayer with correct Human and Skizo click orders, and draws the Fake InvWalk progress line below the title. The Terminal Simulator has <b>Auto Next</b> (also a button in the start menu) to open the next terminal after a solve, Escape or E returns to the start menu, and its Melody is closer to Hypixel's (any target column, a mistimed click pauses the lane, a new row waits briefly before moving). F7 Titles: the terminal title lasts the full Duration, terminal title settings have their own section, and BladeAddons can no longer bring back Hypixel's terminal, device and lever titles.
- **F7/M7 boss.** M7 Relics has a <b>Spawn Timer</b> HUD (counts down to the relic spawn after Necron dies), a <b>Relic Place Triggerbot</b> and a <b>Full Block Anvil</b> option (full-block hitbox for the anvil under the cauldrons in P5, walking is unchanged). The old Relic Triggerbot is now <b>Relic Pickup Triggerbot</b> and keeps your setting. Necron's death is detected from his death sound, so the Necron split and the relic timers start at the right moment, also with Devonian installed. Leap Menu has a <b>Render Mode</b> (Default, Map, and a new Wheel with all five players, so every class keeps its place); your old Map Leap setting becomes Map. <b>Highlight Players in core</b> shows teammates inside the core in green, the order from <code>/lbq leaporder</code> is saved across restarts, and an unbound key can no longer trigger a class leap. Tick Timers has a <b>Fire Freeze Timer</b> for F3/M3 and four colour steps. Leaps anywhere in the P5 arena count in Leap Counter. No Rotate predicts AOTV landings better around slabs, stairs, panes, bars and chests. Auto I4 stops right away when turned off mid-device, prefers Infinileap over Spirit Leap and no longer sends a stray click after leaping.
- **Dungeon Map.** Unopened 1x1 rooms you have seen a door into are coloured with the puzzle, trap or miniboss type they must be (<b>Predict Unopened 1x1s</b>, on by default, based on dtMap). Unopened scanned rooms show Hypixel's "?", checkmarks use Hypixel's textures, and the default room colours match Hypixel's map (colours you saved stay). New <b>Door Width</b> and <b>Head Border Size</b> options, <b>Player Heads Scale</b> goes up to 2.0, the map is centred like Hypixel's, and long room names fit their room again. The extra info line shows crypts and bonus bats, and the HUD editor shows a sample dungeon outside a run. Player Clear Info counts your own secrets even if you are dead at the end, and a crash during world changes is fixed. Walking into a room with the same secret total as the previous one no longer shows the previous room's found count.
- **Dungeon run helpers.** Score Calculator's Full HUD follows Odin's layout and can be narrower (<b>Width</b> 140-200), and <code>/lbq paul &lt;auto|on|off&gt;</code> forces Paul's +10 bonus until restart. Dungeon Waypoints can turn secret highlights on or off per type (chest, item, bat, essence, redstone key, lever) with a <b>Lever Color</b>, and the bundled room data now has lever positions and NoammAddons' latest secret fixes for a few rooms. Blood Camp's Watcher move timer and Kill Title use per-floor timings instead of a flat 20.5s, and there is a new <b>Blood Mobs Triggerbot</b>. Secrets has a new <b>Secrets Triggerbot</b> for chests, levers, skulls and mimic chests. The Blaze solver picks up blazes as soon as they load, <b>Hide Blazes</b> (on by default) hides them and the room's smoke, and Blaze Lines defaults to 2. The Boulder, Water Board and Ice Fill colours are saved separately again (Ice Fill's is now <b>Line Color</b>) and start from their defaults once. Livid Solver is tracked by UUID (fixes an error), Star Mob ESP uses Fel Color for starred Fels and no longer marks Spirit Sceptre bats, Gyro Helper's Show Cooldown works again, Auto GFS Twilight Amount goes up to 32, and entering the Entrance starts the warp cooldown.
- **Party and network.** Party tracking is rewritten after NoammAddons: roles, offline members, promotions, demotions, invites, disconnects and offline kicks, with names no longer case-sensitive. Party Helper's <code>/pl</code> list shows moderators and real online states, chat commands wait a short <b>Response Delay</b> to avoid Hypixel's throttle, and !f0 joins Entrance. Auto Requeue has a <b>Minimum Party Size</b>, requeues on Entrance again, checks that you are leader and respects a pending !dt even with Check Party off. Auto dialogue handles newer NPC dialogue formats, and Remove useless messages hides messages when they are printed so other features still see them. Secrets Counter and <code>/lbq cata</code> use Odin's new stats server (old configs are switched automatically). Party Finder Auto Kick's Maximum Seconds can be set to any second, and web requests time out after 30 seconds.
- **Items and general.** Item Tooltip prices shards again. Loadout Keybinds has <b>Keep Open On</b>, and Loadout and Wardrobe hotbar binds no longer fire on unbound keys, keep mouse and keyboard apart and pick up rebinds right away. Pet Display tracks the active pet more reliably (same-name pets, skins, loadouts, converted pets) and leaves pet menus alone with Active Pet Highlight off. Picking the arrow hit sound as its own replacement no longer crashes, Protect Item no longer blocks the quiver slot and <code>/protectitem</code> respects the feature's settings, Pvp Blink drops its queue on world change, and Storage Overlay previews are saved per SkyBlock profile (your existing previews move to the first profile you join). Tweaks has <b>No AOTV Paths</b> (no ghost dirt paths from the Aspect of the Void). Etherwarp with Use Server Position uses the server's sneak state. Sliders with fractional steps keep fractions, and box outlines respect a separate translucent outline colour (single-colour boxes keep an opaque outline).

---

<h3 align="center">3.2.5: Water Board hotfix</h3>

- **Water Board.** The solver works again. In 3.2.4 it read the board at dungeon start, before Hypixel closes the three doors (that happens when the first player walks in), took the open doors for a solved puzzle and stayed silent for the rest of the run. It now waits for the doors to close, and the puzzle only counts as solved once the map or the reward chest confirms it.
- **The 3.2.3 timers lead again.** The first run in every room shows the shipped timed solution (countdowns, CLICK labels, tracer). The board-reading guidance only takes over after a run fails, when someone else changes the doors, on <code>/lbq water replan</code>, or with the new <b>Water Board-Reading First</b> option (off by default).
- **Board reading.** Positions in unloaded chunks (after a death, a teleport or walking away) keep their last value instead of reading as air, short door flickers are filtered, and a double flick of the water lever no longer counts as a water switch.
- **Water Board Recorder** is now on by default. It writes small local files to <code>config/LayBaltQOL/waterboard/</code> (a trace per room; old files are deleted automatically) so problems can be diagnosed. Turning it off sticks.

---

<h3 align="center">3.2.4: Water Board from the board state</h3>

- **Water Board.** The solver now reads the board itself (water, doors, both lever planes and the real lever states, including levers your teammates flip) instead of only your own clicks. A messed-up run is recognised from where the water went, and the solver shows how to finish from the current state: a quick fix with countdowns when the simulator, checked against the shipped solutions on that board, finds one; otherwise turn the water off, let it drain and run the plays for the doors that are still closed. It never goes silent while the puzzle is unsolved. Fixed: the 3.2.2 restart could close a door that was already open. Teammates' runs are no longer flagged as failures. New options: <b>Water Quick Fixes</b>, <b>Water Ping Compensation</b>, <b>Water Board Recorder</b> and <b>Reset Water</b>. New command: <code>/lbq water status|dump|record on|off|replan|reset</code> (also <code>/lbwater</code>).
- **Auto Enchanting.** A chat message tells you when Chronomatron hands a round back to you. A timer status that arrives late no longer throws the solver off.

---

<h3 align="center">3.2.3: Auto Enchanting fixes</h3>

- **Chronomatron.** Auto Experiments no longer fails on the 3rd click or in round 3. Root cause: Hypixel lights each note as a block of 2-3 slots and the solver tracked single slots, so when one slot of a note went dark before its neighbour, the neighbour was counted as the next note. The solver now follows notes (colours), checks its chain against the timer, and starts fresh for every experiment window.
- **Superpairs.** The solver stays in step with the board: a click that revealed nothing counts as used, wrong pairs get time to flip back before the next click, powerup cards no longer throw off which click is the second of a pair, and it stops at "Remaining Clicks: 0".

---

<h3 align="center">3.2.2: fixes</h3>

- **Secret Hitboxes.** Levers work again. Root cause: the OdinClient importer copied OdinClient's unused "1.8 lever hitbox" default into <b>Lever Hitbox Style</b>, and 1.8 Style is barely bigger than vanilla and ignores the size slider. The importer now only takes "1.8 lever hitbox" and "Flat button hitbox" when the OdinClient module was on, and configs it changed are set back to Scaled once, with a chat notice. The hitbox mixins also hold up when other mods change the same shapes.
- **Scoreboard.** Lines are read live from the sidebar every frame, with vanilla colours and order. The panel is clamped to the HUD canvas instead of the GUI-scaled window, so it no longer drifts into the middle of the screen at GUI scale 3 or 4. It keeps its anchored edge when it resizes or the window changes, and can match the vanilla sidebar's size (<b>Match Vanilla Size</b>). A 3.2.1 position saved with the Right Edge anchor starts at the vanilla spot once. <b>Hide Server ID</b> only applies on Hypixel.
- **Water Board recovery.** The solver notices a failed run (wrong lever, extra click, a click too early or too late) and shows the fix: turn the water off, wait for the board to drain, flip the listed levers back, then pull the water lever to restart. A wrong lever flipped before the water starts is marked FLIP BACK. New options: Water Recovery Guidance, Water Timing Tolerance, Water Fix Color, Water Failure Message, and a Water Board Recovery HUD line. Only your own clicks are tracked.

---

<h3 align="center">3.2.0: Hypixel dungeon update</h3>
<p align="center">
Brings the dungeon and M7 helpers in line with Hypixel's dungeon update, following the latest NoammAddons and Odin changes. Nothing below has been checked in a live run yet.
</p>

- **Terminals.** The Melody solver, overlay, Melody Alert and Melody Display handle the new 3-row Melody (progress is <code>x/3</code>, <code>33%</code> / <code>66%</code>). The Numbers solver uses the new 5x2 grid. Odin's invincibility messages are no longer read as Melody progress. Auto Terminal keeps clicking after a terminal breaks and re-syncs. <code>/lbq termsim</code> simulates the new Melody and Numbers terminals.
- **Wither Dragons.** Your priority is the dragon that starts spawning first. The new <b>Dragon Priority</b> option sends the chosen Healer or Tank to the later dragon. A dragon that is already spawning no longer has its timer reset. The old priority settings (Normal/Easy Power, Purple Solo Debuff, Paul Buff and so on) are gone, and Dragon Priority starts off for everyone.
- **M7 phase 5.** Relic times start when the Wither King's boss bar appears. The relic spawn timer, spawn announcement and the Ragnarock M7 dragon alert are removed. The relic leap box is larger.
- **Run splits.** The M7 Necron split ends when Necron's boss bar empties.
- **Tick Timers.** Maxor start is 83 ticks, Goldor start 64 ticks after Storm dies and PY 62 ticks. The Storm Start, Necron Start, 0s Death Tick and Secret Tick timers are removed.
- **Auto I4** timings count from Goldor's first line. **Simon Says** shows <code>/4</code>. **Inactive Waypoints** boxes the armor stand itself.
- **Livid.** The invulnerability timer is 340 ticks, and the tracer and HP label also show while Livid is asleep.
- **Blood Camp.** Kill Title and the Move Timer HUD start when the blood room opens and fire after 20.5s. Speed Alert is removed. The Watcher's mob count is 8 + floor in Bossbar Health and the Watcher bar.
- **Door ESP (new).** Boxes blood rush doors (by key status) and the doors of your current room. It replaces Dungeon Map's Box Wither Doors, and your old settings move over. Highlight Mimic Room now needs Cheater Map.
- **Breaker Helper.** Prevent Mine also works in boss rooms (levers and buttons stay mineable) and now blocks dispensers, hoppers and droppers.
- **Room data** is updated and parsed field by field. **Secrets Counter** caches teammates' secret totals.
- **Other fixes.** Pet cache and pet skin fixes, Protect Item blocks Hypixel's fake quiver arrows, Command Shortcuts autocomplete and arguments work again, Info Display has ping suffix and colour options and keeps TPS/ping across world changes, settings are autosaved every 5 minutes, section headers in the settings panel show whenever any of their settings is visible, and the NoammAddons/Odin importer follows their latest config changes (Odin 0.3.6 Terminals Status and Temporary Waypoints, NoammAddons slot binds).
- **Config version.** Saved configs move to version 2. Going back to 3.1.1 afterwards cannot read them.
