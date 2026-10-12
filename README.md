<!-- markdownlint-disable-file MD001 MD033 -->
<h2 align="center">LayBalt Repository</h2>
<h2 align="center">Version 3.2.1</h2>
<h4 align="center">LayBaltQOL (LBQ) is a feature-rich Fabric mod for Hypixel Skyblock on Minecraft 26.2.</h4>

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

---

<h3 align="center">Mod Information</h3>
<p align="center">
LayBalt mod is a Minecraft Mod made by LayBalt for Hypixel QOL. This mod is a <i>free access mod</i>, as well as <i>free source code</i>. Version 2.0.0 and later run on <b>Fabric for Minecraft 26.2</b> (the 1.x releases were Forge 1.8.9 mods). The full feature list is below; the Auction Sniper is still Discord only for now.
</p>

---

<h3 align="center">Switching from NoammAddons, Odin or OdinClient</h3>
<p align="center">
On the first launch LayBaltQOL imports your existing setup once. A NoammAddons config (<code>config/NoammAddons</code>) is copied as a whole on a fresh install: settings, profiles, personal bests, slot binds, storage overlay pages, command shortcuts, chat hiders, dungeon waypoints and sounds. Odin (<code>config/odin/odin-config.json</code>) and OdinClient (<code>config/odin/addons/odinClient</code>, <code>config/OdinClient</code>) modules are mapped to the LayBaltQOL features that now carry them, including slot bind profiles, relic, dragon, puzzle and terminal personal bests, the Auto Sell list and the Auto Clicker whitelists. NoammAddons values win where both mods had the same setting, unless you used the Odin version instead. Every imported or skipped setting is listed in <code>config/LayBaltQOL/import-report.txt</code>. Run <code>/lbq import &lt;na|odin|odinclient|all&gt;</code> to import a source again.
</p>

---

<h3 align="center">The LayBalt GUI</h3>
<p align="center">
Type <code>/lbq</code> or <code>/laybalt</code>, press the <b>Click Gui &gt; Open Keybind</b> key or use Mod Menu to open the LayBalt screen. It is LayBalt's own interface, written for this mod, and replaces the NoammAddons click GUI.
</p>

