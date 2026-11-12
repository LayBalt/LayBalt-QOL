package com.laybalt.event.impl

import com.laybalt.event.Event
import com.laybalt.utils.PartyUtils.OnlineState
import com.laybalt.utils.PartyUtils.PartyRole

/**
 * Party state changes, posted by [com.laybalt.utils.PartyUtils] (NoammAddons 7a8b74f3, 385fc5fc, 26984c13).
 * Posted on the thread that delivered the chat line or container (the client thread).
 */
abstract class PartyEvent: Event(false) {
    /** You are in a party now (first member seen, or the first `/pl` header). */
    object Joined: PartyEvent()

    /** You are no longer in a party. [members] are the other members it had. */
    class Left(val members: List<String>): PartyEvent()

    class MemberJoined(val name: String): PartyEvent()
    class MemberLeft(val name: String, val cause: LeaveCause): PartyEvent()
    class MemberStateChange(val name: String, val state: OnlineState): PartyEvent()

    class RoleChange(val name: String, val role: PartyRole): PartyEvent()

    class Invite(val inviter: String, val invitee: String): PartyEvent()
    class InviteExpired(val invitee: String): PartyEvent()

    /** The Dungeon Hub Group Builder (Mort's Party Finder menu) was opened: its dungeon type, floor and group note. */
    class FinderGroupSettings(val floor: Int, val masterMode: Boolean, val note: String): PartyEvent()

    /** The party leader summoned you to their server (`/p warp`). */
    object Warp: PartyEvent()

    /** Any of [com.laybalt.utils.PartyUtils.partyMembers], `pendingInvites` or `isInParty` changed. */
    object Update: PartyEvent()

    enum class LeaveCause { LEFT, KICKED, DISCONNECTED }
}
