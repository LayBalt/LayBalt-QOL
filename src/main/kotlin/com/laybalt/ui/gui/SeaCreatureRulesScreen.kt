package com.laybalt.ui.gui

//#if CHEAT

import com.laybalt.LayBaltQOL.mc
import com.laybalt.features.impl.fishing.BehaviorProfile
import com.laybalt.features.impl.fishing.KillRules
import com.laybalt.features.impl.fishing.KillRules.AttackMode
import com.laybalt.features.impl.fishing.KillRules.KillAction
import com.laybalt.features.impl.fishing.KillRules.KillRule
import com.laybalt.features.impl.fishing.LoadoutSwitcher
import com.laybalt.features.impl.fishing.SeaCreatures
import com.laybalt.features.impl.fishing.SeaCreatures.SeaCreature
import com.laybalt.features.impl.fishing.WeaponPlanner
import com.laybalt.ui.Palette
import com.laybalt.ui.clickgui.LayBaltScreen
import com.laybalt.ui.clickgui.TooltipManager
import com.laybalt.ui.clickgui.UI
import com.laybalt.ui.clickgui.components.Popover
import com.laybalt.ui.clickgui.components.Popovers
import com.laybalt.ui.clickgui.components.Rect
import com.laybalt.ui.clickgui.components.ScrollArea
import com.laybalt.ui.clickgui.components.SearchField
import com.laybalt.ui.clickgui.components.settings.Style
import com.laybalt.ui.render.Icon
import com.laybalt.ui.text.UIFont
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.items.ItemRarity
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.world.item.ItemStack
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Editor of Auto Mob Killer's per sea creature rules: locations on the left, one row per creature with what to do with
 * it (Ignore / Learned / Rod / Weapon / Stop), a loadout + weapon + click line on Weapon rows, a rod mode line on own Rod
 * rules, and what your Fishing Recorder recordings suggest. Creatures your recordings saw split off one of yours (Bayou
 * Sludglings) get a row in their parent's location too. The defaults for creatures without their own rule are in the
 * footer. Everything is edited on a copy and saved when the window closes.
 */
class SeaCreatureRulesScreen(private val parent: Screen? = null): LayBaltSubScreen("Sea Creature Rules") {
    private companion object {
        const val SIDE_W = 124f
        const val SIDE_ROW_H = 18f
        const val SIDE_ROW_GAP = 2f
        const val ROW_H = 30f
        const val EXT_H = 26f
        const val ROW_GAP = 4f
        const val HEADER_ROW_H = 22f
        const val FOOTER_H = 46f
        const val SEG_H = 18f
        const val BOX_H = 18f
        const val LOADOUT_W = 126f
        const val SEARCH_W = 170f

        const val NAME_SIZE = 7.6f
        const val SEG_SIZE = 6.8f
        const val ATTACK_SIZE = 6.6f
        const val BOX_SIZE = 7f
        const val PREFIX_SIZE = 6.4f
        const val CHIP_SIZE = 5.4f
        const val HINT_SIZE = 6f
        const val CAPS_SIZE = 5.6f

        const val PICK_ITEM_H = 18f
        const val PICK_MAX = 9
        const val PICK_PAD = 4f
        const val PICK_HINT_H = 18f
        const val PICK_SIZE = 7.2f
        const val DETAIL_SIZE = 6.2f

        const val KEY_OTHER = "default:other"
        const val KEY_RARE = "default:rare"
        const val KEY_FISHING = "default:fishing"

        val IGNORE_COLOR = Color(150, 140, 178)
        val STOP_COLOR = Color(222, 86, 120)
        val WHITE = Color(250, 247, 255)
        val OFFSCREEN = Rect(-10000f, -10000f, 0f, 0f)

        val ACTION_LABELS = KillAction.entries.map { it.label }
        val ATTACK_LABELS = listOf("Learned", "Left", "Right")
        val ROD_LABELS = AttackMode.entries.map { it.rodLabel }
        /** Actions offered for the defaults (a default Weapon rule would have no loadout or weapon). */
        val DEFAULT_ACTIONS = listOf(KillAction.IGNORE, KillAction.LEARNED, KillAction.ROD, KillAction.STOP)

        /** Star and master star symbols SkyBlock appends to item names. */
        val STARS = Regex("[✪➊➋➌➍➎]")

        /** Location shown when the window opens again (null = All). */
        var lastGroupId: String? = null

        /** Draws [stack] as a [size] x [size] icon (skipped while the window fades, item icons cannot be faded). */
        fun drawStack(ctx: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, size: Float) {
            if (UI.alpha < 0.6f) return
            val pose = ctx.pose()
            pose.pushMatrix()
            pose.translate(x, y)
            pose.scale(size / 16f, size / 16f)
            ctx.item(stack, 0, 0)
            pose.popMatrix()
        }

        /** Reads a field Gson may have left null although Kotlin types it non-null. */
        fun <T> nullable(value: T?): T? = value
    }

    // ------------------------------------------------------------------ model

    private sealed interface Item

    private class Header(val label: String, val count: Int): Item

    /** Animated position of a segmented control's selection pill. */
    private class SegState {
        var x = Float.NaN
        var w = Float.NaN

        fun reset() {
            x = Float.NaN
            w = Float.NaN
        }
    }

    /** [parent]: the creature a child row splits off (only rows your recordings added, see [findChildren]). */
    private class Entry(val creature: SeaCreature, val parent: String = ""): Item {
        val searchText = (creature.oldNames + creature.name + parent).joinToString("\n").lowercase()
        val hover = UI.Smooth()
        val expand = UI.Smooth(0f, 18f)
        val resetHover = UI.Smooth()
        val useHover = UI.Smooth()
        val actionPill = SegState()
        val attackPill = SegState()
        val rodPill = SegState()
        /** Second line shown (Weapon or Rod), kept while it closes. */
        var line = KillAction.WEAPON
        var drawnFrame = -10L
        var loadoutBox = Rect(0f, 0f, 0f, 0f)
        var weaponBox = Rect(0f, 0f, 0f, 0f)
    }

    /** A clickable region recorded while drawing; [clip] limits it to the visible part of a scrolled list. */
    private class Hit(val rect: Rect, val clip: Rect?, val action: () -> Unit)

    private class HotbarItem(val slot: Int, val stack: ItemStack, val id: String, val name: String)

    private class Option(val label: String, val value: Int, val detail: String? = null, val stack: ItemStack? = null, val tip: String? = null, val muted: Boolean = false)

    // working copy, saved in onClosing
    private val rules = LinkedHashMap<String, KillRule>()
    /** Rows that were a Weapon rule in this window (or on disk): switching them back to Weapon keeps your own picks. */
    private val wasWeapon = HashSet<String>()
    /**
     * Rod modes of the working copy (Learned when missing). [KillRule.attack] holds a Weapon rule's click here, so a
     * switch between Rod and Weapon keeps both picks; [asSaved] puts the mode back for saving.
     */
    private val rodModes = HashMap<String, AttackMode>()
    private var defaultAction: KillAction
    private var defaultRareAction: KillAction
    private var fishingLoadout: Int

    init {
        val d = KillRules.data.get()
        nullable(d.rules)?.forEach { (name, stored) ->
            val r = nullable(stored) ?: return@forEach
            if (nullable(name) == null) return@forEach
            val action = nullable(r.action) ?: KillAction.ROD
            val attack = nullable(r.attack) ?: AttackMode.AUTO
            if (action == KillAction.ROD) rodModes[name] = attack
            rules[name] = KillRule(
                action,
                r.loadout.coerceIn(0, KillRules.MAX_LOADOUT),
                nullable(r.weaponId) ?: "",
                nullable(r.weaponName) ?: "",
                if (action == KillAction.ROD) AttackMode.AUTO else attack
            )
        }
        defaultAction = nullable(d.defaultAction) ?: KillAction.LEARNED
        defaultRareAction = nullable(d.defaultRareAction) ?: KillAction.STOP
        fishingLoadout = d.fishingLoadout.coerceIn(0, KillRules.MAX_LOADOUT)
        rules.forEach { (name, r) -> if (r.action == KillAction.WEAPON) wasWeapon += name }
    }

    private val profile: BehaviorProfile.Profile get() = BehaviorProfile.current
    /** What the recordings show per creature name (Gson could leave the map null in a hand-edited file). */
    private val habits: Map<String, BehaviorProfile.Habit> get() = nullable(profile.habits) ?: emptyMap()

    private val groupLabels = SeaCreatures.groups.associate { it.id to it.label }
    private val tableEntries = SeaCreatures.all.map { Entry(it) }
    private val tableByGroup = tableEntries.groupBy { it.creature.groupId }
    /** Rows of the creatures your recordings saw split off yours that the table does not know (see [findChildren]). */
    private var childEntries: List<Entry> = emptyList()
    private var entries: List<Entry> = tableEntries
    private var entriesByGroup: Map<String, List<Entry>> = tableByGroup
    /** The profile [childEntries] were found in: a relearn while the window is open can bring new ones. */
    private var entriesProfile: BehaviorProfile.Profile? = null

    /** What Learned does per creature name ([WeaponPlanner.summary]), for [summariesFor]. */
    private val summaries = HashMap<String, String>()
    private var summariesFor: BehaviorProfile.Profile? = null
    private val sideIds: List<String?> = listOf<String?>(null) + SeaCreatures.groups.map { it.id }
    private var groupId: String? = lastGroupId?.takeIf { it in groupLabels }

    private val search = SearchField("Search creatures…", showHint = false) { rebuild() }
    private val scroll = ScrollArea(ROW_H * 2f)
    private val sideScroll = ScrollArea(SIDE_ROW_H * 3f)

    private val items = ArrayList<Item>()
    private var tops = FloatArray(0)
    private val hits = ArrayList<Hit>()
    private var frame = 0L

    private val sideHover = HashMap<String, UI.Smooth>()
    private val sideSel = UI.Smooth(0f, 18f)
    private var sideSelY = Float.NaN
    private val applyHover = UI.Smooth()
    private val doneHover = UI.Smooth()
    private val fishingUseHover = UI.Smooth()

