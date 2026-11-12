// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
/**
 * Taken from OdinClient 1.8.9
 * odtheking's BSD-3 Clause License applies to this file.
 *
 * BSD 3-Clause License
 *
 * Copyright (c) 2023-2025, odtheking
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.laybalt.features.impl.general

//#if CHEAT

import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.location.LocationUtils

object EscrowFix: Feature("Automatically reopens the Auction House or Bazaar when Hypixel's escrow closes it (expired/invalid bids, BIN claims, Bazaar instant-buy refunds).", name = "Escrow Fix") {
    private val messages = mapOf(
        "There was an error with the auction house! (AUCTION_EXPIRED_OR_NOT_FOUND)" to "ah",
        "There was an error with the auction house! (INVALID_BID)" to "ah",
        "Claiming BIN auction..." to "ah",
        "Visit the Auction House to collect your item!" to "ah"
    )

    private val bazaarRegex = Regex("""^Escrow refunded (\d+) coins for Bazaar Instant Buy Submit!$""")

    override fun init() {
        register<ChatMessageEvent> {
            if (! LocationUtils.inSkyblock) return@register
            val message = event.unformattedText.trim()
            val command = messages[message] ?: if (bazaarRegex.matches(message)) "bz" else return@register
            ChatUtils.sendCommand(command)
        }
    }
}
//#endif
