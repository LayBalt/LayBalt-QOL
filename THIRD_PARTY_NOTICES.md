# Third-party notices

LayBaltQOL contains code and ideas from the projects listed below. This file lists each project, its licence and what LayBaltQOL takes from it. Files that contain ported code start with a comment naming the project, its licence and its copyright holder.

## NoammAddons (core)

- Project: [NoammAddons](https://github.com/Noamm9/NoammAddons) by Noamm9
- Licence: CC0-1.0 (public domain dedication)
- Use: LayBaltQOL 3.x is built on the NoammAddons code base. The mod core (feature system, config, HUD system, event bus, mixins, dungeon utilities and most of the original features) comes from NoammAddons. Removed NoammAddons features were restored from its source in later releases, for example F4 Features, Hidden Mobs, I Hate Doors, Teammate Highlight, Leap Counter, Melody Display, Terminal Highlight, Item Rarity, Item Overlays, Item Tooltip, Inventory Search, Protect Item, Pet Display, Scoreboard, Block Overlay, Box3D, Damage Splash and Mono Audio. The NoammAddons click GUI is no longer used: the LayBalt GUI (window, sidebar, cards, settings panel, widgets, HUD editor and toasts) is LayBaltQOL's own code. CC0-1.0 does not require attribution; it is given here as thanks. The CC0-1.0 text is in [LICENSE](LICENSE).

## Odin

- Project: [Odin](https://github.com/odtheking/Odin)
- Licence: BSD-3-Clause, Copyright (c) 2025 odtheking (full text below)
- Use: code ported and adapted to LayBaltQOL, mainly:
  - Dungeon and Floor 7: terminal solver options, terminal simulator, Terminal Times and personal bests, Wither Dragons, M7 Relics, Arrows Device, Blaze solver, puzzle timers, Mimic, Blood Camp and F7 title/Melody additions, Chest Profit overlay, Star Mob Highlight options.
  - SkyBlock: Command Keybinds, Quiver Display, loadout/pet/slot binds, Player HUD, chat commands and chat features, Gyro Helper, Spring Boots.
  - Kuudra: Kuudra utilities, supply and pearl lineups, Kuudra Tracker, Kuudra Info, Vesuvius, Supply Helper, Pearl Waypoints, Pre-Spot Alert (formerly No Pre), Remove Perks, Fresh Tools, Build Helper, Kuudra Misc.
  - Teleport and render options: Etherwarp (Singleplayer Etherwarp, Use Server Position, swimming eye height, hide in menus), Mage Beam (Depth Check, Hide Particles, Min Points), Render Optimizer (Hide Dying Armor Stands, Hide Archer Passive).
  - Dungeon run tools: Map Info HUDs and portal highlight (in Score Calculator), Secrets Counter, Positional Messages (with /lbq posmsg), Puzzle HUD, Room Clear, Dungeon Queue, Inactive Waypoints, Extra Stats, Run Splits personal bests, Kuudra splits and current split HUD, and the Odin dungeon waypoint pack import.
  - Chat waypoints (Waypoints, with /lbq waypoint and /lbq wp), No Cursor Reset, Hide Players and Player Size, including the mixins `MixinMouseHandlerNoCursorReset` and `MixinAvatarRendererPlayerSize`.
  - Settings import: `/lbq import odin` maps these Odin modules to their LayBaltQOL features, and `/lbq dwp importodin` imports Odin dungeon waypoint packs.

## OdinClient

- Project: [OdinClient](https://github.com/skies-starred/OdinClient)
- Licence: BSD-3-Clause, Copyright (c) 2026 Starred (full text below)
- Use: code ported and adapted to LayBaltQOL, mainly: Secrets (hitboxes, close chest, clicked secret highlight), Breaker Helper, Auto GFS, Auto Superboom, Ghost Block, Auto Sell, Auto Abilities, Camera Helper options, Cheater Map options, I Hate Diorite options, Auto Clicker, Auto Enchanting and Auto Harp parts, World Scanner, Trajectories, Escrow Fix, Farm Keys, Ghosts and No Glow. No new OdinClient code was added in the 3.x GUI and feature-restore round.

## Skyblocker and ThunderHack-Recode

- Projects: [Skyblocker](https://github.com/SkyblockerMod/Skyblocker) and [ThunderHack-Recode](https://github.com/Pan4ur/ThunderHack-Recode)
- No code, assets or data from these projects is included. The features below were re-implemented for LayBaltQOL from descriptions of how the original features behave, without copying code.
  - After Skyblocker: Salvage Overlay and Chest Profit (Croesus) helpers, Sellable Items Highlighter, Star Mob Highlight options, Secret Waypoints.
  - After ThunderHack-Recode: the Render category (Aspect Ratio, Block ESP, Bread Crumbs, Damage Tint, FOV, Fullbright, Hit Bubbles, Hit Particles, Item ESP, Jump Circle, Kill Effect, No Bob, No Render, Skeleton, Sound ESP, Storage ESP, Tracers, Trails, World Tweaks), Smooth Zoom (in Camera Helper) and extra options in Animations and Nametag Tweaks.

## Inter (SIL OFL 1.1, Copyright 2020 The Inter Project Authors)

- Project: [Inter](https://github.com/rsms/inter) by Rasmus Andersson and The Inter Project Authors
- Licence: SIL Open Font License, Version 1.1 (full text below and in the jar at `assets/lbqmod/font/ofl.txt`)
- Use: the unmodified Inter variable font is bundled as `assets/lbqmod/font/inter.ttf`, with the font definition `assets/lbqmod/font/inter.json` (`lbqmod:inter`). It is used for all text in the LayBalt screens: main window, settings panel, tooltips, HUD editor, notifications and sub-screens. The font is not sold on its own, and LayBaltQOL is free software. The copyright notice and the licence are shipped in the jar next to the font as `ofl.txt`.

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

## Licence text: Inter

```
Copyright 2020 The Inter Project Authors (https://github.com/rsms/inter)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```
