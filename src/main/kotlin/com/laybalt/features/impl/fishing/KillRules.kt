package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.config.PogObject
import com.laybalt.features.impl.fishing.SeaCreatures.SeaCreature

/**
 * What Auto Mob Killer does with each of your sea creatures, edited in the Sea Creature Rules window.
 * Creatures without their own rule use [RulesData.defaultAction] (or [RulesData.defaultRareAction] for rare catches).
 */
object KillRules {
    /** Highest loadout number: the `/loadout` menu has up to 3 pages of 12 ("(1/3) Loadouts"). */
    const val MAX_LOADOUT = 36

    enum class KillAction(val label: String, val description: String) {
        IGNORE("Ignore", "Leave it alone and keep fishing."),
        LEARNED(
            "Learned",
            "Picks the weapon each fight the way you do in your recordings: by distance, height and how tough the creature is. The rod when you have nothing learned yet."
        ),
        ROD("Rod", "Kill it with the fishing rod you are holding, nothing is swapped. Whether the recast after the catch goes first (its new bobber kills weak creatures) is learned from your recordings or picked on the row."),
        WEAPON("Weapon", "Equip the chosen loadout and weapon, kill it, then go back to fishing."),
        STOP("Stop", "Stop Auto Fishing and alert you, so you can take this fight yourself.")
    }

    /**
     * The click line of a rule. On a Weapon rule it is the click ([label], [description]); on a Rod rule it is whether
     * the recast after the catch goes first ([rodLabel], [rodDescription]).
     */
    enum class AttackMode(val label: String, val description: String, val rodLabel: String, val rodDescription: String) {
        AUTO(
            "Learned", "Uses the click you used for this creature in your recordings (left click without one).",
            "Learned", "Recasts first when your recordings show it (the bobber killed this creature, or you recast right through your catches), otherwise melee only."
        ),
        LEFT("Left Click", "Melee hits.", "Melee only", "Hits it with the rod without recasting first."),
        RIGHT(
            "Right Click", "Uses the weapon's ability (mage weapons, Fire Veil, Flay...).",
            "Recast first", "Recasts right after the catch so the new bobber can kill it, then hits it with the rod if it survives."
        )
    }

    class KillRule(
        var action: KillAction = KillAction.ROD,
        /** Loadout (1-[MAX_LOADOUT], in /loadout order) equipped before the fight; 0 keeps the current one. */
        var loadout: Int = 0,
        /** SkyBlock id of the weapon taken from the hotbar; empty keeps the item in hand. */
        var weaponId: String = "",
        /** Display name of [weaponId] for the rules window (the weapon may not be in the hotbar while editing). */
        var weaponName: String = "",
        /** A Weapon rule's click, or a Rod rule's Learned / Melee only (LEFT) / Recast first (RIGHT), see [AttackMode]. */
        var attack: AttackMode = AttackMode.AUTO
    ) {
        fun copy() = KillRule(action, loadout, weaponId, weaponName, attack)
    }

    class RulesData(
        /** Creature name -> rule. Only creatures with their own rule are stored. */
        var rules: LinkedHashMap<String, KillRule> = linkedMapOf(),
        var defaultAction: KillAction = KillAction.LEARNED,
        var defaultRareAction: KillAction = KillAction.STOP,
        /** Loadout put back on after a fight that equipped another one; 0 = stay in the fight loadout. */
        var fishingLoadout: Int = 0,
        /**
         * The file's format, [VERSION] once [sanitize] has migrated it. 0 in files from before it existed: Gson keeps
         * the constructor default for a missing field, so this default must stay 0.
         */
        var version: Int = 0
    ) {
        /**
         * Gson leaves fields null that Kotlin types as non-null (explicit nulls in an edited file, enum names it does not
         * know); every one of them gets its default back, so nothing downstream can throw on them. Then an older file is
         * migrated once: its Rod default (the old default) becomes Learned, and a Rod picked again afterwards stays.
         * True when the file must be saved because it was migrated.
         */
        @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
        internal fun sanitize(): Boolean {
            if (rules == null) rules = linkedMapOf()
            rules.entries.removeIf { it.key == null || it.value == null }
            rules.values.forEach { r ->
                if (r.action == null) r.action = KillAction.ROD
                if (r.attack == null) r.attack = AttackMode.AUTO
                if (r.weaponId == null) r.weaponId = ""
                if (r.weaponName == null) r.weaponName = ""
                r.loadout = r.loadout.coerceIn(0, MAX_LOADOUT)
            }
            if (defaultAction == null) defaultAction = KillAction.LEARNED
            if (defaultRareAction == null) defaultRareAction = KillAction.STOP
            fishingLoadout = fishingLoadout.coerceIn(0, MAX_LOADOUT)
            if (version >= VERSION) return false
            if (version < 2 && defaultAction == KillAction.ROD) defaultAction = KillAction.LEARNED
            version = VERSION
            return true
        }

        companion object {
            /** The current [version]: 2 made Learned the default action. */
            const val VERSION = 2
        }
    }

    val data = PogObject("fishingKillRules", RulesData()).also { if (it.get().sanitize()) it.save() }

    /** The rule used for [creature]; [name] is the nametag name for creatures missing from the table. */
    fun ruleFor(creature: SeaCreature?, name: String): KillRule {
        val d = data.get()
        d.rules[creature?.name ?: name]?.let { return it }
        return KillRule(if (creature?.rare == true) d.defaultRareAction else d.defaultAction)
    }

    fun set(name: String, rule: KillRule?) {
        val d = data.get()
        if (rule == null) d.rules.remove(name) else d.rules[name] = rule
        data.save()
    }
}

//#endif