    private var sideRect = Rect(0f, 0f, 0f, 0f)
    private var sideView = Rect(0f, 0f, 0f, 0f)
    private var listRect = Rect(0f, 0f, 0f, 0f)
    private var footerRect = Rect(0f, 0f, 0f, 0f)
    private var otherBox = Rect(0f, 0f, 0f, 0f)
    private var rareBox = Rect(0f, 0f, 0f, 0f)
    private var fishingBox = Rect(0f, 0f, 0f, 0f)
    private var applyRect = Rect(0f, 0f, 0f, 0f)
    private var doneRect = Rect(0f, 0f, 0f, 0f)

    private var actionWidths = FloatArray(0)
    private var attackWidths = FloatArray(0)
    private var rodWidths = FloatArray(0)

    private var flash: String? = null
    private var flashUntil = 0L

    private var hotbarCache: List<HotbarItem> = emptyList()
    private var hotbarAt = 0L

    /** Row that just turned into a Weapon row: kept in view while its weapon line opens (until the list is scrolled). */
    private var revealing: Entry? = null

    // ------------------------------------------------------------------ header

    override val heading get() = "Sea Creature Rules"
    override val headingIcon get() = Icon.FISH

    override val subheading: String get() {
        val n = rules.size
        val own = when (n) {
            0 -> "No own rules yet"
            1 -> "1 own rule"
            else -> "$n own rules"
        }
        val p = profile
        if (!p.hasData) {
            return if (n == 0) "No recordings yet · record yourself with the Fishing Recorder"
            else "$own · no recordings yet, record yourself with the Fishing Recorder"
        }
        val recordings = if (p.sessions == 1) "1 recording" else "${p.sessions} recordings"
        val kills = if (p.kills == 1) "1 kill" else "${p.kills} kills"
        return "$own · learned from $recordings (${p.minutes.roundToInt()} min, $kills)"
    }

    override fun windowSize(canvasW: Float, canvasH: Float) =
        (canvasW * 0.70f).coerceIn(620f, 760f) to (canvasH * 0.80f).coerceIn(380f, 470f)

    override fun returnTo() = parent ?: LayBaltScreen()

    override fun init() {
        super.init()
        rebuild(resetScroll = false)
    }

    private fun searchWidth() = SEARCH_W.coerceAtMost(window.w * 0.28f)

    override fun subtitleRight() = closeRect.x - 8f - searchWidth() - 12f

    override fun drawHeaderExtras(ctx: GuiGraphicsExtractor, right: Float, mx: Float, my: Float) {
        val w = searchWidth()
        val blocked = underPopover(mx, my)
        search.draw(ctx, Rect(right - w, window.y + (HEADER_H - 22f) / 2f, w, 22f), if (blocked) -10000f else mx, if (blocked) -10000f else my)
    }

    // ------------------------------------------------------------------ data

    private fun defaultFor(c: SeaCreature) = if (c.rare) defaultRareAction else defaultAction
    private fun ensureRule(c: SeaCreature) = rules.getOrPut(c.name) { KillRule(defaultFor(c)) }
    private fun rodModeOf(c: SeaCreature) = rodModes[c.name] ?: AttackMode.AUTO

    /** The row's second line: Weapon rows (loadout, weapon, click) and own Rod rules (the rod mode); null = none. */
    private fun lineOf(c: SeaCreature): KillAction? {
        val rule = rules[c.name]
        return when (rule?.action ?: defaultFor(c)) {
            KillAction.WEAPON -> KillAction.WEAPON
            // a Rod default stays one line (Learned): picking Rod gives the row its own rule and the mode line
            KillAction.ROD -> if (rule != null) KillAction.ROD else null
            else -> null
        }
    }

    /** [r] as it is saved: only a Weapon rule keeps its loadout, weapon and click, a Rod rule gets its mode. */
    private fun asSaved(name: String, r: KillRule) = when (r.action) {
        KillAction.WEAPON -> r.copy()
        KillAction.ROD -> KillRule(KillAction.ROD, attack = rodModes[name] ?: AttackMode.AUTO)
        else -> KillRule(r.action)
    }

    /**
     * Creatures your recordings saw split off one of yours (a habit with a parent) that the table does not know, such
     * as the Bayou Sludgling: screen-only rows in their parent's location, so they can have rules too ([KillRules.ruleFor]
     * keys by the nametag name). They are never added to [SeaCreatures.all].
     */
    private fun findChildren(): List<Entry> {
        val out = ArrayList<Entry>()
        for ((name, habit) in habits) {
            val parent = nullable(nullable(habit)?.parent)?.trim().orEmpty()
            if (parent.isEmpty() || name.isBlank() || SeaCreatures.byName(name) != null) continue
            val parentCreature = SeaCreatures.byName(parent)
            val groupId = parentCreature?.groupId ?: nullable(habit.group)?.takeIf { it in groupLabels } ?: continue
            out += Entry(SeaCreature(name, ItemRarity.COMMON, rare = false, catchMessage = null, emptyList(), groupId), parentCreature?.name ?: parent)
        }
        return out.sortedBy { it.creature.name }
    }

    /** Finds the child rows again when the profile changed; true when it did (the list must be rebuilt). */
    private fun refreshEntries(): Boolean {
        val p = profile
        if (p === entriesProfile) return false
        entriesProfile = p
        // a row that stays keeps its state (its animations, an open picker's anchor)
        val old = childEntries.associateBy { it.creature.name }
        childEntries = findChildren().map { e -> old[e.creature.name]?.takeIf { it.parent == e.parent && it.creature.groupId == e.creature.groupId } ?: e }
        entriesByGroup = SeaCreatures.groups.associate { g ->
            val table = tableByGroup[g.id].orEmpty()
            val kids = childEntries.filter { it.creature.groupId == g.id }
            val list = ArrayList<Entry>(table.size + kids.size)
            // each child right below its parent, the ones whose parent is elsewhere at the end
            table.forEach { e ->
                list += e
                kids.filterTo(list) { it.parent == e.creature.name }
            }
            kids.filterTo(list) { k -> table.none { it.creature.name == k.parent } }
            g.id to list
        }
        entries = SeaCreatures.groups.flatMap { entriesByGroup[it.id].orEmpty() }
        return true
    }

    /** The row's creature for a recorded name: the table's, else a child row's. */
    private fun creatureNamed(name: String): SeaCreature? =
        SeaCreatures.byName(name) ?: childEntries.firstOrNull { it.creature.name == name }?.creature

    /** What Learned does with [c] level with you and every learned weapon at hand, e.g. `Rod within 3 m, Flaming Flay (hold) to 11 m`. */
    private fun learnedSummary(c: SeaCreature): String {
        val p = profile
        if (summariesFor !== p) {
            summariesFor = p
            summaries.clear()
        }
        return summaries.getOrPut(c.name) { WeaponPlanner.summary(p, c.name, c.groupId) }
    }

    private fun learnedLine(c: SeaCreature) = "Learned for ${c.name}: ${learnedSummary(c)} (level with you, every weapon you learned in the hotbar)."

    private fun rebuild(resetScroll: Boolean = true) {
        refreshEntries()
        val query = search.query.trim().lowercase()
        items.clear()
        for (g in SeaCreatures.groups) {
            if (groupId != null && g.id != groupId) continue
            val list = entriesByGroup[g.id].orEmpty().filter { query.isEmpty() || it.searchText.contains(query) }
            if (list.isEmpty()) continue
            items += Header(g.label, list.size)
            list.forEach { e ->
                val line = lineOf(e.creature)
                if (line != null) e.line = line
                e.expand.snap(if (line != null) 1f else 0f)
            }
            items += list
        }
        revealing = null
        if (resetScroll) scroll.set(0f, instant = true)
    }

    private fun selectGroup(id: String?) {
        if (groupId == id) return
        groupId = id
        lastGroupId = id
        rebuild()
        Style.playClickSound(1.15f)
    }

    private fun setAction(c: SeaCreature, action: KillAction) {
        val existing = rules[c.name]
        if (existing != null && existing.action == action) return
        // an own Weapon or Rod rule has a second line
        if ((action == KillAction.WEAPON || action == KillAction.ROD) && lineOf(c) == null) revealWhileOpening(c)
        // only the action changes: the weapon picks (and rod mode) stay on the working copy for a switch back (save() drops them)
        val rule = existing ?: KillRule(action).also { rules[c.name] = it }
        rule.action = action
        // a new Weapon rule starts from what the recordings show for this creature
        if (action == KillAction.WEAPON && c.name !in wasWeapon && rule.loadout == 0 && rule.weaponId.isEmpty()) {
            val s = habits[c.name]?.suggestion()
            if (s != null && s.action == KillAction.WEAPON) {
                rule.loadout = s.loadout
                rule.weaponId = s.weaponId
                rule.weaponName = hotbarName(s.weaponId) ?: s.weaponName
                rule.attack = s.attack
            }
        }
        if (action == KillAction.WEAPON) wasWeapon += c.name
        Style.playClickSound(if (action == KillAction.IGNORE) 0.95f else 1.1f)
    }

    /** Keeps [c]'s row in view while its second line opens below it (the list grows under a row near the bottom). */
    private fun revealWhileOpening(c: SeaCreature) {
        revealing = entries.firstOrNull { it.creature === c }
    }

    private fun setAttack(c: SeaCreature, mode: AttackMode) {
        val rule = ensureRule(c)
        if (rule.attack == mode) return
        rule.attack = mode
        Style.playClickSound(1.1f)
    }

    /** Only on an own Rod rule (the rod line is not shown without one). */
    private fun setRodMode(c: SeaCreature, mode: AttackMode) {
        if (rodModeOf(c) == mode) return
        rodModes[c.name] = mode
        Style.playClickSound(1.1f)
    }

    private fun resetRule(c: SeaCreature) {
        rules.remove(c.name)
        wasWeapon -= c.name
        rodModes -= c.name
        Style.playClickSound(0.9f)
    }

    private fun useSuggestion(c: SeaCreature, s: KillRule) {
        if ((s.action == KillAction.WEAPON || s.action == KillAction.ROD) && lineOf(c) == null) revealWhileOpening(c)
        adopt(c, s)
        Style.playClickSound(1.2f)
    }

