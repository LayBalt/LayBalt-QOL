# Third-party notices

LayBaltQOL contains code and ideas from the projects listed below. This file lists each project, its licence and what LayBaltQOL takes from it. Files that contain ported code start with a comment naming the project, its licence and its copyright holder.

## NoammAddons (core)

- Project: [NoammAddons](https://github.com/Noamm9/NoammAddons) by Noamm9
- Licence: CC0-1.0 (public domain dedication)
- Use: LayBaltQOL 3.x is built on the NoammAddons code base. The mod core (feature system, config, click GUI, HUD, event bus, mixins, dungeon utilities and most of the original features) comes from NoammAddons. CC0-1.0 does not require attribution; it is given here as thanks. The CC0-1.0 text is in [LICENSE](LICENSE).

## Odin

- Project: [Odin](https://github.com/odtheking/Odin)
- Licence: BSD-3-Clause, Copyright (c) 2025 odtheking (full text below)
- Use: code ported and adapted to LayBaltQOL, mainly:
  - Dungeon and Floor 7: terminal solver options, terminal simulator, Terminal Times and personal bests, Wither Dragons, M7 Relics, Arrows Device, Blaze solver, puzzle timers, Mimic, Blood Camp and F7 title/Melody additions, Chest Profit overlay, Star Mob ESP options.
  - SkyBlock: Command Keybinds, Quiver Display, loadout/pet/slot binds, Player HUD, chat commands and chat features, Gyro Helper, Spring Boots.
  - Kuudra: Kuudra utilities, supply and pearl lineups, Kuudra Tracker, Kuudra Info, Vesuvius, Supply Helper, Pearl Waypoints, No Pre, Remove Perks, Fresh Tools, Build Helper, Kuudra Misc.
  - Teleport and render options: Etherwarp (Singleplayer Etherwarp, Use Server Position, swimming eye height), Mage Beam (Depth Check, Hide Particles, Min Points), Render Optimizer (Hide Dying Armor Stands, Hide Archer Passive).
  - Dungeon run tools: Map Info HUDs and portal highlight (in Score Calculator), Secrets Counter, Positional Messages (with /lbq posmsg), Puzzle HUD, Room Clear, Dungeon Queue, Inactive Waypoints, Extra Stats, Run Splits personal bests, Kuudra splits and current split HUD, and the Odin dungeon waypoint pack import.
  - Chat waypoints (Waypoints, with /lbq waypoint), No Cursor Reset, Hide Players and Player Size.

## OdinClient

- Project: [OdinClient](https://github.com/skies-starred/OdinClient)
- Licence: BSD-3-Clause, Copyright (c) 2026 Starred (full text below)
- Use: code ported and adapted to LayBaltQOL, mainly: Secrets (hitboxes, close chest, clicked secret highlight), Breaker Helper, Auto GFS, Auto Superboom, Ghost Block, Auto Sell, Auto Abilities, Camera Helper options, Cheater Map options, I Hate Diorite options, Auto Clicker, Auto Enchanting and Auto Harp parts, World Scanner, Trajectories, Escrow Fix, Farm Keys, Ghosts and No Glow.

## Skyblocker and ThunderHack-Recode

- Projects: [Skyblocker](https://github.com/SkyblockerMod/Skyblocker) and [ThunderHack-Recode](https://github.com/Pan4ur/ThunderHack-Recode)
- No code, assets or data from these projects is included. The features below were re-implemented for LayBaltQOL from descriptions of how the original features behave, without copying code.
  - After Skyblocker: Salvage Overlay and Chest Profit (Croesus) helpers, Sellable Items Highlighter, Star Mob ESP highlight options, Secret Waypoints.
  - After ThunderHack-Recode: the Render category (Aspect Ratio, Block ESP, Bread Crumbs, Damage Tint, FOV, Fullbright, Hit Bubbles, Hit Particles, Item ESP, Jump Circle, Kill Effect, No Bob, No Render, Skeleton, Sound ESP, Storage ESP, Tracers, Trails, World Tweaks), Smooth Zoom (in Camera Helper) and extra options in Animations and Nametag Tweaks.

---

## Licence text: Odin

```
BSD 3-Clause License

Copyright (c) 2025, odtheking

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Licence text: OdinClient

```
BSD 3-Clause License

Copyright (c) 2026, Starred

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
