// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
package com.laybalt.features.impl.render

import com.laybalt.features.Feature

/**
 * The work is done in [com.laybalt.mixin.MixinEntityNoGlow]: while enabled, `Entity.isCurrentlyGlowing()` returns false
 * on the client. Glow added by LayBalt features (ESP etc.) is applied separately and is not affected.
 */
object NoGlow: Feature("Disables the vanilla Minecraft glow effect (Spectral arrows, Glowing effect, server-side glowing). Glow added by LayBalt features such as ESP keeps working.", name = "No Glow")