    /**
     * Gives [c] the suggested rule [s]. An Ignore / Rod / Stop suggestion is only an action (like picking it) plus a
     * Rod rule's mode: the row's weapon picks stay.
     */
    private fun adopt(c: SeaCreature, s: KillRule) {
        if (s.action == KillAction.WEAPON) {
            rules[c.name] = withHotbarName(s)
            wasWeapon += c.name
            return
        }
        rules.getOrPut(c.name) { KillRule(s.action) }.action = s.action
        if (s.action == KillAction.ROD) rodModes[c.name] = s.attack
    }

    private fun withHotbarName(s: KillRule): KillRule {
        val r = s.copy()
        if (r.weaponId.isNotEmpty()) hotbarName(r.weaponId)?.let { r.weaponName = it }
        return r
    }

    /** True when following [s] would change what happens with a creature whose rule (as saved) is [current]. */
    private fun differs(current: KillRule, s: KillRule): Boolean {
        // Learned already follows the recordings, fight by fight (the row's Pin fixes one rule instead)
        if (current.action == KillAction.LEARNED) return false
        if (current.action != s.action) return true
        // Learned already does what the recordings show (and a Learned suggestion picks nothing)
        val attackDiffers = current.attack != AttackMode.AUTO && s.attack != AttackMode.AUTO && current.attack != s.attack
        return when (s.action) {
            KillAction.WEAPON -> current.loadout != s.loadout || current.weaponId != s.weaponId || attackDiffers
            KillAction.ROD -> attackDiffers
            else -> false
        }
    }

    /** Recorded creatures without an own rule whose suggestion differs from their default. */
    private fun learnedDiffering(): List<Pair<SeaCreature, KillRule>> {
        val out = ArrayList<Pair<SeaCreature, KillRule>>()
        for ((recordedName, habit) in habits) {
            val c = creatureNamed(recordedName) ?: continue
            if (rules.containsKey(c.name)) continue
            val s = habit.suggestion() ?: continue
            if (differs(KillRule(defaultFor(c)), s)) out += c to s
        }
        return out
    }

    /** Recorded creatures with a suggestion that follow their recordings on Learned (no own rule, a Learned default). */
    private fun onLearnedDefault() = habits.count { (name, habit) ->
        val c = creatureNamed(name)
        c != null && ! rules.containsKey(c.name) && defaultFor(c) == KillAction.LEARNED && habit.suggestion() != null
    }

    /**
     * What Apply learned changes: [learnedDiffering] without the creatures on a Stop default. Stop means you take that
     * fight yourself, and a rare catch can look unfought in the recordings because someone else killed it; the row's
     * Use button still changes such a creature on purpose.
     */
    private fun learnedToApply(differing: List<Pair<SeaCreature, KillRule>> = learnedDiffering()) =
        differing.filter { defaultFor(it.first) != KillAction.STOP }

    private fun applyLearned() {
        val list = learnedToApply()
        if (list.isEmpty()) {
            Style.playClickSound(0.6f)
            return
        }
        list.forEach { (c, s) -> adopt(c, s) }
        flash = if (list.size == 1) "Applied 1 learned rule" else "Applied ${list.size} learned rules"
        flashUntil = System.currentTimeMillis() + 3000L
        Style.playClickSound(1.2f)
    }

    private fun hotbar(fresh: Boolean = false): List<HotbarItem> {
        val now = System.currentTimeMillis()
        if (fresh || now - hotbarAt > 1000L) {
            hotbarAt = now
            hotbarCache = readHotbar()
        }
        return hotbarCache
    }