- **One window.** A rounded, centred window with a dark violet theme opens with a short fade and scale animation.
- **Sidebar.** Each category has its own icon in the sidebar. The HUD Editor and Discord buttons sit at the bottom.
- **Search.** Click the search bar or press <code>Ctrl+F</code> to search every category at once. Results are sorted by relevance.
- **Feature cards.** Every feature is a card with its name, description, an on/off switch and a gear icon when it has settings. Click the switch (or middle-click the card) to toggle it. Left- or right-click the card to open its settings.
- **Settings panel.** Settings slide in from the right over the cards. The switches, sliders, dropdowns, colour pickers, keybinds, text fields and sound pickers are all restyled to match the window.
- **Accent colour.** The accent colour used for switches, borders and highlights is set by **Click Gui > Accent Color**. Card order is set by **Sorting**.
- **Matching screens.** The HUD editor, notifications (toasts), tooltips and the Sound Manager, Command Shortcuts and Dungeon Waypoints screens use the same style.
- **Font.** All text in these screens uses the [Inter](https://github.com/rsms/inter) typeface (SIL Open Font License 1.1). It is bundled as <code>assets/lbqmod/font/inter.ttf</code> and is also available as the resource font <code>lbqmod:inter</code>. Shapes, icons and text are drawn by the mod itself. No external UI library is used.

---

<h3 align="center">Features</h3>
<p align="center">
Grouped by GUI category (<code>/lbq</code>) and generated from the feature classes: 188 features in total. Features marked <i>WIP</i> are shown greyed out and cannot be enabled yet.
</p>

#### General (26)

- **Auction Price Input** - Replaces the sign input with a proper textbox and undercut mode.
- **Auto Clicker** - Auto clicker for left click, right click or both, while a hold key is held.
- **Auto Enchanting** - Solves Chronomatron and Ultrasequencer (and, experimentally, Superpairs) at the Experimentation Table.
- **Auto Harp** - Plays the Melody's Harp minigame for you.
- **Chat Features** - Useful tweaks for the chat such as Ctrl + Click to copy messages.
- **Command Keybinds** - Keybinds that run common SkyBlock commands, such as /pets, /storage or /warp dungeon_hub.
- **Command Shortcuts** - Create your own command shortcuts.
- **Container Value** - Shows the total Bazaar / lowest BIN value of the open chest, backpack or ender chest page.
- **Enchant Colors** - Changes the color of enchantments in items lore.
- **Escrow Fix** - Automatically reopens the Auction House or Bazaar when Hypixel's escrow closes it (expired/invalid bids, BIN claims, Bazaar instant-buy refunds).
- **Etherwarp** - Etherwarp overlay and sound.
- **Farm Keys** - Temporarily rebinds Attack (block breaking) and Jump while enabled, for farming, and can lock the camera. Your original binds are restored when the feature is turned off.
- **Gyro Helper** - Renders a circle where your gyro will be located.
- **Inventory Search** - Adds a search bar to inventories that highlights matching items and evaluates math (Ctrl+F to focus).
- **Item Overlays** - Draws New Year Cake years and enchanted book abbreviations/levels on item slots.
- **Item Rarity** - Draws the rarity of item behind the slot (inventories, hotbar and storage overlay).
- **Item Tooltip** - Adds item information and controls to item tooltips.
- **Item Value HUD** - Shows a compact price card for the item under your mouse in inventories.
- **Loadout Keybinds** - Allows you to bind SkyBlock loadout slots to your keyboard.
- **Party Helper** - Chat commands for party, guild, private and co-op chat, plus party list reformatting.
- **Protect Item** - Prevents dropping or selling important items via /protectitem or keybind.
- **Slot Binding** - Allows you to bind slots to hotbar slots for quick item swaps.
- **Storage Overlay** - Shows all storage pages in an overlay when opening storage.
- **Tac Timer** - Shows a 3 seconds timer when you use the Tactical Insertion ability.
- **Term AC** - Automatically uses Salvation ability when holding right click.
- **Wardrobe Keybinds** - Make it possible to bind armor slots to your keyboard.

#### Dungeon (40)

- **Architect Draft** - Announces and can automatically get an Architect's First Draft after a failed puzzle.
- **Auto Abilities** - Allows you to use dungeon class abilities with keybinds and automatically trigger ultimates when needed.
- **Auto GFS** - Automatically refills dungeon items from your sacks using /gfs.
- **Auto Requeue** - Automatically starts the next dungeon run when the current one ends.
- **Auto Sell** - Automatically sells listed items in trade and Booster Cookie sell menus. Manage the list with /lbqsell.
- **Auto Superboom** - Automatically swaps to Superboom TNT and uses it when you click a cracked (breakable) dungeon wall.
- **Blood Camp** - Features for Blood Room.
- **Bossbar Health** - Shows the health number of the bossbar boss.
- **Breaker Helper** - Utilities for Dungeon Breaker.
- **Chest Profit** - Dungeon Chest Profit Calculator.
- **Door Keys** - ESP box & Tracer for wither and blood doors.
- **Door ESP** - Highlights blood rush doors (coloured by key status) and the doors of the room you are in.
- **Dungeon Map** - Custom dungeon map HUD with room names, checkmarks, player heads and run info under the map (wither door boxes moved to Door ESP).
- **Dungeon Queue** - Requeues dungeons automatically, shows the warp cooldown and announces kicks.
- **Dungeon Waypoints** - Add a custom waypoint with /ndw add while looking at a block.
- **Extra Stats** - Replaces Hypixel's end-of-run summary with a compact one that includes the extra stats.
- **F4 Features** - Spirit bear spawn timer, highlights, and more.
- **Ghost Block** - Turns blocks into client-side ghost blocks (air) with a held key or by right-clicking with a pickaxe. Use at your own risk.
- **Hidden Mobs** - Reveals invisible Fels, Shadow Assassins, Watcher mobs and stealthy Giants in dungeons.
- **I Hate Doors** - Visually replaces entrance, wither and blood doors with colored glass (works with Sodium).
- **Leap Menu** - Custom Leap Menu and leap message.
- **Livid Solver** - Finds the real Livid in F5/M5, with HP display, tracer and hiding of the wrong Livids.
- **Mage Beam** - Renders a fully custom, animated beam whenever a mage casts their beam ability.
- **Mimic** - Detects and announces Mimic, Prince and Bat kills in dungeons.
- **Party Finder** - Party Finder helpers: join stats, level requirement and missing class overlays.
- **Positional Messages** - Sends a party message when you reach a saved position. Manage the list with /lbq posmsg.
- **Puzzle HUD** - Displays the remaining, completed and failed puzzles of the dungeon on your HUD.
- **Puzzle Solvers** - Dungeon puzzle solvers (Blaze, Boulder, Creeper Beams, Ice Fill, Ice Path, Quiz, Teleport Maze, Three Weirdos, Tic Tac Toe, Water Board) with puzzle timers.
- **Ragnarock** - Ragnarock alerts.
- **Room Alerts** - Alerts when certain stuff happens in your current room.
- **Room Clear** - Notifies you when the dungeon room you are in is cleared.
- **Salvage Overlay** - Highlights dungeon gear that is safe to salvage.
- **Score Calculator** - Shows the score of the dungeon run, plus Odin's Map Info score and secret HUDs.
- **Secret Waypoints** - Shows waypoints for every secret of the room you are in. Chests, items, bats, wither essence and redstone keys work out of the box. Entrance, superboom, lever, stonk, AOTV, pearl, fairy soul and prince waypoints have no built-in data: record them with /lsw add, or load a secret waypoint dataset you already have (a folder, .json, .zip or a mod .jar) with /lsw import <path>. Found secrets are hidden automatically.
- **Secrets** - Secret count HUD, auto close for secret chests, bigger secret hitboxes and highlights for clicked secrets.
- **Secrets Counter** - Counts the secrets each player found and prints them at the end of a dungeon run.
- **Sellable Items Highlighter** - Highlights dungeon junk in your inventory (Defuse Kits, Training Weights, Journal Entries, Revive Stones, Healing VIII splash potions) while the Ophelia or Booster Cookie menu is open, so you can sell it quickly.
- **Star Mob Highlight** - Highlights all starred mobs in a dungeon.
- **Teammate Highlight** - Highlights your dungeon party.
- **Terracotta Timer** - Displays a timer until Terracottas respawn in F6/M6.

#### Floor 7 (23)

- **Arrow Align** - Arrow Align Solver.
- **Arrows Device** - Shows a solution for the Sharp Shooter (arrows) device in Floor 7 phase 3.
- **Auto I4** - Fully Automated I4.
- **Auto Terminal** - Automatically clicks terminals for you.
- **Debuff Helper** - Automatically pulls and fires bows based on Server Ticks (Lag Proof).
- **F7 Titles** - Custom Titles for F7 boss fight.
- **Gate Highlight** - Highlights Gate as long as its not destroyed.
- **I Hate Diorite** - Replaces the diorite pillars in the Storm (P2) fight with glass.
- **I4 Helper** - Highlights the targets for the F7 4th device (I4) and predicts the next block to shoot.
- **Inactive Waypoints** - Shows inactive terminals, devices and levers in the Goldor phase of Floor 7.
- **Leap Counter** - Shows how many players have leaped to you at each P3 stack spot and alerts when everyone is there.
- **M7 Relics** - A bunch of M7 Relics features.
- **Maxors Crystals** - Utilities for F7 Maxor's Crystals.
- **Melody Alert** - Tells your party when you open Melody and how far you are, and shows your teammates' Melody progress.
- **Melody Display** - Displays the current progress someone for melody on screen.
- **Relic Timer** - Counts server ticks from Necron's death to the relics spawning; /timer start|stop for a manual tick timer.
- **Simon Says** - Simon Says Solver.
- **Terminal Highlight** - Highlights the interactable hitboxes of the terminals in F7/M7.
- **Terminal Simulator** - Practice floor 7 terminals in a local simulator. Clicking the toggle opens it; also /lbq termsim [type] [ping].
- **Terminal Solver** - Renders solutions for Floor 7 terminals.
- **Terminal Times** - Times every terminal you complete in floor 7 (with personal bests per terminal type) and adds section splits to the terminal messages.
- **Tick Timers** - Shows various types of server tick timers for F7 boss fight.
- **Wither Dragons** - M7 dragons timers, boxes, priority, health, and alerts.
- **Wither Highlight** - Highlights all Withers in F7.

#### Visual (26)

- **Animations** - Allows you to modify your hand view-model: item position, rotation and scale per hand, swing style and speed, and equip animations.
- **Armor HUD** - Shows your armor and held item with durability bars or percentages.
- **Arrow Poison** - Shows on screen the amount of poison arrows you have in inventory.
- **Blessing Display** - Displays the current active blessings in the dungeon.
- **Coords HUD** - Shows your coordinates, facing direction, biome, chunk and the world time.
- **Damage Splash** - Reformat Skyblock's Damage Indicators.
- **Dark Mode** - Darkens the screen.
- **Effects HUD** - Lists your active potion effects with their remaining time. Handles long SkyBlock (God Potion) durations.
- **Freeze Display** - Shows how long the server froze after a chosen threshold.
- **Hub Map** - Replaces the SkyBlock Hub world map with a custom image.
- **Info Display** - Displays the system time, clicks per second, FPS, TPS, and ping on screen.
- **Keystrokes** - Shows your movement keys, jump and mouse buttons with live CPS and a press animation.
- **Lava To Water** - Replaces lava with the water texture.
- **Mask Timers** - Mask Cooldown Timers, Invulnerability Timers, and more.
- **Performance Graph** - Rolling graphs of your FPS, the server TPS and your ping.
- **Pet Display** - Active pet HUD, autopet swap titles and active pet highlight in the Pets menu.
- **Pet Menu** - Replaces the Pets inventory with a custom pet wheel and adds pet keybinds (also usable in the vanilla menu).
- **Player HUD** - Displays your stats as moveable HUD elements.
- **Quiver Display** - Displays the arrow type and the number of arrows left in your quiver.
- **Render Optimizer** - Optimize Rendering by hiding useless stuff.
- **Run Splits** - A Splits HUD for Dungeons (and Kuudra), with personal bests.
- **Scoreboard** - Draws a custom scoreboard instead of the vanilla one.
- **Session Timer** - Shows how long you have been playing this session. Can be reset at any time.
- **Speed HUD** - Shows how fast you are moving in blocks per second, with an optional speed graph.
- **Spring Boots** - Shows the spring boots charge progress on screen.
- **Warp Cooldown** - Displays on screen how long until you can start another dungeon run.

#### Render (32)

- **Aspect Ratio** - Stretches or squeezes the world view by forcing a custom aspect ratio. The HUD is not affected.
- **Block ESP** - Highlights the block types you choose (ores, spawners, ...) around you. Pick blocks with /blockesp add <id>.
- **Box3D** - Replaces the Glow ESP with 3D boxes.
- **Bread Crumbs** - Draws the path you have walked as a line that follows your feet.
- **Break Highlight** - Shows a box on the block you are mining that fills up as the block breaks.
- **Custom Crosshair** - Replaces the crosshair with your own shape, size, colours and a dynamic spread.
- **Custom ESP** - Outlines entities. Press the add key while looking at a mob to add or remove it.
- **Damage Tint** - Tints the screen red when your health is low; the lower your health, the stronger the tint.
- **FOV** - Sets your field of view beyond the vanilla limits and lets the held item use its own field of view.
- **Fullbright** - Brightens dark areas by raising the minimum light level of the world. At 1.0 everything is fully lit.
- **Glint Color** - Recolours the enchantment glint on items and armour and changes its strength, speed and size.
- **Hide Players** - Hides players in your vicinity.
- **Hit Bubbles** - Draws an expanding, spinning and fading bubble at the spot where you hit an entity.
- **Hit Color** - Changes the colour and strength of the flash entities show when they are hurt.
- **Hit Indicator** - Shows a hit marker at your crosshair when your melee attacks or arrows hit, with a separate colour for kills.
- **Hit Particles** - Spawns decorative particles around players while they are hurt, or floating damage and heal numbers above entities whose health changes.
- **Item ESP** - Shows the name of every dropped item and marks it with a box on screen or a ring on the ground.
- **Jump Circle** - Draws a spinning, expanding circle on the ground where a player jumps.
- **Kill Effect** - Plays an effect when a nearby player (or mob) dies: a floating cross with a sound, falling lava drops or a lightning bolt.
- **No Bob** - Removes the side-to-side sway and tilt of the view while walking, keeping an optional small vertical bounce.
- **No Glow** - Disables the vanilla Minecraft glow effect (Spectral arrows, Glowing effect, server-side glowing). Glow added by LayBalt features such as ESP keeps working.
- **No Render** - Hides visual clutter: chosen entities, particles, HUD elements, fog, weather and more. Fire, water, block and portal overlays, nausea and blindness are in Camera Helper; the hand swing animation is in Animations.
- **Particle Multiplier** - Spawns more or fewer particles, resizes them, caps how many exist and blocks particle types you list.
- **Player Size** - Changes the size of the player (client-side only).
- **Skeleton** - Draws the skeleton (head, spine, arms and legs) of players as lines that follow their animation.
- **Sound ESP** - Shows the name of every sound played around you as a fading label where it came from.
- **Storage ESP** - Highlights chests, shulker boxes, barrels and other containers around you.
- **Tracers** - Draws lines from your crosshair to other players, so you always know where they are.
- **Trails** - Draws trails behind moving players and flying pearls and arrows, or shows where flying projectiles will land.
- **Trajectories** - Predicts and draws the flight path of bow arrows (all three Terminator arrows) and ender pearls, with impact boxes and the entities they will hit.
- **Weather** - Sets the weather on your client: always clear, or rain and thunder whenever you want. The server weather is not changed.
- **World Tweaks** - Customises the open-world fog, sky and clouds, and can lock the time of day on your client.

#### Misc (22)

- **Arrow Fix** - Disables Bow Pullback on Shortbows.
- **Arrow Hit Sound** - Plays a custom sound when your arrow hits.
- **Auto Blaze Dagger** - Automatically swaps to the correct dagger for blaze!
- **Auto Sprint** - Automatically sprint for you.
- **Blaze Dagger HUD** - Blaze Slayer Dagger Attunement Helper.
- **Block Overlay** - Replaces the block selection outline with your own outline, fill, colours and animation.
- **Camera Helper** - Tweaks to the vanilla camera: camera distance, smooth zoom, camera clip, no front camera and hiding screen overlays and effects. Brightness and FOV are in Render > Fullbright / FOV.
- **Ghosts** - Rendering tweaks for the invisible ghost creepers in the Dwarven Mines (The Mist).
- **Mono Audio** - Plays all the game audio through a single channel.
- **Nametag Tweaks** - Nametag tweaks: show your own nametag, hide the background, add a text shadow and more.
- **No Cursor Reset** - Prevents the cursor from being reset to the middle of the screen when a menu opens right after another.
- **No Item Place** - Stops you from placing skull blocks/items.
- **No Rotate** - Prevents the server from snapping back your head when teleporting.
- **Pvp Blink** - Desyncs your connection to eat knockback or spoof position.
- **SB Kick** - Shows a timer on screen for when you can rejoin Skyblock.
- **Snappy Tappy** - Prevents standing still when pressing opposing direction keys.
- **Sound Manager** - Adjust volumes for every sound in the game.
- **Time Changer** - Changes the world time.
- **Trevor Helper** - Helper features for Trevor the Trapper on the Farming Islands.
- **Tweaks** - Small quality of life tweaks.
- **Waypoints** - Renders temporary waypoints from coordinates posted in chat. Commands: /lbq waypoint (alias /lbq wp).
- **World Scanner** - Scans loaded Crystal Hollows chunks for structures (crystal spots, mob spots, Fairy Grottos, Golden Dragon nest, worm fishing) and marks them with waypoints. Optional lava/water ESP.

#### Fishing (3)

- **Auto Fishing** - Automatically casts and reels in your fishing rod.
- **Auto Mob Killer** <i>(WIP)</i> - Kills sea creatures near you while Auto Fishing is enabled.
- **Auto Path** <i>(WIP)</i> - Walks a recorded route to the fishing spot. Works only while Auto Fishing is enabled.

#### Rift (2)

- **Dance Room Solver** <i>(WIP)</i> - Solves the Rift dance room. Coming soon.
- **Vampire Helper** <i>(WIP)</i> - Helps with the Riftstalker Bloodfiend: reacts to Impel, heals with melon, uses Holy Ice on Twinclaws.

#### Kuudra (10)

- **Build Helper** - Shows the state of the ballista build in Kuudra: build %, builders, freshers and the unfinished piles.
- **Fresh Tools** - Tracks the Fresh Tools perk of your party during the Kuudra build phase.
- **Kuudra Info** - Displays information about the Kuudra boss itself: a highlight box and its health.
- **Kuudra Misc** - Small Kuudra helpers: mana drain callout and automatic requeue after a run.
- **Kuudra Tracker** - Tracks the loot and profit of every Kuudra chest you open.
- **Pearl Waypoints** - Renders ender pearl lineups for the Kuudra supply phase.
- **Pre-Spot Alert** - Tells your party when the supply of your pre-spot (or its second supply) is missing in Kuudra.
- **Remove Perks** - Hides useless perks in Kuudra's Perk Menu and blocks clicks on them, so you can't buy them by accident.
- **Supply Helper** - Visual aid for the Kuudra supply phase: crate pick-up and drop-off waypoints and supply times.
- **Vesuvius** - Shows the value of Kuudra reward chests and hides already claimed runs in the Vesuvius menu.

#### Client (4)

- **Class Colors** - Allows setting custom color for every dungeon class.
- **Click Gui** - Settings of the LayBalt GUI itself: open keybind, accent color and card order.
- **Door Fix** - Fixes the door rotations for s3.
- **Mod Hider** - Stops the exploit that lets servers put a translation key inside a sign or anvil to find out which mods you use. Mods without language keys are safe from it.

---

<h3 align="center">Commands</h3>
<p align="center">
Every command is under <code>/lbq</code> (or <code>/laybalt</code>), so you never need another mod's command. <code>/lbq help</code> lists them by group (click a line to type it), <code>/lbq help &lt;command|group&gt;</code> shows every form of one command, and Tab completes feature names, floors, tiers, players and files. The standalone commands (<code>/ndw</code>, <code>/pfs</code>, <code>/lsw</code>, <code>/lbqsell</code>, ...) keep working.
</p>

#### Dungeons & Kuudra

- `/lbq f1`..`f7`, `/lbq m1`..`m7` - Join a Catacombs floor or a Master Mode floor.
- `/lbq t1`..`t5` - Join a Kuudra tier (Basic, Hot, Burning, Fiery, Infernal).
- `/lbq leaporder <name|class> <player1> [player2] [player3] [player4]` - Custom leap menu order.
- `/lbq leap <class>` - Leap to the teammate playing that class.

#### Sacks

- `/lbq ep|ij|sl|sb|dd|tap|twap [amount]` - Refill ender pearls, inflatable jerries, spirit leaps, superboom TNT, decoys, toxic or twilight arrow poison from your sacks up to `[amount]` (default 16 for pearls and leaps, 64 for the rest).

#### Terminals & F7

- `/lbq termsim [ping]`, `/lbq termsim <type|random> [ping]` - Terminal simulator.
- `/lbq timer [start|stop]` (also `/timer`) - Server tick timer (Relic Timer feature): starts by itself when Necron dies and stops when the relics spawn.

#### Waypoints

- `/lbq wp` (or `/lbq waypoint`) `add [<x> <y> <z> [name]]`, `addtemp [name] [<x> <y> <z>]`, `here <name>`, `share [<x> <y> <z>]`, `list`, `clear` - Temporary waypoints.
- `/lbq ndw <add|edit|remove|clear>` (also `/ndw`) - Dungeon waypoint on the block you look at.
- `/lbq dwp importodin [all|clipboard|<pack>]` - Import Odin dungeon waypoint packs.
- `/lbq dwp import [data|https link]`, `/lbq dwp export` - Odin export strings: import one (a string, an https:// link or the clipboard), copy all your dungeon waypoints in Odin's format.
- `/lbq dwp color <RRGGBB[AA]>`, `/lbq dwp fill`, `/lbq dwp depth` - Style a new `/ndw add` waypoint starts with (also in Dungeon Waypoints > New Waypoints).
- `/lbq dwp resetsecrets` - Show every collected secret again.
- `/lbq relative` (also `/relative`) - Room-relative coordinates of the block you look at.
- `/lbq lsw ...` (also `/lsw`) - Secret Waypoints: `add`, `remove`, `found`, `missing`, `list`, `relpos`, `reltarget`, `import`, `unimport`, `reload`.

#### Party & Players

- `/lbq cata [player]` - Dungeon stats card (level, secrets, classes, floor times, magical power, armour, missing items). Uses the stats server set in Secrets Counter > API URL.
- `/lbq soopy <command> [player]` - Soopy stats commands (dungeon, secrets, nw, skills, ...).
- `/lbq pfs [player]` (also `/pfs`) - Party Finder stats.
- `/lbq sendcoords [message]` - Send your coordinates in chat.
- `/lbq pcbl <add|remove|list|clear>` (also `/pcbl`) - Party command blacklist.
- `/lbq posmsg <add at|add in|remove|clear|list>` - Positional messages.

#### Items & Inventory

- `/lbq sell <add|remove|clear|list|defaults>` (also `/lbqsell`) - Auto Sell list.
- `/lbq autoclicker` or `/lbq ac` `<add|remove|clear|list>` (also `/lbqac`) - Auto Clicker item whitelists.
- `/lbq protectitem` (also `/protectitem`) - Protect the held item from dropping and selling.
- `/lbq swapmask`, `/lbq rodswap`, `/lbq swapto <skyblock id>` - Equipment swaps.

#### Config & Imports

- `/lbq` - Open the LayBalt GUI. `/lbq hud` or `/lbq edithud` - HUD editor.
- `/lbq toggle <feature>` - Turn a feature on or off; `Info Display` and `Info_Display` both work (WIP features stay off).
- `/lbq reset <feature|hud|gui>` - Reset a feature's settings, every HUD position or the GUI state.
- `/lbq config <load|create|delete> <name>` - Config profiles.
- `/lbq import config <na|odin|odinclient|all>` (old form `/lbq import <source>` still works) - Import settings from another mod again.
- `/lbq import odinwaypoints [all|clipboard|<pack>]`, `/lbq import secrets <path>` - Import Odin dungeon waypoints or a secret waypoint dataset.
- `/lbq export odinwaypoints` - Copy your dungeon waypoints in Odin's format.

#### Misc

- `/lbq help [command|group]`, `/lbq ping`, `/lbq tps`, `/lbq discord`.
- `/lbq esp <list|add|remove|clear>` - Custom ESP list (OdinClient's `/odc highlight`).
- `/lbq blockesp <add|remove|list|clear>` (also `/blockesp`) - Block ESP list.
- `/lbq chathider <add|remove|list>` (also `/chathider`) - Hide chat messages by regex.
- `/lbq sim <message>`, `/lbq debug [flag]` - Testing helpers.

#### Coming from NoammAddons, Odin or OdinClient

| Old command | LayBaltQOL |
| --- | --- |
| `/na`, `/od`, `/odin` | `/lbq` |
| `/na hud`, `/od edithud` | `/lbq hud`, `/lbq edithud` |
| `/od f7`, `/od m7`, `/od t5` | `/lbq f7`, `/lbq m7`, `/lbq t5` |
| `/na ep`, `/od ep [amount]` (and ij, sl, sb, dd, tap, twap) | `/lbq ep [amount]` |
| `/od toggle module <name>` | `/lbq toggle <feature>` |
| `/od reset module <name>`, `/od reset hud`, `/od reset clickgui` | `/lbq reset <feature>`, `/lbq reset hud`, `/lbq reset gui` |
| `/od sendcoords [message]` | `/lbq sendcoords [message]` |
| `/na leaporder`, `/od leaporder <p1> .. <p4>` | `/lbq leaporder name <p1> .. <p4>` |
| `/cata [player]` | `/lbq cata [player]` |
| `/soopycmd <command> [player]` | `/lbq soopy <command> [player]` |
| `/termsim [type] [ping]` | `/lbq termsim [type] [ping]` |
| `/odw addtemp`, `/odw share`, `/odw clear` | `/lbq wp addtemp`, `/lbq wp share`, `/lbq wp clear` |
| `/dwp import`, `/dwp export`, `/dwp color`, `/dwp fill`, `/dwp depth`, `/dwp resetsecrets` | `/lbq dwp import`, `export`, `color`, `fill`, `depth`, `resetsecrets` |
| `/ndw ...`, `/pfs`, `/pcbl`, `/chathider`, `/protectitem`, `/timer`, `/relative` | `/lbq ndw ...`, `/lbq pfs`, `/lbq pcbl`, `/lbq chathider`, `/lbq protectitem`, `/lbq timer`, `/lbq relative` (old commands still work) |
| `/na config`, `/na sim`, `/na debug`, `/na ping`, `/na tps`, `/na swapmask`, `/na rodswap`, `/na swapto`, `/na leap` | same subcommand under `/lbq` |
| `/odc sell ...`, `/odc ac ...`, `/odc highlight ...` | `/lbq sell ...`, `/lbq ac ...`, `/lbq esp ...` |

Not carried over: `/na rtca`, `/na update` and `/ws` (they need NoammAddons' servers), `/odw` screen and `/odw clear area` (LayBaltQOL has no saved per-area waypoints), `/dwp type|size|useblocksize` (LayBaltQOL waypoints always outline the block itself), `/oddev`, `/odc stream` (no hidden cheat category in the LayBalt GUI), NoammAddons' dev `/test`.

---

<h3 align="center">Installation</h3>
<p align="center">
Requires Minecraft 26.2 with <a href="https://fabricmc.net/use/">Fabric Loader 0.19.3+</a> and the following mods in your <code>mods</code> folder:
</p>

- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- `LayBaltQOL-<version>-26.2.jar` from the [releases](https://github.com/LayBalt/LayBalt-QOL/releases)
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) to open the config from the mods list

Type <code>/lbq</code> (or <code>/laybalt</code>) in chat to open the LayBalt GUI. The config is stored in <code>config/LayBaltQOL/</code>.

---

<h3 align="center">Official Links</h3>
<p align="center">
Discord: <a href="https://discord.gg/laybalt">discord.gg/laybalt</a><br>
More links coming soon...
</p>

---

<h3 align="center">Contribution Information</h3>
<p align="center">
Pull requests are welcome. The project builds with Gradle on JDK 25.
For more info: <a href="https://github.com/LayBalt/LayBalt-QOL/blob/main/ContributingGuide.md">Contributor Guide</a>.

---

<h3 align="center">Notice</h3>
<p align="center">
NOTICE: LayBalt QOL 3.x is built on the <a href="https://github.com/Noamm9/NoammAddons">NoammAddons</a> core by Noamm9, released under the CC0-1.0 license. Huge thanks to Noamm9 for the original work.<br>
Some features are ported from <a href="https://github.com/odtheking/Odin">Odin</a> and OdinClient (both BSD-3-Clause), and some were re-implemented after features of Skyblocker and ThunderHack-Recode. The bundled <a href="https://github.com/rsms/inter">Inter</a> font is licensed under the SIL Open Font License 1.1. See <a href="THIRD_PARTY_NOTICES.md">THIRD_PARTY_NOTICES.md</a> for the licences and details.
</p>