    private fun readHotbar(): List<HotbarItem> {
        val inventory = mc.player?.inventory ?: return emptyList()
        val seen = HashSet<String>()
        return (0 .. 8).mapNotNull { slot ->
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) return@mapNotNull null
            val id = runCatching { stack.skyblockId }.getOrDefault("")
            if (id.isEmpty() || !seen.add(id)) return@mapNotNull null
            HotbarItem(slot, stack, id, stack.hoverName.unformattedText.replace(STARS, "").trim().ifEmpty { BehaviorProfile.prettyId(id) })
        }
    }

    private fun hotbarName(id: String) = if (id.isEmpty()) null else hotbar(fresh = true).firstOrNull { it.id == id }?.name

    private fun loadoutName(n: Int): String? {
        val info = LoadoutSwitcher.known.get()[n] ?: return null
        return nullable(info.name)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** "3 · Mage" (or "3" without a cached name); [zero] for 0. With [prefixed] the box already says "Loadout". */
    private fun loadoutText(n: Int, zero: String, prefixed: Boolean): String {
        if (n == 0) return zero
        val name = loadoutName(n)
        return when {
            name != null -> "$n · $name"
            prefixed -> "$n"
            else -> "Loadout $n"
        }
    }

    private fun weaponText(r: KillRule) = if (r.weaponId.isEmpty()) "item in hand" else r.weaponName.ifEmpty { BehaviorProfile.prettyId(r.weaponId) }

    private fun describe(r: KillRule): String = when (r.action) {
        KillAction.WEAPON -> buildList {
            add("Weapon")
            add(weaponText(r))
            add(if (r.loadout == 0) "current loadout" else loadoutName(r.loadout)?.let { "loadout ${r.loadout} ($it)" } ?: "loadout ${r.loadout}")
            if (r.attack != AttackMode.AUTO) add(r.attack.label.lowercase())
        }.joinToString(" · ")
        KillAction.ROD -> if (r.attack == AttackMode.AUTO) r.action.label else "${r.action.label} · ${r.attack.rodLabel.lowercase()}"
        else -> r.action.label
    }

    /** The Rod line's note: what [mode] does, for Learned what your recordings decide. */
    private fun rodNote(c: SeaCreature, mode: AttackMode): String = when (mode) {
        AttackMode.LEFT -> "Rod hits only, no recast first"
        AttackMode.RIGHT -> "Recast first, then rod hits if it survives"
        AttackMode.AUTO -> {
            val h = habits[c.name]
            val bobberRule = h?.suggestion()?.let { it.action == KillAction.ROD && it.attack == AttackMode.RIGHT } == true
            when {
                h != null && bobberRule -> "Recast first: the bobber killed ${h.bobberKills} of ${h.killed} in your recordings"
                profile.recastThroughCatches -> "Recast first: you recast right through your catches"
                h != null && h.killed > 0 -> "Melee only: the bobber killed ${if (h.bobberKills == 0) "none" else h.bobberKills} of ${h.killed} in your recordings"
                else -> "Melee only until your recordings show the bobber killing it"
            }
        }
    }

    /** The info icon's text; [learned]: the row is on Learned, so its button pins [s] instead of using it. */
    private fun learnedText(c: SeaCreature, h: BehaviorProfile.Habit, s: KillRule?, canUse: Boolean, learned: Boolean): String {
        val parts = ArrayList<String>()
        parts += "seen ${h.seen}"
        val by = buildList {
            if (h.bobberKills > 0) add("${h.bobberKills} by the bobber")
            if (h.sweepKills > 0) add("${h.sweepKills} by sweep hits")
        }
        parts += if (by.isEmpty()) "killed ${h.killed}" else "killed ${h.killed} (${by.joinToString(", ")})"
        val clicks = h.left + h.right
        if (clicks > 0 && h.rodClicks * 10 >= clicks * 6) parts += "rod"
        else h.topWeapon?.let { parts += BehaviorProfile.prettyId(it) }
        if (clicks > 0) parts += if (h.weaponPrefersRight) "right click" else "left click"
        if (h.topLoadout != 0) parts += "loadout ${h.topLoadout}"
        if (h.killMs.isNotEmpty()) parts += "%.1fs per kill".format(h.killMs.average() / 1000.0)
        val options = nullable(h.killsBy).orEmpty().filterKeys { it != WeaponPlanner.BOBBER }.entries.sortedByDescending { it.value }.take(4)
        val killedWith = if (options.isEmpty()) "" else "\nKilled with: " + options.joinToString(", ") { (o, n) -> "${if (o == WeaponPlanner.ROD) "the rod" else BehaviorProfile.prettyId(o)} $n" }
        val tail = when {
            learned && s != null -> "${learnedLine(c)}\nClick Pin to give it its suggestion as its own rule instead: ${describe(s)}."
            learned -> learnedLine(c)
            s == null -> "Not enough recorded fights for a suggestion yet."
            canUse -> "Suggests: ${describe(s)}. Click Use to apply it."
            else -> "Suggests: ${describe(s)}, which is what happens now."
        }
        return "Recorded: ${parts.joinToString(" · ")}$killedWith\n$tail"
    }

    private fun defaultTip(c: SeaCreature): String {
        val d = defaultFor(c)
        val rod = if (d == KillAction.ROD) " (an own Rod rule also picks Learned, Melee only or Recast first)" else ""
        val learned = if (d == KillAction.LEARNED) "\n${learnedLine(c)}" else ""
        return "No own rule: uses the ${if (c.rare) "Rare catches" else "Other creatures"} default from the footer (${d.label}). Pick an action to give it its own rule$rod.$learned"
    }

    private fun needsFishingWarning() = fishingLoadout == 0 && rules.values.any { it.action == KillAction.WEAPON && it.loadout != 0 }

    /** The loadout your recordings show you fishing in, offered while the Fishing loadout is Stay; 0 = none. */
    private fun learnedFishingLoadout() = profile.topFishingLoadout.takeIf { fishingLoadout == 0 && it in 1 .. KillRules.MAX_LOADOUT } ?: 0

    private fun rarityColor(r: ItemRarity): Color = ItemRarity.getHypixelColor(r)

    private fun actionBase(a: KillAction): Color = when (a) {
        KillAction.IGNORE -> IGNORE_COLOR
        KillAction.LEARNED -> Palette.success
        KillAction.ROD -> Palette.accent
        KillAction.WEAPON -> Palette.warning
        KillAction.STOP -> STOP_COLOR
    }

    private fun actionText(a: KillAction): Color = when (a) {
        KillAction.IGNORE -> Color(206, 198, 224)
        KillAction.LEARNED -> Color(178, 234, 198)
        KillAction.ROD -> Palette.accentText
        KillAction.WEAPON -> Color(246, 214, 156)
        KillAction.STOP -> Color(246, 156, 178)
    }

    // ------------------------------------------------------------------ drawing

    private fun hit(rect: Rect, clip: Rect?, action: () -> Unit) {
        hits += Hit(rect, clip, action)
    }

    /** True while the open popover covers ([mx], [my]); a picker is measured as it was last drawn. */
    private fun underPopover(mx: Float, my: Float) = when (val p = Popovers.current) {
        null -> false
        is Picker -> p.covers(mx, my)
        else -> p.contains(mx, my)
    }

    private fun layoutRects() {
        val b = body
        footerRect = Rect(b.x, b.bottom - FOOTER_H, b.w, FOOTER_H)
        sideRect = Rect(b.x, b.y, SIDE_W, b.h - FOOTER_H)
        sideView = Rect(sideRect.x, sideRect.y + 26f, sideRect.w, sideRect.h - 26f - 6f)
        listRect = Rect(b.x + SIDE_W + 12f, b.y + 8f, b.w - SIDE_W - 26f, b.h - FOOTER_H - 12f)
    }

    override fun drawBody(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        frame++
        hits.clear()
        if (refreshEntries()) rebuild(resetScroll = false)
        layoutRects()
        if (Popovers.current != null && Popovers.current !is Picker) Popovers.close()
        // a row picker's live size follows its anchor, which reads as offscreen until the row is drawn again
        val blocked = underPopover(mx, my)
        val cmx = if (blocked) -10000f else mx
        val cmy = if (blocked) -10000f else my
        actionWidths = FloatArray(ACTION_LABELS.size) { UI.width(ACTION_LABELS[it], SEG_SIZE, UIFont.Weight.SEMIBOLD) + 14f }
        attackWidths = FloatArray(ATTACK_LABELS.size) { UI.width(ATTACK_LABELS[it], ATTACK_SIZE, UIFont.Weight.SEMIBOLD) + 12f }
        rodWidths = FloatArray(ROD_LABELS.size) { UI.width(ROD_LABELS[it], ATTACK_SIZE, UIFont.Weight.SEMIBOLD) + 12f }

        drawSidebar(ctx, cmx, cmy, my)
        drawList(ctx, cmx, cmy, my)
        drawFooter(ctx, cmx, cmy)

        (Popovers.current as? Picker)?.let { Popovers.render(ctx, mx, my, it.visibleArea(), it.bounds()) }
    }

    private fun drawSidebar(ctx: GuiGraphicsExtractor, mx: Float, my: Float, rawMy: Float) {
        val r = sideRect
        UI.rect(ctx, r.x, r.y, r.w, r.h, 0f, Palette.surfaceRaised)
        UI.rect(ctx, r.right - UI.hair(ctx), r.y, UI.hair(ctx), r.h, 0f, Palette.divider)
        UI.textMid(ctx, "LOCATIONS", r.x + 15f, r.y + 15f, Palette.textMuted, 5.8f, UIFont.Weight.SEMIBOLD)

        val own = HashMap<String, Int>()
        entries.forEach { if (rules.containsKey(it.creature.name)) own.merge(it.creature.groupId, 1, Int::plus) }

        val v = sideView
        val step = SIDE_ROW_H + SIDE_ROW_GAP
        sideScroll.update(r.right - 2f, v.y, v.h, sideIds.size * step - SIDE_ROW_GAP, rawMy)
        val inView = v.contains(mx, my)
        val smx = if (inView) mx else -10000f
        val smy = if (inView) my else -10000f
        val rowX = r.x + 8f
        val rowW = r.w - 18f

        val selTarget = sideIds.indexOf(groupId).coerceAtLeast(0) * step
        if (sideSelY.isNaN()) {
            // first frame: the location remembered from last time may be further down
            sideScroll.reveal(selTarget, SIDE_ROW_H, 24f)
            sideScroll.set(sideScroll.target, instant = true)
        }
        sideSelY = if (sideSelY.isNaN()) selTarget else UI.approach(sideSelY, selTarget, UI.dt, 22f)
        val top = v.y - sideScroll.offset

        ctx.enableScissor(r.x.toInt(), v.y.toInt(), r.right.toInt(), v.bottom.toInt())
        val sy = top + sideSelY
        UI.rect(ctx, rowX, sy, rowW, SIDE_ROW_H, 6f, UI.fade(Palette.accentPill, sideSel.update(1f)))
        UI.rect(ctx, rowX, sy + SIDE_ROW_H / 2f - 5f, 3f, 10f, 1.5f, Palette.accent)

        sideIds.forEachIndexed { i, id ->
            val ry = top + i * step
            if (ry + SIDE_ROW_H < v.y || ry > v.bottom) return@forEachIndexed
            val row = Rect(rowX, ry, rowW, SIDE_ROW_H)
            val hot = sideHover.getOrPut(id ?: "") { UI.Smooth() }.update(row.contains(smx, smy))
            if (hot > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
            val selected = id == groupId
            if (!selected && hot > 0.01f) UI.rect(ctx, row.x, row.y, row.w, row.h, 6f, UI.fade(Palette.chip, hot))

            var textRight = row.right - 6f
            val count = if (id == null) own.values.sum() else own[id] ?: 0
            if (count > 0) {
                val text = count.toString()
                val cw = UI.chipWidth(text, 6f, padX = 4f)
                UI.chip(ctx, text, row.right - 5f - cw, row.y + (row.h - 11f) / 2f, 11f, if (selected) UI.fade(Palette.accent, 0.3f) else Palette.chip, if (selected) Palette.accentText else Palette.chipText, 6f, padX = 4f)
                textRight = row.right - 5f - cw - 4f
            }
            val label = if (id == null) "All locations" else groupLabels[id] ?: id
            val weight = if (selected) UIFont.Weight.SEMIBOLD else UIFont.Weight.MEDIUM
            val color = if (selected) Palette.textPrimary else UI.mix(Palette.textSecondary, Palette.textPrimary, hot)
            UI.textMid(ctx, UIFont.trim(label, textRight - row.x - 10f, 7.4f, weight), row.x + 10f, row.y + row.h / 2f, color, 7.4f, weight)
            if (count > 0 && hot > 0.5f) TooltipManager.hover(if (count == 1) "1 creature with its own rule" else "$count creatures with their own rule", mx, my)
            hit(row, v) { selectGroup(id) }
        }
        ctx.disableScissor()
        if (sideScroll.offset > 1f) UI.vGradient(ctx, r.x, v.y, r.w - UI.hair(ctx), 8f, Palette.surfaceRaised, UI.fade(Palette.surfaceRaised, 0f))
        if (sideScroll.offset < sideScroll.max - 1f) UI.vGradient(ctx, r.x, v.bottom - 8f, r.w - UI.hair(ctx), 8f, UI.fade(Palette.surfaceRaised, 0f), Palette.surfaceRaised)
        sideScroll.draw(ctx, mx, my)
    }

    private fun drawList(ctx: GuiGraphicsExtractor, mx: Float, my: Float, rawMy: Float) {
        val lr = listRect
        if (tops.size < items.size) tops = FloatArray(items.size + 16)
        var total = 0f
        for (i in items.indices) {
            tops[i] = total
            total += when (val item = items[i]) {
                is Header -> HEADER_ROW_H
                is Entry -> ROW_H + EXT_H * item.expand.update(if (lineOf(item.creature) != null) 1f else 0f) + ROW_GAP
            }
        }
        scroll.update(lr.right + 10f, lr.y, lr.h, total, rawMy)
        // followed every frame: the scroll limit only grows with the row, so a single reveal could not reach it
        revealing?.let { e ->
            val i = items.indexOf(e)
            if (i < 0 || lineOf(e.creature) == null) revealing = null
            else {
                scroll.reveal(tops[i], ROW_H + EXT_H, 8f)
                if (e.expand.value >= 1f) revealing = null
            }
        }

        if (items.isEmpty()) {
            val query = search.query.trim()
            if (query.isEmpty()) drawEmptyState(ctx, lr, Icon.SEARCH, "No sea creatures here", null)
            else drawEmptyState(
                ctx, lr, Icon.SEARCH, "No sea creatures match \"${UIFont.trim(query, 120f, 8f, UIFont.Weight.MEDIUM)}\"",
                if (groupId != null) "Try another name, or look in All locations." else "Try another name."
            )
            return
        }

        val inList = lr.contains(mx, my)
        val rmx = if (inList) mx else -10000f
        val rmy = if (inList) my else -10000f
        val learnCols = habits.isNotEmpty()

        ctx.enableScissor(lr.x.toInt() - 2, lr.y.toInt(), lr.right.toInt() + 2, lr.bottom.toInt())
        val top = lr.y - scroll.offset
        for (i in items.indices) {
            val y = top + tops[i]
            if (y > lr.bottom + 4f) break
            when (val item = items[i]) {
                is Header -> if (y + HEADER_ROW_H >= lr.y - 4f) drawHeaderRow(ctx, item, lr.x, y)
                is Entry -> {
                    val h = ROW_H + EXT_H * item.expand.value
                    if (y + h >= lr.y - 4f) drawEntry(ctx, item, Rect(lr.x, y, lr.w, h), rmx, rmy, learnCols)
                }
            }
        }
        ctx.disableScissor()
        if (scroll.offset > 1f) UI.vGradient(ctx, lr.x - 2f, lr.y, lr.w + 4f, 8f, Palette.surface, UI.fade(Palette.surface, 0f))
        if (scroll.offset < scroll.max - 1f) UI.vGradient(ctx, lr.x - 2f, lr.bottom - 8f, lr.w + 4f, 8f, UI.fade(Palette.surface, 0f), Palette.surface)
        scroll.draw(ctx, mx, my)
    }

    private fun drawHeaderRow(ctx: GuiGraphicsExtractor, h: Header, x: Float, y: Float) {
        val label = h.label.uppercase()
        val cy = y + HEADER_ROW_H / 2f + 1f
        UI.textMid(ctx, label, x + 2f, cy, Palette.textMuted, 6f, UIFont.Weight.SEMIBOLD)
        val lw = UI.width(label, 6f, UIFont.Weight.SEMIBOLD)
        UI.textMid(ctx, h.count.toString(), x + lw + 8f, cy, UI.fade(Palette.textMuted, 0.7f), 6f, UIFont.Weight.MEDIUM)
    }

    private fun segRects(area: Rect, widths: FloatArray): Array<Rect> {
        var x = area.x + 2f
        return Array(widths.size) { i -> Rect(x, area.y, widths[i], area.h).also { x += widths[i] } }
    }

    /**
     * Segmented control with an animated selection pill tinted [base] (strong = an own choice, weak = a default);
     * returns the hovered segment or -1.
     */
    private fun drawSegmented(
        ctx: GuiGraphicsExtractor, area: Rect, rects: Array<Rect>, labels: List<String>, selected: Int,
        base: Color, text: Color, strong: Boolean, pill: SegState, mx: Float, my: Float, size: Float
    ): Int {
        UI.rect(ctx, area.x, area.y, area.w, area.h, 5f, Palette.input)
        UI.outline(ctx, area.x, area.y, area.w, area.h, 5f, Palette.outline)
        val hovered = rects.indexOfFirst { it.contains(mx, my) }
        if (hovered >= 0 && hovered != selected) {
            val h = rects[hovered]
            UI.rect(ctx, h.x, h.y + 2f, h.w, h.h - 4f, 4f, Palette.chip)
        }
        if (selected in rects.indices) {
            val t = rects[selected]
            pill.x = if (pill.x.isNaN()) t.x else UI.approach(pill.x, t.x, UI.dt, 22f)
            pill.w = if (pill.w.isNaN()) t.w else UI.approach(pill.w, t.w, UI.dt, 22f)
            UI.rect(ctx, pill.x, t.y + 2f, pill.w, t.h - 4f, 4f, UI.fade(base, if (strong) 0.30f else 0.12f))
            if (strong) UI.outline(ctx, pill.x, t.y + 2f, pill.w, t.h - 4f, 4f, UI.fade(base, 0.6f))
        }
        labels.forEachIndexed { i, label ->
            val r = rects[i]
            val isSelected = i == selected
            val fg = when {
                isSelected && strong -> text
                isSelected -> UI.mix(Palette.textMuted, text, if (i == hovered) 0.9f else 0.55f)
                i == hovered -> Palette.textPrimary
                else -> Palette.textMuted
            }
            UI.textMidCentered(ctx, label, r.x + r.w / 2f, r.y + r.h / 2f, fg, size, if (isSelected) UIFont.Weight.SEMIBOLD else UIFont.Weight.MEDIUM)
        }
        return hovered
    }

    /** Select box (optional muted [prefix], item icon, value, chevron); returns true while hovered. */
    private fun drawSelect(
        ctx: GuiGraphicsExtractor, box: Rect, prefix: String?, value: String, valueColor: Color, open: Boolean,
        mx: Float, my: Float, stack: ItemStack? = null
    ): Boolean {
        val over = box.contains(mx, my)
        val hot = open || over
        if (over) ctx.requestCursor(CursorTypes.POINTING_HAND)
        UI.rect(ctx, box.x, box.y, box.w, box.h, 5f, if (hot) Palette.inputHover else Palette.input)
        UI.outline(ctx, box.x, box.y, box.w, box.h, 5f, if (open) Palette.accentBorder else if (over) Palette.outlineHover else Palette.outline)
        val cy = box.y + box.h / 2f
        var tx = box.x + 7f
        if (prefix != null) {
            UI.textMid(ctx, prefix, tx, cy, Palette.textMuted, PREFIX_SIZE, UIFont.Weight.MEDIUM)
            tx += UI.width(prefix, PREFIX_SIZE, UIFont.Weight.MEDIUM) + 5f
        }
        if (stack != null) {
            drawStack(ctx, stack, tx, box.y + (box.h - 11f) / 2f, 11f)
            tx += 14f
        }
        UI.textMid(ctx, UIFont.trim(value, box.right - 17f - tx, BOX_SIZE), tx, cy, UI.mix(valueColor, Palette.textPrimary, if (hot) 0.6f else 0f), BOX_SIZE)
        UI.icon(ctx, if (open) Icon.CHEVRON_UP else Icon.CHEVRON_DOWN, box.right - 14f, box.y + (box.h - 8f) / 2f, 8f, Palette.textSecondary)
        return over
    }

    private fun drawEntry(ctx: GuiGraphicsExtractor, e: Entry, r: Rect, mx: Float, my: Float, learnCols: Boolean) {
        val c = e.creature
        val rule = rules[c.name]
        val explicit = rule != null
        val action = rule?.action ?: defaultFor(c)
        val line = lineOf(c)
        if (e.drawnFrame != frame - 1) {
            e.actionPill.reset()
            e.attackPill.reset()
            e.rodPill.reset()
        }
        if (line != null && line != e.line) {
            e.line = line
            e.attackPill.reset()
            e.rodPill.reset()
        }
        e.drawnFrame = frame
        val clip = listRect

        val hot = e.hover.update(r.contains(mx, my))
        UI.rect(ctx, r.x, r.y, r.w, r.h, 7f, UI.mix(Palette.card, Palette.cardHover, hot))
        UI.outline(ctx, r.x, r.y, r.w, r.h, 7f, if (explicit) UI.mix(UI.fade(Palette.accentBorder, 0.5f), Palette.accentBorder, hot) else UI.mix(Palette.divider, Palette.outline, hot))
        // a child row has no rarity of its own (its creature is not in the table)
        val rarity = if (e.parent.isNotEmpty()) Palette.textMuted else rarityColor(c.rarity)
        UI.rect(ctx, r.x + 6f, r.y + 8f, 3f, r.h - 16f, 1.5f, rarity)

        // right side of the first line: reset, actions, "default"
        val cy = r.y + ROW_H / 2f
        val reset = Rect(r.right - 6f - 18f, cy - 9f, 18f, 18f)
        val segW = actionWidths.sum() + 4f
        val seg = Rect(reset.x - 4f - segW, cy - SEG_H / 2f, segW, SEG_H)
        val hintRight = seg.x - 6f
        val hintW = UI.width("default", HINT_SIZE, UIFont.Weight.MEDIUM)
        var nameRight = hintRight - hintW - 10f

        // learned from recordings: info icon + "Use" ("Pin" on Learned)
        if (learnCols) {
            val useW = maxOf(UI.chipWidth("Use", 6.2f, UIFont.Weight.SEMIBOLD, 6f), UI.chipWidth("Pin", 6.2f, UIFont.Weight.SEMIBOLD, 6f))
            val slot = Rect(nameRight - useW, cy - 6.5f, useW, 13f)
            val info = Rect(slot.x - 4f - 12f, cy - 6f, 12f, 12f)
            nameRight = info.x - 8f
            val habit = habits[c.name]
            if (habit != null) {
                val suggestion = habit.suggestion()
                // Learned never differs from the recordings, so its button pins the suggestion as an own rule
                val learned = action == KillAction.LEARNED
                val usable = suggestion?.takeIf { learned || differs(asSaved(c.name, rule ?: KillRule(action)), it) }
                val infoHot = info.contains(mx, my)
                UI.icon(ctx, Icon.INFO, info.x, info.y, info.w, if ((usable != null && ! learned) || infoHot) Palette.accentText else Palette.textMuted)
                if (infoHot) TooltipManager.hover(learnedText(c, habit, suggestion, usable != null, learned), mx, my)
                if (usable != null) {
                    val label = if (learned) "Pin" else "Use"
                    val use = Rect(slot.x, slot.y, UI.chipWidth(label, 6.2f, UIFont.Weight.SEMIBOLD, 6f), slot.h)
                    val uh = e.useHover.update(use.contains(mx, my))
                    if (uh > 0.5f) {
                        ctx.requestCursor(CursorTypes.POINTING_HAND)
                        TooltipManager.hover(
                            if (learned) "Pin what your recordings suggest as its own rule: ${describe(usable)}.\n${learnedLine(c)}"
                            else "Use what your recordings suggest: ${describe(usable)}",
                            mx, my
                        )
                    }
                    // a pin is offered, not urged: Learned already follows the recordings
                    val rest = if (learned) UI.fade(Palette.accent, 0.12f) else UI.fade(Palette.accent, 0.24f)
                    UI.rect(ctx, use.x, use.y, use.w, use.h, use.h / 2f, UI.mix(rest, Palette.accent, uh))
                    UI.textMidCentered(ctx, label, use.x + use.w / 2f, use.y + use.h / 2f, UI.mix(Palette.accentText, WHITE, uh), 6.2f, UIFont.Weight.SEMIBOLD)
                    hit(use, clip) { useSuggestion(c, usable) }
                }
                else e.useHover.snap(0f)
            }
        }

        // name, rarity chip (the parent on a child row), rare catch badge (the first chip goes first when space runs out)
        val child = e.parent.isNotEmpty()
        val nameX = r.x + 16f
        val avail = nameRight - nameX
        val rarityLabel = if (child) "FROM ${e.parent.uppercase()}" else c.rarity.name
        val rarityW = UI.chipWidth(rarityLabel, CHIP_SIZE, UIFont.Weight.SEMIBOLD, 4f)
        val rareW = if (c.rare) UI.chipWidth("RARE CATCH", CHIP_SIZE, UIFont.Weight.SEMIBOLD, 4f) else 0f
        val nameW = UI.width(c.name, NAME_SIZE, UIFont.Weight.MEDIUM)
        var showRarity = true
        var chipsW = 6f + rarityW + if (c.rare) 4f + rareW else 0f
        if (avail - chipsW < minOf(nameW, 48f)) {
            showRarity = false
            chipsW = if (c.rare) 6f + rareW else 0f
        }
        val name = UIFont.trim(c.name, avail - chipsW, NAME_SIZE, UIFont.Weight.MEDIUM)
        UI.textMid(ctx, name, nameX, cy, if (action == KillAction.IGNORE) Palette.textSecondary else Palette.textPrimary, NAME_SIZE, UIFont.Weight.MEDIUM)
        var chipX = nameX + UI.width(name, NAME_SIZE, UIFont.Weight.MEDIUM) + 6f
        if (child) {
            if (showRarity) {
                UI.chip(ctx, rarityLabel, chipX, cy - 5.5f, 11f, Palette.chip, Palette.chipText, CHIP_SIZE, UIFont.Weight.SEMIBOLD, 4f)
                chipX += rarityW + 4f
            }
            // on the name too: the chip saying where it comes from is the first thing dropped in a narrow window
            if (UI.inside(mx, my, nameX, cy - 6f, chipX - nameX, 12f)) TooltipManager.hover(
                "Splits off your ${e.parent} when it dies, so it is yours too and Auto Mob Killer fights it. Your recordings added this row: the sea creature list does not have it.",
                mx, my
            )
        }
        else if (showRarity) {
            UI.chip(ctx, rarityLabel, chipX, cy - 5.5f, 11f, UI.fade(rarity, 0.16f), UI.mix(rarity, Color.WHITE, 0.15f), CHIP_SIZE, UIFont.Weight.SEMIBOLD, 4f)
            chipX += rarityW + 4f
        }
        if (c.rare) {
            val badge = Rect(chipX, cy - 5.5f, rareW, 11f)
            UI.rect(ctx, badge.x, badge.y, badge.w, badge.h, 5.5f, UI.fade(Palette.accent, 0.12f))
            UI.outline(ctx, badge.x, badge.y, badge.w, badge.h, 5.5f, UI.fade(Palette.accentBorder, 0.8f))
            UI.textMid(ctx, "RARE CATCH", badge.x + 4f, cy, Palette.accentText, CHIP_SIZE, UIFont.Weight.SEMIBOLD)
            if (badge.contains(mx, my)) TooltipManager.hover("Rare catch: without its own rule it uses the Rare catches default (${defaultRareAction.label}).", mx, my)
        }

        if (!explicit) {
            UI.textMidRight(ctx, "default", hintRight, cy, UI.fade(Palette.textMuted, 0.85f), HINT_SIZE, UIFont.Weight.MEDIUM)
            if (UI.inside(mx, my, hintRight - hintW, cy - 6f, hintW, 12f)) TooltipManager.hover(defaultTip(c), mx, my)
        }

        val segments = segRects(seg, actionWidths)
        val hovered = drawSegmented(ctx, seg, segments, ACTION_LABELS, action.ordinal, actionBase(action), actionText(action), explicit, e.actionPill, mx, my, SEG_SIZE)
        if (hovered >= 0) {
            ctx.requestCursor(CursorTypes.POINTING_HAND)
            val a = KillAction.entries[hovered]
            TooltipManager.hover(when {
                ! explicit && a == action -> "${a.description}\n${defaultTip(c)}"
                a == KillAction.LEARNED -> "${a.description}\n${learnedLine(c)}"
                else -> a.description
            }, mx, my)
        }
        KillAction.entries.forEachIndexed { i, a -> hit(segments[i], clip) { setAction(c, a) } }

        if (rule != null) {
            val rh = e.resetHover.update(reset.contains(mx, my))
            if (rh > 0.5f) {
                ctx.requestCursor(CursorTypes.POINTING_HAND)
                TooltipManager.hover("Remove the own rule (back to the default: ${defaultFor(c).label})", mx, my)
            }
            UI.iconButton(ctx, Icon.RESET, reset.x, reset.y, reset.w, rh, Palette.textMuted, Palette.accentText, 0.62f)
            hit(reset, clip) { resetRule(c) }
        }
        else e.resetHover.snap(0f)

        val ext = e.expand.value
        if (ext > 0.01f) drawLine(ctx, e, rule, r, seg, ext, mx, my, line != null)
    }

    /** The row's second line ([Entry.line]), faded and clipped while the row opens or closes; [active] while it is shown. */
    private fun drawLine(ctx: GuiGraphicsExtractor, e: Entry, rule: KillRule?, r: Rect, seg: Rect, ext: Float, mx: Float, my: Float, active: Boolean) {
        val saved = UI.alpha
        UI.alpha = saved * ext * ext
        val clipRow = ext < 0.999f
        if (clipRow) ctx.enableScissor(r.x.toInt(), r.y.toInt(), r.right.toInt(), (r.bottom + 1f).toInt())
        val live = active && ext > 0.6f
        if (e.line == KillAction.ROD) drawRodLine(ctx, e, r, seg, live, mx, my)
        else drawWeaponLine(ctx, e, rule, r, seg, live, mx, my)
        if (clipRow) ctx.disableScissor()
        UI.alpha = saved
    }

    /** Second line of an own Rod rule: whether the recast after the catch goes first, and what that means here. */
    private fun drawRodLine(ctx: GuiGraphicsExtractor, e: Entry, r: Rect, seg: Rect, live: Boolean, mx: Float, my: Float) {
        val c = e.creature
        val mode = rodModeOf(c)
        val y = r.y + ROW_H
        val modesW = rodWidths.sum() + 4f
        val modes = Rect(seg.right - modesW, y, modesW, SEG_H)
        val imx = if (live) mx else -10000f
        val imy = if (live) my else -10000f

        val noteX = r.x + 16f
        UI.textMid(ctx, UIFont.trim(rodNote(c, mode), modes.x - 12f - noteX, PREFIX_SIZE, UIFont.Weight.MEDIUM), noteX, y + SEG_H / 2f, Palette.textMuted, PREFIX_SIZE, UIFont.Weight.MEDIUM)

        val rects = segRects(modes, rodWidths)
        val hovered = drawSegmented(ctx, modes, rects, ROD_LABELS, mode.ordinal, Palette.accent, Palette.accentText, true, e.rodPill, imx, imy, ATTACK_SIZE)
        if (hovered >= 0) {
            ctx.requestCursor(CursorTypes.POINTING_HAND)
            val m = AttackMode.entries[hovered]
            TooltipManager.hover("${m.rodLabel}: ${m.rodDescription}", mx, my)
        }
        if (live) AttackMode.entries.forEachIndexed { i, m -> hit(rects[i], listRect) { setRodMode(c, m) } }
    }

    /** Second line of a Weapon row: loadout, weapon and click pickers. */
    private fun drawWeaponLine(ctx: GuiGraphicsExtractor, e: Entry, rule: KillRule?, r: Rect, seg: Rect, live: Boolean, mx: Float, my: Float) {
        val c = e.creature
        val shown = rule ?: KillRule(KillAction.WEAPON)
        val y = r.y + ROW_H
        val attackW = attackWidths.sum() + 4f
        val attack = Rect(seg.right - attackW, y, attackW, BOX_H)
        val loadout = Rect(r.x + 16f, y, LOADOUT_W, BOX_H)
        val weapon = Rect(loadout.right + 6f, y, attack.x - 6f - loadout.right - 6f, BOX_H)
        e.loadoutBox = loadout
        e.weaponBox = weapon
        val imx = if (live) mx else -10000f
        val imy = if (live) my else -10000f
        val clip = listRect

        val loadoutKey = "loadout:${c.name}"
        if (drawSelect(ctx, loadout, "Loadout", loadoutText(shown.loadout, "Keep current", prefixed = true), Palette.textSecondary, isOpen(loadoutKey), imx, imy))
            TooltipManager.hover("Loadout equipped before the fight. Keep current fights in the loadout you are wearing.", mx, my)

        val weaponKey = "weapon:${c.name}"
        val inHotbar = if (shown.weaponId.isEmpty()) null else hotbar().firstOrNull { it.id == shown.weaponId }
        val missing = shown.weaponId.isNotEmpty() && inHotbar == null && mc.player != null
        val label = if (shown.weaponId.isEmpty()) "Item in hand" else weaponText(shown)
        if (drawSelect(ctx, weapon, "Weapon", label, if (missing) Palette.warning else Palette.textSecondary, isOpen(weaponKey), imx, imy, inHotbar?.stack)) {
            val tip = when {
                shown.weaponId.isEmpty() -> "Fights with whatever you hold when the fight starts (usually the rod)."
                inHotbar != null -> "Taken from hotbar slot ${inHotbar.slot + 1} for the fight."
                // opened from the main menu: there is no hotbar to look in yet
                mc.player == null -> "$label is looked up in your hotbar once you are in game; while it is missing the item in hand is used."
                else -> "$label is not in your hotbar right now. Auto Mob Killer uses the item in hand while it is missing."
            }
            TooltipManager.hover(tip, mx, my)
        }

        val attackRects = segRects(attack, attackWidths)
        val hovered = drawSegmented(ctx, attack, attackRects, ATTACK_LABELS, shown.attack.ordinal, Palette.accent, Palette.accentText, true, e.attackPill, imx, imy, ATTACK_SIZE)
        if (hovered >= 0) {
            ctx.requestCursor(CursorTypes.POINTING_HAND)
            val mode = AttackMode.entries[hovered]
            val habit = habits[c.name]
            val recorded = if (mode == AttackMode.AUTO && habit != null && habit.left + habit.right > 0) "\nYour recordings: ${if (habit.weaponPrefersRight) "right" else "left"} click." else ""
            TooltipManager.hover("${mode.label}: ${mode.description}$recorded", mx, my)
        }

        if (live) {
            hit(loadout, clip) {
                openLoadoutPicker(loadoutKey, "Keep current", "Fight in the loadout you are wearing.", shown.loadout, { if (e.drawnFrame == frame) e.loadoutBox else OFFSCREEN }, { listRect }) { v ->
                    ensureRule(c).loadout = v
                }
            }
            hit(weapon, clip) { openWeaponPicker(e, weaponKey) }
            AttackMode.entries.forEachIndexed { i, mode -> hit(attackRects[i], clip) { setAttack(c, mode) } }
        }
    }

    private fun drawFooter(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val f = footerRect
        UI.hLine(ctx, f.x, f.y, f.w, Palette.divider)

        // defaults
        val labelY = f.y + 12f
        val boxY = f.y + 19f
        otherBox = Rect(f.x + PAD, boxY, 72f, BOX_H)
        rareBox = Rect(otherBox.right + 10f, boxY, 72f, BOX_H)
        fishingBox = Rect(rareBox.right + 10f, boxY, 104f, BOX_H)
        UI.textMid(ctx, "OTHER CREATURES", otherBox.x + 1f, labelY, Palette.textMuted, CAPS_SIZE, UIFont.Weight.SEMIBOLD)
        UI.textMid(ctx, "RARE CATCHES", rareBox.x + 1f, labelY, Palette.textMuted, CAPS_SIZE, UIFont.Weight.SEMIBOLD)
        UI.textMid(ctx, "FISHING LOADOUT", fishingBox.x + 1f, labelY, Palette.textMuted, CAPS_SIZE, UIFont.Weight.SEMIBOLD)

        if (drawSelect(ctx, otherBox, null, defaultAction.label, actionText(defaultAction), isOpen(KEY_OTHER), mx, my))
            TooltipManager.hover("What happens with sea creatures that have no own rule. Learned picks the weapon each fight the way your recordings show.", mx, my)
        if (drawSelect(ctx, rareBox, null, defaultRareAction.label, actionText(defaultRareAction), isOpen(KEY_RARE), mx, my))
            TooltipManager.hover("What happens with rare catches (Thunder, Lord Jawbus, Water Hydra...) that have no own rule.", mx, my)
        if (drawSelect(ctx, fishingBox, null, loadoutText(fishingLoadout, "Stay", prefixed = false), Palette.textSecondary, isOpen(KEY_FISHING), mx, my))
            TooltipManager.hover("Loadout put back on after a fight that equipped another one. Stay keeps the fight loadout.", mx, my)
        hit(otherBox, null) { openDefaultPicker(KEY_OTHER, defaultAction, { otherBox }) { defaultAction = it } }
        hit(rareBox, null) { openDefaultPicker(KEY_RARE, defaultRareAction, { rareBox }) { defaultRareAction = it } }
        val learned = learnedFishingLoadout()
        hit(fishingBox, null) {
            openLoadoutPicker(KEY_FISHING, "Stay", "Stay in the fight loadout after a fight.", fishingLoadout, { fishingBox }, { body }, learned) { fishingLoadout = it }
        }

        var leftEnd = fishingBox.right
        if (needsFishingWarning()) {
            val w = Rect(fishingBox.right + 7f, boxY + 3f, 12f, 12f)
            UI.icon(ctx, Icon.WARNING, w.x, w.y, w.w, Palette.warning)
            if (UI.inside(mx, my, w.x - 2f, w.y - 2f, w.w + 4f, w.h + 4f))
                TooltipManager.hover("After a fight you stay in the fight loadout; pick a Fishing loadout to switch back.", mx, my)
            leftEnd = w.right
        }
        // only offered: Stay is kept until you pick it
        if (learned != 0) {
            val text = "Use $learned"
            val chip = Rect(leftEnd + 7f, boxY + (BOX_H - 13f) / 2f, UI.chipWidth(text, 6.2f, UIFont.Weight.SEMIBOLD, 6f), 13f)
            val uh = fishingUseHover.update(chip.contains(mx, my))
            if (uh > 0.5f) {
                ctx.requestCursor(CursorTypes.POINTING_HAND)
                val name = loadoutName(learned)?.let { " ($it)" } ?: ""
                TooltipManager.hover("Your recordings: you fished in loadout $learned$name. Use it as the Fishing loadout, put back on after a fight that equipped another one.", mx, my)
            }
            UI.rect(ctx, chip.x, chip.y, chip.w, chip.h, chip.h / 2f, UI.mix(UI.fade(Palette.accent, 0.24f), Palette.accent, uh))
            UI.textMidCentered(ctx, text, chip.x + chip.w / 2f, chip.y + chip.h / 2f, UI.mix(Palette.accentText, WHITE, uh), 6.2f, UIFont.Weight.SEMIBOLD)
            hit(chip, null) {
                fishingLoadout = learned
                Style.playClickSound(1.2f)
            }
            leftEnd = chip.right
        }
        else fishingUseHover.snap(0f)

        // buttons
        val bh = 22f
        val by = f.y + (f.h - bh) / 2f
        val doneW = UI.width("Done", 7.4f, UIFont.Weight.MEDIUM) + 36f
        doneRect = Rect(f.right - PAD - doneW, by, doneW, bh)
        val applyW = UI.width("Apply learned", 7.4f, UIFont.Weight.MEDIUM) + 26f
        applyRect = Rect(doneRect.x - 8f - applyW, by, applyW, bh)

        val differing = learnedDiffering()
        val pending = learnedToApply(differing).size
        val keptStop = differing.size - pending
        val canApply = pending > 0
        val overApply = applyRect.contains(mx, my)
        val ah = applyHover.update(canApply && overApply)
        val dh = doneHover.update(doneRect.contains(mx, my))
        if (ah > 0.5f || dh > 0.5f) ctx.requestCursor(CursorTypes.POINTING_HAND)
        else if (!canApply && overApply) ctx.requestCursor(CursorTypes.NOT_ALLOWED)
        val stopNote = when (keptStop) {
            0 -> ""
            1 -> "\n1 recorded creature on a Stop default keeps it (Stop is for fights you take yourself); the Use button on its row still changes it."
            else -> "\n$keptStop recorded creatures on a Stop default keep it (Stop is for fights you take yourself); the Use button on their rows still changes them."
        }
        if (overApply) {
            val learnedNote = when (val n = onLearnedDefault()) {
                0 -> ""
                1 -> "\n1 recorded creature on Learned already follows your recordings fight by fight; Pin on its row makes its suggestion its own rule."
                else -> "\n$n recorded creatures on Learned already follow your recordings fight by fight; Pin on a row makes its suggestion its own rule."
            }
            TooltipManager.hover(when {
                ! profile.hasData -> "No recordings yet. Record yourself fishing with the Fishing Recorder and Auto Mob Killer learns your weapon, click and loadout per creature."
                canApply -> "Gives ${if (pending == 1) "the 1 recorded creature" else "the $pending recorded creatures"} without an own rule the rule your recordings suggest.$stopNote$learnedNote"
                keptStop > 0 -> "Nothing to apply.$stopNote$learnedNote\nThe other recorded creatures already have their own rule or do what your recordings suggest."
                learnedNote.isNotEmpty() -> "Nothing to apply.$learnedNote\nThe other recorded creatures already have their own rule or do what your recordings suggest."
                else -> "Nothing to apply: every recorded creature already has its own rule or does what your recordings suggest."
            }, mx, my)
        }
        val saved = UI.alpha
        if (!canApply) UI.alpha = saved * 0.45f
        UI.button(ctx, "Apply learned", applyRect.x, applyRect.y, applyRect.w, applyRect.h, ah, UI.ButtonStyle.SECONDARY, size = 7.4f)
        UI.alpha = saved
        UI.button(ctx, "Done", doneRect.x, doneRect.y, doneRect.w, doneRect.h, dh, UI.ButtonStyle.PRIMARY, Icon.CHECK, 7.4f)
        hit(applyRect, null) { applyLearned() }
        hit(doneRect, null) {
            Style.playClickSound(1f)
            onClose()
        }

        val message = flash?.takeIf { System.currentTimeMillis() < flashUntil }
        val textRight = applyRect.x - 12f
        val maxW = textRight - (leftEnd + 14f)
        if (maxW > 40f) {
            val weight = if (message != null) UIFont.Weight.MEDIUM else UIFont.Weight.REGULAR
            val text = UIFont.trim(message ?: "Saved when you close this window", maxW, 7f, weight)
            UI.textMidRight(ctx, text, textRight, f.y + f.h / 2f, if (message != null) Palette.success else Palette.textMuted, 7f, weight)
        }
    }

    // ------------------------------------------------------------------ pickers

    private fun isOpen(key: String) = (Popovers.current as? Picker)?.key == key

    private fun open(picker: Picker) {
        search.unfocus()
        Popovers.open(picker)
        Style.playClickSound(1f)
    }

    private fun openDefaultPicker(key: String, current: KillAction, anchor: () -> Rect, set: (KillAction) -> Unit) {
        val actions = if (current in DEFAULT_ACTIONS) DEFAULT_ACTIONS else DEFAULT_ACTIONS + current
        val options = actions.map { Option(it.label, it.ordinal, tip = it.description) }
        open(Picker(key, anchor, { body }, { window }, options, current.ordinal, null) { set(KillAction.entries[it]) })
    }

    /** Loadout list with [zero] first; [learned] (when not 0) is marked as the one your recordings show. */
    private fun openLoadoutPicker(key: String, zero: String, zeroTip: String, current: Int, anchor: () -> Rect, area: () -> Rect, learned: Int = 0, set: (Int) -> Unit) {
        val known = LoadoutSwitcher.known.get()
        // whole menu pages of 12: as many as the menu had, or as far as the cached names and the picks reach
        val highest = maxOf(current, learned, known.keys.maxOrNull() ?: 0, LoadoutSwitcher.menuPages * 12)
        val max = ((highest - 1) / 12 + 1).coerceIn(1, KillRules.MAX_LOADOUT / 12) * 12
        val options = ArrayList<Option>()
        options += Option(zero, 0, tip = zeroTip)
        for (i in 1 .. max) {
            val name = loadoutName(i)
            val mark = if (i == learned) "learned" else null
            options += if (name != null) Option(name, i, detail = if (mark != null) "#$i · $mark" else "#$i") else Option("Loadout $i", i, detail = mark)
        }
        val hint = if (known.isEmpty()) "Open /loadout once to see your loadout names" else null
        open(Picker(key, anchor, area, { window }, options, current, hint, set))
    }

    private fun openWeaponPicker(e: Entry, key: String) {
        val c = e.creature
        val current = rules[c.name] ?: KillRule(KillAction.WEAPON)
        val hotbarItems = hotbar(fresh = true)
        // opened from the main menu there is no hotbar yet, so nothing is "missing" from it
        val inGame = mc.player != null
        val options = ArrayList<Option>()
        var selected = -1
        if (current.weaponId.isNotEmpty() && hotbarItems.none { it.id == current.weaponId }) {
            options += if (inGame) Option(weaponText(current), -2, detail = "not in hotbar", tip = "Put it in your hotbar before fishing; until then the item in hand is used.", muted = true)
            else Option(weaponText(current), -2, detail = "checked in game", tip = "Looked up in your hotbar once you are in game; while it is missing the item in hand is used.", muted = true)
            selected = -2
        }
        options += Option("Item in hand", -1, tip = "Fights with whatever you hold when the fight starts (usually the rod).")
        hotbarItems.forEachIndexed { i, h ->
            options += Option(h.name, i, detail = "Slot ${h.slot + 1}", stack = h.stack)
            if (h.id == current.weaponId) selected = i
        }
        val hint = when {
            hotbarItems.isNotEmpty() -> null
            inGame -> "Put the weapon in your hotbar to pick it"
            else -> "Join a world to pick a weapon from your hotbar"
        }
        open(Picker(key, { if (e.drawnFrame == frame) e.weaponBox else OFFSCREEN }, { listRect }, { window }, options, selected, hint) { v ->
            val picked = hotbarItems.getOrNull(v)
            if (v == -1) {
                val r = ensureRule(c)
                r.weaponId = ""
                r.weaponName = ""
            }
            else if (picked != null) {
                val r = ensureRule(c)
                r.weaponId = picked.id
                r.weaponName = picked.name
            }
        })
    }

    /**
     * Dropdown list of [options] under (or above) its control; [visibleArea] closes it when the control scrolls away,
     * [bounds] is where it may be placed.
     */
    private class Picker(
        val key: String,
        private val anchorRect: () -> Rect,
        val visibleArea: () -> Rect,
        val bounds: () -> Rect,
        private val options: List<Option>,
        private val selected: Int,
        private val hint: String?,
        private val onPick: (Int) -> Unit
    ): Popover() {
        private val scroll = ScrollArea(PICK_ITEM_H * 2f)
        private var highlighted = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
        private val icons = options.any { it.stack != null }
        private val hintH = if (hint != null) PICK_HINT_H else 0f
        /** Rows shown in the last drawn frame (clicks arrive between frames). */
        private var rows = options.size.coerceAtMost(PICK_MAX)
        /** Size the card was last laid out and drawn with (0 until the first frame). */
        private var laidW = 0f
        private var laidH = 0f
        // the mouse takes over the highlight only when it moves, so a cursor resting on the list does not undo arrow keys
        private var lastMx = Float.NaN
        private var lastMy = Float.NaN
        private var mouseOwns = false
        private val contentW: Float by lazy {
            val rows = options.maxOfOrNull { o ->
                (if (icons) 16f else 0f) + UI.width(o.label, PICK_SIZE, UIFont.Weight.SEMIBOLD) + (o.detail?.let { d -> UI.width(d, DETAIL_SIZE) + 10f } ?: 0f)
            } ?: 60f
            maxOf(rows + 36f, hint?.let { UI.width(it, DETAIL_SIZE) + 34f } ?: 0f)
        }

        init {
            scroll.set(((highlighted - 3) * PICK_ITEM_H).coerceAtLeast(0f), instant = true)
        }

        /** Rows that fit above or below the control inside [bounds] (at most [PICK_MAX]), so the list never covers it. */
        private fun rowsFor(a: Rect): Int {
            val b = bounds()
            val most = options.size.coerceAtMost(PICK_MAX)
            // Popover.layout keeps 4 px to the control and 6 px to the bounds
            val room = maxOf(a.y - b.y, b.bottom - a.bottom) - 10f - PICK_PAD * 2f - hintH
            return (room / PICK_ITEM_H).toInt().coerceIn(minOf(3, most), most)
        }

        override val width get() = maxOf(contentW, anchorRect().w, 110f).coerceAtMost(250f)
        override val height get() = rowsFor(anchorRect()) * PICK_ITEM_H + PICK_PAD * 2f + hintH

        override fun anchor() = anchorRect()

        /**
         * Hit test against the card as drawn. The live [width] and [height] follow the anchor, which a row picker reports
         * offscreen early in a frame (before its row is drawn again), so [contains] would shrink the card there.
         */
        fun covers(mx: Float, my: Float) = mx >= x && mx < x + laidW && my >= y && my < y + laidH

        override fun drawContent(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
            // layout() just placed the card with this frame's anchor; keep that size for hit tests until the next frame
            rows = rowsFor(anchorRect())
            laidW = width
            laidH = height
            if (mx != lastMx || my != lastMy) mouseOwns = true
            lastMx = mx
            lastMy = my
            val viewY = y + PICK_PAD
            val viewH = rows * PICK_ITEM_H
            scroll.update(x + width - 2f, viewY, viewH, options.size * PICK_ITEM_H, my)
            ctx.enableScissor(x.toInt(), viewY.toInt(), (x + width).toInt(), (viewY + viewH + 1f).toInt())
            options.forEachIndexed { i, o ->
                val oy = viewY + i * PICK_ITEM_H - scroll.offset
                if (oy + PICK_ITEM_H < viewY || oy > viewY + viewH) return@forEachIndexed
                val hot = UI.inside(mx, my, x + 4f, oy, width - 8f, PICK_ITEM_H) && my >= viewY && my < viewY + viewH
                val lit = hot && mouseOwns
                if (lit) highlighted = i
                if (hot) {
                    ctx.requestCursor(CursorTypes.POINTING_HAND)
                    o.tip?.let { TooltipManager.hover(it, mx, my) }
                }
                val isSelected = o.value == selected
                if (i == highlighted) UI.rect(ctx, x + 4f, oy + 1f, width - 8f, PICK_ITEM_H - 2f, 5f, if (lit) Palette.accentPill else Palette.accentHover)
                var tx = x + 11f
                if (icons) {
                    o.stack?.let { drawStack(ctx, it, tx, oy + (PICK_ITEM_H - 12f) / 2f, 12f) }
                    tx += 16f
                }
                val right = x + width - if (isSelected) 24f else 12f
                val detailW = o.detail?.let { UI.width(it, DETAIL_SIZE) + 8f } ?: 0f
                val weight = if (isSelected) UIFont.Weight.SEMIBOLD else UIFont.Weight.REGULAR
                val color = when {
                    isSelected -> Palette.accentText
                    o.muted -> Palette.textMuted
                    lit -> Palette.textPrimary
                    else -> Palette.textSecondary
                }
                UI.textMid(ctx, UIFont.trim(o.label, right - detailW - tx, PICK_SIZE, weight), tx, oy + PICK_ITEM_H / 2f, color, PICK_SIZE, weight)
                o.detail?.let { UI.textMidRight(ctx, it, right, oy + PICK_ITEM_H / 2f, Palette.textMuted, DETAIL_SIZE) }
                if (isSelected) UI.icon(ctx, Icon.CHECK, x + width - 19f, oy + (PICK_ITEM_H - 9f) / 2f, 9f, Palette.accentText)
            }
            ctx.disableScissor()
            scroll.draw(ctx, mx, my)

            if (hint != null) {
                val hy = viewY + viewH + PICK_PAD
                UI.hLine(ctx, x + 8f, hy, width - 16f, Palette.divider)
                val cy = hy + PICK_HINT_H / 2f - 1f
                UI.icon(ctx, Icon.INFO, x + 9f, cy - 4.5f, 9f, Palette.textMuted)
                UI.textMid(ctx, UIFont.trim(hint, width - 32f, DETAIL_SIZE), x + 22f, cy, Palette.textMuted, DETAIL_SIZE)
            }
        }

        override fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) return true
            if (scroll.mouseClicked(mx, my)) return true
            val viewY = y + PICK_PAD
            if (my < viewY || my >= viewY + rows * PICK_ITEM_H) return true
            val index = ((my - viewY + scroll.offset) / PICK_ITEM_H).toInt()
            if (index in options.indices) pick(index)
            return true
        }

        private fun pick(index: Int) {
            Style.playClickSound(1f)
            Popovers.close()
            onPick(options[index].value)
        }

        override fun mouseReleased(button: Int) = scroll.mouseReleased()

        override fun mouseScrolled(mx: Float, my: Float, delta: Double): Boolean {
            scroll.scroll(delta)
            return true
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            when (event.key) {
                InputConstants.KEY_DOWN -> highlighted = (highlighted + 1).coerceAtMost(options.lastIndex)
                InputConstants.KEY_UP -> highlighted = (highlighted - 1).coerceAtLeast(0)
                InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_SPACE -> {
                    pick(highlighted)
                    return true
                }
                else -> return false
            }
            // the keyboard owns the highlight until the mouse moves again (reveal may slide rows under a still cursor)
            mouseOwns = false
            scroll.reveal(highlighted * PICK_ITEM_H, PICK_ITEM_H, 4f)
            return true
        }
    }

    // ------------------------------------------------------------------ input

    override fun onMouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (Popovers.mouseClicked(mx, my, button)) return true
        if (search.mouseClicked(mx, my, button)) return true
        search.unfocus()
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return window.contains(mx, my)
        if (scroll.mouseClicked(mx, my)) {
            // a scrollbar drag wins over a row still being revealed
            revealing = null
            return true
        }
        if (sideScroll.mouseClicked(mx, my)) return true
        val target = hits.lastOrNull { it.rect.contains(mx, my) && (it.clip == null || it.clip.contains(mx, my)) }
        if (target != null) {
            target.action()
            return true
        }
        return window.contains(mx, my)
    }

    override fun onMouseReleased(button: Int) {
        Popovers.mouseReleased(button)
        scroll.mouseReleased()
        sideScroll.mouseReleased()
        search.mouseReleased()
    }

    override fun onMouseScrolled(mx: Float, my: Float, delta: Double): Boolean {
        if (Popovers.current != null && Popovers.mouseScrolled(mx, my, delta)) return true
        if (sideRect.contains(mx, my)) sideScroll.scroll(delta)
        else {
            revealing = null
            scroll.scroll(delta)
        }
        return true
    }

    override fun onKeyPressed(event: KeyEvent): Boolean {
        if (Popovers.keyPressed(event)) return true
        if (search.keyPressed(event)) return true
        if (event.hasControlDown() && event.key == InputConstants.KEY_F) {
            Popovers.close()
            search.focus()
            return true
        }
        return false
    }

    override fun onCharTyped(event: CharacterEvent): Boolean {
        if (Popovers.charTyped(event)) return true
        if (search.charTyped(event)) return true
        // Typing anywhere starts a search.
        val c = event.codepoint
        if (c > 32 && !Character.isISOControl(c)) {
            Popovers.close()
            search.focus()
            return search.charTyped(event)
        }
        return false
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onClosing() {
        Popovers.close()
        search.unfocus()
        save()
    }

    override fun removed() {
        Popovers.close()
        // replaced by another screen without being closed (a server menu, disconnect): keep the edits anyway
        if (!closing) save()
        super.removed()
    }

    private fun save() {
        val d = KillRules.data.get()
        // the picks the working copy keeps on other rows (for a switch back) are not saved, see asSaved
        d.rules = LinkedHashMap<String, KillRule>().apply {
            rules.forEach { (name, r) -> put(name, asSaved(name, r)) }
        }
        d.defaultAction = defaultAction
        d.defaultRareAction = defaultRareAction
        d.fishingLoadout = fishingLoadout
        KillRules.data.save()
        lastGroupId = groupId
    }
}

//#endif
