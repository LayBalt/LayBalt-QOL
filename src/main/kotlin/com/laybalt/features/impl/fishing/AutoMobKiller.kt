package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.LayBaltQOL
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.SliderSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.EventBus
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.event.impl.PlayerInteractEvent
import com.laybalt.event.priority.EventPriority
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.fishing.BehaviorProfile.Habit
import com.laybalt.features.impl.fishing.HumanAim.Owner
import com.laybalt.features.impl.fishing.KillRules.AttackMode
import com.laybalt.features.impl.fishing.KillRules.KillAction
import com.laybalt.features.impl.fishing.KillRules.KillRule
import com.laybalt.features.impl.fishing.MotorModel.View
import com.laybalt.features.impl.fishing.SeaCreatureTracker.TrackedCreature
import com.laybalt.features.impl.fishing.TimingModel.Action
import com.laybalt.features.impl.fishing.TimingModel.StreakState
import com.laybalt.features.impl.fishing.ViewPolicy.Decision
import com.laybalt.features.impl.fishing.ViewPolicy.Need
import com.laybalt.features.impl.fishing.WeaponPlanner.BOBBER
import com.laybalt.features.impl.fishing.WeaponPlanner.Plan
import com.laybalt.features.impl.fishing.WeaponPlanner.ROD
import com.laybalt.mixin.IKeyMapping
import com.laybalt.mixin.IMinecraft
import com.laybalt.ui.gui.SeaCreatureRulesScreen
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.GuiUtils
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.mojang.blaze3d.platform.InputConstants
import gg.essential.universal.UMinecraft
import gg.essential.universal.USound
import net.minecraft.client.KeyMapping
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.BeehiveBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.PumpkinBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.TntBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Kills your own sea creatures while Auto Fishing runs, the way the Sea Creature Rules say: the way your recordings
 * show (Learned), with the rod you hold, with a weapon (after equipping a loadout), not at all, or by stopping Auto
 * Fishing for rare catches.
 *
 * A fight: when Auto Fishing's recast right after the catch comes first (your recordings show its bobber killing your
 * creatures, or the rule says so) the fight waits for that recast and for the bobber's chance; otherwise Auto Fishing
 * waits from the catch message on. After your reaction time the weapon and then the loadout are equipped, and the
 * creature is hit at your recorded click speed. The view turns the way your hand does ([HumanAim], [ViewPolicy]): only
 * when the plan needs aim and the creature is off by more than the plan accepts, after your reaction to it, to within
 * that error and not onto it, and it stays still while the creature moves until it is off by a good deal more. Like you,
 * it now and then looks at a creature of yours no plan needs a turn for, at your rate per second alive, and mostly back
 * after it, never holding back a click ([ViewPolicy.Looks]), and it looks around and back the way you do ([GlancePolicy],
 * Auto Fishing's Look Around) only while no plan needs the view yet. A rod fight with your view still on the water (no
 * turn or look took it away) lets Auto Fishing reel and cast meanwhile, like you did. A melee click goes out a
 * reaction after the crosshair came onto that creature (now and then right next to it, missing at your share of misses)
 * and is cancelled when the game's own pick at the moment of the attack finds another entity or a block it would break at
 * once, so melee never hits other players' creatures nor breaks seagrass; a right click or a repeat of the held use key
 * waits (and the key is let go at once) while that pick finds any entity but the creature fought or a block a right click
 * opens or toggles, or while anything not yours is on the ability's way along the view before a creature of yours, so no
 * use interacts with or casts at another player's creature or anything else; it goes a reaction after the way came clear.
 * After a screen of yours closed, the killer's next click, swap, /loadout and move each wait your reaction to it. Like yours,
 * the hand reacts to a death a moment later: a use key held stays down a while, a move goes on, and now and then a click
 * already on its way still goes out at nothing ([HandLag]). Afterwards the
 * fishing loadout and then the rod come back, the view turns back towards your fishing view when it ended far from it
 * (most of the time, like you) and Auto Fishing casts again. Moving, pressing a mouse button,
 * changing the hotbar slot or turning the mouse yourself hands the fight to you (Auto Fishing then waits until those
 * creatures are dead); nothing happens while the Fishing Recorder records. A right click or the use key never goes out
 * with an item that moves you, a fishing tool or any item but the one the killer took.
 *
 * Learned creatures get a [WeaponPlanner] plan by distance, height and toughness: rod melee, single right presses or
 * the use key held (the Flaming Flay), a teleport blade looking down. A plan sticks while the creature stays in its
 * reach; one that does nothing to it is given up on that creature for the next option, and a creature nothing you have
 * reaches (a lava creature sunk deep below) is parked while Auto Fishing goes on, until it comes back in reach.
 * Creatures that split off yours as they die (Bayou Sludglings) are yours too and are fought the same way.
 */
object AutoMobKiller: Feature("Kills your sea creatures while Auto Fishing is on, following your Sea Creature Rules and recorded behaviour.") {
    private val openRules by ButtonSetting("Open Sea Creature Rules") { GuiUtils.setScreen(SeaCreatureRulesScreen(mc.gui.screen())) }
        .withDescription("Choose per sea creature: fight it the way your recordings show, kill with the rod, kill with a loadout and weapon, ignore, or stop Auto Fishing.")
    private val abilityRange by SliderSetting("Ability Range", 8, 3, 20, 1, " blocks")
        .withDescription("Right click attacks (weapon abilities) of Weapon rules are used up to this distance from the creature. Learned fights use the distances from your recordings.")
    private val giveUpAfter by SliderSetting("Give Up After", 20, 5, 60, 1, "s")
        .withDescription("A creature that takes no damage for this long (out of reach, stuck behind a wall) is left alone.")
    private val turnBack by ToggleSetting("Turn Back After Fight", true)
        .withDescription("Turns your view back towards where you were fishing before Auto Fishing casts again, when a fight left it far from there.")
    private val announce by ToggleSetting("Fight Messages", false)
        .withDescription("Writes what Auto Mob Killer does (target, weapon picked, creatures parked out of reach, loadout) in chat.")
    private val trace by ToggleSetting("Trace", false)
        .withDescription("Writes the killer's own view, fights and clicks every tick to config/LayBaltQOL/fishing/traces (one file per session) to compare them with your recordings. The learner never reads them.")

    private const val CATCH_HOLD_MS = 4000L
    /** A catch message without a fight of yours this long after it (someone else's creature, an unknown one) lets Auto Fishing go on. */
    private const val NO_SPAWN_MS = 600L
    /** Auto Fishing's recast this close to a creature's spawn is the one after its catch. */
    private const val RECAST_WAIT_MS = 600L
    /** The first turn towards a creature comes at the latest this long before Give Up After would give up on it. */
    private const val FIRST_TURN_MARGIN_MS = 2000.0
    /** An explicit rule's ability turns this share of your right-click error short of the creature (drawn per move). */
    private val EXPLICIT_SHORT = 0.5 .. 0.9
    /**
     * A creature closer than this sideways is right above or below you and usually falls into the crosshair by itself
     * within a while from its spawn ([Action.FALL_WAIT], drawn per creature); only then the view turns.
     */
    private const val FALL_RADIUS = 1.0
    private const val MELEE_REACH = 3.0
    /** Auto Fishing waits at most this long for creatures you took over. */
    private const val HANDOVER_HOLD_MS = 90_000L
    /** Degrees of your own mouse movement (decaying over [MOUSE_DECAY_MS]) that hand the fight to you. */
    private const val TAKEOVER_DEGREES = 2.5
    private const val MOUSE_DECAY_MS = 250.0
    /** How often an owed Fishing Loadout is retried while nothing is fought. */
    private const val OWE_CHECK_MS = 5000L
    /** Learned right clicks and holds aim at this share of the creature's height, the point your recordings measured. */
    private const val LEARNED_AIM_HEIGHT = 0.6
    /** The height of a creature whose mob is not linked. */
    private const val NO_MOB_HEIGHT = 1.8
    /**
     * A cast or reel shows up at most this long after the use of the item it came from: the bobber comes from the server,
     * a round trip and up to two ticks after the click (no time limit alone tells the mod's casts from yours, [UseOwner]).
     */
    private const val USE_WINDOW_MS = 1500L
    /** Your fishing view is taken this long after your own mouse turns stopped, between fights. */
    private const val HOME_SETTLE_MS = 1000L
    /** A look's error to a creature is measured at this share of its height, where the learner measured your turns. */
    private const val LOOK_AIM_SHARE = 0.5
    /** No look at a recast-first creature before this long after the recast (or its spawn): the bobber's chance. */
    private const val BOBBER_LOOK_MS = 600L
    /** How far a look's goal is checked for keeping a melee target under the crosshair. */
    private const val LOOK_RAY = 32.0
    /** A look around may go to where your creature died this long ago or less, this close. */
    private const val DEAD_SPOT_MS = 1500L
    private const val DEAD_SPOT_RANGE = 8.0
    /** A recast-first rod target needs no aim until this long before its first hit: until then it is the bobber's. */
    private const val FIRST_HIT_LEAD_MS = 1000L
    /** An ability's way is checked this much farther than its reach... */
    private const val PATH_MARGIN = 2.0
    /** ...and an entity's box this much wider than the crosshair's pick (a projectile meets boxes widened by 0.3). */
    private const val PATH_HIT_MARGIN = 0.5
    /** A look straight down: a teleport blade's area is planned around it before the view got there. */
    private val DOWN = Vec3(0.0, - 1.0, 0.0)
    /** No lead drawn yet against a pending recast ([recastLead]). */
    private const val NO_LEAD = Long.MIN_VALUE

    private enum class Phase { IDLE, REACT, SWAP, LOADOUT, FIGHT, SWITCH, AFTER_KILL, RESTORE_LOADOUT, RESTORE_ROD, TURN_BACK }

    /** The view driver every automatic turn goes through; the killer's moves are [Owner.KILLER]'s, its looks [Owner.LOOK]'s. */
    private val aim get() = HumanAim.shared
    /** When and how far the view turns in this fight. */
    private val policy = ViewPolicy()
    /** The looks at your creatures that no plan needs, in this fight. */
    private val looks = ViewPolicy.Looks()
    /** The pitch-only aim (a creature right above or below you) of the move in flight, decided at its start. */
    private var movePitchOnly = false
    /** The turn back after the fight, planned at its last death (or when the loadout menu closed). */
    private var back: ViewPolicy.TurnBack? = null
    /** The killer's last weapon swap: the first turn towards creatures it swapped for is timed from it. */
    private var swapAt = 0L
    /** The planned length of a move started in this tick, for the trace. */
    private var movePlannedMs: Double? = null
    private val afterKillStreak = StreakState()
    private val firstHitStreak = StreakState()
    private val rodRhythm = StreakState()
    private val weaponRhythm = StreakState()
    /** Your own mouse turns, and your fishing view taken once they settled between fights. */
    private val homeWatch = HomeWatch()
    /** Your presses of the use key, against the mod's uses: whose a cast or reel is. */
    private val uses = UseOwner()
    /** The killer's last press of the use key or the end of its hold (a held key uses the item every 4 ticks meanwhile). */
    private var killerUseAt = Long.MIN_VALUE / 2
    /** When the use key the killer held last went up ([Long.MIN_VALUE]: never yet): the turn back after a kill is timed from it. */
    private var keyUpAt = Long.MIN_VALUE
    private var autoFishingWasOn = false
    /** Auto Fishing started and its fishing view is still to be taken: a move of the bot's turned the view then ([HomeWatch.atStart]). */
    private var homeOnStart = false
    private var phase = Phase.IDLE
    private var nextAt = 0L
    private var holdUntil = 0L

    /** The rule the current group is fought with: an explicit one, or the one a Learned target's plan resolves to. */
    private var rule: KillRule? = null
    private var target: TrackedCreature? = null
    private var aimHeight = 0.65
    private var lastHpDrop = 0L
    private var lastHp = 0.0
    private var nextClickAt = 0L
    /**
     * When the clock of the killer's next click started: its click before, a swap, the target's spawn or the recast its
     * first hit is timed from ([FightRules.firstClickFrom]). Auto Fishing's rod clicks before then are inside it ([CrossGap]).
     */
    private var clickFrom = Long.MIN_VALUE
    /** The rules want Auto Fishing's recast before this fight: REACT waits for it. */
    private var waitRecast = false
    /**
     * What a pending recast is weighed against in this REACT ([FightRules.waitsForRecast], [NO_LEAD]: not drawn yet): the
     * rod's first hit drawn from the spawn, on a copy of your streak of slow first hits ([leadStreak], taken into it only
     * when that hit is the one that goes), or a weapon's swap one of your waits ([recastSwapMs]) after the recast's due time.
     */
    private var recastLead = NO_LEAD
    private var leadStreak: StreakState? = null
    private var recastSwapMs = NO_LEAD
    /** A weapon's REACT waited past [recastLead] for a recast Auto Fishing held ([FightRules.recastHeldUntil]). */
    private var recastHeldPast = false
    /**
     * Creature id -> its first hit in a recast-first rod fight, after its bobber had its chance (like your hits after a
     * missed bobber); every target gets its own, also one caught while another was fought.
     */
    private val firstHitAt = HashMap<Int, Long>()
    /**
     * Creature id -> the reaction to the crosshair getting onto it that its first hit waits ([BoxEntry]), drawn ahead
     * when a turn is timed to arrive that long before the first hit (your first hits measured both in one wait).
     */
    private val firstEntryLead = HashMap<Int, Long>()
    /** The first click after the killer's loadout menu closed: not before your time from a menu closing to a click (0: none). */
    private var menuClickAt = 0L
    /** The target's first turn comes then ([ViewPolicy.Situation.notBefore]): after the loadout menu, inside the wait for that click. */
    private var firstTurnAt = Long.MIN_VALUE
    /** When the killer's loadout menu closed: a swap its replan makes is timed from it, once ([replanSwapDrawn]). */
    private var menuClosedAt = 0L
    private var replanSwapDrawn = false
    /** Your hand after the target died or a new creature came up, until it reacted ([HandLag]). */
    private val handLag = HandLag()
    /** The target whose death [handLag] lags behind: a click still on its way was meant for it. */
    private var lagTarget: TrackedCreature? = null
    /** The killer's last click at its target (a melee click, a press, a hold's start), and that target. */
    private var targetClickAt = Long.MIN_VALUE / 2
    private var targetClickId = - 1
    /** How the current target is clicked at, for the hand after its death. */
    private var fightStyle: ClickStyle? = null
    /** Auto Fishing stopped (or the Fishing Recorder started) in a fight: the killer lets go a reaction later. */
    private val offReaction = Reaction()
    /** An owed Fishing Loadout goes back a reaction after the moment it may. */
    private val oweReaction = Reaction()
    /** After the kill: a /loadout owed that waited for Auto Fishing's rod goes a reaction after it is free, as in IDLE. */
    private val rodFree = FreeWait()
    /** The held use key goes up a reaction after the view or the distance stopped keeping the hold. */
    private val keepOff = Reaction()
    /** A click on its way after a death went out: the next click of the killer comes a gap of yours after it, not before. */
    private var afterLagClick = Long.MIN_VALUE
    /** When the killer's loadout switch was over (its callback): what was due between the close and then comes after it. */
    private var menuDoneAt = 0L
    /**
     * An explicit Weapon rule's acceptance drawn ahead for the target picked now (NaN: none): the policy's first update takes
     * it, so the first turn's timing after a loadout menu ([planTurnMs]) and the policy judge the view by the same error.
     */
    private var explicitAccept = Double.NaN
    /** A screen of yours was open at the last tick, and the killer's next click comes then at the soonest (your reaction to its close). */
    private var screenOpen = false
    private var screenClickAt = Long.MIN_VALUE
    /** After a screen of yours closed, the killer's next slot change, /loadout and move each wait your reaction to the close. */
    private val screenGate = ScreenGate { draw(it.action, emptyList()) }
    /** The killer's uses wait while something they must not touch is where they would go, and a reaction after it moved away. */
    private val useWay = ClearWay()
    /**
     * An area ability's look down for creature [downFor] is held while its area holds something not yours, the view on
     * the creature, from your reaction to it coming in on, and goes a reaction after it came clear ([DownHold]).
     */
    private var downFor = - 1
    private val down = DownHold()
    /** The killer's move in flight is a look down (started with lookDown). */
    private var lookingDown = false
    /**
     * When your hand was freed for creatures of yours already alive (the death that ended the killer's group, or of the
     * creature its reaction was for), whether it held the use key then, and when its reaction to them comes
     * ([Action.DEATH_TO_SWAP] from that death, or [Action.KEY_UP_TO_SWAP] from the key-up; [Long.MAX_VALUE] while the key
     * is still down): one already there is never reacted to from its own spawn, long past by then ([react]).
     */
    private var freedAt = Long.MIN_VALUE
    private var freedReactAt = Long.MIN_VALUE
    /**
     * REACT is for a creature that was alive before your hand was freed ([freedAt]): its reaction is [freedReactAt] when
     * its rule takes another item or a loadout ([reactSwaps]), else the next creature after a gap of its plan's own clicks
     * ([FightRules.olderReact]).
     */
    private var reactOlder = false
    private var reactSwaps = true
    /**
     * The first click at such a creature with no swap, one of its plan's gaps after the death ([olderStart]; [Long.MIN_VALUE]:
     * none): the fight starts a turn early for it.
     */
    private var olderClickAt = Long.MIN_VALUE
    /** The creature [olderStart] timed that click and its turn for, the one the fight picks ([pickTarget]; -1: none). */
    private var olderFor = - 1
    /**
     * After Auto Fishing stopped in a fight with the use key held: the rod comes back then (0: none owed), one of your
     * waits from a key-up to a swap after the key went up, unless the slot in hand is no longer [offRodSlot].
     */
    private var offRodAt = 0L
    private var offRodSlot = - 1
    /** Creature id -> when one right above or below you no longer falls into the crosshair by itself (drawn per creature). */
    private val fallWaits = HashMap<Int, Long>()
    /** You moved or turned in the cleanup after the killer's fight: the view is yours, no turn back comes. */
    private var viewTaken = false
    /** The rod comes back then after a fight you took over and won (0: none owed), unless the slot in hand is no longer [wonSlot]. */
    private var wonBackAt = 0L
    private var wonSlot = - 1
    /** The creature REACT is timed for. */
    private var reactId = - 1
    /** REACT is for a Learned weapon plan: Auto Fishing waits meanwhile, unless its recast comes first. */
    private var reactWeapon = false
    /** This group's weapon loadout was tried (a failed switch is not retried within the group). */
    private var loadoutTried = false
    /** The creature the current group started with. */
    private var leadId = - 1
    /** Creature id -> loadouts tried for it in this fight (each one once, a failed one is not tried again). */
    private val triedLoadouts = HashMap<Int, HashSet<Int>>()
    /** A loadout was equipped: SWAP plans the group again, the loadout may have changed the hotbar. */
    private var replan = false
    /** A plan switch in the middle of a fight has taken its item. */
    private var switchSwapped = false
    /** The killer turned your view in this fight: Auto Fishing waits until it is back on the water. */
    private var turned = false
    /** The killer clicked, swapped, equipped or turned in this fight; without any of that there is nothing to put back. */
    private var acted = false
    /**
     * The fight's creatures died without the killer doing anything (the recast's bobber killed them) and only a look around
     * keeps the fight open, for its turn back: nothing holds Auto Fishing meanwhile ([FightRules.endHolds]).
     */
    private var lookOnly = false
    /** The player tick of the killer's last click: Auto Fishing never uses the rod in the same tick. */
    private var clickTick = - 1
    /** When the killer's last left click went out: Auto Fishing's rod click follows it no sooner than your hand's switch ([CrossGap]). */
    var lastLeftClickAt = Long.MIN_VALUE
        private set
    /** Your hand's switch from Auto Fishing's last rod click to the killer's next left click ([CrossGap]). */
    private val rodCross = CrossGap()
    private val givenUp = HashSet<Int>()
    /** Creatures whose fight you took over; Auto Fishing waits for them (at most [HANDOVER_HOLD_MS]). */
    private val handedOver = HashSet<Int>()
    private var handedOverAt = 0L
    /** The creatures you took over all died; the next idle tick turns back to the water. */
    private var handoverWon = false
    private var warnedWeapon = HashSet<String>()
    /** The simulated melee click on its way: checked against the game's pick when the click is handled, and counted by what that pick found. */
    private var pendingMelee: MeleeClick? = null
    /** When the crosshair came onto the target: its hits go out a reaction after that. */
    private val boxEntry = BoxEntry()
    /** The misses next to your creatures the killer's rod and weapon hits owe ([MissOdds]). */
    private val rodMisses = MissOdds()
    private val weaponMisses = MissOdds()
    /** The killer pressed the use key in this tick: the game uses the item in its key handling, after the killer's tick. */
    private var usePending = false
    /**
     * The hotbar slot and item that press, or the use key the killer holds, is meant for; checked when the game uses the
     * item (the slot also for a melee click).
     */
    private var useSlot = - 1
    private var useId = ""
    /** The hotbar slot the killer fights with: any other change of it is yours. */
    private val slotWatch = SlotWatch()
    private var mouseTurn = 0.0
    private var mouseAt = 0L
    /** You pressed the attack or use button yourself during a fight. */
    private var buttonPressed = false
    private var lastErrorAt = 0L
    private var lastOweCheckAt = 0L
    /**
     * A creature others split off died: Auto Fishing does not cast into them until then, one of your child lags after the
     * death ([Action.CHILD_WAIT]). The killer's own hand after such a kill waits your wait after it instead
     * ([Action.PARENT_AFTER_KILL]), never when children would come.
     */
    private var childHoldUntil = 0L
    /** When the last of your creatures died and where (half its height), for your looks around ([GlancePolicy]). */
    private var ownDeathAt = Long.MIN_VALUE / 2
    private var ownDeathSpot: Vec3? = null
    /** When the killer's last fight ended: Auto Fishing's cast after it counts as after the kill for the looks around. */
    @JvmStatic
    var fightEndedAt = Long.MIN_VALUE / 2
        private set
    /** How long a weapon loadout stays on for parked creatures. */
    private val parkedLoadout = ParkedLoadout()
    private var warnedChoose = false

    /** Plans, parking and given-up options of your Learned creatures. */
    private val book = PlanBook()
    /** Learned creature id -> the rule its plan fights with (a Rod rule, or a Weapon rule with the plan's item). */
    private val learnedRules = HashMap<Int, KillRule>()
    private val escalation = Escalation()
    private val hold = HoldCycle()
    private var hotbarTick = - 1
    /** The options in your hotbar ([WeaponPlanner.choose]'s available): its items' ids, [ROD] for a fishing rod. */
    private var available: Set<String> = emptySet()
    /** The option in your hand: [ROD] for a fishing rod, else the item's id. */
    private var heldOption: String? = null
    private var parentsOf: BehaviorProfile.Profile? = null
    private var parents: Set<String> = emptySet()

    private var rodSlot = - 1
    private var rodId = ""
    private var fishingYaw = 0f
    private var fishingPitch = 0f
    /** A fight equipped a weapon loadout and the Fishing Loadout has not been put back yet (kept across fights and resets). */
    private var owesFishingLoadout = false

    /**
     * True while a fight (yours or the killer's) holds Auto Fishing, and while a move of the killer turns the view (its
     * stop tail, its looks and turn back included). A rod fight with your view still on the water does not: like you, Auto
     * Fishing keeps reeling and casting while the rod hits the creature in front of you. Nor does the end of a fight the
     * killer did nothing in ([lookOnly]), nor the turn back's slow tail to rest ([FightRules.backTail]): Auto Fishing's own
     * tail rule takes it from there.
     */
    @JvmStatic
    fun holdsAutoFishing(): Boolean {
        if (! enabled) return false
        val now = System.currentTimeMillis()
        if (now < holdUntil || now < childHoldUntil || userFights()) return true
        if (FightRules.backTail(phase == Phase.TURN_BACK, back?.done == true, aim.movingInFight(), aim.turning)) return false
        // the turn back came to rest and the fight finishes in the killer's own tick: no hold again in between, whichever
        // tick handler runs first (Auto Fishing's may run before the killer's, its cast then timed as the tail released it)
        if (phase == Phase.TURN_BACK && back?.done == true && ! aim.movingInFight() && targets().isEmpty()) return false
        return turned || aim.movingInFight() || when (phase) {
            Phase.IDLE -> false
            Phase.REACT -> reactWeapon && ! waitRecast
            Phase.SWAP, Phase.SWITCH, Phase.FIGHT -> rule?.action != KillAction.ROD
            Phase.AFTER_KILL, Phase.RESTORE_ROD, Phase.TURN_BACK -> FightRules.endHolds(phase == Phase.AFTER_KILL, rule?.action == KillAction.ROD, lookOnly)
            else -> true
        }
    }

    /** A fight of the killer is going on, or its move (or look) still turns the view (Auto Fishing does not shake your head meanwhile). */
    @JvmStatic
    fun busy() = enabled && (phase != Phase.IDLE || aim.movingInFight())

    /**
     * A target of the killer's is fought, or the next one of its group is about to be (between two, a plan switch): the
     * view is its plan's, and Auto Fishing never turns it home for a cast meanwhile, which would pull the crosshair off
     * the creature ([AutoFishing.CastAim]). Before the fight (REACT) and after its last death no plan aims.
     */
    @JvmStatic
    fun aimsAtTarget() = enabled && (phase == Phase.SWAP || phase == Phase.SWITCH || phase == Phase.FIGHT)

    /** The killer feeds your looks around in its fights; Auto Fishing does between them. */
    @JvmStatic
    fun feedsGlances() = enabled && phase != Phase.IDLE

    /** Your mouse turned the view within [ms] before [now] (with Lock Mouse on it cannot). */
    fun userMouseWithin(now: Long, ms: Long) = homeWatch.within(now, ms)

    /** Since the last death of one of your creatures, ms (infinite before the first). */
    fun sinceOwnDeathMs(now: Long) = (now - ownDeathAt).toDouble()

    /** The view on where one of your creatures died [DEAD_SPOT_MS] or less before [now], within [DEAD_SPOT_RANGE] blocks; null otherwise. */
    fun deadSpotView(p: LocalPlayer, now: Long): View? {
        val at = ownDeathSpot ?: return null
        if (now - ownDeathAt > DEAD_SPOT_MS || p.eyePosition.distanceTo(at) > DEAD_SPOT_RANGE) return null
        return viewTo(p.eyePosition, at)
    }

    /** The killer clicked in this tick: Auto Fishing leaves the rod alone until the next one. */
    @JvmStatic
    fun clickedThisTick() = enabled && mc.player?.tickCount == clickTick

    /** You took over a fight and those creatures still live: Auto Fishing waits and Lock Mouse lets you turn. */
    @JvmStatic
    fun userFights(): Boolean {
        if (handedOver.isEmpty()) return false
        val won = SeaCreatureTracker.ownAlive.none { it.id in handedOver }
        if (won || System.currentTimeMillis() - handedOverAt > HANDOVER_HOLD_MS) {
            handedOver.clear()
            // your fight is over: the next tick turns back to the water before Auto Fishing casts (not on the time limit,
            // you may still be fighting then)
            if (won) handoverWon = true
            return false
        }
        return true
    }

    /**
     * Your own mouse movement in pixels, from [com.laybalt.mixin.MixinMouseHandlerAutoFishing] every frame before
     * Lock Mouse drops it; enough of it during a fight (or while the killer's move turns the view, or a turn back is
     * owed) hands the fight to you, and a turn between fights becomes your fishing view once it settles ([HomeWatch]).
     * The killer's own turns never come through here.
     */
    @JvmStatic
    fun onMouseTurn(dx: Double, dy: Double) {
        if (dx == 0.0 && dy == 0.0) return
        val now = System.currentTimeMillis()
        val o = mc.options
        val f = o.sensitivity().get() * 0.6 + 0.2
        val step = f * f * f * 1.2
        // Lock Mouse drops this turn right after (the view stays where the bot left it): no view of yours to take
        if (! AutoFishing.shouldLockMouse()) {
            // the view turns by this much in the game's turnPlayer (the smooth camera only spreads it over frames)
            val yaw = dx * step * if (o.invertMouseX().get()) - 1.0 else 1.0
            val pitch = dy * step * if (o.invertMouseY().get()) - 1.0 else 1.0
            homeWatch.turned(now, betweenFights(), yaw, pitch, lookOut())
        }
        if (! enabled || phase == Phase.IDLE && ! aim.movingInFight()) return
        mouseTurn = mouseTurn * kotlin.math.exp(- (now - mouseAt) / MOUSE_DECAY_MS) + kotlin.math.hypot(dx, dy) * step
        mouseAt = now
    }

    /**
     * The cast showing up now came from your own press of the use key, or the key you hold: never from Auto Fishing's rod
     * click or the killer's press or held use key, whatever the ping, nor is it Auto Fishing's cast a press of yours reeled
     * in as it came ([UseOwner]).
     */
    fun rodUseIsYours(now: Long = System.currentTimeMillis()) = uses.yours(now, modUseAt(now), PlayerUtils.isPhysicallyDown(mc.options.keyUse), cast = true)

    /** The [cast] (else the reel) showing up now came from the mod's use of the item: Auto Fishing's rod click, the killer's press or hold. */
    fun rodUseIsMods(cast: Boolean, now: Long = System.currentTimeMillis()) = uses.mods(now, modUseAt(now), cast)

    /** A look around keeps the view away or turns it: the view is the look's, not yours. */
    private fun lookOut() = HumanAim.glances.out || aim.moving(Owner.GLANCE)

    /** The mod's last use of the item in hand: Auto Fishing's rod click, the killer's press, or now while it holds the use key. */
    private fun modUseAt(now: Long) = maxOf(AutoFishing.lastRodUseAt, killerUseAt, if (hold.holding) now else Long.MIN_VALUE)

    /**
     * You pressed the key or button [value] of [type] with no screen open: a press of the use key may cast or reel; made
     * before Auto Fishing's last cast showed up, it reels that cast in.
     */
    private fun notePress(type: InputConstants.Type, value: Int) {
        if (UMinecraft.currentScreenObj != null) return
        val k = (mc.options.keyUse as IKeyMapping).key
        val use = k.type == type && k.value == value
        if (use) uses.pressed(System.currentTimeMillis(), AutoFishing.lastCastClickAt, SeaCreatureTracker.castAt)
    }

    /** What your mouse turns are as your fishing view ([FightRules.homeTurns]). */
    private fun homeTurns() = FightRules.homeTurns(enabled, SeaCreatureTracker.ownAlive.isNotEmpty())

    override fun init() {
        HumanAim.install()
        // rest nudges in the killer's fights are always on, whatever Shake Head says (never into the Fishing Recorder's lines)
        HumanAim.killerRest = { enabled && ! FishingRecorder.enabled && phase != Phase.IDLE && phase != Phase.LOADOUT && phase != Phase.RESTORE_LOADOUT }
        KillerTrace.install()
        // your own casts between fights are where you fish (the killer's turn back and idle motion come back to that view);
        // Auto Fishing's and the killer's own casts never are, nor one from where a look around took the view
        SeaCreatureTracker.onCast += {
            mc.player?.let { p -> if (betweenFights() && ! lookOut() && rodUseIsYours()) aim.home = View(p.yRot.toDouble(), p.xRot.toDouble()) }
        }
        // whose a cast is needs your presses of the use key whatever is enabled (the Fishing Recorder marks the mod's);
        // the killer's clicks and held use key never go through the mouse or keyboard
        EventBus.register<MouseClickEvent>(receiveCancelled = true) { if (event.action == InputConstants.PRESS) notePress(InputConstants.Type.MOUSE, event.button) }
        EventBus.register<KeyboardEvent.KeyPressed>(receiveCancelled = true) { if (event.action == InputConstants.PRESS) notePress(InputConstants.Type.KEYSYM, event.keyEvent.key) }
        EventBus.register<TickEvent.Start> { watchHome() }
        // the player after a world change counts its ticks from 0 again: no click of the old one went out in its ticks
        // (whether the killer runs or not, Auto Fishing reads it)
        EventBus.register<WorldChangeEvent> { clickTick = - 1 }
        EventBus.register<TickEvent.End> {
            KillerTrace.update(enabled && trace.value)
            if (KillerTrace.open) {
                val motion = when {
                    ! aim.movingInFight() -> "still"
                    aim.stopping -> "stop"
                    else -> "move"
                }
                KillerTrace.tick(phase.name.lowercase(), motion, movePlannedMs, phase != Phase.IDLE || aim.movingInFight(), HumanAim.glances.traceName)
            }
            movePlannedMs = null
        }

        SeaCreatureTracker.onCatchMessage += { creature, _ ->
            if (canRun()) {
                val r = KillRules.ruleFor(creature, creature.name)
                when (r.action) {
                    KillAction.STOP -> stopForCatch(creature.name)
                    KillAction.IGNORE -> Unit
                    // the plan comes with the creature; until then like a Rod rule in Learned mode
                    KillAction.LEARNED -> if (! recastFirst(r, creature.name)) holdUntil = System.currentTimeMillis() + CATCH_HOLD_MS
                    // Auto Fishing waits for the fight, unless its recast after the catch comes first
                    else -> if (! recastFirst(r, creature.name)) holdUntil = System.currentTimeMillis() + CATCH_HOLD_MS
                }
            }
        }

        SeaCreatureTracker.onSpawn += { c ->
            // a rare catch without a known catch message (a creature split off yours is no catch, nor one of yours seen again
            // after the tracker stopped a while)
            if (canRun() && c.own && ! c.kept && c.parent.isEmpty() && c.creature?.catchMessage == null && KillRules.ruleFor(c.creature, c.name).action == KillAction.STOP) stopForCatch(c.name)
        }

        SeaCreatureTracker.onDeath += { c ->
            // creatures split off yours as it dies (Bayou Sludglings) come up within moments: Auto Fishing does not cast
            // into them meanwhile, and they hold it as targets afterwards
            if (canRun() && c.own && c.deathCause != "gone" && c.name in parentNames())
                childHoldUntil = maxOf(childHoldUntil, TimingModel.dueAt(c.deadAt, draw(Action.CHILD_WAIT, emptyList())))
            if (c.own && c.deathCause != "gone") {
                ownDeathAt = System.currentTimeMillis()
                val height = c.mob?.takeUnless { it.isRemoved }?.bbHeight?.toDouble() ?: NO_MOB_HEIGHT
                ownDeathSpot = c.position.add(0.0, height * LOOK_AIM_SHARE, 0.0)
            }
        }

        register<TickEvent.Start> {
            try {
                onTick()
                driveGlances()
            }
            catch (e: Exception) {
                // never leave Auto Fishing held by a broken fight, nor the weapon in hand or the use key down
                val now = System.currentTimeMillis()
                if (now - lastErrorAt > 10_000L) ChatUtils.modMessage("§cAuto Mob Killer stopped a fight after an error: ${e.message}")
                lastErrorAt = now
                val restore = phase != Phase.IDLE && phase != Phase.REACT
                val held = hold.holding
                val menu = LoadoutSwitcher.busy && LoadoutSwitcher.menuOpen()
                reset()
                // the rod comes back a moment later, never in the tick the key went up or the menu closed, nor behind a screen
                if (restore) runCatching { mc.player?.let { p -> oweRod(p, now, held, atOnce = false, menu) } }
            }
        }

        // The game picks the crosshair target again right before it handles the click: only the meant creature may be
        // hit. EventBus dispatches by exact class, so each concrete click event gets the guard (first, even if cancelled).
        register<PlayerInteractEvent.LEFT_CLICK.ENTITY>(EventPriority.HIGHEST, receiveCancelled = true) { guardMelee(event, event.entity) }
        register<PlayerInteractEvent.LEFT_CLICK.AIR>(EventPriority.HIGHEST, receiveCancelled = true) { guardMelee(event, null) }
        register<PlayerInteractEvent.LEFT_CLICK.BLOCK>(EventPriority.HIGHEST, receiveCancelled = true) { guardMelee(event, null) }
        // The same for the killer's right clicks and every use its held key repeats: the item in hand then must be the one
        // it chose in its tick (a hotbar key changes the slot after that, in the game's key handling).
        register<PlayerInteractEvent.RIGHT_CLICK.AIR>(EventPriority.HIGHEST, receiveCancelled = true) { guardUse(event, null) }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK>(EventPriority.HIGHEST, receiveCancelled = true) { guardUse(event, null, event.pos) }
        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY>(EventPriority.HIGHEST, receiveCancelled = true) { guardUse(event, event.entity) }
        register<TickEvent.End> {
            pendingMelee = null
            usePending = false
        }

        // only real presses get here (the killer's clicks and its held use key never go through the mouse or keyboard)
        register<MouseClickEvent>(receiveCancelled = true) { if (event.action == InputConstants.PRESS) onPhysicalPress(InputConstants.Type.MOUSE, event.button) }
        register<KeyboardEvent.KeyPressed>(receiveCancelled = true) { if (event.action == InputConstants.PRESS) onPhysicalPress(InputConstants.Type.KEYSYM, event.keyEvent.key) }

        register<WorldChangeEvent> { reset() }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
    }

    /**
     * Your fishing view ([HumanAim.home]): taken when Auto Fishing starts and once your own mouse turns stopped for a
     * second between fights, a hand resting on the mouse only moving it by as much as it turned ([HomeWatch]); your own
     * casts take it too. Never from the killer's or Auto Fishing's own motion or casts: never while a move of the bot's
     * turns the view or comes to rest (Auto Fishing turned on again while its last move still turned keeps the view it
     * had, [HomeWatch.atStart]).
     */
    private fun watchHome() {
        val p = mc.player ?: return
        val now = System.currentTimeMillis()
        val view = View(p.yRot.toDouble(), p.xRot.toDouble())
        val on = AutoFishing.enabled
        if (on && ! autoFishingWasOn) homeOnStart = true
        if (! on) homeOnStart = false
        autoFishingWasOn = on
        val moving = aim.moving
        if (homeOnStart) when (HomeWatch.atStart(aim.home != null, moving)) {
            HomeWatch.Start.TAKE -> {
                aim.home = view
                homeOnStart = false
            }
            HomeWatch.Start.KEEP -> homeOnStart = false
            HomeWatch.Start.WAIT -> Unit
        }
        // nor while a look around keeps the view away: your turn is taken once the look came back or was given up
        homeWatch.settled(now, betweenFights(), aim.home, view, moving || HumanAim.glances.out)?.let { aim.home = it }
    }

    /**
     * No fight goes on: none of the killer's (its move included), none you took over, no creature of yours alive while it
     * is on. With the killer off your creatures are no fight of its ([homeTurns]): the view is yours, and where your mouse
     * rests a second ([HomeWatch]) is where Auto Fishing fishes from then on.
     */
    private fun betweenFights() = phase == Phase.IDLE && ! aim.movingInFight() && handedOver.isEmpty() && homeTurns() != FightRules.HomeTurns.FIGHT

    /** You turned the view with the mouse within the last [HOME_SETTLE_MS]: Auto Fishing's idle re-aims leave it alone. */
    fun userTurnedRecently(now: Long) = homeWatch.recently(now)

    /**
     * Cancels a simulated melee click that the game's fresh pick would land on another entity than the meant creature, on
     * a block it breaks at once, or that would swing another item than the one in hand when the killer clicked. Air or
     * another block is a miss, as yours were, whatever the click was meant as; the click is counted by what it does
     * ([settleMelee]).
     */
    private fun guardMelee(event: PlayerInteractEvent, hit: Entity?) {
        val click = pendingMelee ?: return
        pendingMelee = null
        val p = mc.player ?: return event.cancel()
        val hand = p.mainHandItem
        // the slot and the very item the click was meant for (a server or another feature may swap what the slot holds)
        val same = FightRules.sameItem(p.inventory.selectedSlot, hand.skyblockId, click.slot, click.id) && hand.item === click.item
        val outcome = FightRules.meleeOutcome(same, hit != null, hit != null && (hit === click.c.mob || hit === click.c.stand)) { breaksAtOnce(p) }
        if (outcome != MeleeOutcome.HIT && outcome != MeleeOutcome.MISS) event.cancel()
        settleMelee(p, click, outcome)
    }

    /**
     * Counts the killer's melee [click] by its [outcome]: a hit is a use of the plan and owes misses at your share, a
     * miss pays the one owed (also one meant as a hit: your share stays yours) and is no use of the plan, a click another
     * entity was in the way of is a use that did nothing to the creature. Only what went out is traced.
     */
    private fun settleMelee(p: LocalPlayer, click: MeleeClick, outcome: MeleeOutcome) {
        if (click.after) {
            // a click on its way after the death is no use of a plan; a miss next to another creature of yours counts
            // like your misses did (the learner counted yours there)
            if (outcome == MeleeOutcome.MISS && SeaCreatureTracker.ownAlive.any { p.eyePosition.distanceTo(it.position) <= BehaviorProfile.Profile.MISS_NEAR })
                missOdds(click.rod).missed()
            if (outcome == MeleeOutcome.HIT || outcome == MeleeOutcome.MISS) KillerTrace.click(p, left = true, hold = false, target = - 1)
            return
        }
        when (outcome) {
            MeleeOutcome.HIT -> {
                escalation.use()
                missOdds(click.rod).hit(BehaviorProfile.current.missShare(click.rod), Random)
            }
            MeleeOutcome.MISS -> missOdds(click.rod).missed()
            MeleeOutcome.OTHER_ENTITY -> escalation.use()
            MeleeOutcome.CANCELLED -> Unit
        }
        if (outcome == MeleeOutcome.HIT || outcome == MeleeOutcome.MISS) KillerTrace.click(p, left = true, hold = false, target = if (outcome == MeleeOutcome.HIT) click.c.id else - 1)
    }

    /**
     * Cancels the game's use of an item for the killer (its press in this tick, or a repeat of its held use key) when the
     * item in hand is not the one it chose then (a hotbar key, another feature or the server changed it since), must never
     * be used (it moves you, casts, a teleport blade not looking down), the game's fresh pick found an entity ([hit])
     * that is not the creature fought now (another player's creature, a player, any other entity: the use would interact
     * with it; a creature of yours, alive or dying, is none) or a block a right click opens or toggles ([block]: a door, a
     * lever, a chest), something the ability must not hit is on its way along the view ([otherOnPath]) or, for an ability
     * that hits everything around where it lands, in that area ([otherInArea]); the held key goes up at once as well, and
     * is pressed again one of your gaps later at the soonest, a reaction after the way is clear.
     */
    private fun guardUse(event: PlayerInteractEvent, hit: Entity?, block: BlockPos? = null) {
        if (! usePending && ! hold.holding) return
        val p = mc.player ?: return
        val stack = p.mainHandItem
        val id = stack.skyblockId
        val way = ! FightRules.usesOnOther(hit != null, hit != null && isFought(hit), hit != null && ownCreature(hit)) && ! (block != null && usableBlock(block)) &&
            ! otherOnPath(p, id) && ! otherInArea(p, id)
        if (p.inventory.selectedSlot == useSlot && id == useId && FightRules.useAllowed(id, AutoFishing.isFishingRod(stack), p.xRot) && way) return
        event.cancel()
        usePending = false
        if (! way) useWay.block()
        if (! hold.holding) return
        releaseUse()
        // never down again in the next free tick: one of your gaps after a let-go, and the reaction to the way coming clear
        nextClickAt = maxOf(nextClickAt, TimingModel.dueAt(System.currentTimeMillis(), holdGap(useId)))
    }

    /** [e] is the creature the killer fights now (its mob or its nametag), alive. */
    private fun isFought(e: Entity) = target?.let { c -> ! c.dead && (e === c.mob || e === c.stand) } == true

    /**
     * [e] is one of your creatures (its mob or its nametag), alive or dying: a dying one stays pickable until it is
     * removed, and the tracker keeps it past that ([SeaCreatureTracker.creatures]), as Auto Fishing's own check does.
     */
    private fun ownCreature(e: Entity) = SeaCreatureTracker.creatures.values.any { it.own && (e === it.mob || e === it.stand) }

    /**
     * The crosshair is on an entity that is neither the creature fought now nor any creature of yours, alive or dying
     * (the one just killed included, with no target after its death): a right click would interact with it.
     */
    private fun otherUnderCrosshair(): Boolean {
        val e = (mc.hitResult as? EntityHitResult)?.entity ?: return false
        return FightRules.usesOnOther(true, isFought(e), ownCreature(e))
    }

    /** The block at [pos] does something on a right click, whatever is in hand: a door, a trapdoor, a lever, a chest ([BlockUses]). */
    private fun usableBlock(pos: BlockPos) = mc.level?.let { BlockUses.usable(it.getBlockState(pos)) } == true

    /** The crosshair is on a block a right click would open or toggle ([usableBlock]). */
    private fun usableBlockUnderCrosshair(): Boolean {
        val b = mc.hitResult as? BlockHitResult ?: return false
        return b.type == HitResult.Type.BLOCK && usableBlock(b.blockPos)
    }

    /**
     * Something a use of the killer now must not touch: an entity that is no creature of yours under the crosshair, a
     * usable block, one on the ability's way, or one in its area.
     */
    private fun useBlocked(p: LocalPlayer, id: String) = otherUnderCrosshair() || usableBlockUnderCrosshair() || otherOnPath(p, id) || otherInArea(p, id)

    /**
     * How far along the view the ability of [id] is checked for something it must not hit ([otherOnPath]): the farthest of
     * Ability Range and the reach your recordings show with it, its height range included, and a margin.
     */
    private fun abilityPath(id: String): Double {
        val plan = WeaponPlanner.plan(BehaviorProfile.current, id)
        val reach = plan?.let { hypot(it.reach, max(abs(it.dyMin), abs(it.dyMax))) } ?: 0.0
        return max(abilityRange.value.toDouble(), reach) + PATH_MARGIN
    }

    /**
     * The ability of [id] cast along the view now would hit something it must never hit: on the line from your eye within
     * its reach ([abilityPath]), an entity that is no creature of yours nor the one fought (another player's creature, a
     * player, any other entity a projectile meets) comes before the first creature of yours alive ([FightRules.pathBlocked]).
     */
    private fun otherOnPath(p: LocalPlayer, id: String): Boolean {
        val level = mc.level ?: return false
        val eye = p.eyePosition
        val dir = Vec3.directionFromRotation(p.xRot, p.yRot)
        val range = abilityPath(id)
        val end = eye.add(dir.scale(range))
        val mine = HashSet<Entity>()
        for (c in SeaCreatureTracker.creatures.values) if (c.own) {
            mine += c.stand
            c.mob?.let { mine += it }
        }
        val hits = ArrayList<Pair<Double, FightRules.PathKind>>()
        for (e in level.getEntities(p, p.boundingBox.expandTowards(dir.scale(range)).inflate(1.0)) { it.isAlive && ! it.isSpectator }) {
            val kind = FightRules.pathKind(e in mine || isFought(e), e is ArmorStand, e is LivingEntity || e.isPickable || e.canBeHitByProjectile())
            if (kind == FightRules.PathKind.NONE) continue
            // a creature of yours shields only where the ability really meets it: widened like a blocker, a line passing
            // beside it would let another player's creature right behind it go unchecked
            val box = e.boundingBox.inflate(e.pickRadius + FightRules.hitMargin(kind, PATH_HIT_MARGIN))
            val d = if (box.contains(eye)) 0.0 else box.clip(eye, end).map { it.distanceTo(eye) }.orElse(null) ?: continue
            hits += d to kind
        }
        return FightRules.pathBlocked(hits)
    }

    /**
     * An ability of [id] that hits everything around where it lands ([WeaponPlanner.area]: a teleport blade's implosion, an
     * area weapon) used now would hit something it must never hit: a living entity that is no creature of yours (nor rides
     * with one), not the one fought and no armor stand, within its area from your eye along the view; [planned]: along a
     * look straight down, widened for any look down a teleport blade is used with ([FightRules.plannedArea]), before the
     * view got there; [margin] wider still (an option taken back only once its area is clear by that much,
     * [FightRules.usableFor]).
     */
    private fun otherInArea(p: LocalPlayer, id: String, planned: Boolean = false, margin: Double = 0.0): Boolean {
        val area = WeaponPlanner.area(id, WeaponPlanner.plan(BehaviorProfile.current, id))?.let { if (planned) FightRules.plannedArea(it, margin) else it } ?: return false
        val level = mc.level ?: return false
        val eye = p.eyePosition
        val dir = if (planned) DOWN else Vec3.directionFromRotation(p.xRot, p.yRot)
        val mine = HashSet<Entity>()
        for (c in SeaCreatureTracker.creatures.values) if (c.own) {
            mine += c.stand.rootVehicle
            c.mob?.let { mine += it.rootVehicle }
        }
        val near = AABB(eye, eye.add(dir.scale(area.length))).inflate(area.radius + 1.0)
        return level.getEntities(p, near) { it.isAlive && ! it.isSpectator }.any { e ->
            FightRules.areaBlocks(e.rootVehicle in mine || isFought(e), e is ArmorStand, e is LivingEntity) && FightRules.inArea(area, eye, dir, e.boundingBox)
        }
    }

    /**
     * The options of [available] whose area holds something they must never hit now ([otherInArea], planned): none of
     * them is planned until it is clear, the next one you use for the creature is (or it is parked, as when nothing
     * reaches it).
     */
    private fun areaBlocked(p: LocalPlayer, available: Set<String>, margin: Double = 0.0): Set<String> =
        available.filterTo(HashSet()) { otherInArea(p, it, planned = true, margin = margin) }

    /**
     * The options of your hotbar [c]'s plan may use now ([FightRules.usableFor]): its current one while its planned area
     * holds nothing that is not yours ([near]), any other only once its area is clear by [FightRules.RETAKE_MARGIN]
     * ([wide]), so a creature wandering at the edge of an invisible zone does not swap the blade in and out.
     */
    private fun usableFor(c: TrackedCreature, near: Set<String>, wide: Set<String>) = FightRules.usableFor(available, book.plans[c.id]?.option, near, wide)

    /**
     * You pressed the key or button [value] of [type]: the attack or use button during a fight hands it to you, and so
     * does a hotbar key of another slot. The game changes the slot only in the next tick's key handling, after the
     * killer's tick, and would use the new item right then while the use key is held: the key goes up at once.
     */
    private fun onPhysicalPress(type: InputConstants.Type, value: Int) {
        if (phase == Phase.IDLE || UMinecraft.currentScreenObj != null) return
        val o = mc.options
        fun bound(key: KeyMapping) = (key as IKeyMapping).key.let { k -> k.type == type && k.value == value }
        if (bound(o.keyAttack) || bound(o.keyUse)) buttonPressed = true
        val index = o.keyHotbarSlots.indexOfFirst { bound(it) }
        val p = mc.player ?: return
        if (index >= 0 && slotWatch.hotbarKey(index, p.inventory.selectedSlot)) releaseUse()
    }

    private fun canRun() = enabled && AutoFishing.enabled && ! FishingRecorder.enabled

    /** The view never turns behind a screen: the killer's move and look end where they are (the turn back starts again once it closed). */
    private fun haltForScreen() {
        if (aim.moving(Owner.KILLER)) {
            aim.abort(Owner.KILLER)
            back?.aborted()
        }
        aim.abort(Owner.LOOK)
        looks.hold()
    }

    /**
     * The rod owed after the killer let go of a fight outside one ([offRodAt]: Auto Fishing stopped, an error) comes back
     * once due, never behind a screen nor before your reaction to one closing ([ScreenGate]); never once a fight has the
     * hand again, the Recorder records, or you took another slot meanwhile.
     */
    private fun offRodTick(p: LocalPlayer, now: Long) {
        if (offRodAt == 0L || now < offRodAt || UMinecraft.currentScreenObj != null || ! screenGate.ready(ScreenGate.Kind.SLOT, now)) return
        offRodAt = 0L
        if (phase != Phase.IDLE || FishingRecorder.enabled || p.isDeadOrDying || p.inventory.selectedSlot != offRodSlot) return
        rodSlotNow()?.let { p.inventory.selectedSlot = it }
    }

    /**
     * The rod is owed back after the killer let go of a fight at [now] outside one (Auto Fishing stopped, an error): with the
     * use key [held] up to then one of your waits from a key-up to a swap later, with the killer's loadout menu closed now
     * ([menuClosed]) one of your waits from a menu closing to the rod, else in a tick of its own; never in the tick the key
     * went up or the menu closed, and never behind a screen ([offRodTick]).
     */
    private fun oweRod(p: LocalPlayer, now: Long, held: Boolean, atOnce: Boolean, menuClosed: Boolean) {
        offRodAt = FightRules.offRodAt(now, held, atOnce, menuClosed, { draw(Action.KEY_UP_TO_SWAP, emptyList()) }) {
            draw(Action.MENU_CLOSE_TO_ROD, BehaviorProfile.current.menuCloseToRodMs)
        }
        offRodSlot = p.inventory.selectedSlot
    }

    /**
     * Your screens (not the killer's loadout menu), whether Auto Fishing runs or not: once one closed, the killer's next
     * click, slot change, /loadout and move each wait your reaction to the close, never the first tick after it.
     */
    private fun watchScreen(now: Long) {
        if (UMinecraft.currentScreenObj != null) {
            // one you opened while the loadout switch was under way is yours too, as is one still open after it
            if (FightRules.screenYours(LoadoutSwitcher.busy, LoadoutSwitcher.menuOpen())) screenOpen = true
            return
        }
        if (! screenOpen) return
        screenOpen = false
        screenClickAt = TimingModel.dueAt(now, draw(Action.SCREEN_CLOSE_TO_CLICK, emptyList()))
        screenGate.closed(now)
    }

    /** Forgets every fight; [brake]: a move of the killer still turning comes to rest instead of stopping dead (Auto Fishing stopped, not a world change). */
    private fun reset(brake: Boolean = false) {
        releaseUse()
        if (LoadoutSwitcher.busy) LoadoutSwitcher.cancel()
        if (brake) aim.stopFight() else aim.abortFight()
        handLag.clear()
        lagTarget = null
        oweReaction.clear()
        rodFree.clear()
        keepOff.clear()
        afterLagClick = Long.MIN_VALUE
        explicitAccept = Double.NaN
        offRodAt = 0L
        useWay.clear()
        downFor = - 1
        down.clear()
        freedAt = Long.MIN_VALUE
        freedReactAt = Long.MIN_VALUE
        reactOlder = false
        fallWaits.clear()
        viewTaken = false
        replanSwapDrawn = false
        wonBackAt = 0L
        // a look around's move still turning when Auto Fishing stopped is its own to bring to rest ([AutoFishing.OffHalt])
        if (brake) HumanAim.glances.drop() else dropGlance()
        policy.reset()
        looks.reset()
        back = null
        swapAt = 0L
        phase = Phase.IDLE
        rule = null
        target = null
        holdUntil = 0L
        childHoldUntil = 0L
        parkedLoadout.clear()
        turned = false
        acted = false
        lookOnly = false
        replan = false
        reactWeapon = false
        leadId = - 1
        givenUp.clear()
        firstHitAt.clear()
        firstEntryLead.clear()
        menuClickAt = 0L
        handedOver.clear()
        handoverWon = false
        pendingMelee = null
        usePending = false
        mouseTurn = 0.0
        buttonPressed = false
        book.clear()
        learnedRules.clear()
        triedLoadouts.clear()
        escalation.start("", 0L)
        hotbarTick = - 1
    }

    private fun say(msg: String) {
        if (announce.value) ChatUtils.modMessage("§7Auto Mob Killer: $msg")
    }

    private fun stopForCatch(name: String) {
        AutoFishing.stop("Rare catch: $name")
        NotificationManager.push(LayBaltQOL.MOD_NAME, "$name! Auto Fishing stopped, it's your fight.", 6000L)
        ChatUtils.showTitle("§c§l$name", "§7Auto Fishing stopped")
        USound.playSoundStatic(SoundEvents.NOTE_BLOCK_PLING, 1f, 1.2f)
    }

    /**
     * Auto Fishing's recast right after the catch comes before this fight, because its bobber kills most creatures by
     * itself: Rod rules by their mode and Learned rules like a Rod rule in Learned mode (when your recordings recast
     * through catches, or show the bobber killing this creature), Weapon rules when your recordings recast through
     * catches (you recast before your weapon fights too).
     */
    private fun recastFirst(r: KillRule, name: String): Boolean {
        val profile = BehaviorProfile.current
        return when (r.action) {
            KillAction.LEARNED -> recastLearned(profile, name)
            KillAction.ROD -> when (r.attack) {
                AttackMode.LEFT -> false
                AttackMode.RIGHT -> true
                AttackMode.AUTO -> recastLearned(profile, name)
            }
            KillAction.WEAPON -> profile.recastThroughCatches
            else -> false
        }
    }

    private fun recastLearned(profile: BehaviorProfile.Profile, name: String) =
        profile.recastThroughCatches || profile.habits[name]?.suggestion()?.let { it.action == KillAction.ROD && it.attack == AttackMode.RIGHT } == true

    private fun isLearned(c: TrackedCreature) = KillRules.ruleFor(c.creature, c.name).action == KillAction.LEARNED

    /** The rule [c] is fought with now: its Rod or Weapon rule, or its Learned plan's; null when it is not fought (Ignore, Stop, parked). */
    private fun ruleOf(c: TrackedCreature): KillRule? {
        val r = KillRules.ruleFor(c.creature, c.name)
        return when (r.action) {
            KillAction.ROD, KillAction.WEAPON -> r
            KillAction.LEARNED -> learnedRules[c.id]
            else -> null
        }
    }

    /** Your creatures that the rules want dead and something you have reaches. */
    private fun targets() = SeaCreatureTracker.ownAlive.filter { c -> c.id !in givenUp && c.id !in handedOver && ruleOf(c) != null }

    /**
     * Targets fought with one equip: weapon and loadout only matter for Weapon rules (old Rod rules may still carry them);
     * a Learned target joins the group of the item its plan uses, whatever loadout it would bring.
     */
    private fun groupTargets(r: KillRule) = targets().filter { o -> ruleOf(o)?.let { FightRules.sameGroup(r, it, isLearned(o)) } == true }

    /** Names of creatures that others split off as they die (the profile's children's parents). */
    private fun parentNames(): Set<String> {
        val profile = BehaviorProfile.current
        if (parentsOf !== profile) {
            parentsOf = profile
            parents = profile.habits.values.mapNotNullTo(HashSet()) { h -> h.parent.takeIf { it.isNotEmpty() } }
        }
        return parents
    }

    private fun onTick() {
        val p = mc.player ?: return
        val now = System.currentTimeMillis()

        watchScreen(now)
        offRodTick(p, now)
        if (! canRun() || p.isDeadOrDying) {
            if (phase != Phase.IDLE || handedOver.isNotEmpty() || hold.holding || book.plans.isNotEmpty() || book.parked.isNotEmpty()) {
                // the Fishing Recorder writes what happens from now on as yours: like a death, everything of the killer's
                // stops at once (no held key, motion or rest nudge of it in your lines, no slot change either)
                val recorder = FishingRecorder.enabled
                // your hand moving, clicking, taking a slot or turning meanwhile has the fight at once, as in a fight
                val watching = phase != Phase.IDLE && phase != Phase.LOADOUT && phase != Phase.RESTORE_LOADOUT
                val takeover = watching && ! recorder && ! p.isDeadOrDying && userTookOver(p, now) != null
                buttonPressed = false
                // your hand reacts to Auto Fishing stopping (a rare catch, its key) a moment later, not in that tick: nothing
                // new starts meanwhile, then the key goes up, the move still turning comes to rest and the rod comes back (not
                // when you took another slot meanwhile). At once when you died, the Recorder started or you took over.
                val exit = FightRules.offExit(p.isDeadOrDying, recorder, takeover, phase != Phase.IDLE && phase != Phase.REACT, slotWatch.changed(p.inventory.selectedSlot))
                // drawn within your slowest key-up after a death: a key held meanwhile stays down no longer than yours did
                if (! exit.atOnce && ! offReaction.ready(now, true) { offReactionMs() }) {
                    // a screen opened meanwhile: nothing turns behind it, as in a fight
                    if (UMinecraft.currentScreenObj != null) {
                        haltForScreen()
                        if (! LoadoutSwitcher.busy) releaseUse()
                    }
                    // the key still held meanwhile goes up at once when something it must not hit comes under the crosshair
                    // or onto the ability's way (each repeat is checked again as the game uses the item, [guardUse])
                    else if (hold.holding && useBlocked(p, useId)) {
                        useWay.block()
                        releaseUse()
                    }
                    return
                }
                if (takeover) KillerTrace.takeover("off")
                val held = hold.holding
                val menu = LoadoutSwitcher.busy && LoadoutSwitcher.menuOpen()
                reset(brake = ! exit.atOnce)
                // after the reset, so a cancelled loadout switch cannot swap to the weapon again; a key held up to now went up
                // in this tick and the killer's menu closed in it: your swap after either came a moment after it, never in
                // its tick, and none goes out behind a screen of yours ([offRodTick])
                if (exit.restore) oweRod(p, now, held, exit.atOnce, menu)
            }
            // a move of the killer's still braking after its fight (or a look) is no motion of yours in the Recorder's lines
            if (FishingRecorder.enabled && aim.movingInFight()) aim.abortFight()
            offReaction.clear()
            return
        }
        offReaction.clear()
        // the use key went up without the killer (a screen let every key go, or you did): the hold starts over, and a
        // bobber its last repeat still casts is the mod's, as after [releaseUse]
        if (hold.holding && ! mc.options.keyUse.isDown) {
            hold.stop()
            killerUseAt = now
            keyUpAt = now
            nextClickAt = TimingModel.dueAt(now, holdGap(p.mainHandItem.skyblockId))
        }
        if (UMinecraft.currentScreenObj != null) {
            // the view never turns behind a screen (a loadout menu included, its hooks do not sample meanwhile): the move
            // ends where it is instead of finishing after the screen closed, and the fight aims anew
            haltForScreen()
            if (! LoadoutSwitcher.busy) {
                releaseUse()
                return
            }
        }
        // Auto Fishing clicked the rod this tick: a swap or a click now would change what that click does. Its click is the
        // rod busy, whichever feature's tick ran first: an owed /loadout goes a reaction after the rod is free again
        if (AutoFishing.usedRodThisTick()) {
            if (phase == Phase.AFTER_KILL) rodFree.ready(now, false) { 0L }
            oweReaction.clear()
            return
        }
        lagTick(p, now)
        // looks go out only while a target is fought: between targets a look comes to rest (its return may follow at the
        // next one), after the last death the turn back owns the view; one still turning at a death or a new creature comes
        // to rest with the hand's reaction to it ([HandLag])
        val lagging = handLag.stopAt != HandLag.NONE
        when (phase) {
            Phase.FIGHT, Phase.SWITCH -> Unit
            Phase.REACT, Phase.SWAP, Phase.LOADOUT -> if (! lagging) aim.stop(Owner.LOOK)
            else -> {
                looks.hold()
                if (! lagging) aim.stop(Owner.LOOK)
            }
        }

        refreshPlans(p, now)
        // a loadout kept on for parked creatures: none of them left, or the Fishing Loadout is back
        if (book.parked.isEmpty() || ! owesFishingLoadout) parkedLoadout.clear()

        val watching = phase != Phase.IDLE && phase != Phase.LOADOUT && phase != Phase.RESTORE_LOADOUT
        val takeover = if (watching) userTookOver(p, now) else null
        buttonPressed = false
        // the slot in hand is the killer's from here on: no fight is watched (a loadout may change it), or your change is handled
        if (! watching || takeover != null) slotWatch.note(p.inventory.selectedSlot)
        // you go on moving or turning in a cleanup whose view you took already: nothing new is handed to you
        val again = viewTaken && (takeover == Takeover.KEYS || takeover == Takeover.MOUSE) && targets().isEmpty()
        if (takeover != null && ! again) {
            releaseUse()
            // your hand has it now: nothing of the killer's hand lags behind
            handLag.clear()
            KillerTrace.takeover(takeover.name.lowercase())
            val ids = targets().map { it.id } + listOfNotNull(target?.id?.takeIf { target?.dead == false })
            mouseTurn = 0.0
            looks.hold()
            dropGlance()
            when {
                ids.isNotEmpty() -> {
                    // your hand has the view now: the killer's move stops dead, no tail
                    aim.abortFight()
                    back = null
                    rule = null
                    target = null
                    holdUntil = 0L
                    turned = false
                    acted = false
                    lookOnly = false
                    ChatUtils.modMessage("§7Auto Mob Killer: you took over, this fight is yours. Auto Fishing waits until those creatures are dead.")
                    handedOver += ids
                    handedOverAt = now
                    phase = Phase.IDLE
                    return
                }
                // only the cleanup after the killer's own fight is left. With Lock Mouse you could not turn back to the
                // water yourself, so it goes on unchanged (the turn back included).
                AutoFishing.shouldLockMouse() -> Unit
                // you are clicking with what you hold, or took another item: keep it in your hand, the Fishing Loadout
                // comes back later
                takeover == Takeover.BUTTON || takeover == Takeover.SLOT -> {
                    aim.abortFight()
                    finishFight()
                    return
                }
                // you move or turn: your view is left alone (no turn back), and the rod and loadout still come back at the
                // cleanup's own pace, a while after the fight's end, not in the tick you moved
                else -> {
                    aim.abortFight()
                    back = null
                    viewTaken = true
                    rule = null
                    target = null
                    holdUntil = 0L
                    when (phase) {
                        Phase.TURN_BACK -> finishFight()
                        Phase.AFTER_KILL, Phase.RESTORE_ROD -> Unit
                        else -> afterKill(now)
                    }
                    return
                }
            }
        }

        when (phase) {
            Phase.IDLE -> {
                val first = targets().minByOrNull { it.spawnedAt }
                if (handoverWon) {
                    handoverWon = false
                    // the fight you took is won: the rod back in hand and the view back to the water, then Auto Fishing goes
                    // on; like after a kill of the killer's, a while after the death, not in its tick
                    if (first == null && turnBack.value) {
                        wonBackAt = TimingModel.dueAt(now, draw(Action.AFTER_KILL, BehaviorProfile.current.afterKillMs, afterKillStreak))
                        wonSlot = p.inventory.selectedSlot
                    }
                }
                if (wonBackAt > 0L) when (FightRules.wonBack(now, wonBackAt, first != null, p.inventory.selectedSlot == wonSlot)) {
                    WonBack.WAIT -> return
                    WonBack.BACK -> {
                        if (! rodBackReady(p, now)) return
                        wonBackAt = 0L
                        back = planTurnBack(now)
                        return rodBack(now)
                    }
                    WonBack.DROP -> wonBackAt = 0L
                }
                if (first == null) {
                    // a catch that brought nothing to fight (someone else's creature, one the rules leave alone, one already
                    // dead, one out of reach): Auto Fishing goes on instead of waiting out the hold
                    if (now < holdUntil && (now - AutoFishing.lastCatchAt > NO_SPAWN_MS || SeaCreatureTracker.creatures.values.any { it.own && it.spawnedAt >= AutoFishing.lastCatchAt })) holdUntil = 0L
                    // a weapon loadout left on by a fight that ended without the killer (you took over, a reset), or kept
                    // on for parked creatures that stayed out of reach; not while Auto Fishing reels in or is about to
                    // cast (the menu holds it: that bite or recast would be lost), a later tick tries again. Like your /loadout
                    // after a kill, a while after the moment it may (a parked creature died, the rod is free after a cast, the
                    // loadout's keep time is over), not in that tick
                    val may = owesFishingLoadout && now >= holdUntil && now - lastOweCheckAt > OWE_CHECK_MS && ! userFights() && ! LoadoutSwitcher.busy &&
                        ! AutoFishing.rodBusy() && AutoFishing.isFishingRod(p.mainHandItem) && parkedLoadout.restoreDue(now, book.parked.isNotEmpty())
                    // and after a screen of yours closed, your reaction to that as well
                    if (oweReaction.ready(now, may) { draw(Action.DEATH_TO_RESTORE, BehaviorProfile.current.deathToRestoreMs) } && screenGate.ready(ScreenGate.Kind.COMMAND, now)) {
                        oweReaction.clear()
                        lastOweCheckAt = now
                        restoreLoadout(now) { finishFight() }
                    }
                    return
                }
                // a fight started with a weapon in hand (the Flay) never remembers it as the rod
                val hand = p.mainHandItem
                val (slot, id) = FightRules.rodSlotAtStart(
                    AutoFishing.isFishingRod(hand), p.inventory.selectedSlot, hand.skyblockId,
                    rodSlot, rodId, Inventory.isHotbarSlot(rodSlot) && AutoFishing.isFishingRod(p.inventory.getItem(rodSlot))
                )
                rodSlot = slot
                rodId = id
                fishingYaw = p.yRot
                fishingPitch = p.xRot
                if (aim.home == null) aim.home = View(p.yRot.toDouble(), p.xRot.toDouble())
                aim.clearRestAnchor()
                policy.reset()
                looks.reset()
                swapAt = 0L
                back = null
                mouseTurn = 0.0
                react(first)
            }

            Phase.REACT -> {
                // died before the fight (the recast's bobber kills most of them by itself), was given back or parked
                val first = targets().minByOrNull { it.spawnedAt } ?: return afterKill(now)
                // the creature this reaction was for died (or was given back, parked): the next one gets a reaction of its
                // own, one already there from this moment on, its own spawn long past ([freeHand])
                if (first.id != reactId) {
                    if (targets().none { it.id == reactId }) freeHand(now)
                    return react(first)
                }
                // the creature a start with no swap was timed for (the group's nearest, [olderStart]) died or was given back
                // before the fight started: that death frees your hand too, and the next one is timed from it, never a turn
                // timed for one that is gone
                if (FightRules.olderGone(olderFor) { id -> targets().any { it.id == id } }) {
                    freeHand(now)
                    return react(first)
                }
                // one already there with the use key held through that moment: from its key-up, one of your waits to a swap,
                // or with its item in hand already the plan's own gap ([FightRules.olderReact])
                if (reactOlder && nextAt == Long.MAX_VALUE) {
                    if (hold.holding) {
                        if (handLag.keyUpAt != HandLag.NONE) return
                        releaseUse()
                    }
                    val from = maxOf(keyUpAt, freedAt)
                    if (reactSwaps && freedReactAt == Long.MAX_VALUE) freedReactAt = TimingModel.dueAt(from, draw(Action.KEY_UP_TO_SWAP, emptyList()))
                    nextAt = olderStart(p, first, FightRules.olderReact(reactSwaps, false, freedReactAt, from, now) { olderGap(p, first) }, from, now)
                }
                if (waitRecast) {
                    val cast = recastFor(first)
                    val rod = ruleOf(first)?.action == KillAction.ROD
                    // a recast still to come is weighed once against what it goes before: the rod's first hit drawn from
                    // the spawn, a weapon's swap your wait after the recast's due time. Whichever is due first goes, the
                    // other waits for the hand (one use a tick); no wait of a fixed length
                    val due = AutoFishing.recastDueAt
                    val anchor = maxOf(first.spawnedAt, book.unparkedAt[first.id] ?: 0L)
                    if (cast == null && due > 0L) {
                        if (recastLead == NO_LEAD) recastLead = if (rod) leadHit(first, now) else TimingModel.dueAt(due, recastSwapGap())
                        // a weapon's swap also waits for a recast Auto Fishing still holds past the swap's time (its flight's
                        // way, a screen), as long as one then is still this catch's ([recastFor]): you recast before your swap
                        // 39 times of 39, and the swap would take the rod and that recast with it
                        if (FightRules.waitsForRecast(now, due, recastLead, true, if (rod) recastLead else FightRules.recastHeldUntil(anchor, recastLead))) {
                            if (now >= recastLead) recastHeldPast = true
                            return
                        }
                    }
                    waitRecast = false
                    if (rod) {
                        // no recast came first (none was pending, or the hit drawn from the spawn was due before it): that
                        // first hit of yours after a spawn, drawn from it alone; only one that would fall before now is drawn again
                        val led = cast == null && takeLeadHit(first)
                        val hitAt = firstRodHitAt(first, now)
                        // nothing moves before the bobber had its chance: a creature it kills meanwhile ends the fight
                        // here, never turned to (nor back from), and Auto Fishing is not held. A turn, if one is needed,
                        // could start in time for the first hit.
                        val chance = cast?.let { TimingModel.dueAt(it, draw(Action.BOBBER_DEATH, BehaviorProfile.current.bobberDeathMs)) } ?: 0L
                        // your first hits came a reaction after the crosshair got onto the creature, inside that wait:
                        // the turn gets there that reaction early, and the hit waits the same reaction ([BoxEntry])
                        val turnAt = FightRules.turnAtForHit(hitAt, turnMsTo(first)) { firstEntryLead.getOrPut(first.id) { draw(Action.BOX_ENTRY_CLICK, emptyList()) } }
                        // and your reaction to the spawn as for a weapon, unless the first hit drawn from the spawn is the
                        // one that goes ([led]): that hit holds your reaction to the spawn already, so with the recast held
                        // past it the fight starts at its time, no second reaction on top ([FightRules.rodReactAt]). Else
                        // one that fell before now is drawn again until it falls after it, never piled where a wait ended
                        if (cast == null) nextAt = FightRules.rodReactAt(now, nextAt, led, recastLead) {
                            FightRules.reactAfter(anchor, now, { draw(Action.SPAWN_REACTION, BehaviorProfile.current.spawnReactionMs) }) { draw(Action.SPAWN_REACTION, BehaviorProfile.current.spawnReactionMs) }
                        }
                        nextAt = maxOf(nextAt, chance, turnAt)
                    }
                    // a weapon: your swap came a while after your recast (p50 750 ms, never the tick after it), so the swap is
                    // timed from the recast, a slow one too, by the wait drawn against its due time; with none, at the swap's
                    // own time, or once a recast held past it was waited for in vain your reaction after that wait
                    else if (cast != null || recastLead != NO_LEAD && leadStreak == null) nextAt = FightRules.swapAfterRecast(cast, recastLead, recastHeldPast, ::recastSwapGap) { swapReactAfter(anchor, now) }
                    // no recast came: your swap reaction from the spawn, drawn again until it falls after now, never piled there
                    else if (now >= nextAt) nextAt = swapReactAfter(anchor, now)
                }
                if (now < nextAt) return
                // a use key still held from the fight before goes up first, the swap a moment after it ([lagTick]); and after
                // a screen of yours closed, the swap waits your reaction to the close
                if (hold.holding && handLag.keyUpAt != HandLag.NONE || ! swapReady(ruleOf(first), p, now)) return
                startGroup(now)
            }

            Phase.LOADOUT, Phase.RESTORE_LOADOUT -> Unit // LoadoutSwitcher's callback moves on

            Phase.SWAP -> {
                // the group's last creature died while the hand still went to the next one after the death before: its fight
                // is over at this death, as it is when the target was the last; the wait after your kill, your turn back and
                // your reaction to a creature of another group left are timed from it (yours were), not from the end of a
                // gap to a creature that no longer exists. After a loadout any creature left is planned again ([replan])
                val anyLeft = targets().isNotEmpty()
                if (FightRules.swapOver(replan, rule?.let { groupTargets(it).isNotEmpty() } ?: anyLeft, anyLeft)) return afterKill(now)
                if (now < nextAt) return
                if (replan) {
                    // after a loadout: the lead target's plan again, with the hotbar the loadout left
                    val all = targets()
                    val lead = all.firstOrNull { it.id == leadId } ?: all.minByOrNull { it.spawnedAt } ?: return afterKill(now)
                    val r = ruleOf(lead) ?: return afterKill(now)
                    // the loadout moved the weapon (or brought it): your swap after a menu closed came a moment later, never
                    // in its tick
                    val slot = slotFor(r, p)
                    if (! replanSwapDrawn && slot != null && Inventory.isHotbarSlot(slot) && slot != p.inventory.selectedSlot) {
                        replanSwapDrawn = true
                        nextAt = FightRules.afterMenu(menuClosedAt, menuDoneAt) { draw(Action.MENU_CLOSE_TO_SWAP, emptyList()) }
                        if (now < nextAt) return
                    }
                    if (! swapReady(r, p, now)) return
                    replan = false
                    rule = r
                    return swapFor(r, now)
                }
                val r = rule ?: return startGroup(now)
                // your /loadout after a screen of yours closed came a reaction after the close
                if (needsLoadout(r)) {
                    if (screenGate.ready(ScreenGate.Kind.COMMAND, now)) equipLoadout(r)
                }
                else pickTarget(now)
            }

            Phase.FIGHT -> fight(now)

            Phase.SWITCH -> {
                val c = target
                if (c == null || c.dead || c.stand.isRemoved) return dropTarget(now)
                // the view coming where the other plan needs it is watched in these ticks too: an entry meanwhile is waited
                // a reaction from then, a view there all along waits none (your swap's own wait to the first click holds it)
                boxEntry.update(now, viewOnFor(p, c, now)) { draw(FightRules.entryAction(glanceNeed(c, now)), emptyList()) }
                if (now < nextAt) return
                // the use key held for the old plan goes up first, the swap a moment after it ([lagTick])
                if (hold.holding && handLag.keyUpAt != HandLag.NONE) return
                val r = rule ?: return startGroup(now)
                // the other plan's fight starts: a move still turning for the old one has come to rest by now
                if (handLag.stopAt != HandLag.NONE) {
                    handLag.stopped()
                    aim.stopFight()
                }
                if (! switchSwapped) {
                    val slot = slotFor(r, p)
                    val swaps = slot != null && slot != p.inventory.selectedSlot
                    if (swaps && ! screenGate.ready(ScreenGate.Kind.SLOT, now)) return
                    switchSwapped = true
                    if (slot != null && swaps) {
                        selectSlot(p, slot)
                        acted = true
                        swapAt = now
                        // the first click after the swap, like yours (it may go out in the swap tick)
                        nextClickAt = maxOf(nextClickAt, TimingModel.dueAt(now, swapClickMs(p.inventory.getItem(slot).skyblockId)))
                        clickFrom = now
                    }
                }
                phase = Phase.FIGHT
                fight(now)
            }

            Phase.AFTER_KILL -> {
                // a creature of yours left is reacted to: one split off the one just killed (they come up within moments,
                // your child lags) from its own spawn, one that was there already your reaction from the death ([react],
                // [Action.DEATH_TO_SWAP]); the rod (or /loadout) waits for none that never comes ([afterKill])
                targets().minByOrNull { it.spawnedAt }?.let { return react(it) }
                driveTurnBack(now)
                if (now < nextAt) return
                // the use key you held through the kill went up before the item swap, a moment before it ([lagTick])
                if (hold.holding) {
                    if (handLag.keyUpAt != HandLag.NONE) return
                    releaseUse()
                }
                // with creatures parked (you may fight them in a moment) a weapon loadout stays on; IDLE puts it back once
                // none of them came in reach for a while (counted from the first fight that kept it, not from every
                // fight against new catches meanwhile)
                if (book.parked.isNotEmpty()) {
                    if (owesLoadout()) parkedLoadout.fightEnded(now)
                    return rodBack(now)
                }
                // like you: /loadout first, then the rod a moment after the menu closed
                if (! owesLoadout()) return rodBack(now)
                // Auto Fishing went on through a rod fight: the menu waits for its reel or recast, as in IDLE, and goes a
                // reaction after the rod is free (never in the tick after its cast)
                if (! rodFree.ready(now, ! AutoFishing.rodBusy()) { draw(Action.DEATH_TO_RESTORE, BehaviorProfile.current.deathToRestoreMs) }) return
                // and after a screen of yours closed, your reaction to the close
                if (! screenGate.ready(ScreenGate.Kind.COMMAND, now)) return
                restoreLoadout(now) { at ->
                    phase = Phase.RESTORE_ROD
                    nextAt = FightRules.afterMenu(at, menuDoneAt) { draw(Action.MENU_CLOSE_TO_ROD, BehaviorProfile.current.menuCloseToRodMs) }
                    // your turn back after a /loadout came after its menu closed
                    back = planTurnBack(at)?.also { it.notBefore(menuDoneAt) }
                }
            }

            Phase.RESTORE_ROD -> {
                if (targets().isEmpty()) driveTurnBack(now)
                if (now < nextAt) return
                rodBack(now)
            }

            Phase.TURN_BACK -> {
                targets().minByOrNull { it.spawnedAt }?.let { return react(it) }
                val b = back ?: return finishFight()
                driveTurnBack(now)
                // one move and it is over, wherever it ended (a screen that stopped it starts it again once closed)
                if (b.done && ! aim.movingInFight()) finishFight()
            }
        }
    }

    /**
     * Plans every Learned creature of yours for this tick ([PlanBook.update]): keeps, switches, parks or brings back its
     * plan, and resolves the plan to the rule it is fought with.
     */
    private fun refreshPlans(p: LocalPlayer, now: Long) {
        val alive = SeaCreatureTracker.ownAlive
        val ids = alive.mapTo(HashSet()) { it.id }
        book.retain(ids)
        learnedRules.keys.retainAll(ids)
        if (alive.isEmpty()) return
        scanHotbar(p)
        // an area ability with something not yours in its reach is no option now: the plan moves on to the next one you
        // use for the creature, or parks it when nothing else reaches it, never waiting on that ability; it comes back
        // only once that area is clear by a margin
        val near = areaBlocked(p, available)
        val wide = areaBlocked(p, available, FightRules.RETAKE_MARGIN)
        for (c in alive) {
            if (c.id in givenUp || c.id in handedOver || ! isLearned(c)) {
                book.forget(c.id)
                learnedRules.remove(c.id)
                continue
            }
            updatePlan(p, c, now, usableFor(c, near, wide))
        }
    }

    private fun updatePlan(p: LocalPlayer, c: TrackedCreature, now: Long, usable: Set<String> = usableFor(c, areaBlocked(p, available), areaBlocked(p, available, FightRules.RETAKE_MARGIN))) {
        val (hd, dy) = geo(p, c)
        val change = book.update(c.id, now, hd, dy, usable) { excluded, shrink -> choose(c, hd, dy, excluded, shrink, usable) }
        when (change) {
            PlanBook.Change.NONE -> Unit
            PlanBook.Change.PARKED -> {
                learnedRules.remove(c.id)
                say("${c.name} parked: ${FightRules.where(hd, dy)}")
            }
            else -> {
                val plan = book.plans[c.id] ?: return
                learnedRules[c.id] = FightRules.ruleForPlan(plan, FightRules.learnedLoadout(BehaviorProfile.current.habits[c.name], plan.option))
                if (change == PlanBook.Change.UNPARKED) {
                    // the loadout kept on for it is used soon
                    parkedLoadout.unparked(now)
                    say("${c.name} back in reach: ${plan.label}, %.1f m".format(hd))
                }
                else say("${c.name}: ${plan.label}, %.1f m".format(hd))
            }
        }
    }

    /**
     * [WeaponPlanner.choose] for [c] with what your hotbar holds: its group (a child's is its parent's), its max hp, the
     * item in hand for ties. The rod is skipped when it would take more than 8 hits and you fought this creature with
     * something else that reaches it now. [usable]: the options of your hotbar that may be used now. An error plans the
     * rod (reported once).
     */
    private fun choose(c: TrackedCreature, hd: Double, dy: Double, excluded: Set<String>, shrink: Boolean, usable: Set<String> = available): Plan? {
        val profile = BehaviorProfile.current
        return try {
            val habit = profile.habits[c.name]
            val group = c.creature?.groupId ?: habit?.group?.takeIf { it.isNotEmpty() } ?: SeaCreatures.byName(c.parent)?.groupId.orEmpty()
            val maxHp = if (c.maxHp > 0.0) c.maxHp else habit?.maxHp ?: 0.0
            val plan = WeaponPlanner.choose(profile, c.name, group, maxHp, hd, dy, usable, excluded, heldOption, shrink)
            if (plan?.option == ROD && habit != null && FightRules.rodTooSlow(habit, usable - excluded))
                WeaponPlanner.choose(profile, c.name, group, maxHp, hd, dy, usable, excluded + ROD, heldOption, shrink) ?: plan
            else plan
        }
        catch (e: Exception) {
            if (! warnedChoose) {
                warnedChoose = true
                ChatUtils.modMessage("§eAuto Mob Killer could not plan a fight (${e.message}), it uses the rod.")
            }
            WeaponPlanner.rodPlan.takeIf { ROD in usable && ROD !in excluded && WeaponPlanner.inEnvelope(it, hd, dy, shrink) }
        }
    }

    /** What your hotbar holds and what is in your hand, read once a tick. */
    private fun scanHotbar(p: LocalPlayer) {
        if (p.tickCount == hotbarTick) return
        hotbarTick = p.tickCount
        val ids = LinkedHashSet<String>()
        for (i in 0 .. 8) {
            val stack = p.inventory.getItem(i)
            if (stack.isEmpty) continue
            if (AutoFishing.isFishingRod(stack)) ids += ROD
            else stack.skyblockId.takeIf { it.isNotEmpty() }?.let(ids::add)
        }
        available = ids
        val hand = p.mainHandItem
        heldOption = if (AutoFishing.isFishingRod(hand)) ROD else hand.skyblockId.ifEmpty { null }
    }

    /** Horizontal distance and height of [c] from your feet, the way your recordings measured them. */
    private fun geo(p: LocalPlayer, c: TrackedCreature): Pair<Double, Double> {
        val pos = c.position
        return hypot(pos.x - p.x, pos.z - p.z) to pos.y - p.y
    }

    private fun inEnvelope(p: LocalPlayer, plan: Plan, c: TrackedCreature) = geo(p, c).let { (hd, dy) -> WeaponPlanner.inEnvelope(plan, hd, dy) }

    /**
     * Your reaction to the oldest target, timed from its spawn (or from when it came back in reach): with a weapon until
     * the swap (after Auto Fishing's recast when that comes first; your swaps came a good while after the catch), with
     * the rod until the fight starts. One that was there already when your hand was freed for it (a death ended the group
     * before, or the creature the reaction was for), its spawn long past, comes your reaction from that moment instead
     * ([freedReactAt]: your swaps to another creature after a death), never in the ticks right after it.
     */
    private fun react(first: TrackedCreature) {
        val declared = KillRules.ruleFor(first.creature, first.name)
        val r = ruleOf(first) ?: declared
        val profile = BehaviorProfile.current
        val now = System.currentTimeMillis()
        back = null
        lookOnly = false
        oweReaction.clear()
        // a new fight turns the view again: its end turns back
        viewTaken = false
        val anchor = maxOf(first.spawnedAt, book.unparkedAt[first.id] ?: 0L)
        reactOlder = FightRules.olderThanFree(anchor, freedAt)
        // a creature split off yours is no catch: no recast comes for it, nor for one whose recast came long ago
        waitRecast = ! reactOlder && first.parent.isEmpty() && recastFirst(declared, first.name)
        recastLead = NO_LEAD
        leadStreak = null
        recastSwapMs = NO_LEAD
        recastHeldPast = false
        reactWeapon = declared.action == KillAction.LEARNED && r.action != KillAction.ROD
        reactId = first.id
        val alive = SeaCreatureTracker.ownAlive.map { it.id }.toSet()
        firstHitAt.keys.retainAll(alive)
        firstEntryLead.keys.retainAll(alive)
        fallWaits.keys.retainAll(alive)
        menuClickAt = 0L
        phase = Phase.REACT
        val wait = if (r.action != KillAction.WEAPON) draw(Action.SPAWN_REACTION, profile.spawnReactionMs) else draw(Action.SWAP_REACTION, profile.swapReactionMs)
        val p = mc.player
        reactSwaps = p == null || swapsFor(p, r)
        // one already there: your swap to it after that death, or with its item in hand already the plan's own gap from it
        // (from the key-up instead while the use key held through it is still down, [Phase.REACT])
        olderClickAt = Long.MIN_VALUE
        olderFor = - 1
        nextAt = when {
            ! reactOlder -> TimingModel.dueAt(anchor, wait)
            p == null -> freedReactAt
            else -> maxOf(keyUpAt, freedAt).let { from -> olderStart(p, first, FightRules.olderReact(reactSwaps, freedReactAt == Long.MAX_VALUE, freedReactAt, from, now) { olderGap(p, first) }, from, now) }
        }
        // a turn back (or a move or look towards the creature before) still turning comes to rest after your reaction to the
        // new creature, not in the tick it came up (yours ran on like at any other moment), and before the fight starts;
        // the new fight turns when it needs to
        if (aim.movingInFight())
            handLag.stopBy(TimingModel.dueAt(now, TimingModel.draw(Action.SPAWN_REACTION, profile.spawnReactionMs, null, Random, (nextAt - now).toDouble().takeIf { it > 0.0 })))
    }

    /** Equips what the oldest remaining target needs (like you: the weapon first, then its loadout), then fights every target with the same rule. */
    private fun startGroup(now: Long) {
        // the fight starts: a move still turning from before ([react]) has come to rest by now
        if (handLag.stopAt != HandLag.NONE) {
            handLag.stopped()
            aim.stopFight()
        }
        val first = targets().minByOrNull { it.spawnedAt } ?: return afterKill(now)
        val r = ruleOf(first) ?: return afterKill(now)
        rule = r
        leadId = first.id
        loadoutTried = false
        replan = false
        // still in a weapon loadout from an earlier fight that never got the Fishing Loadout back
        if (r.action == KillAction.WEAPON && r.loadout != 0 && r.loadout == LoadoutSwitcher.lastEquipped && r.loadout != KillRules.data.get().fishingLoadout) owesFishingLoadout = true
        swapFor(r, now)
    }

    /** The group's weapon loadout is not on yet and was not tried in this group, nor for its first creature in this fight. */
    private fun needsLoadout(r: KillRule) = r.action == KillAction.WEAPON && r.loadout != 0 && r.loadout != LoadoutSwitcher.lastEquipped && ! loadoutTried &&
        triedLoadouts[leadId]?.contains(r.loadout) != true

    private fun equipLoadout(r: KillRule) {
        // the menu would let the key go anyway; a held use key must never meet the items the loadout moves
        releaseUse()
        handLag.dropClick()
        loadoutTried = true
        triedLoadouts.getOrPut(leadId) { HashSet() } += r.loadout
        acted = true
        phase = Phase.LOADOUT
        say("equipping loadout ${r.loadout}")
        val back = KillRules.data.get().fishingLoadout
        LoadoutSwitcher.equip(r.loadout) { ok ->
            // a click without Hypixel's confirmation may still have gone through: unless the Fishing Loadout is known to
            // be on, it is owed (putting back one that is already on costs a menu trip, no click)
            if (r.loadout != back && (ok || LoadoutSwitcher.lastEquipped != back)) owesFishingLoadout = true
            // what the loadout left in hand is the killer's doing, not yours
            mc.player?.let { slotWatch.note(it.inventory.selectedSlot) }
            // the weapon (and a Learned plan) is picked again at once, in case the loadout moved or changed it. Your time
            // from a menu closing to your first click holds back that click, and any turn of yours was inside it, timed
            // to get there before it ([FightRules.menuTurnAt]): a big turn as the menu closes, a small one a while after.
            replan = true
            phase = Phase.SWAP
            // timed from the menu's close like yours, not from Hypixel's confirmation coming after it (yours came before
            // your close 22 times of 22): what fell before the switch was over is drawn again after it
            val done = System.currentTimeMillis()
            val at = menuClose(done)
            nextAt = done
            menuClosedAt = at
            menuDoneAt = menuDone(at, done)
            replanSwapDrawn = false
            menuClickAt = FightRules.afterMenu(at, menuDoneAt) { draw(Action.MENU_CLOSE_TO_CLICK, BehaviorProfile.current.menuCloseToClickMs) }
        }
    }

    /** When the switch's menu closed, at or before [done] (its callback); [done] itself when it never opened. */
    private fun menuClose(done: Long) = LoadoutSwitcher.closedAt.takeIf { it in 1 .. done } ?: done

    /** When the switch closed at [closedAt] was over, its callback at [done] ([FightRules.menuDone]: by ticks, not by ms). */
    private fun menuDone(closedAt: Long, done: Long) = FightRules.menuDone(closedAt, done, LoadoutSwitcher.closedTicksAgo())

    /**
     * Puts the Fishing Loadout back when a fight left another one on, then [next] with the moment its menu closed (at
     * once when none is owed; [menuDoneAt] is when the switch was over).
     */
    private fun restoreLoadout(now: Long, next: (Long) -> Unit) {
        if (! owesLoadout()) {
            menuDoneAt = now
            return next(now)
        }
        releaseUse()
        handLag.dropClick()
        // the menu trip is the killer's: the rod comes back after it before Auto Fishing goes on
        lookOnly = false
        val back = KillRules.data.get().fishingLoadout
        phase = Phase.RESTORE_LOADOUT
        say("equipping fishing loadout $back")
        LoadoutSwitcher.equip(back) { _ ->
            // one try per fight: a failed switch is reported in chat and not retried over and over
            owesFishingLoadout = false
            parkedLoadout.clear()
            // what the loadout left in hand is the killer's doing, not yours
            mc.player?.let { slotWatch.note(it.inventory.selectedSlot) }
            val done = System.currentTimeMillis()
            val at = menuClose(done)
            menuDoneAt = menuDone(at, done)
            next(at)
        }
    }

    /** A fight left a weapon loadout on and the Fishing Loadout is not back yet (forgotten once it is, or with none set). */
    private fun owesLoadout(): Boolean {
        val back = KillRules.data.get().fishingLoadout
        if (back == 0 || back == LoadoutSwitcher.lastEquipped) owesFishingLoadout = false
        return owesFishingLoadout
    }

    /** The hotbar slot [r] fights with: its weapon's (null when not in the hotbar), the item in hand for none, else the rod's. */
    private fun slotFor(r: KillRule, p: LocalPlayer): Int? = when (r.action) {
        KillAction.WEAPON -> if (r.weaponId.isEmpty()) p.inventory.selectedSlot else PlayerUtils.findHotbarSlot { it.skyblockId == r.weaponId }
        else -> rodSlotNow()
    }

    private fun swapFor(r: KillRule, now: Long) {
        val p = mc.player ?: return
        val slot = slotFor(r, p) ?: run {
            // never a right click with another item than the rule's (explicitStyle): it only hits with what you hold. A
            // loadout still to come may bring the weapon.
            if (r.action == KillAction.WEAPON && ! needsLoadout(r) && warnedWeapon.add(r.weaponId))
                ChatUtils.modMessage("§eAuto Mob Killer: ${r.weaponName.ifEmpty { r.weaponId }} is not in your hotbar, only hitting with the item in hand.")
            p.inventory.selectedSlot
        }
        phase = Phase.SWAP
        nextAt = now
        val swapped = Inventory.isHotbarSlot(slot) && slot != p.inventory.selectedSlot
        if (swapped) {
            selectSlot(p, slot)
            acted = true
            swapAt = now
        }
        // like you: /loadout a moment after the weapon is in hand
        if (needsLoadout(r)) {
            if (swapped) nextAt = TimingModel.dueAt(now, draw(Action.WEAPON_TO_LOADOUT, BehaviorProfile.current.weaponToLoadoutMs))
            return
        }
        // without one the first swing after the swap, like yours (it may go out in the swap tick itself); with no swap for a
        // creature already there, its first click one of the plan's gaps after the death, a turn started inside that wait
        // from now ([olderStart])
        val older = olderClickAt
        val timed = olderFor
        olderClickAt = Long.MIN_VALUE
        olderFor = - 1
        if (! swapped && older != Long.MIN_VALUE) pickTarget(now, older, turnAt = now, prefer = timed)
        else pickTarget(now, if (swapped) TimingModel.dueAt(now, swapClickMs(p.mainHandItem.skyblockId)) else now)
    }

    /** Takes hotbar [slot]; a held use key goes up first (the same tick is fine, the key is read after this). */
    private fun selectSlot(p: LocalPlayer, slot: Int) {
        releaseUse()
        // a click still on its way after a death was the hand's before it went to the hotbar
        handLag.dropClick()
        p.inventory.selectedSlot = slot
        slotWatch.note(slot)
        // the item in hand from now on, also for a click in this very tick
        val hand = p.inventory.getItem(slot)
        heldOption = if (AutoFishing.isFishingRod(hand)) ROD else hand.skyblockId.ifEmpty { null }
    }

    /** The wait from a swap to its first click with [id]: yours with a teleport blade, the Flay, or any other weapon. */
    private fun swapClickMs(id: String) = draw(FightRules.swapClickAction(id), emptyList())

    /**
     * The next target of the group, fought from this tick on: the nearest, or the one [prefer]red while it is still there
     * (the click and turn were timed for it, [olderStart]); its first click not before [clickAt], its first turn at
     * [turnAt] ([ViewPolicy.Situation.notBefore]; [Long.MIN_VALUE]: after its latency) unless the loadout menu times it.
     */
    private fun pickTarget(now: Long, clickAt: Long = now, turnAt: Long = Long.MIN_VALUE, prefer: Int = - 1) {
        val p = mc.player ?: return
        val r = rule ?: return startGroup(now)
        val next = FightRules.pick(groupTargets(r), prefer, { it.id }) { it.position.distanceToSqr(p.position()) } ?: return afterKill(now)
        target = next
        lastHp = next.hp
        lastHpDrop = now
        aimHeight = FightRules.aimHeight(BehaviorProfile.current.aimHeights, Random)
        // the next target's fight has the hand: a key still held after a death is its to keep or let go, a click on its
        // way after the death is dropped
        handLag.keyUp()
        handLag.dropClick()
        keepOff.clear()
        // a turn timed to get there a reaction before the first hit: its hit waits that very reaction. After the loadout
        // menu that hit is your first click after it closed (or after the swap its replan made), and a turn of yours came
        // inside that wait: the move the plan makes, none for a plan that makes none, and the reaction to the view getting
        // where that plan needs it. A moment that fell before the switch was over (Hypixel's confirmation came after the
        // close) is drawn again after it, like the swap after a menu.
        var lead = firstEntryLead.remove(next.id)
        // an explicit rule's ability accepts one of your right-click errors, drawn now for this target: the first turn's
        // timing below and the policy's first look at the view use that very one
        explicitAccept = if (book.plans[next.id] == null && glanceNeed(next, now) == Need.RANGED) FightRules.explicitAcceptance(BehaviorProfile.current.rightClickErr, Random) else Double.NaN
        firstTurnAt = if (menuClickAt <= 0L) turnAt
            else FightRules.menuTurnAt(maxOf(clickAt, menuClickAt), planTurnMs(p, next, now)) { draw(FightRules.entryAction(glanceNeed(next, now)), emptyList()).also { lead = it } }
        if (firstTurnAt != Long.MIN_VALUE && menuDoneAt > menuClosedAt && firstTurnAt <= menuDoneAt)
            firstTurnAt = FightRules.afterMenu(menuClosedAt, menuDoneAt) { draw(Action.MENU_CLOSE_TO_SWAP, emptyList()) }
        boxEntry.reset(lead)
        // the reaction (and a turn) already took their time; the first hit waits only for the swap or the loadout menu, the
        // crosshair (and the bobber)
        val declared = KillRules.ruleFor(next.creature, next.name)
        val gate = if (r.action == KillAction.ROD && next.parent.isEmpty() && recastFirst(declared, next.name)) firstRodHitAt(next) else 0L
        nextClickAt = maxOf(clickAt, gate, menuClickAt)
        clickFrom = FightRules.firstClickFrom(maxOf(next.spawnedAt, book.unparkedAt[next.id] ?: 0L), recastFor(next), swapAt, lastLeftClickAt)
        menuClickAt = 0L
        val plan = book.plans[next.id]
        escalation.start(plan?.option.orEmpty(), now)
        say("fighting ${next.name} (${plan?.label ?: r.action.label}${if (plan == null && r.weaponId.isNotEmpty() && r.action == KillAction.WEAPON) ", ${r.weaponName.ifEmpty { r.weaponId }}" else ""})")
        phase = Phase.FIGHT
        fight(now)
    }

    /**
     * No creature of this group is left: the wait after your kill, or back to fishing at once when the fight never began
     * (the bobber killed it) and no look around keeps the view away (then the turn back is its return, like after yours,
     * and Auto Fishing goes on meanwhile: [lookOnly]). The last kill a creature others split off as it dies ([parent]):
     * your wait after such a death when none came up (children that do come are reacted to, [Phase.AFTER_KILL]).
     */
    private fun afterKill(now: Long, parent: Boolean = false) {
        // creatures of yours still alive (fought with another item) are your hand's to turn to from this death on
        freeHand(now)
        if (! acted && ! HumanAim.glances.out) return finishFight()
        lookOnly = ! acted
        val profile = BehaviorProfile.current
        phase = Phase.AFTER_KILL
        val owes = owesLoadout()
        rodFree.clear()
        // like you: /loadout a while after the kill when one is owed, otherwise the rod back
        nextAt = TimingModel.dueAt(now, when {
            owes -> draw(Action.DEATH_TO_RESTORE, profile.deathToRestoreMs)
            parent -> draw(Action.PARENT_AFTER_KILL, emptyList())
            else -> draw(Action.AFTER_KILL, profile.afterKillMs, afterKillStreak)
        })
        // the turn back runs on its own clock from the death (from the menu closing when a /loadout comes first)
        back = if (owes && book.parked.isEmpty()) null else planTurnBack(now)
    }

    /**
     * The turn back after a fight that ended at [now] ([ViewPolicy.turnBack]), from where the view comes to rest (a move
     * stopped at the death still coasts, a look around still on its way goes on: the turn back waits for it) to your
     * fishing view; null with Turn Back After Fight off. One you skip is made up for late only when a cast from there
     * would not land like one from your fishing view ([BobberFlight]).
     */
    private fun planTurnBack(now: Long): ViewPolicy.TurnBack? {
        // you moved or turned in this cleanup: the view is yours
        if (! turnBack.value || viewTaken) return null
        val p = mc.player ?: return null
        val home = homeView()
        val from = aim.restingAt ?: View(p.yRot.toDouble(), p.xRot.toDouble())
        val distance = HumanAim.distance(from.yaw.toFloat(), from.pitch.toFloat(), home.yaw.toFloat(), home.pitch.toFloat()).toDouble()
        val cast = BobberWorld.judge(p, home, from)
        // only the move still turning the view at the death goes on past it (its slow tail to rest does not: your moves
        // ended at their last moving tick): one started after it (a look around) fits the turn back
        val b = ViewPolicy.turnBack(now, distance, FightRules.landsInFluid(cast.same, distance), ViewPolicy.Learned.of(BehaviorProfile.current.policy, null), Random,
            if (aim.turning) aim.started else ViewPolicy.TurnBack.NO_MOVE)
        KillerTrace.backPlanned(b.kind.name.lowercase(), distance, cast.name.lowercase())
        return b
    }

    /** Your fishing view: the one [HumanAim] keeps, else the view this fight started from. */
    private fun homeView() = aim.home ?: View(fishingYaw.toDouble(), fishingPitch.toDouble())

    /**
     * Starts the turn back's move once it is due ([ViewPolicy.TurnBack.update]); a look around out or in its dwell is
     * waited for, and a move still turning the view or the use key still held through the kill (never the Flay going off
     * on the way back) ends first: the turn back then comes one of your waits after that rest or key-up, instead of your
     * latency from the death, which holds them already. It starts from where the view looks. A parent's death waits for
     * no children here: your first moves after one with none came as after any other kill (p50 500 ms against 499).
     */
    private fun driveTurnBack(now: Long) {
        val b = back ?: return
        val glances = HumanAim.glances
        if (glances.state == GlancePolicy.State.OUT || glances.state == GlancePolicy.State.DWELL && now < glances.dwellEnd) return
        // a turn back due behind a screen of yours (or started again after one stopped it) waits your reaction to its close
        if (! screenGate.ready(ScreenGate.Kind.MOVE, now)) return
        val p = mc.player ?: return
        val view = View(p.yRot.toDouble(), p.xRot.toDouble())
        val fishing = homeView()
        val home = HumanAim.homeGoal(view.yaw, fishing)
        // a move still turning the view ends first, its slow tail to rest does not hold it; the waits run from its last
        // moving tick, and the move starts its lead before its moment ([ViewPolicy.TurnBack.update])
        val params = HumanAim.turnBackParamsOf(BehaviorProfile.current.motor)
        // short of your view by your usual error, as far as the bobber still lands where yours does: drawn once for this
        // rest, the lead planned on the very goal that starts ([ViewPolicy.TurnBack.short])
        val short = {
            b.short(aim.started) {
                val world = BobberWorld.here()
                val learned = ViewPolicy.Learned.of(BehaviorProfile.current.policy, null)
                FightRules.backShort(b.shortBy, { ViewPolicy.backUndershoot(learned, Random, it) }) { s ->
                    BobberWorld.judge(p, fishing, MotorModel.towards(view.yaw, view.pitch, home.yaw, home.pitch, s), world).same
                }
            }
        }
        val lead = { MotorModel.towards(view.yaw, view.pitch, home.yaw, home.pitch, short()).let { g -> aim.leadMs(g.yaw, g.pitch, params) } }
        if (b.update(now, aim.turning, aim.turnEndAt(now), hold.holding, keyUpAt, aim.started, lead) != Decision.START) return
        val shortBy = short()
        val g = MotorModel.towards(view.yaw, view.pitch, home.yaw, home.pitch, shortBy)
        KillerTrace.backStarted(b.shortBy, shortBy)
        // with the hand of your turn backs, what the learner finds yours after a fight to be (back from a turn toward a
        // creature): its first stroke never past your fishing view ([HumanAim.turnBackParamsOf])
        if (startMove(g, lookDown = false, params)) b.begun(now, aim.plannedMs)
    }

    /** The point melee and explicit right clicks aim at: your aim height on the creature (its nametag without a mob). */
    private fun aimPoint(c: TrackedCreature): Vec3 {
        val mob = c.mob?.takeUnless { it.isRemoved } ?: return c.position
        return mob.position().add(0.0, mob.bbHeight * aimHeight, 0.0)
    }

    /** The point [l] of [c]'s box ([FightRules.landOffset]), where a melee move comes to rest; its nametag without a mob. */
    private fun landPoint(p: LocalPlayer, c: TrackedCreature, l: ViewPolicy.Landing): Vec3 {
        val mob = c.mob?.takeUnless { it.isRemoved } ?: return c.position
        val pos = mob.position()
        val eye = p.eyePosition
        val o = FightRules.landOffset(pos.x - eye.x, pos.z - eye.z, mob.bbWidth.toDouble(), mob.bbHeight.toDouble(), l)
        return pos.add(o[0], o[1], o[2])
    }

    /** Where a melee move comes to rest on the box: beside its centre line like yours ([ViewPolicy.landingSide]), at one of your aim heights. */
    private fun drawLanding() = ViewPolicy.Landing(ViewPolicy.landingSide(Random), FightRules.aimHeight(BehaviorProfile.current.aimHeights, Random))

    /** The point a Learned right click or hold is aimed at: 0.6 of the creature's height, the point your recordings measured. */
    private fun learnedPoint(c: TrackedCreature): Vec3 {
        val height = c.mob?.takeUnless { it.isRemoved }?.bbHeight?.toDouble() ?: NO_MOB_HEIGHT
        return c.position.add(0.0, height * LEARNED_AIM_HEIGHT, 0.0)
    }

    /** Degrees between your view and [point] from your eye. */
    private fun viewError(p: LocalPlayer, point: Vec3): Double {
        val to = point.subtract(p.eyePosition)
        val yaw = Math.toDegrees(atan2(- to.x, to.z)).toFloat()
        val pitch = Math.toDegrees(- atan2(to.y, hypot(to.x, to.z))).toFloat()
        return HumanAim.angle(p.yRot, p.xRot, yaw, pitch).toDouble()
    }

    /**
     * The view on [point] from your eye ([HumanAim.aimView]); a move still turning the view keeps the pitch-only or full aim
     * it started with (one in its slow tail is over: the next decides afresh).
     */
    private fun aimViewAt(p: LocalPlayer, point: Vec3): Pair<View, Boolean> {
        val to = point.subtract(p.eyePosition)
        return HumanAim.aimView(p.yRot.toDouble(), p.xRot.toDouble(), to.x, to.y, to.z, if (aim.moving(Owner.KILLER) && aim.turning) movePitchOnly else null)
    }

    /** The angular half-size of [c]'s box from your eye, at least 3°: within it the view is on the creature. */
    private fun boxHalfDeg(p: LocalPlayer, c: TrackedCreature): Double {
        val mob = c.mob?.takeUnless { it.isRemoved }
        val size = mob?.let { max(it.bbWidth, it.bbHeight).toDouble() } ?: NO_MOB_HEIGHT
        val dist = p.eyePosition.distanceTo(mob?.boundingBox?.center ?: c.position)
        return max(FightRules.MIN_BOX_DEG, Math.toDegrees(atan2(size / 2, max(dist, 0.1))))
    }

    /** When the first turn towards [c] is timed from: the killer's swap for it, else its spawn or its return from parking. */
    private fun anchorOf(c: TrackedCreature): Pair<Long, Boolean> {
        val spawn = maxOf(c.spawnedAt, book.unparkedAt[c.id] ?: 0L)
        return if (swapAt >= spawn && swapAt > 0L) swapAt to true else spawn to false
    }

    /** The weapon:button class your recordings learn turns for, of the item in hand. */
    private fun turnClass(p: LocalPlayer, style: ClickStyle) = FightRules.turnClass(p.mainHandItem.skyblockId, style)

    /**
     * Asks the [policy] about this tick's view for [c] and carries the decision out: a move started, retargeted, ended
     * after its strokes or brought to rest; then the looks of this tick ([driveLooks]). [pitchOnly] is the aim's
     * pitch-only flag, kept for a move started now; [point] the point the plan aims at (null for a look-down). A melee
     * move comes to rest on a point of the box drawn per move ([landPoint]), a look-down at a [restPitch] drawn per move.
     */
    private fun steer(
        p: LocalPlayer, c: TrackedCreature, need: Need, aimView: View, pitchOnly: Boolean, error: Double, tolerance: Double, now: Long,
        cls: String, point: Vec3?, onTarget: Boolean = false, lookDown: Boolean = false, accept: () -> Double = { 0.0 },
        shortShare: ClosedFloatingPointRange<Double> = 1.0 .. 1.0, restPitch: () -> Double = { Double.NaN },
    ) {
        // after a screen of yours closed (it stopped the move in flight), a turn or a look due meanwhile waits your reaction
        // to the close; the policy is asked again once it passed
        if (! screenGate.ready(ScreenGate.Kind.MOVE, now)) return
        val (anchorAt, swapped) = anchorOf(c)
        policy.learned = ViewPolicy.Learned.of(BehaviorProfile.current.policy, cls)
        val land: ((ViewPolicy.Landing) -> View)? = if (need == Need.MELEE) { l -> aimViewAt(p, landPoint(p, c, l)).first } else null
        // a move's slow tail to rest after its last moving tick is over for the learner: your moves ended there, and your
        // next ones were timed from there
        val turning = aim.turning
        val params = HumanAim.paramsOf(BehaviorProfile.current.motor)
        val s = ViewPolicy.Situation(
            now, c.id, need, View(p.yRot.toDouble(), p.xRot.toDouble()), aimView, error, tolerance, onTarget,
            aim.moving(Owner.KILLER) && ! aim.stopping && turning, aim.strokes, anchorAt, swapped,
            need == Need.MELEE && fallsIntoView(c, now), giveUpAfter.value * 1000.0 - FIRST_TURN_MARGIN_MS, accept, shortShare,
            aim.moving(Owner.LOOK) && turning, firstTurnAt, land, ::drawLanding, restPitch, aim.moving(Owner.KILLER) && aim.stopping && turning,
            // any move of the view, whoever's, and the end of its last moving tick as the driver saw it (not when this
            // tick's fight noticed it); the next move's lead planned with the hand it starts with ([startMove])
            aim.turnEndAt(now), turning, aim.turns, { g -> aim.leadMs(g.yaw, g.pitch, params, lookDown) },
        )
        val decision = policy.update(s)
        when (decision) {
            Decision.START -> policy.goal?.let { g ->
                if (startMove(g, lookDown)) {
                    movePitchOnly = pitchOnly
                    turned = true
                    acted = true
                }
            }
            Decision.RETARGET -> policy.goal?.let {
                aim.retarget(it.yaw, it.pitch, policy.force)
                // a move turned to the plan's new need (a look down held for its area and let go) is that need's now
                lookingDown = lookDown
            }
            Decision.END -> aim.endAfterStroke()
            Decision.STOP -> aim.stop(Owner.KILLER)
            Decision.NONE -> Unit
        }
        driveLooks(p, c, need, point, tolerance, decision == Decision.START, now, cls)
    }

    /**
     * The looks at your creatures that no plan needs ([ViewPolicy.Looks]) in this tick of [c]'s fight, after the policy's
     * own decision ([started]: it started a move, which always wins). A look's goal keeps [c] well within the plan's
     * [tolerance] of [point] (NaN: the explicit rule's acceptance, at most its hold's), and a melee target under the
     * crosshair, and its way there never goes past it ([HumanAim.keepParamsOf]), so the plan's clicks and holds go on as
     * they would without it; one the creature still left is corrected after a
     * reaction ([ViewPolicy.Situation.looking]). A look holds Auto Fishing like a turn.
     */
    private fun driveLooks(p: LocalPlayer, c: TrackedCreature, need: Need, point: Vec3?, tolerance: Double, started: Boolean, now: Long, cls: String) {
        looks.table = ViewPolicy.Looks.Table.of(BehaviorProfile.current.policy.looks)
        val eye = p.eyePosition
        val tol = ViewPolicy.Looks.KEEP_SHARE * if (tolerance.isNaN()) min(policy.acceptance, FightRules.KEEP_HOLD_DEG.toDouble()) else tolerance
        val mob = c.mob?.takeUnless { it.isRemoved }
        val keeps = { g: View ->
            need == Need.NONE || point != null && angle(g, viewTo(eye, point)) <= tol && (need != Need.MELEE || mob == null || crosshairOn(eye, g, mob))
        }
        val quiet = need == Need.PITCH || started || policy.waiting || aim.moving(Owner.KILLER) || now < bobberChanceAt(c)
        val view = View(p.yRot.toDouble(), p.xRot.toDouble())
        val creatures = lookCreatures(p, c, cls, now)
        val motor = BehaviorProfile.current.motor
        val keep = need != Need.NONE
        // the look's way back is timed from the end of the view's last move of 3 degrees or more (its slow tail to rest, or a
        // smaller move split off it, is no rest of the learner's), and starts its lead before, with the hand it goes with
        val backLead = { g: View -> aim.leadMs(g.yaw, g.pitch, HumanAim.wayBackParamsOf(motor, HumanAim.lookBackKind(view, g, creatures.map { it.aim }), keep)) }
        val scene = ViewPolicy.Looks.Scene(now, view, creatures, c.id, need, policy.free, policy.foundFree, keeps, quiet, aim.moving(Owner.LOOK) && aim.turning,
            aim.bigTurnedAt(now), backLead, aim.turnedAt(now))
        when (looks.update(scene)) {
            Decision.START -> {
                val g = looks.goal ?: return
                // a look that keeps the plan's creature in view keeps it there on the way too: never past its goal; its way
                // back goes with the hand of your moves of the kind the learner finds it to be: one that closes on a creature
                // of yours an aim's, any other a turn back's (back from a turn toward a creature)
                val back = if (looks.kind == ViewPolicy.Looks.Kind.RETURN) HumanAim.lookBackKind(view, g, scene.creatures.map { it.aim }) else null
                aim.params = when {
                    back != null -> HumanAim.wayBackParamsOf(motor, back, keep)
                    keep -> HumanAim.keepParamsOf(motor)
                    else -> HumanAim.paramsOf(motor)
                }
                if (! aim.start(g.yaw, g.pitch, Owner.LOOK)) return looks.failed()
                movePlannedMs = aim.plannedMs
                handLag.stopped()
                // the view leaves the water: Auto Fishing waits for the fight's end and its turn back, as after a turn
                turned = true
                acted = true
                KillerTrace.look(if (looks.kind == ViewPolicy.Looks.Kind.RETURN) "return" else "look", if (looks.kind == ViewPolicy.Looks.Kind.LOOK) looks.lookedAt else - 1,
                    angle(view, g))
            }
            Decision.STOP -> aim.stop(Owner.LOOK)
            else -> Unit
        }
    }

    /**
     * Your creatures alive as the looks see them: the class each one is fought with ([c]'s is [cls]), its age, the error
     * and view to half its height, whether it is right above or below you, when its recast's bobber has had its chance,
     * and whether the plan turns to it once it is the target ([FightRules.planTurns]: while the rod melees the target, a
     * rod creature past its bobber's chance as well, whose first hit is drawn only once it is the target).
     */
    private fun lookCreatures(p: LocalPlayer, c: TrackedCreature, cls: String, now: Long): List<ViewPolicy.Looks.Creature> {
        val eye = p.eyePosition
        val view = View(p.yRot.toDouble(), p.xRot.toDouble())
        // the rod meleeing its target: the group's other rod creatures are next
        val rodFight = rule?.action == KillAction.ROD && glanceNeed(c, now) == Need.MELEE
        return SeaCreatureTracker.ownAlive.filter { it.id !in handedOver }.map { o ->
            val height = o.mob?.takeUnless { it.isRemoved }?.bbHeight?.toDouble() ?: NO_MOB_HEIGHT
            val to = viewTo(eye, o.position.add(0.0, height * LOOK_AIM_SHARE, 0.0))
            val error = angle(view, to)
            val overhead = hypot(o.position.x - p.x, o.position.z - p.z) < BehaviorProfile.Looks.OVERHEAD_SIDE && error > BehaviorProfile.Looks.OVERHEAD_DEG
            val chance = bobberChanceAt(o)
            ViewPolicy.Looks.Creature(o.id, if (o.id == c.id) cls else lookClass(o), now - o.spawnedAt, error, to, overhead, chance,
                planTurns = o.id != c.id && FightRules.planTurns(glanceNeed(o, now), bobberRod(o), now >= chance, rodFight))
        }
    }

    /** [c] is fought with the rod and its recast comes first: the bobber's until its chance passed ([bobberChanceAt]). */
    private fun bobberRod(c: TrackedCreature) = ruleOf(c)?.action == KillAction.ROD && c.parent.isEmpty() && recastFirst(KillRules.ruleFor(c.creature, c.name), c.name)

    /**
     * The weapon:button class a creature that is not the target is fought with: its plan's weapon and click, the rod's
     * right click when the recast's bobber comes first for it, else the rod's or its rule's weapon's click (null: none).
     */
    private fun lookClass(o: TrackedCreature): String? {
        val plan = book.plans[o.id]
        if (plan != null && plan.option != ROD) return FightRules.turnClass(plan.option, plan.style)
        val r = ruleOf(o) ?: return null
        return when (r.action) {
            KillAction.ROD -> FightRules.turnClass(rodId, if (o.parent.isEmpty() && recastFirst(KillRules.ruleFor(o.creature, o.name), o.name)) ClickStyle.RIGHT else ClickStyle.LEFT)
            KillAction.WEAPON -> r.weaponId.takeIf { it.isNotEmpty() }?.let { FightRules.turnClass(it, if (r.attack == AttackMode.RIGHT) ClickStyle.RIGHT else ClickStyle.LEFT) }
            else -> null
        }
    }

    /** No look at [c] before this: a recast-first creature's first hit after its bobber's chance, else that chance; 0 for any other. */
    private fun bobberChanceAt(c: TrackedCreature): Long {
        if (c.parent.isNotEmpty() || ! recastFirst(KillRules.ruleFor(c.creature, c.name), c.name)) return 0L
        return firstHitAt[c.id] ?: ((recastFor(c) ?: c.spawnedAt) + BOBBER_LOOK_MS)
    }

    /** The view from [eye] on [point]. */
    private fun viewTo(eye: Vec3, point: Vec3): View {
        val to = point.subtract(eye)
        return View(Math.toDegrees(atan2(- to.x, to.z)), Math.toDegrees(- atan2(to.y, hypot(to.x, to.z))))
    }

    private fun angle(a: View, b: View) = MotorModel.angle(a.yaw, a.pitch, b.yaw, b.pitch)

    /** The crosshair of the view [g] from [eye] is on [mob] (inside its box at your eye counts too). */
    private fun crosshairOn(eye: Vec3, g: View, mob: Entity): Boolean {
        val box = mob.boundingBox.inflate(mob.pickRadius.toDouble())
        return box.contains(eye) || box.clip(eye, eye.add(Vec3.directionFromRotation(g.pitch.toFloat(), g.yaw.toFloat()).scale(LOOK_RAY))).isPresent
    }

    /**
     * Your looks around and back in the killer's fights ([GlancePolicy], looks/SPEC §3.4): fed once a tick after the
     * fight's own tick, in the phase of the fight (a fight that needs your aim only before its first turn and while no
     * click needs the view; after the kill until the turn back starts), never while a hold or a press is on its way.
     * Auto Fishing feeds them between fights and stops them where none may go on; in a fight that does not hold it, the
     * looks keep to its reel and recast as they do between fights ([AutoFishing.glanceWindowMs]).
     */
    private fun driveGlances() {
        val p = mc.player ?: return
        val now = System.currentTimeMillis()
        if (! canRun() || phase == Phase.IDLE || ! AutoFishing.glancesAllowed(p, now) || HumanAim.glanceTick == p.tickCount) return
        // none starts before your reaction to a screen of yours closing
        if (! screenGate.ready(ScreenGate.Kind.MOVE, now)) return
        HumanAim.glanceTick = p.tickCount
        val (glancePhase, window) = glancePhase(now)
        val b = back
        val profile = BehaviorProfile.current
        aim.glance(HumanAim.glances, now, glancePhase, profile.motor, profile.glances, min(window, AutoFishing.glanceWindowMs(now)), sinceOwnDeathMs(now),
            deadSpotView(p, now), AutoFishing.glanceReturnDue(), b != null && ! b.started && ! b.done, AutoFishing.glanceReturnNotBefore())
    }

    /** The phase of looks around in this tick of the fight, and how long the view is free for one. */
    private fun glancePhase(now: Long): Pair<GlancePolicy.Phase?, Double> {
        if (hold.holding || usePending) return null to 0.0
        return when (phase) {
            Phase.IDLE, Phase.LOADOUT, Phase.RESTORE_LOADOUT -> null to 0.0
            // the turn back owns the view once its move started
            Phase.AFTER_KILL, Phase.RESTORE_ROD, Phase.TURN_BACK -> {
                val b = back
                if (b != null && b.started) null to 0.0
                else GlancePolicy.Phase.AFTER_KILL to (b?.takeIf { ! it.done }?.let { (it.at - now).toDouble() } ?: Double.POSITIVE_INFINITY)
            }
            Phase.REACT, Phase.SWAP, Phase.SWITCH, Phase.FIGHT -> {
                if (targets().none { glanceNeed(it, now) != Need.NONE }) return GlancePolicy.Phase.FIGHT_FREE to Double.POSITIVE_INFINITY
                var window = Double.POSITIVE_INFINITY
                if (phase != Phase.FIGHT) window = (nextAt - now).toDouble()
                else if (target != null) {
                    window = policy.freeForMs(now)
                    // with the view on the creature its clicks go out: a look would hold them back
                    if (! policy.waiting) window = min(window, (nextClickAt - now).toDouble())
                }
                GlancePolicy.Phase.FIGHT_AIM to max(0.0, window)
            }
        }
    }

    /**
     * What [c]'s plan needs the view for, as far as a look around goes: a Learned plan's need ([FightRules.planNeed]), a
     * rod's melee (none while a recast-first creature is still the bobber's), an explicit Weapon rule's click.
     */
    private fun glanceNeed(c: TrackedCreature, now: Long): Need {
        val r = ruleOf(c) ?: return Need.NONE
        if (bobberRod(c) && now < (firstHitAt[c.id] ?: Long.MAX_VALUE) - FIRST_HIT_LEAD_MS) return Need.NONE
        book.plans[c.id]?.let { return FightRules.planNeed(it) }
        if (r.action != KillAction.WEAPON) return Need.MELEE
        val profile = BehaviorProfile.current
        val id = r.weaponId.ifEmpty { mc.player?.mainHandItem?.skyblockId.orEmpty() }
        if (id in WeaponPlanner.TELEPORT_BLADES) return Need.PITCH
        val learned = profile.weaponStyles[id]?.takeIf { WeaponPlanner.isWeapon(profile, id) }?.style
        return if (FightRules.explicitStyle(r, id, false, learned, profile.habits[c.name]?.weaponPrefersRight == true) == ClickStyle.LEFT) Need.MELEE else Need.RANGED
    }

    /** A look around stops dead and is forgotten (a takeover, a reset). */
    private fun dropGlance() {
        aim.abort(Owner.GLANCE)
        HumanAim.glances.drop()
    }

    /** Starts a move of the killer to [g] with your learned hand ([params]: your turns'); [lookDown]: a teleport blade's look-down. */
    private fun startMove(g: View, lookDown: Boolean, params: MotorParams = HumanAim.paramsOf(BehaviorProfile.current.motor)): Boolean {
        aim.params = params
        if (! aim.start(g.yaw, g.pitch, Owner.KILLER, lookDown)) return false
        lookingDown = lookDown
        movePlannedMs = aim.plannedMs
        // a new move: the one a death or a new creature was to bring to rest is over
        handLag.stopped()
        return true
    }

    private fun fight(now: Long) {
        val p = mc.player ?: return
        val r = rule ?: return startGroup(now)
        val c = target ?: return pickTarget(now)
        if (c.dead || c.stand.isRemoved) {
            // your hand reacts to the death a moment later, not in its tick: a use key held stays down, a move goes on, and
            // now and then a click already on its way still goes out at nothing ([HandLag]; guardUse and guardMelee still
            // apply to them)
            lagTarget = c
            val clicking = targetClickId == c.id && now - targetClickAt <= HandLag.CLICKING_MS
            // yours came a beat of your rhythm after the last click at it at the soonest (97 ms, p50 200), never two at once;
            // the beat is read off your rhythm without moving it on (that click may never go out)
            val rod = AutoFishing.isFishingRod(p.mainHandItem)
            val id = p.mainHandItem.skyblockId
            val beat = { style: ClickStyle ->
                TimingModel.dueAt(targetClickAt, if (style == ClickStyle.LEFT) meleeGap(rod, rhythm = true, consume = false) else pressGap(id, learned = true))
            }
            handLag.died(now, hold.holding, fightStyle, clicking, SeaCreatureTracker.ownAlive.any { it.id != c.id }, Random,
                { draw(Action.DEATH_REACTION, emptyList()) }, beat) { style ->
                draw(if (style == ClickStyle.LEFT) Action.DEATH_CLICK else Action.DEATH_PRESS, emptyList())
            }
            target = null
            // after the last one of the group the wait after the kill, timed from this death like yours (not a tick later)
            if (groupTargets(r).isEmpty()) return afterKill(now, c.name in parentNames())
            // the next creature of the group after a gap of the plan's own clicks (not a beat of your rhythm)
            phase = Phase.SWAP
            nextAt = TimingModel.dueAt(now, FightRules.nextTargetGap(fightStyle, { meleeGap(rod, rhythm = false) }, { pressGap(id, isLearned(c)) }) { holdGap(id) })
            return
        }

        if (c.hp < lastHp) {
            lastHpDrop = now
            escalation.hpDrop(now)
        }
        lastHp = c.hp
        if (now - lastHpDrop > giveUpAfter.value * 1000L) {
            say("giving up on ${c.name}")
            givenUp += c.id
            return dropTarget(now)
        }

        var plan: Plan? = null
        if (isLearned(c)) {
            // parked (nothing you have reaches it now): the next target, or back to fishing
            val effective = learnedRules[c.id] ?: return dropTarget(now)
            plan = book.plans[c.id] ?: return dropTarget(now)
            // its plan moved on to another item (it moved out of reach, or the last one did nothing), or the item is not in hand
            if (! FightRules.sameGroup(r, effective, true) || heldOption != plan.option) return switchPlan(effective, now)
            if (escalation.option != plan.option) escalation.start(plan.option, now)
        }
        // an explicit Weapon rule's weapon is in your hotbar but no longer in hand (not by your slot change, that is a
        // takeover): it is taken again instead of clicking with what is there
        else if (r.action == KillAction.WEAPON && r.weaponId.isNotEmpty() && p.mainHandItem.skyblockId != r.weaponId && slotFor(r, p) != null) return switchPlan(r, now)

        val style = plan?.style ?: explicitStyle(r, p, c)
        fightStyle = style
        if (style == ClickStyle.LEFT) melee(p, c, plan, now) else ranged(p, c, plan, style, now)

        if (plan != null && escalation.due(now)) escalate(p, c, plan, now)
    }

    /**
     * Leaves the current target (given up on, or parked): the next one of the group after the swap phase, or back to
     * fishing. Your hand reacts to it like to a death, not in its tick: the key held goes up and the move comes to rest
     * a reaction later ([HandLag.letGo]), and the next creature is turned to after a reaction of its own.
     */
    private fun dropTarget(now: Long) {
        handLag.letGo(now, hold.holding) { draw(Action.DEATH_REACTION, emptyList()) }
        keepOff.clear()
        target = null
        phase = Phase.SWAP
        nextAt = TimingModel.dueAt(now, draw(Action.DEATH_REACTION, emptyList()))
    }

    /**
     * The target's plan changed in the middle of its fight: let go, take the other item a moment later and fight the same
     * target on. Not through [pickTarget], so Give Up After keeps counting; no loadout either. The key held goes up and
     * the move comes to rest a reaction later, as after a death ([HandLag.letGo]); the swap waits for the key ([lagTick]).
     */
    private fun switchPlan(effective: KillRule, now: Long) {
        handLag.letGo(now, hold.holding) { draw(Action.DEATH_REACTION, emptyList()) }
        keepOff.clear()
        // the other plan needs the view elsewhere (within its aim, its pitch, the crosshair): its first click waits a
        // reaction to the view getting there, watched from this tick on (SWITCH)
        boxEntry.reset()
        rule = effective
        // the rest of this group is fought in the loadout that is on, and the other item's turn is timed from its own swap
        loadoutTried = true
        firstTurnAt = Long.MIN_VALUE
        phase = Phase.SWITCH
        switchSwapped = false
        nextAt = TimingModel.dueAt(now, draw(Action.PLAN_SWITCH, emptyList()))
    }

    /**
     * The current option did nothing to [c] for a while: it is excluded for [c] and the next option planned (or [c] parked).
     * The hand lets go a reaction later, like at the other plan switches ([HandLag.letGo]; the switch or drop that follows
     * keeps that reaction), not in the tick of the use that tipped it.
     */
    private fun escalate(p: LocalPlayer, c: TrackedCreature, plan: Plan, now: Long) {
        handLag.letGo(now, hold.holding) { draw(Action.DEATH_REACTION, emptyList()) }
        say("${c.name}: ${plan.label} did nothing in ${escalation.uses} uses")
        book.exclude(c.id, plan.option, now)
        learnedRules.remove(c.id)
        escalation.start("", now)
        updatePlan(p, c, now)
    }

    /**
     * How an explicit rule clicks with the item in hand: a Weapon rule's Right Click holds the use key with an item you
     * hold it with, and only hits with an item that moves you, a tool or another item than its weapon.
     */
    private fun explicitStyle(r: KillRule, p: LocalPlayer, c: TrackedCreature): ClickStyle {
        val profile = BehaviorProfile.current
        val stack = p.mainHandItem
        val id = stack.skyblockId
        val learned = profile.weaponStyles[id]?.takeIf { WeaponPlanner.isWeapon(profile, id) }?.style
        return FightRules.explicitStyle(r, id, AutoFishing.isFishingRod(stack), learned, profile.habits[c.name]?.weaponPrefersRight == true)
    }

    /**
     * Rod or weapon melee: with the crosshair really on this creature, within reach, a reaction after it got there
     * ([BoxEntry]); now and then, like you, right next to it, a miss ([MissOdds]). The view turns only when the policy
     * says so ([ViewPolicy]: after your reaction, not while the creature falls into the crosshair by itself).
     */
    private fun melee(p: LocalPlayer, c: TrackedCreature, plan: Plan?, now: Long) {
        // an explicit rule's item changed under a hold (a hotbar key, the server): the key must not stay down with a rod
        releaseUse()
        // the crosshair on this creature (inside its box the game picks it at your eye)
        val hit = (mc.hitResult as? EntityHitResult)?.takeIf { it.entity === c.mob || it.entity === c.stand }
        val (aimView, pitchOnly) = aimViewAt(p, aimPoint(c))
        val error = MotorModel.angle(p.yRot.toDouble(), p.xRot.toDouble(), aimView.yaw, aimView.pitch)
        val half = boxHalfDeg(p, c)
        // on the creature: under the crosshair, or within its box's size from your eye (too far or below you for a hit)
        val onTarget = hit != null || error <= half
        steer(p, c, Need.MELEE, aimView, pitchOnly, error, half, now, turnClass(p, ClickStyle.LEFT), aimPoint(c), onTarget)

        val lands = hit != null && p.eyePosition.distanceTo(hit.location) <= MELEE_REACH
        val entered = boxEntry.ready(now, lands) { draw(Action.BOX_ENTRY_CLICK, emptyList()) }
        if (now < nextClickAt || ! handFree(p, now)) return
        val rod = AutoFishing.isFishingRod(p.mainHandItem)
        // never while the view turns faster than your hand did at any click of yours, nor while vanilla would swallow the
        // attack (its cooldown with a screen just closed; the killer's own misses leave none, its key is up at once)
        val canClick = FightRules.clickTurnOk(MotorModel.angle(p.yRotO.toDouble(), p.xRotO.toDouble(), p.yRot.toDouble(), p.xRot.toDouble())) &&
            (mc as IMinecraft).missTime <= 0
        if (! lands) {
            // a miss of yours next to the creature: no use of the plan, it never counts towards giving the plan up
            if (canClick && mc.hitResult !is EntityHitResult && ! breaksAtOnce(p) && FightRules.missNear(error, half, p.eyePosition.distanceTo(c.position)) &&
                missOdds(rod).owed) return meleeClick(p, c, rod, now)
            // a Learned melee plan that cannot land a hit with the view on the creature (below you, a little too far)
            // did nothing either: enough of that and the next option takes over. Waiting for the policy is no failure.
            if (plan != null && FightRules.countsUse(onTarget, aim.movingInFight(), policy.waiting)) {
                escalation.use()
                nextClickAt = TimingModel.dueAt(now, meleeGap(rod, rhythm = false))
                clickFrom = now
            }
            return
        }
        if (entered && canClick) meleeClick(p, c, rod, now)
    }

    /**
     * A melee click at [c] now, in your melee rhythm: a hit, or the owed miss next to it. The game picks again as it
     * handles the click, so what it was is known only then ([guardMelee]).
     */
    private fun meleeClick(p: LocalPlayer, c: TrackedCreature, rod: Boolean, now: Long) {
        pendingMelee = MeleeClick(c, rod, p.inventory.selectedSlot, p.mainHandItem)
        PlayerUtils.leftClick()
        nextClickAt = TimingModel.dueAt(now, meleeGap(rod, rhythm = true))
        clickFrom = now
        clickTick = p.tickCount
        lastLeftClickAt = now
        acted = true
        clickedAt(c.id, now)
    }

    /** The killer clicked at its target [id] at [now]: a death soon after finds the hand still clicking ([HandLag]). */
    private fun clickedAt(id: Int, now: Long) {
        targetClickId = id
        targetClickAt = now
    }

    /**
     * The hand's lag behind the last death or new creature ([HandLag]): the use key held goes up, a move still turning
     * comes to rest and a click on its way goes out, each at its time. The held key's repeats meanwhile are traced, no use
     * of a plan.
     */
    private fun lagTick(p: LocalPlayer, now: Long) {
        if (hold.holding && phase != Phase.FIGHT) repeat(hold.newRepeats(p.tickCount)) { KillerTrace.click(p, left = false, hold = true, target = - 1) }
        // a key still held with no creature of yours in view goes up at once when something it must not hit comes under the
        // crosshair or onto the ability's way (each repeat is checked again as the game uses the item, [guardUse])
        if (hold.holding && phase != Phase.FIGHT && useBlocked(p, useId)) {
            useWay.block()
            handLag.keyUpBy(now)
        }
        if (now >= handLag.keyUpAt) {
            handLag.keyUp()
            if (hold.holding) {
                val id = useId
                releaseUse()
                nextClickAt = TimingModel.dueAt(now, holdGap(id))
                // your item swap after such a kill (or a plan switch, or for a new creature) came a moment after the key went
                // up; for one that was there before the death REACT times it from the key-up alone ([freedReactAt])
                if (phase == Phase.AFTER_KILL || phase == Phase.SWITCH || phase == Phase.REACT && ! reactOlder)
                    nextAt = maxOf(nextAt, TimingModel.dueAt(now, draw(Action.KEY_UP_TO_SWAP, emptyList())))
            }
        }
        if (now >= handLag.stopAt) {
            handLag.stopped()
            aim.stopFight()
        }
        if (now >= handLag.clickAt) {
            // yours all came within a second of the death: one held back that long (a fast turn, a screen) never goes
            if (handLag.clickExpired(now)) handLag.dropClick()
            else if (UMinecraft.currentScreenObj == null) lagClick(p, now)
        }
    }

    /**
     * The click on its way after the death of [lagTarget] goes out with the item in hand, at whatever the crosshair is on:
     * a melee click ([guardMelee] cancels it on another entity or a block it would break at once), or a right press
     * where one may go out at all. Not while the view turns faster than your hand did at a click, nor into vanilla's
     * attack cooldown, nor in a tick another click of the killer's went out in: then a tick later. The killer's next
     * click comes a gap of yours after it ([afterLagClick]).
     */
    private fun lagClick(p: LocalPlayer, now: Long) {
        val c = lagTarget ?: return handLag.dropClick()
        // a target fought again has the hand: what was on its way is dropped (pickTarget drops it as well)
        if (target != null) return handLag.dropClick()
        // its own gap after the fight's last click and after the one before it is drawn with it ([HandLag]): its clock
        // started at that click
        if (! handFree(p, now, screenClickAt, left = handLag.style == ClickStyle.LEFT, due = handLag.clickAt)) return
        when (handLag.style) {
            ClickStyle.LEFT -> {
                if (! FightRules.clickTurnOk(MotorModel.angle(p.yRotO.toDouble(), p.xRotO.toDouble(), p.yRot.toDouble(), p.xRot.toDouble())) || (mc as IMinecraft).missTime > 0) return
                val rod = AutoFishing.isFishingRod(p.mainHandItem)
                pendingMelee = MeleeClick(c, rod, p.inventory.selectedSlot, p.mainHandItem, after = true)
                PlayerUtils.leftClick()
                clickTick = p.tickCount
                lastLeftClickAt = now
                afterLagClick = TimingModel.dueAt(now, meleeGap(rod, rhythm = false))
                clickFrom = now
                handLag.clicked(now, Random) { meleeGap(rod, rhythm = true) }
            }
            ClickStyle.RIGHT -> {
                val id = p.mainHandItem.skyblockId
                if (! hold.holding && FightRules.useAllowed(id, AutoFishing.isFishingRod(p.mainHandItem), p.xRot)) {
                    // with nothing of yours in view the press still never touches what it must not: it waits while
                    // something is under the crosshair or on the ability's way, and goes a reaction after it moved away
                    // (or never, past your window after the death)
                    if (! useWay.ready(now, useBlocked(p, id)) { draw(Action.PATH_CLEAR, emptyList()) }) return
                    meanUse(p, id)
                    PlayerUtils.rightClick()
                    KillerTrace.click(p, left = false, hold = false, target = - 1)
                    clickTick = p.tickCount
                    afterLagClick = TimingModel.dueAt(now, pressGap(id, learned = true))
                }
                handLag.clicked(now, Random) { 0L }
            }
            else -> handLag.dropClick()
        }
    }

    /**
     * A click of the killer (an attack or a use) may go out now: none went out in this tick yet, so each one is checked by
     * its own guard as the game handles it ([guardMelee], [guardUse]), a click on its way after a death was a gap of yours
     * ago (yours never came closer than 92 ms, nor two in one tick), and a screen of yours closed your reaction ago. A
     * [left] click due at [due] whose own clock started before Auto Fishing's last rod click ([clickFrom]: in your hitting
     * rhythm, a rod click between) comes one of your switches to a click already due after that rod click ([CrossGap],
     * [Action.CROSS_R_TO_L]); one whose clock started after it (your first hit from the recast or the spawn) holds the
     * switch already. None goes out while a rod click of Auto Fishing's waits for your hand to switch the other way
     * ([AutoFishing.waitsForHand]).
     */
    private fun handFree(
        p: LocalPlayer, now: Long, notBefore: Long = maxOf(afterLagClick, screenClickAt), left: Boolean = true, due: Long = maxOf(nextClickAt, notBefore),
    ): Boolean {
        val after = if (left) maxOf(notBefore, rodCross.notBefore(AutoFishing.lastRodUseAt, clickFrom, due) { draw(Action.CROSS_R_TO_L, emptyList()) }) else notBefore
        return FightRules.clickFree(p.tickCount, clickTick, pendingMelee != null, usePending, now, after) && ! AutoFishing.waitsForHand()
    }

    /**
     * A melee click of the killer meant for [c], swung with a fishing rod ([rod]) or a weapon, the [stack] in hotbar [slot];
     * [after]: on its way after [c] died ([HandLag]).
     */
    private class MeleeClick(val c: TrackedCreature, val rod: Boolean, val slot: Int, stack: ItemStack, val after: Boolean = false) {
        val id: String = stack.skyblockId
        val item: Item = stack.item
    }

    /** The misses owed by the killer's hits with a fishing rod ([rod]) or a weapon. */
    private fun missOdds(rod: Boolean) = if (rod) rodMisses else weaponMisses

    /**
     * A swing now would break the block under the crosshair at once (seagrass, a flower): no melee click of the killer
     * goes there. A pick that found nothing in reach is a miss into the air ([FightRules.breaksAtOnce]).
     */
    private fun breaksAtOnce(p: LocalPlayer): Boolean {
        val hit = mc.hitResult ?: return false
        val level = mc.level
        return FightRules.breaksAtOnce(hit.type == HitResult.Type.BLOCK) {
            val b = hit as? BlockHitResult
            if (b == null || level == null) Float.POSITIVE_INFINITY else level.getBlockState(b.blockPos).getDestroyProgress(p, level, b.blockPos)
        }
    }

    /**
     * Right presses or the use key held. A Learned plan turns only beyond its aim (never for an area weapon), stopping
     * short of the creature by your usual error, and clicks inside its distances; an explicit Weapon rule turns to within
     * your right-click error and clicks within Ability Range. A teleport blade looks down instead (to one of your
     * look-downs, drawn per move) and never clicks looking anywhere else. The first press or hold once the view got
     * there waits a reaction like yours ([BoxEntry]): not in the tick the error came within the aim, now and then while
     * the view still turns.
     */
    private fun ranged(p: LocalPlayer, c: TrackedCreature, plan: Plan?, style: ClickStyle, now: Long) {
        val profile = BehaviorProfile.current
        val id = p.mainHandItem.skyblockId
        val viewPitch = plan?.viewPitch?.takeUnless { it.isNaN() }
            ?: if (id in WeaponPlanner.TELEPORT_BLADES) WeaponPlanner.plan(profile, id)?.viewPitch?.takeUnless { it.isNaN() } ?: FightRules.BLADE_VIEW_PITCH else Float.NaN
        val cls = turnClass(p, style)
        val need: Need
        val aimed: Boolean
        val keep: Boolean
        val lookDown = ! viewPitch.isNaN()
        val reached = lookDown && FightRules.pitchReached(p.xRot, viewPitch)
        // an area ability's area (until the view got down the one planned around the look down, never along the near-level
        // view it turns from; down there the view's own): something not yours coming into it has the view turn up to the
        // creature your reaction later, never in the tick it came in (a look down under way goes on and is checked where
        // it ends), a view already down there too; the look down goes again a reaction after that area came clear
        if (downFor != c.id || ! lookDown) {
            downFor = c.id
            down.clear()
        }
        val areaBlocked = lookDown && otherInArea(p, id, planned = ! reached)
        val plannedBlocked = areaBlocked && ! reached
        // the reaction to it coming in: no data (your recordings log no other player's creatures), so the conservative
        // reaction to a way changing, never under 50 ms, not the faster one to a creature of yours leaving the crosshair
        val downHeld = lookDown && down.update(now, areaBlocked, aim.moving(Owner.KILLER) && lookingDown, { draw(Action.PATH_CLEAR, emptyList()) }) {
            draw(Action.PATH_CLEAR, emptyList())
        }
        if (downHeld) {
            // the view on the creature as an explicit rule's ability turns to it; nothing is used meanwhile (a teleport
            // blade never clicks looking anywhere but down)
            val (aimView, pitchOnly) = aimViewAt(p, aimPoint(c))
            val err = MotorModel.angle(p.yRot.toDouble(), p.xRot.toDouble(), aimView.yaw, aimView.pitch)
            need = Need.RANGED
            steer(p, c, need, aimView, pitchOnly, err, Double.NaN, now, cls, aimPoint(c), accept = ::takeExplicitAccept, shortShare = EXPLICIT_SHORT)
            aimed = false
            keep = false
        }
        else if (lookDown) {
            // only the pitch matters: how far the view is above the plan's pitch
            val aimView = View(p.yRot.toDouble(), viewPitch.toDouble())
            val pitches = styleOf(plan?.option ?: id)?.pitch.orEmpty()
            need = Need.PITCH
            steer(p, c, need, aimView, true, max(0.0, (viewPitch - p.xRot).toDouble()), FightRules.PITCH_SLACK.toDouble(), now, cls, null, lookDown = true,
                restPitch = { FightRules.lookDownPitch(pitches, viewPitch, Random) })
            aimed = reached && if (plan != null) inEnvelope(p, plan, c) else p.eyePosition.distanceTo(c.position) <= abilityRange.value
            keep = aimed
        }
        else if (plan != null) {
            val err = viewError(p, learnedPoint(c))
            val (aimView, pitchOnly) = aimViewAt(p, learnedPoint(c))
            val aimErr = styleOf(plan.option)?.aimErr.orEmpty()
            // an area weapon (90 degrees or more) never turns
            need = if (plan.aimDeg >= 90.0) Need.NONE else Need.RANGED
            steer(p, c, need, aimView, pitchOnly, err, plan.aimDeg, now, cls, learnedPoint(c), accept = { ViewPolicy.rangedAcceptance(aimErr, plan.aimDeg, Random) })
            aimed = FightRules.aimOk(err, plan.aimDeg) && inEnvelope(p, plan, c)
            keep = aimed
        }
        else {
            // an ability needs the view on the creature, within your right-click error (drawn once per move)
            val (aimView, pitchOnly) = aimViewAt(p, aimPoint(c))
            val err = MotorModel.angle(p.yRot.toDouble(), p.xRot.toDouble(), aimView.yaw, aimView.pitch)
            need = Need.RANGED
            steer(p, c, need, aimView, pitchOnly, err, Double.NaN, now, cls, aimPoint(c), accept = ::takeExplicitAccept, shortShare = EXPLICIT_SHORT)
            val inRange = p.eyePosition.distanceTo(c.position) <= abilityRange.value
            aimed = inRange && err <= policy.acceptance
            // a hold already going is not let go for a creature moving a little
            keep = inRange && err <= FightRules.KEEP_HOLD_DEG
        }
        // your first press after the view got where the plan needs it came a reaction later (on the view there from the
        // target's first tick, none); the entry is watched every tick, off ticks too, or no reaction is ever drawn
        val ready = boxEntry.ready(now, aimed) { draw(FightRules.entryAction(need), emptyList()) }
        // never with a fishing rod in hand (it would cast), an item that moves you (the Aspect of the Void, the Grappling
        // Hook) or a fishing tool, on any entity but this creature (another player's creature, a player: it would interact
        // with it; a hold on it goes up at once), a door or a lever, with something the ability must not hit on its way
        // or in its area, nor a teleport blade while not looking down; held back for any of those, a reaction after it
        // moved away (a Learned plan moves on to another option first while its area is not clear: [refreshPlans]). A look
        // down still on its way is checked over the area planned around it, never along the near-level view it turns from
        val blocked = when (FightRules.useCheck(lookDown, reached, downHeld)) {
            FightRules.AreaCheck.VIEW -> useBlocked(p, id)
            FightRules.AreaCheck.PLANNED -> plannedBlocked
            FightRules.AreaCheck.NONE -> false
        }
        val clear = useWay.ready(now, blocked) { draw(Action.PATH_CLEAR, emptyList()) }
        val allowed = FightRules.useAllowed(id, AutoFishing.isFishingRod(p.mainHandItem), p.xRot) && clear
        if (style == ClickStyle.HOLD) holdUse(p, id, ready && allowed, keep, allowed, now)
        else press(p, id, plan != null, ready && allowed, now)
    }

    /** Single right presses at your gap with that item (Learned) or your right-click gap. */
    private fun press(p: LocalPlayer, id: String, learned: Boolean, ready: Boolean, now: Long) {
        // a hold from a plan just switched: never a press while the key is held (it would let the key go)
        if (hold.holding) {
            releaseUse()
            nextClickAt = TimingModel.dueAt(now, holdGap(id))
        }
        if (now < nextClickAt || ! ready || ! handFree(p, now, left = false)) return
        meanUse(p, id)
        PlayerUtils.rightClick()
        KillerTrace.click(p, left = false, hold = false, target = target?.id ?: - 1)
        nextClickAt = TimingModel.dueAt(now, pressGap(id, learned))
        escalation.use()
        clickTick = p.tickCount
        acted = true
        target?.let { clickedAt(it.id, now) }
    }

    /**
     * The use key held for one of your hold lengths with that item, then let go for one of your gaps, again while the
     * target is in reach and in view ([ready] to start, [keep] to go on). Vanilla uses the item every 4 ticks meanwhile,
     * as for you. Once the view or the distance no longer keeps it, the key goes up a reaction later like yours at a
     * death ([Action.DEATH_REACTION]: 50-950 ms, none of it at once), not in that tick; its repeats meanwhile are no use
     * of the plan.
     */
    private fun holdUse(p: LocalPlayer, id: String, ready: Boolean, keep: Boolean, allowed: Boolean, now: Long) {
        if (hold.holding) {
            if (FightRules.holdEnds(allowed, hold.releaseDue(now), keep, keepOff, now) { draw(Action.DEATH_REACTION, emptyList()) }) {
                keepOff.clear()
                releaseUse()
                nextClickAt = TimingModel.dueAt(now, holdGap(id))
                return
            }
            repeat(hold.newRepeats(p.tickCount)) {
                if (keep) escalation.use()
                KillerTrace.click(p, left = false, hold = true, target = target?.id ?: - 1)
            }
            return
        }
        keepOff.clear()
        if (now < nextClickAt || ! ready || ! handFree(p, now, left = false)) return
        meanUse(p, id)
        // let go in the tick nearest to the drawn length, like every other wait checked once a tick
        hold.start(now, p.tickCount, holdMs(id) - TimingModel.TICK_MS / 2)
        PlayerUtils.holdUse()
        KillerTrace.click(p, left = false, hold = false, target = target?.id ?: - 1)
        escalation.use()
        clickTick = p.tickCount
        acted = true
        target?.let { clickedAt(it.id, now) }
    }

    /** The killer's press or hold that starts now is meant for [id] in the selected slot ([guardUse] checks it when the game uses the item). */
    private fun meanUse(p: LocalPlayer, id: String) {
        useSlot = p.inventory.selectedSlot
        useId = id
        usePending = true
        // a rod-type item casts with it: that bobber is the killer's, never your fishing view
        killerUseAt = System.currentTimeMillis()
    }

    /** Lets go of the use key the killer holds (a hold of yours stays). */
    private fun releaseUse() {
        if (! hold.holding) return
        hold.stop()
        PlayerUtils.releaseUse()
        // the hold's last repeat may still cast: its bobber shows up after this
        killerUseAt = System.currentTimeMillis()
        keyUpAt = killerUseAt
    }

    private fun styleOf(id: String) = BehaviorProfile.current.weaponStyles[id]

    /** A wait for [action]: one of your [samples] ([TimingModel]), [state] for the actions whose slow waits come in streaks. */
    private fun draw(action: Action, samples: List<Int>, state: StreakState? = null) = TimingModel.draw(action, samples, state, Random)

    /** Your gap between right presses with [id] (Learned, enough of them), else your right-click gaps. */
    private fun pressGap(id: String, learned: Boolean): Long {
        val gaps = styleOf(id)?.gapMs.orEmpty()
        return draw(Action.PRESS_GAP, if (learned && gaps.size >= FightRules.MIN_SAMPLES) gaps else BehaviorProfile.current.clickRightMs)
    }

    /** One of your hold lengths with [id] (yours lasted 1.1 s at the median, up to 10.5 s). */
    private fun holdMs(id: String) = draw(Action.HOLD, styleOf(id)?.holdMs.orEmpty())

    /** One of your gaps between letting go and the next press with [id]. */
    private fun holdGap(id: String) = draw(Action.HOLD_GAP, styleOf(id)?.gapMs.orEmpty())

    /** Auto Fishing's recast after [c]'s catch (it comes around the spawn, a slow one up to 3 s after it), or null when there was none. */
    private fun recastFor(c: TrackedCreature) =
        AutoFishing.lastCatchRecastAt.takeIf { it >= c.spawnedAt - RECAST_WAIT_MS && it <= c.spawnedAt + FightRules.RECAST_PENDING_MS }

    /**
     * The first hit on a recast-first rod target, drawn once per creature: your first rod hit after a missed bobber,
     * timed from the recast after its catch, or your usual first hit after a spawn when no recast came, drawn from the
     * spawn alone and again only when it would fall before [after] (REACT's first tick, or where its wait for a slow
     * recast that never came ran out: never piled there).
     */
    private fun firstRodHitAt(c: TrackedCreature, after: Long = Long.MIN_VALUE): Long = firstHitAt.getOrPut(c.id) {
        val profile = BehaviorProfile.current
        // only the first draw keeps your streak of slow first hits going; one drawn again is the same hit
        var state: StreakState? = firstHitStreak
        FightRules.firstRodHit(c.spawnedAt, recastFor(c), after, { draw(Action.BOBBER_MISS, profile.bobberMissMs) },
            { draw(Action.FIRST_HIT, profile.firstHitMs, state).also { state = null } }) { draw(Action.FIRST_HIT, profile.firstHitMs) }
    }

    /**
     * One of your first hits after [c]'s spawn, drawn from it alone after [after] (REACT's tick), to weigh against a
     * recast still to come ([recastLead]): on a copy of your streak of slow first hits, taken into it only when this hit is
     * the one that goes ([takeLeadHit]), so a hit drawn only to be weighed never moves that streak on.
     */
    private fun leadHit(c: TrackedCreature, after: Long): Long {
        val profile = BehaviorProfile.current
        val trial = FightRules.peek(firstHitStreak)
        leadStreak = trial
        var state: StreakState? = trial
        return FightRules.firstRodHit(c.spawnedAt, null, after, { error("no recast came") },
            { draw(Action.FIRST_HIT, profile.firstHitMs, state).also { state = null } }) { draw(Action.FIRST_HIT, profile.firstHitMs) }
    }

    /** The first hit drawn against the recast ([leadHit]) is [c]'s: the recast did not come before it. False when none was drawn (or [c] had one already). */
    private fun takeLeadHit(c: TrackedCreature): Boolean {
        if (recastLead == NO_LEAD || leadStreak == null || c.id in firstHitAt) return false
        firstHitAt[c.id] = recastLead
        leadStreak?.let { FightRules.carry(it, firstHitStreak) }
        return true
    }

    /** Your wait from a recast after a catch to your swap to a weapon, drawn once per REACT ([recastSwapMs]). */
    private fun recastSwapGap(): Long {
        if (recastSwapMs == NO_LEAD) recastSwapMs = draw(Action.RECAST_TO_SWAP, emptyList())
        return recastSwapMs
    }

    /** Your swap reaction from [anchor] (the spawn, or the return in reach) drawn again until it falls after [now], past its tries a reaction from [now]. */
    private fun swapReactAfter(anchor: Long, now: Long) = FightRules.reactAfter(anchor, now,
        { draw(Action.SWAP_REACTION, BehaviorProfile.current.swapReactionMs) }) { draw(Action.SPAWN_REACTION, BehaviorProfile.current.spawnReactionMs) }

    /**
     * About how long a turn from your view to [c] usually takes ([MotorModel.typicalMs] with your hand), 0 when the policy
     * would not turn: the creature is under the crosshair or within its box already, or falls into it by itself.
     */
    private fun turnMsTo(c: TrackedCreature): Long {
        val p = mc.player ?: return 0L
        val (aimView, _) = aimViewAt(p, aimPoint(c))
        val error = MotorModel.angle(p.yRot.toDouble(), p.xRot.toDouble(), aimView.yaw, aimView.pitch)
        val typical = MotorModel.typicalMs(p.yRot.toDouble(), p.xRot.toDouble(), aimView.yaw, aimView.pitch, HumanAim.paramsOf(BehaviorProfile.current.motor))
        return FightRules.turnMs(error, boxHalfDeg(p, c), fallsIntoView(c, System.currentTimeMillis()), typical)
    }

    /**
     * About how long the first turn towards [c] takes with the move its plan makes ([glanceNeed]): a melee turn onto it
     * ([turnMsTo]), a teleport blade's look-down to its pitch, a ranged turn to within the plan's aim (an explicit rule's:
     * your usual right-click error); 0 when no turn comes, the view within that already or the plan an area weapon's.
     */
    private fun planTurnMs(p: LocalPlayer, c: TrackedCreature, now: Long): Long {
        val profile = BehaviorProfile.current
        val params = HumanAim.paramsOf(profile.motor)
        val plan = book.plans[c.id]
        val yaw = p.yRot.toDouble()
        val pitch = p.xRot.toDouble()
        return when (glanceNeed(c, now)) {
            Need.NONE -> 0L
            Need.MELEE -> turnMsTo(c)
            Need.PITCH -> {
                val viewPitch = planViewPitch(p, plan)
                FightRules.lookDownMs(p.xRot, viewPitch) { MotorModel.typicalMs(yaw, pitch, yaw, viewPitch.toDouble(), params, lookDown = true) }
            }
            Need.RANGED -> {
                val point = if (plan != null) learnedPoint(c) else aimPoint(c)
                val (aimView, _) = aimViewAt(p, point)
                // an explicit rule: the acceptance the policy will judge the view by ([explicitAccept]), not its median
                FightRules.turnMs(viewError(p, point), FightRules.rangedTolerance(plan?.aimDeg ?: explicitAccept.takeUnless { it.isNaN() }, profile.rightClickErr), false,
                    MotorModel.typicalMs(yaw, pitch, aimView.yaw, aimView.pitch, params))
            }
        }
    }

    /** The pitch a look-down plan clicks at: the [plan]'s, else the learned one of the blade in hand, else straight down. */
    private fun planViewPitch(p: LocalPlayer, plan: Plan?): Float {
        val id = plan?.option ?: p.mainHandItem.skyblockId
        return plan?.viewPitch?.takeUnless { it.isNaN() } ?: WeaponPlanner.plan(BehaviorProfile.current, id)?.viewPitch?.takeUnless { it.isNaN() } ?: FightRules.BLADE_VIEW_PITCH
    }

    /** The explicit rule's acceptance drawn ahead for this target ([explicitAccept]) the first time, then one drawn per move. */
    private fun takeExplicitAccept(): Double {
        val ahead = explicitAccept
        explicitAccept = Double.NaN
        return if (ahead.isNaN()) FightRules.explicitAcceptance(BehaviorProfile.current.rightClickErr, Random) else ahead
    }

    /**
     * The view is where [c]'s plan needs it for its first click, in ticks no plan steers (a plan switch, [BoxEntry]): the
     * crosshair on it within reach for melee, its pitch for a look-down, within the plan's aim (an explicit rule's: the
     * acceptance of its last move, else your usual right-click error) for a ranged plan; always for an area weapon.
     */
    private fun viewOnFor(p: LocalPlayer, c: TrackedCreature, now: Long): Boolean {
        val plan = book.plans[c.id]
        return when (glanceNeed(c, now)) {
            Need.NONE -> true
            Need.MELEE -> (mc.hitResult as? EntityHitResult)?.takeIf { it.entity === c.mob || it.entity === c.stand }?.let { p.eyePosition.distanceTo(it.location) <= MELEE_REACH } == true
            Need.PITCH -> FightRules.pitchReached(p.xRot, planViewPitch(p, plan))
            Need.RANGED -> viewError(p, if (plan != null) learnedPoint(c) else aimPoint(c)) <=
                FightRules.rangedTolerance(plan?.aimDeg ?: policy.acceptance.takeUnless { it.isNaN() }, BehaviorProfile.current.rightClickErr)
        }
    }

    /**
     * A creature right above or below you (it lands on you) usually comes into the crosshair by itself soon after its
     * spawn: until a wait drawn once per creature ([Action.FALL_WAIT]), never the same moment after every spawn.
     */
    private fun fallsIntoView(c: TrackedCreature, now: Long): Boolean {
        val until = fallWaits.getOrPut(c.id) { TimingModel.dueAt(c.spawnedAt, draw(Action.FALL_WAIT, emptyList())) }
        if (now > until) return false
        val eye = mc.player?.eyePosition ?: return false
        val pos = c.position
        return kotlin.math.hypot(pos.x - eye.x, pos.z - eye.z) < FALL_RADIUS
    }

    /**
     * Time to your next melee click: your recorded gaps with the rod or a weapon (old profiles only have them pooled),
     * else the gaps of a recorded player: rod hits in two rhythms, weapon hits fast and now and then slower. [rhythm]:
     * a real click, which keeps your fast or slow rhythm going; a missed click or the gap to the next creature does not.
     * [consume] false: a beat in your rhythm read for a click that may never go out, which leaves the rhythm as it was.
     */
    private fun meleeGap(rod: Boolean, rhythm: Boolean, consume: Boolean = true): Long {
        val profile = BehaviorProfile.current
        val pooled = profile.clickLeftRodMs.isEmpty() && profile.clickLeftWeaponMs.isEmpty()
        val samples = if (pooled) profile.clickLeftMs else if (rod) profile.clickLeftRodMs else profile.clickLeftWeaponMs
        // the rod rhythm the learner fitted within your runs of hits; the gap list alone mixes runs and reads less of it
        rodRhythm.stay = FightRules.learnedRho(profile.timing)
        val state = if (! rhythm) null else if (rod) rodRhythm else weaponRhythm
        return draw(if (rod) Action.MELEE_GAP_ROD else Action.MELEE_GAP_WEAPON, samples, if (consume) state else state?.let(FightRules::peek))
    }

    /**
     * The rod back in hand, then the view back towards the water when the fight's turn back wants it; not before your
     * reaction to a screen of yours closing ([rodBackReady]: the phase tries again next tick).
     */
    private fun rodBack(now: Long) {
        val p = mc.player
        if (p != null) {
            if (! rodBackReady(p, now)) return
            rodSlotNow()?.let { selectSlot(p, it) }
        }
        startTurnBack(now)
    }

    /** Taking the rod back may go now: it is in hand already (or none is there), or your reaction to a screen's close passed ([ScreenGate]). */
    private fun rodBackReady(p: LocalPlayer, now: Long) = rodSlotNow()?.let { it == p.inventory.selectedSlot || screenGate.ready(ScreenGate.Kind.SLOT, now) } ?: true

    /** A swap to what [r] fights with may go now: none is needed, or your reaction to a screen's close passed ([ScreenGate]). */
    private fun swapReady(r: KillRule?, p: LocalPlayer, now: Long): Boolean {
        val slot = r?.let { slotFor(it, p) } ?: return true
        return ! Inventory.isHotbarSlot(slot) || slot == p.inventory.selectedSlot || screenGate.ready(ScreenGate.Kind.SLOT, now)
    }

    /**
     * Your hand is free at [now] for creatures of yours already alive (a death ended the killer's group, or the creature its
     * reaction was for is gone): your reaction to them comes from now, one of your swaps to another creature after a death
     * ([Action.DEATH_TO_SWAP]); with the use key held through it, one of your waits from its key-up to a swap instead (drawn
     * once it went up, [Phase.REACT]): your wait from the death holds that key-up already, so never both. One whose item
     * is in hand already needs no swap: the plan's own gap instead ([olderGap], [FightRules.olderReact]).
     */
    private fun freeHand(now: Long) {
        freedAt = now
        freedReactAt = if (hold.holding) Long.MAX_VALUE else TimingModel.dueAt(now, draw(Action.DEATH_TO_SWAP, emptyList()))
    }

    /** [r] takes another item than the one in hand, or its loadout first: a swap (or /loadout) comes before its fight. */
    private fun swapsFor(p: LocalPlayer, r: KillRule): Boolean {
        val slot = slotFor(r, p)
        val loadout = r.action == KillAction.WEAPON && r.loadout != 0 && r.loadout != LoadoutSwitcher.lastEquipped
        return loadout || slot != null && Inventory.isHotbarSlot(slot) && slot != p.inventory.selectedSlot
    }

    /**
     * When the fight on [c], a creature already there when the hand was freed, starts ([FightRules.olderReact] gave [at]):
     * with a swap (or the use key still down) at [at]. With no swap [at] is its first click, one of the plan's own gaps
     * after the death ([olderGap]), and a turn its plan makes ([planTurnMs]) starts inside that wait, a reaction to the view
     * getting there before the click ([FightRules.turnAtForHit], waited by that click: [BoxEntry]), though never before
     * the hand's reaction to the death ([from]: it, or the key-up after it; [FightRules.olderTurnAt]): your first clicks at
     * one with no swap came p50 448 ms after the death with your turn inside (181; the rod's left clicks p50 351). The
     * turn and the reaction to the view getting there are timed for the creature the fight then picks ([pickTarget]: the
     * nearest of [c]'s group, [olderFor]), never for one and taken by another.
     */
    private fun olderStart(p: LocalPlayer, c: TrackedCreature, at: Long, from: Long, now: Long): Long {
        if (reactSwaps || at == Long.MAX_VALUE) return at
        val pick = ruleOf(c)?.let { r -> FightRules.pick(groupTargets(r), - 1, { it.id }) { it.position.distanceToSqr(p.position()) } } ?: c
        olderClickAt = at
        olderFor = pick.id
        return FightRules.olderTurnAt(at, from, planTurnMs(p, pick, now), { firstEntryLead.getOrPut(pick.id) { draw(FightRules.entryAction(glanceNeed(pick, now)), emptyList()) } }) {
            draw(Action.DEATH_REACTION, emptyList())
        }
    }

    /**
     * A gap of the plan's own clicks for [c] with the item in hand ([FightRules.nextTargetGap], as [Phase.SWAP] takes it
     * for the next creature of a group after a death): your first clicks at another creature already there after a death,
     * with no swap, came p50 448 ms after it (181; the rod's left clicks p50 351 of 87), not a swap's 1296 (45).
     */
    private fun olderGap(p: LocalPlayer, c: TrackedCreature): Long {
        val r = ruleOf(c) ?: KillRules.ruleFor(c.creature, c.name)
        val style = book.plans[c.id]?.style ?: explicitStyle(r, p, c)
        val rod = AutoFishing.isFishingRod(p.mainHandItem)
        val id = p.mainHandItem.skyblockId
        return FightRules.nextTargetGap(style, { meleeGap(rod, rhythm = false) }, { pressGap(id, isLearned(c)) }) { holdGap(id) }
    }

    /** Your hand's reaction to Auto Fishing stopping in a fight: your reaction to a new creature, within your slowest key-up after a death. */
    private fun offReactionMs() = TimingModel.draw(Action.SPAWN_REACTION, BehaviorProfile.current.spawnReactionMs, null, Random, Action.DEATH_REACTION.capMs)

    /** The turn back's phase while one is owed (or its move still turns the view), else back to fishing. */
    private fun startTurnBack(now: Long) {
        val b = back
        if (b == null || b.done) return finishFight()
        phase = Phase.TURN_BACK
        driveTurnBack(now)
    }

    private fun finishFight() {
        if (acted) say("back to fishing")
        fightEndedAt = System.currentTimeMillis()
        releaseUse()
        // a move still turning comes to rest (it holds Auto Fishing until then)
        aim.stopFight()
        handLag.clear()
        rodFree.clear()
        keepOff.clear()
        // the press it held for the way is gone with the fight: the next fight's first press is no reaction to this way
        useWay.clear()
        viewTaken = false
        looks.hold()
        back = null
        policy.reset()
        swapAt = 0L
        phase = Phase.IDLE
        rule = null
        target = null
        holdUntil = 0L
        turned = false
        acted = false
        lookOnly = false
        reactWeapon = false
        reactOlder = false
        replan = false
        leadId = - 1
        buttonPressed = false
        triedLoadouts.clear()
    }

    /** The fishing rod to put back: the one in hand when the fight started, else that kind, else any fishing rod (never a rod-type weapon). */
    private fun rodSlotNow(): Int? {
        val p = mc.player ?: return null
        if (Inventory.isHotbarSlot(rodSlot) && p.inventory.getItem(rodSlot).let { AutoFishing.isFishingRod(it) && (rodId.isEmpty() || it.skyblockId == rodId) }) return rodSlot
        return PlayerUtils.findHotbarSlot { AutoFishing.isFishingRod(it) && (rodId.isEmpty() || it.skyblockId == rodId) }
            ?: PlayerUtils.findHotbarSlot { AutoFishing.isFishingRod(it) }
    }

    /** Movement keys, a held attack or use button, another hotbar slot, or turning the mouse yourself. */
    private enum class Takeover { KEYS, BUTTON, SLOT, MOUSE }

    /**
     * What you did yourself, if anything: movement keys, the attack or use button (pressed, or held while the killer does
     * not hold the use key), another hotbar slot (a hotbar key, the scroll wheel), or turning the mouse.
     */
    private fun userTookOver(p: LocalPlayer, now: Long): Takeover? {
        val o = mc.options
        if (o.keyUp.isDown || o.keyDown.isDown || o.keyLeft.isDown || o.keyRight.isDown || o.keyJump.isDown) return Takeover.KEYS
        if (buttonPressed || o.keyAttack.isDown || ! hold.holding && o.keyUse.isDown) return Takeover.BUTTON
        if (slotWatch.changed(p.inventory.selectedSlot)) return Takeover.SLOT
        return Takeover.MOUSE.takeIf { mouseTurn * kotlin.math.exp(- (now - mouseAt) / MOUSE_DECAY_MS) > TAKEOVER_DEGREES }
    }

    /**
     * The plans of your Learned creatures, by creature id. A plan sticks while the creature stays in its reach, its item in
     * your hotbar and its option not given up on it; a re-choose about every 2 s ([rechoose], drawn each time, so no
     * switch falls on a grid) may still switch to another weapon, never back to the rod. A creature nothing reaches is
     * parked and checked again every 500 ms; it comes back once a plan reaches it with a margin
     * ([WeaponPlanner.inEnvelope]'s shrink) after 1.5 s parked. Options that did nothing to a creature stay excluded until
     * 15 s after the last one. No game state, so it is unit tested.
     */
    internal class PlanBook(private val rechoose: () -> Long = { TimingModel.draw(Action.RECHOOSE, emptyList(), null, Random) }) {
        enum class Change { NONE, PLANNED, SWITCHED, PARKED, UNPARKED }

        /** Creature id -> its current plan (none while parked). */
        val plans = HashMap<Int, Plan>()
        /** Parked creature id -> when it is checked again. */
        val parked = HashMap<Int, Long>()
        /** Creature id -> when it came back from parking. */
        val unparkedAt = HashMap<Int, Long>()
        private val parkedSince = HashMap<Int, Long>()
        /** Creature id -> when its plan is chosen again. */
        private val rechooseAt = HashMap<Int, Long>()
        private val excluded = HashMap<Int, HashSet<String>>()
        private val excludedAt = HashMap<Int, Long>()

        /** The options given up on creature [id]. */
        fun excluded(id: Int): Set<String> = excluded[id].orEmpty()

        /**
         * This tick's plan for creature [id] at [hd], [dy] from your feet with [available] in your hotbar; [choose] is
         * [WeaponPlanner.choose] for it with the excluded options and the shrink flag.
         */
        fun update(id: Int, now: Long, hd: Double, dy: Double, available: Set<String>, choose: (excluded: Set<String>, shrink: Boolean) -> Plan?): Change {
            excludedAt[id]?.let {
                if (now - it >= EXCLUDE_MS) {
                    excluded.remove(id)
                    excludedAt.remove(id)
                }
            }
            val ex = excluded(id)
            parked[id]?.let { at ->
                if (now < at) return Change.NONE
                val plan = choose(ex, false)
                if (plan == null || now - (parkedSince[id] ?: now) < UNPARK_AFTER_MS || choose(ex, true) == null) {
                    parked[id] = now + RECHECK_MS
                    return Change.NONE
                }
                parked.remove(id)
                parkedSince.remove(id)
                plans[id] = plan
                rechooseAt[id] = now + rechoose()
                unparkedAt[id] = now
                return Change.UNPARKED
            }
            val current = plans[id]
            if (current != null && current.option in available && current.option !in ex && WeaponPlanner.inEnvelope(current, hd, dy)) {
                if (now < (rechooseAt[id] ?: Long.MAX_VALUE)) return Change.NONE
                rechooseAt[id] = now + rechoose()
                val again = choose(ex, false)
                if (again == null || again.option == ROD || again.option == current.option) return Change.NONE
                plans[id] = again
                return Change.SWITCHED
            }
            rechooseAt[id] = now + rechoose()
            val plan = choose(ex, false)
            if (plan == null) {
                plans.remove(id)
                parked[id] = now + RECHECK_MS
                parkedSince[id] = now
                return Change.PARKED
            }
            plans[id] = plan
            return when {
                current == null -> Change.PLANNED
                current.option != plan.option -> Change.SWITCHED
                else -> Change.NONE
            }
        }

        /** [option] did nothing to creature [id]: it is not planned for it again for a while, and its plan is chosen anew. */
        fun exclude(id: Int, option: String, now: Long) {
            excluded.getOrPut(id) { HashSet() } += option
            excludedAt[id] = now
            plans.remove(id)
        }

        fun forget(id: Int) {
            plans.remove(id)
            parked.remove(id)
            unparkedAt.remove(id)
            parkedSince.remove(id)
            rechooseAt.remove(id)
            excluded.remove(id)
            excludedAt.remove(id)
        }

        /** Drops every creature but [ids] (the dead, the ones gone out of view). */
        fun retain(ids: Set<Int>) {
            listOf(plans, parked, unparkedAt, parkedSince, rechooseAt, excluded, excludedAt).forEach { it.keys.retainAll(ids) }
        }

        fun clear() = retain(emptySet())

        companion object {
            const val RECHECK_MS = 500L
            const val UNPARK_AFTER_MS = 1500L
            const val EXCLUDE_MS = 15_000L
        }
    }

    /**
     * Uses of one option on the current target since its last hp drop (or since the option started on it); enough of
     * them for long enough and the option is given up on that target. How many and how long are drawn anew each time the
     * count starts over ([needUses], [needMs]): never the same number of uses that did nothing before every switch. No game
     * state, so it is unit tested.
     */
    internal class Escalation(
        private val needUses: () -> Int = { FightRules.escalateUses(Random) },
        private val needMs: () -> Long = { TimingModel.draw(Action.ESCALATE, emptyList(), null, Random) },
    ) {
        /** The option counted for, empty for none (explicit rules never escalate). */
        var option = ""
            private set
        var uses = 0
            private set
        private var since = 0L
        private var uses0 = FightRules.ESCALATE_USES
        private var ms0 = FightRules.ESCALATE_MS

        fun start(option: String, now: Long) {
            this.option = option
            over(now)
        }

        fun hpDrop(now: Long) = over(now)

        private fun over(now: Long) {
            since = now
            uses = 0
            if (option.isEmpty()) return
            uses0 = needUses()
            ms0 = needMs()
        }

        /** A press, a hold repeat in reach and view, or a melee click at the creature (or one due that could not land; never a miss next to it). */
        fun use() {
            uses ++
        }

        fun due(now: Long) = option.isNotEmpty() && uses >= uses0 && now - since >= ms0
    }

    /** The use key held by the killer: when to let go, and the uses vanilla repeats meanwhile (every 4th tick). Unit tested. */
    internal class HoldCycle {
        var holding = false
            private set
        private var releaseAt = 0L
        private var startTick = 0
        private var counted = 0

        /** Pressed in player tick [tick] for [ms]. */
        fun start(now: Long, tick: Int, ms: Long) {
            holding = true
            releaseAt = now + ms
            startTick = tick
            counted = 0
        }

        fun releaseDue(now: Long) = holding && now >= releaseAt

        /** Repeats of the use since the last call: the first comes 4 ticks after the press, then every 4 ticks. */
        fun newRepeats(tick: Int): Int {
            if (! holding) return 0
            val total = maxOf(0, (tick - startTick) / REPEAT_TICKS)
            val n = total - counted
            counted = total
            return maxOf(0, n)
        }

        fun stop() {
            holding = false
        }

        companion object {
            /** Vanilla's use delay while the use key stays down. */
            const val REPEAT_TICKS = 4
        }
    }

    /**
     * How long a weapon loadout stays on for parked creatures: a [keep] drawn from the first fight that ended with creatures
     * parked, and again from each one that comes back in reach ([Action.PARKED_KEEP]: your /loadout back came 3.7-22.7 s
     * after the rod while a creature of yours stayed alive, never on a fixed timer). Fights against other creatures
     * meanwhile (a new catch every few seconds) do not keep it on longer. No game state, so it is unit tested.
     */
    internal class ParkedLoadout(private val keep: () -> Long = { TimingModel.draw(Action.PARKED_KEEP, emptyList(), null, Random) }) {
        /** The Fishing Loadout may come back from then on while creatures stay parked. */
        var until = 0L
            private set
        private var kept = false

        /** A fight ended with creatures parked and a weapon loadout on: it stays on for a while, unless a fight kept it already. */
        fun fightEnded(now: Long) {
            if (kept) return
            kept = true
            until = now + keep()
        }

        /** A parked creature came back in reach: the loadout is used soon. */
        fun unparked(now: Long) {
            until = now + keep()
        }

        /** The Fishing Loadout may come back: nothing is parked ([anyParked]), or nothing came back in reach for a while. */
        fun restoreDue(now: Long, anyParked: Boolean) = ! anyParked || now >= until

        /** The Fishing Loadout is back, or nothing is parked any more: the next fight that keeps the loadout starts over. */
        fun clear() {
            kept = false
            until = 0L
        }
    }

    /**
     * Your hand after the creature it fought died, or after a new one came up while the view turned: it reacts a moment
     * later, not in that tick. Until then the use key held stays down (vanilla repeats the use meanwhile) and a move still
     * turning goes on; then the key goes up and the move comes to rest, unless the next fight has taken them over (or the
     * move ended) by then. Now and then a click already on its way still goes out at nothing: after 16 % of your melee
     * kills with nothing else of yours alive and 32 % with another one alive, one more in your rhythm after 37 % of those;
     * after 23 % of your right presses (73 and 9 such clicks in your recordings). No game state, so it is unit tested.
     */
    internal class HandLag {
        /** The use key held goes up then ([NONE]: nothing to let go). */
        var keyUpAt = NONE
            private set
        /** A move of the fight still turning comes to rest then ([NONE]: none to bring to rest). */
        var stopAt = NONE
            private set
        /** A click on its way goes out then ([NONE]: none), clicked like the fight before the death ([style]). */
        var clickAt = NONE
            private set
        var style: ClickStyle? = null
            private set
        /** The click at [clickAt] may be followed by one more in your rhythm. */
        private var again = false
        /** The death the clicks on their way follow: none goes out more than [CLICK_WINDOW_MS] after it. */
        private var diedAt = Long.MIN_VALUE / 2

        /**
         * The target died at [now] with the use key [held] down, fought with [style]; [clicking]: the killer clicked at it
         * within [CLICKING_MS], [others]: another creature of yours is alive. The key and the move each wait a drawn
         * [reaction]; a click on its way waits [clickDelay] for its style, and comes no sooner than [notBefore] for it (a
         * beat of yours after the killer's last click at it: yours were never closer than 97 ms), drawn only for one.
         */
        fun died(
            now: Long, held: Boolean, style: ClickStyle?, clicking: Boolean, others: Boolean, rng: Random, reaction: () -> Long,
            notBefore: (ClickStyle) -> Long = { Long.MIN_VALUE }, clickDelay: (ClickStyle) -> Long,
        ) {
            keyUpAt = if (held) TimingModel.dueAt(now, reaction()) else NONE
            stopAt = TimingModel.dueAt(now, reaction())
            this.style = style
            clickAt = NONE
            again = false
            diedAt = now
            if (! clicking || style == null || style == ClickStyle.HOLD) return
            val share = if (style == ClickStyle.LEFT) (if (others) MELEE_OTHERS else MELEE_ALONE) else PRESS
            if (rng.nextDouble() >= share) return
            clickAt = maxOf(TimingModel.dueAt(now, clickDelay(style)), notBefore(style))
            again = style == ClickStyle.LEFT
        }

        /**
         * The target was left alive at [now] (parked out of reach, given up on, its plan switched): the hand lets go of
         * the key [held] and the move comes to rest a drawn [reaction] later each, as after a death; no click is on its way.
         * A reaction already on its way (the plan was given up a tick before its switch) is kept, never drawn again.
         */
        fun letGo(now: Long, held: Boolean, reaction: () -> Long) {
            keyUpAt = if (! held) NONE else if (keyUpAt != NONE) keyUpAt else TimingModel.dueAt(now, reaction())
            if (stopAt == NONE) stopAt = TimingModel.dueAt(now, reaction())
        }

        /** The click at [clickAt] is still held back at [now] more than [CLICK_WINDOW_MS] after the death: it never goes. */
        fun clickExpired(now: Long) = now - diedAt > CLICK_WINDOW_MS

        /** A new creature came up: a move still turning comes to rest at [at] (a reaction to it), or earlier when one is due already. */
        fun stopBy(at: Long) {
            stopAt = min(stopAt, at)
        }

        /** The click at [clickAt] went out at [now]: one more melee click follows after a [gap] of your rhythm at your odds, else none. */
        fun clicked(now: Long, rng: Random, gap: () -> Long) {
            clickAt = if (again && rng.nextDouble() < MELEE_AGAIN) TimingModel.dueAt(now, gap()) else NONE
            again = false
        }

        fun keyUp() {
            keyUpAt = NONE
        }

        /** The use key held goes up at [at] at the latest (something it must not hit came onto its way). */
        fun keyUpBy(at: Long) {
            keyUpAt = min(keyUpAt, at)
        }

        fun stopped() {
            stopAt = NONE
        }

        fun dropClick() {
            clickAt = NONE
            again = false
        }

        fun clear() {
            keyUp()
            stopped()
            dropClick()
            style = null
        }

        companion object {
            const val NONE = Long.MAX_VALUE
            /** A death this long after the killer's last click at the creature finds the hand still clicking (your last ones came up to 1.45 s before). */
            const val CLICKING_MS = 1500L
            /** Your melee kills followed by a click at nothing: 35 of 215 with nothing else of yours alive... */
            const val MELEE_ALONE = 0.163
            /** ...38 of 119 with another one alive (not counting your clicks at that one)... */
            const val MELEE_OTHERS = 0.319
            /** ...and a second one after 27 of those 73. */
            const val MELEE_AGAIN = 0.37
            /** Your kills with right presses followed by one more press: 9 of 40. */
            const val PRESS = 0.225
            /** Your clicks at nothing after a death were counted within a second of it, 902 ms after it the latest. */
            const val CLICK_WINDOW_MS = 1000L
        }
    }

    /**
     * A reaction to a moment that came: due a drawn wait after the first tick its conditions held, and drawn anew once they
     * stopped holding before it. No game state, so it is unit tested.
     */
    internal class Reaction {
        private var dueAt = Long.MIN_VALUE

        /** The conditions hold ([holds]) at [now]: true once the wait [draw]n at the first tick they held has passed. */
        fun ready(now: Long, holds: Boolean, draw: () -> Long): Boolean {
            if (! holds) {
                dueAt = Long.MIN_VALUE
                return false
            }
            if (dueAt == Long.MIN_VALUE) dueAt = TimingModel.dueAt(now, draw())
            return now >= dueAt
        }

        fun clear() {
            dueAt = Long.MIN_VALUE
        }
    }

    /**
     * Something due waits while what it needs is not free (Auto Fishing's rod reeling or casting): never held back, it goes
     * at once; once it waited, a [Reaction] after the first tick it is free, not in that tick. No game state, so it is
     * unit tested.
     */
    internal class FreeWait {
        private val reaction = Reaction()
        private var waited = false

        /** It may go at [now] with what it needs [free] ([draw]: the reaction to it coming free). */
        fun ready(now: Long, free: Boolean, draw: () -> Long): Boolean {
            if (! free) waited = true
            if (! waited) return true
            if (! reaction.ready(now, free, draw)) return false
            clear()
            return true
        }

        fun clear() {
            waited = false
            reaction.clear()
        }
    }

    /**
     * After a screen of yours closed, each kind of the killer's actions ([Kind]: a slot change, a command, a move of the
     * view) waits your reaction to the close, drawn once per close for the first one of that kind ([draw]): what fell due
     * behind the screen never goes in the first tick after it (a move may, as 4 of your 14 did). Nothing waits before the
     * first close. No game state, so it is unit tested.
     */
    internal class ScreenGate(private val draw: (Kind) -> Long) {
        enum class Kind(val action: Action) {
            SLOT(Action.SCREEN_CLOSE_TO_SLOT),
            COMMAND(Action.SCREEN_CLOSE_TO_COMMAND),
            MOVE(Action.SCREEN_CLOSE_TO_MOVE),
        }

        private var closedAt = NONE
        private val due = LongArray(Kind.entries.size) { NONE }

        /** A screen of yours closed at [now]: each kind waits a reaction of its own to it. */
        fun closed(now: Long) {
            closedAt = now
            due.fill(NONE)
        }

        /** An action of [kind] may go at [now]: no screen closed yet, or your reaction to the last close passed. */
        fun ready(kind: Kind, now: Long): Boolean {
            if (closedAt == NONE) return true
            if (due[kind.ordinal] == NONE) due[kind.ordinal] = TimingModel.dueAt(closedAt, draw(kind))
            return now >= due[kind.ordinal]
        }

        private companion object {
            const val NONE = Long.MIN_VALUE
        }
    }

    /**
     * A click of the bot held while something it must never touch is where it would go (another player's creature, a
     * player or any other entity under the crosshair or on the ability's way, a door, a lever): it goes a reaction of yours
     * after the first tick that saw the way clear, never in the tick it cleared, and waits again when it is blocked again
     * meanwhile. Nothing blocked it: it goes when due. No game state, so it is unit tested.
     */
    internal class ClearWay {
        private val reaction = Reaction()

        /** The way was blocked and the click waits: for the way, or the reaction to it coming clear. */
        var held = false
            private set

        /** The click may go at [now] with the way [blocked] or not ([draw]: the reaction to it coming clear). */
        fun ready(now: Long, blocked: Boolean, draw: () -> Long): Boolean {
            if (blocked) {
                block()
                return false
            }
            if (! held) return true
            if (! reaction.ready(now, true, draw)) return false
            clear()
            return true
        }

        /** The way was found blocked (as the game handled a use): the next click waits a reaction after it is seen clear. */
        fun block() {
            held = true
            reaction.clear()
        }

        fun clear() {
            held = false
            reaction.clear()
        }
    }

    /**
     * An area ability's look down held for something not yours in its area ([FightRules.lookDownHeld]): from your
     * reaction to it coming in ([Reaction], drawn at the first tick it is seen there, dropped when it left before),
     * never in that tick, a look down under way going on meanwhile; let go a reaction after the area came clear
     * ([ClearWay]). No game state, so it is unit tested.
     */
    internal class DownHold {
        private val seen = Reaction()
        private val way = ClearWay()

        /** The view is kept on the creature, the look down held. */
        var held = false
            private set

        /**
         * At [now], with the area [blocked] or not and a look down under way ([downward]): held or not from now on;
         * [react]: your reaction to something coming into the area, [clear]: to it leaving.
         */
        fun update(now: Long, blocked: Boolean, downward: Boolean, react: () -> Long, clear: () -> Long): Boolean {
            val cleared = way.ready(now, blocked, clear)
            val noticed = seen.ready(now, blocked && ! held, react)
            held = FightRules.lookDownHeld(held, blocked, downward, cleared, noticed)
            return held
        }

        fun clear() {
            held = false
            seen.clear()
            way.clear()
        }
    }

    /**
     * Blocks a right click does something to whatever is in hand (opens, toggles, takes from, puts into, rings): a door, a
     * trapdoor, a fence gate, a lever, a button, a chest, a note block, a shelf. Told by the block's class having a use of
     * its own (it overrides [BlockBehaviour]'s useWithoutItem or useItemOn), so blocks no list names count too, a use that
     * does nothing with the item in hand included; only those whose use needs an item the bot never uses (a lead, a
     * bucket, food, shears, flint) do not.
     */
    internal object BlockUses {
        private const val USE = "useWithoutItem"
        private const val USE_ON = "useItemOn"
        private val cache = ConcurrentHashMap<Class<*>, Boolean>()
        /** The game's own names are readable (an unobfuscated game): else a list of the common ones stands in. */
        private val named by lazy { overrides(DoorBlock::class.java) && overrides(ChestBlock::class.java) }

        fun usable(state: BlockState) = usable(state.block)

        fun usable(block: Block): Boolean = cache.getOrPut(block.javaClass) { usableClass(block.javaClass) }

        /** A block of class [c] does something on a right click with the bot's items. */
        fun usableClass(c: Class<*>) = ! HARMLESS.any { it.isAssignableFrom(c) } && if (named) overrides(c) else LISTED.any { it.isAssignableFrom(c) }

        /** [c] or a class between it and [BlockBehaviour] has a use of its own. */
        fun overrides(c: Class<*>): Boolean {
            var k: Class<*>? = c
            while (k != null && k != BlockBehaviour::class.java) {
                if (k.declaredMethods.any { it.name == USE || it.name == USE_ON }) return true
                k = k.superclass
            }
            return false
        }

        /** Their use needs an item the bot never right clicks with (a fence a lead, a cauldron a bucket, a campfire food, a pumpkin or a hive shears, TNT flint). */
        private val HARMLESS = listOf(FenceBlock::class.java, AbstractCauldronBlock::class.java, CampfireBlock::class.java, PumpkinBlock::class.java, BeehiveBlock::class.java, TntBlock::class.java)
        /** The common ones, where the game's names cannot be read. */
        private val LISTED = listOf(DoorBlock::class.java, TrapDoorBlock::class.java, FenceGateBlock::class.java, LeverBlock::class.java, ButtonBlock::class.java,
            NoteBlock::class.java, SignBlock::class.java, EntityBlock::class.java)
    }

    /**
     * Your fishing view from your own mouse turns: a turn between fights becomes it once your mouse has been still for
     * [settleMs]. A turn that took the view less than [REAL_TURN_DEG] away (a hand resting on the mouse, a back and forth)
     * only moves your fishing view by as much as it turned the view, since Auto Fishing's own motion (a look around, a
     * re-aim short of home) may have left the view elsewhere; while a look around keeps the view away it is no turn at
     * all, the look goes on and comes back. A turn in a fight (the killer's, one you took over, any with a creature of
     * yours alive while the killer is on) never does, and a fight forgets a turn made right before it, so a won fight
     * never makes the fight view the one Auto Fishing and the turn back come back to. No game state, so it is unit tested.
     */
    internal class HomeWatch(private val settleMs: Long = HOME_SETTLE_MS) {
        /** Your last mouse turn that moved the view. */
        private var turnedAt = Long.MIN_VALUE / 2
        private var owed = false
        /** How far your turns since your fishing view was last taken took the view, in yaw and pitch... */
        private var turnYaw = 0.0
        private var turnPitch = 0.0
        /** ...and one of them took it from a look around: the view it leaves is yours. */
        private var lookTaken = false
        /** How far your mouse took the view while a look around kept it away, and when it last moved. */
        private var lookYaw = 0.0
        private var lookPitch = 0.0
        private var lookAt = Long.MIN_VALUE / 2

        /**
         * Your mouse turned the view at [now] by [yaw] and [pitch] degrees; [between]: no fight goes on; [lookOut]: a look
         * around keeps the view away or turns it.
         */
        fun turned(now: Long, between: Boolean, yaw: Double, pitch: Double, lookOut: Boolean = false) {
            if (lookOut) {
                // movements of yours a moment apart add up: a slow turn is a turn too
                if (now - lookAt > settleMs) {
                    lookYaw = 0.0
                    lookPitch = 0.0
                }
                lookYaw += yaw
                lookPitch += pitch
                lookAt = now
                if (hypot(lookYaw, lookPitch) < REAL_TURN_DEG) return
            }
            else {
                lookYaw = 0.0
                lookPitch = 0.0
            }
            turnedAt = now
            if (! between) return
            owed = true
            turnYaw += yaw
            turnPitch += pitch
            if (lookOut) lookTaken = true
        }

        /**
         * Once a tick ([between]: no fight goes on), with your fishing view [home] (null: none yet) and the [view] now:
         * your fishing view from now on when your turns just settled, else null; never while a move of the bot's turns the
         * view or comes to rest ([moving]): your settled turn is taken once it rested; nor while it is [pending] (held for
         * later, taken then); forgotten when a fight begins ([between] false).
         */
        fun settled(now: Long, between: Boolean, home: View?, view: View, moving: Boolean = false, pending: Boolean = false): View? {
            if (! between) forget()
            if (! owed || now - turnedAt < settleMs || moving || pending) return null
            val turn = lookTaken || hypot(turnYaw, turnPitch) >= REAL_TURN_DEG
            val yaw = turnYaw
            val pitch = turnPitch
            forget()
            if (turn || home == null) return view
            return View(home.yaw + yaw, (home.pitch + pitch).coerceIn(- 90.0, 90.0))
        }

        private fun forget() {
            owed = false
            turnYaw = 0.0
            turnPitch = 0.0
            lookTaken = false
        }

        /** You turned the view within [settleMs] before [now]. */
        fun recently(now: Long) = within(now, settleMs)

        /** You turned the view within [ms] before [now]. */
        fun within(now: Long, ms: Long) = now - turnedAt < ms

        /** What Auto Fishing's start does with your fishing view. */
        enum class Start {
            /** The view now is your fishing view. */
            TAKE,
            /** The one you had stays: the view now is a move of the bot's. */
            KEEP,
            /** None yet, and a move of the bot's turns the view: the view where it rested. */
            WAIT,
        }

        companion object {
            /** Your mouse moved this far in all: a turn of yours, the view it leaves is where you fish (as for a takeover). */
            const val REAL_TURN_DEG = TAKEOVER_DEGREES

            /**
             * Auto Fishing started (or its start waits) with a fishing view already ([hasHome]) or none, while a move of the
             * bot's turns the view or comes to rest ([moving]): Auto Fishing turned on again while the move its stop let
             * run out still turned. That move's view is no view of yours: the one you had stays, and without one the start
             * waits for it to rest. Your mouse turning it would have ended it.
             */
            fun atStart(hasHome: Boolean, moving: Boolean) = when {
                ! moving -> Start.TAKE
                hasHome -> Start.KEEP
                else -> Start.WAIT
            }
        }
    }

    /**
     * Whose use of the item a cast or reel showing up now came from: the last use before it, yours (a press of the use key
     * seen on the mouse or keyboard with no screen open) or the mod's (Auto Fishing's rod click, the killer's press or
     * held use key), within [windowMs] of it. The bobber comes from the server a round trip after the click, so no time
     * limit alone tells them apart: with a high ping the mod's casts would pass for yours. The server uses the item in the
     * order the uses were sent, so your press made after Auto Fishing's cast click but before that bobber showed up reels
     * that bobber in: the cast that shows up next is still the mod's. No game state, so it is unit tested.
     */
    internal class UseOwner(private val windowMs: Long = USE_WINDOW_MS) {
        /** Your last press of the use key... */
        private var pressedAt = Long.MIN_VALUE / 2
        /** ...made while Auto Fishing's cast was on its way: it reels that cast in, it casts nothing. */
        private var reelsModCast = false

        /**
         * You pressed the use key at [now]; Auto Fishing last cast at [modCastAt] (within [windowMs]) and a cast last showed
         * up at [castShownAt]: when that cast has not shown up yet, this press reels it in.
         */
        fun pressed(now: Long, modCastAt: Long = Long.MIN_VALUE / 2, castShownAt: Long = Long.MIN_VALUE / 2) {
            pressedAt = now
            reelsModCast = modCastAt > castShownAt && now - modCastAt <= windowMs
        }

        /**
         * The [cast] (else a reel) came from your press: none of the mod's uses ([modAt]: its last) came after it, it is
         * recent or still [held], and a cast is none your press reeled in as it came.
         */
        fun yours(now: Long, modAt: Long, held: Boolean, cast: Boolean) = pressedAt >= modAt && ! (cast && reelsModCast) && (held || now - pressedAt <= windowMs)

        /** The [cast] (else a reel) came from the mod's use at [modAt], recent: none of your presses came after it, or it is the cast your press reeled in. */
        fun mods(now: Long, modAt: Long, cast: Boolean) = (modAt > pressedAt || cast && reelsModCast) && now - modAt <= windowMs
    }

    /**
     * The crosshair coming onto the creature within reach: your hits went out a reaction after that
     * ([Action.BOX_ENTRY_CLICK], now and then in that very tick), not as it got there; likewise your first right press
     * after the view came within a ranged plan's aim ([Action.AIM_ENTRY_PRESS]) and your teleport-blade click after a
     * look-down ([Action.LOOK_DOWN_CLICK]). A view off it for less than [offMs] (a creature bobbing at the edge of its
     * box) starts no new reaction, unless it is the target's first coming onto it; a target the view is on from its
     * first tick needs none (your reaction to it came before the fight). It is fed every tick, off ticks too ([ready]).
     * No game state, so it is unit tested.
     */
    internal class BoxEntry(private val offMs: Long = REENTRY_MS) {
        /** Since when the crosshair has been off the creature; null while on it, or before the target's first tick. */
        private var offSince: Long? = null
        private var readyAt = Long.MIN_VALUE
        /** The reaction the target's first coming onto it waits, drawn ahead; null: drawn then. */
        private var first: Long? = null
        /** The view has not been on this target yet: its first coming onto it waits a reaction however short the way. */
        private var fresh = false

        /**
         * A new target; [firstReaction]: the reaction its first entry waits, drawn ahead (a turn timed to get there that
         * long before its first hit).
         */
        fun reset(firstReaction: Long? = null) {
            offSince = null
            readyAt = Long.MIN_VALUE
            first = firstReaction
            fresh = true
        }

        /** The crosshair is [on] the creature at [now] ([reaction]: a drawn wait): a hit may go out from the time returned on. */
        fun update(now: Long, on: Boolean, reaction: () -> Long): Long {
            if (! on) {
                if (offSince == null) offSince = now
                return readyAt
            }
            val off = offSince
            offSince = null
            if (off != null && (fresh || now - off >= offMs)) readyAt = TimingModel.dueAt(now, first ?: reaction())
            // only the first time it is on: on from the first tick, or back from its edge, the reaction drawn ahead is moot
            first = null
            fresh = false
            return readyAt
        }

        /**
         * This tick's [update] (every tick, off ticks too: an entry is only seen when the ticks before it were), and
         * whether the plan's click may go out: [on], and its reaction over.
         */
        fun ready(now: Long, on: Boolean, reaction: () -> Long): Boolean {
            val at = update(now, on, reaction)
            return on && now >= at
        }

        companion object {
            /** Off the creature for two ticks: the crosshair comes back onto it, not along its edge. */
            const val REENTRY_MS = 100L
        }
    }

    /**
     * Your misses next to your creatures ([BehaviorProfile.Profile.missShare]). The killer's hits only go out with the
     * crosshair on its creature, so each one owes a miss at the odds that keep your share of misses among your clicks
     * there (share / (1 - share) per hit), [owed] by its next click due with the crosshair off the creature but next to
     * it ([FightRules.missNear]), the way most of yours came before the first hit. At most one is owed: misses never come
     * in a burst. A click meant as a hit that the game's fresh pick found off the creature is a miss too: it pays the one
     * owed, or the next one, so the misses stay at your share instead of adding up. At most one is paid ahead, so a burst
     * of them (one awkward fight) never keeps the next fights' misses away; more of them than your share asks for come
     * on top of it. No game state, so it is unit tested.
     */
    internal class MissOdds {
        var owed = false
            private set
        /** A miss went out with none owed (a click meant as a hit): it pays the next miss owed. */
        private var paidAhead = false

        /** A hit went out: it owes a miss at the odds that keep [share] of the clicks misses. */
        fun hit(share: Double, rng: Random) {
            // a share of a half or more would owe a miss on every hit: as many misses as hits is all one owed at a time gives
            val s = if (share.isFinite()) share.coerceIn(0.0, MAX_SHARE) else 0.0
            if (rng.nextDouble() >= s / (1.0 - s)) return
            if (paidAhead) paidAhead = false
            else owed = true
        }

        /** A miss went out: the owed one, or one meant as a hit (it pays the one owed, else the next unless one is paid already). */
        fun missed() {
            if (owed) owed = false
            else paidAhead = true
        }

        companion object {
            const val MAX_SHARE = 0.5
        }
    }

    /** What the rod after a fight you took over and won does in this tick ([FightRules.wonBack]). */
    internal enum class WonBack { WAIT, BACK, DROP }

    /** How the killer lets go of a fight once Auto Fishing stopped ([FightRules.offExit]): [atOnce], and the rod put back ([restore]). */
    internal class OffExit(val atOnce: Boolean, val restore: Boolean)

    /** What a melee click of the killer does once the game picked again as it handled it ([FightRules.meleeOutcome]). */
    internal enum class MeleeOutcome {
        /** The meant creature. */
        HIT,
        /** Air or a block that does not break at once, next to the creature. */
        MISS,
        /** Cancelled: another entity was under the crosshair. */
        OTHER_ENTITY,
        /** Cancelled: another item in hand, or a block the swing would break at once. */
        CANCELLED,
    }

    /**
     * The hotbar slot the killer fights with: taken while it watches no fight (a loadout may change it) and by its own
     * swaps, so any other change of the slot is yours. A hotbar key of yours counts at once: the game changes the slot
     * only in the next tick's key handling, after the killer's tick. No game state, so it is unit tested.
     */
    internal class SlotWatch {
        var slot = - 1
            private set
        private var keyPressed = false

        /** [slot] is in hand by the killer (no fight watched, its own swap) or with your change handled. */
        fun note(slot: Int) {
            this.slot = slot
            keyPressed = false
        }

        /** You pressed the hotbar key of slot [index] while [selected] is in hand: true when it changes the slot. */
        fun hotbarKey(index: Int, selected: Int): Boolean {
            if (index == selected) return false
            keyPressed = true
            return true
        }

        /** The slot in hand ([selected]) changed without the killer, or a hotbar key of yours is about to change it. */
        fun changed(selected: Int) = keyPressed || selected != slot
    }

    /** The killer's decisions that need no game, unit tested in AutoMobKillerTest. */
    internal object FightRules {
        /** Uses of an option without an hp drop of its target (the median of the drawn number)... */
        const val ESCALATE_USES = 8
        /** ...over at least this long (the median of the drawn time, [Action.ESCALATE]) give the option up on that target. */
        const val ESCALATE_MS = 1500L
        /** The drawn number of uses: ln-sd, and the fewest and most (drawn within them, never piled at one). */
        private const val ESCALATE_SIGMA = 0.3
        private const val ESCALATE_MIN = 4
        private const val ESCALATE_MAX = 20

        /**
         * How many uses that did nothing give an option up: nothing of yours measures it (your recordings have no weapon that
         * did nothing for long), so a conservative lognormal around the [ESCALATE_USES] it used to be, drawn again outside
         * [ESCALATE_MIN]..[ESCALATE_MAX].
         */
        fun escalateUses(rng: Random): Int {
            repeat(REDRAW_TRIES) {
                val n = TimingModel.lognormal(ESCALATE_USES.toDouble(), ESCALATE_SIGMA, rng, ESCALATE_MAX + 0.5).roundToInt()
                if (n >= ESCALATE_MIN) return n
            }
            return ESCALATE_USES
        }
        /** The rod takes more than 8 hits: a mean share of max hp per rod hit below this (from 3 hits on). */
        const val SLOW_ROD_SHARE = 0.125
        const val ROD_SAMPLES = 3
        /** Fights with another option that make it yours for this creature. */
        const val OTHER_FIGHTS = 2
        /** A teleport blade never clicks looking down less than this (its ability would move you). */
        const val BLADE_PITCH = 80f
        /** A teleport blade with no learned pitch looks down this far. */
        const val BLADE_VIEW_PITCH = 89f
        /** A plan's view pitch counts as reached this many degrees short of it. */
        const val PITCH_SLACK = 5f
        /** An explicit Weapon rule's hold goes on while the view is within this many degrees of the creature. */
        const val KEEP_HOLD_DEG = 12f
        /** Fewer samples than this of a weapon's gaps use your right-click gaps instead. */
        const val MIN_SAMPLES = 8
        /** Melee counts the view as on a creature within its box's angular half-size, and never less than this. */
        const val MIN_BOX_DEG = 3.0
        /** When the bobber flight cannot tell, a view within this many degrees of your fishing view casts into the water. */
        const val FLUID_FALLBACK_DEG = 6.0
        /**
         * Auto Fishing's recast this long after a creature's spawn at most is the one after its catch ([recastFor]): which
         * recast it is, not how long one is waited for (REACT waits for a pending one by its due time, [waitsForRecast]).
         */
        const val RECAST_PENDING_MS = 3000L

        /** The view is close enough for a Learned click. */
        fun aimOk(err: Double, aimDeg: Double) = aimDeg >= 90.0 || err <= aimDeg

        /**
         * The end of a fight holds Auto Fishing as far as its phase goes: the wait after the kill ([afterKill]) unless a rod
         * fought ([rod]: Auto Fishing went on meanwhile), the rod coming back and the turn back always; none of it when
         * only a look around kept the fight open ([lookOnly]: the killer did nothing, the recast's bobber killed the
         * creature). A move of the turn back holds by itself.
         */
        fun endHolds(afterKill: Boolean, rod: Boolean, lookOnly: Boolean) = ! lookOnly && ! (afterKill && rod)

        /**
         * The fight is over but for the turn back's slow tail to rest ([turnBack]: its phase; [backDone]: the turn back's
         * move no longer turns the view, [ViewPolicy.TurnBack.done]; [moving]: the move in flight is the fight's; [turning]:
         * [HumanAim.turning]): the killer needs the view no more, so that tail holds Auto Fishing no longer than one of
         * Auto Fishing's own moves does: a cast goes in it where its bobber lands like one from your fishing view
         * ([AutoFishing.CastCheck.tailHolds]), its waits running from the last moving tick, not the motor's rest.
         */
        fun backTail(turnBack: Boolean, backDone: Boolean, moving: Boolean, turning: Boolean) = turnBack && backDone && moving && ! turning

        /** What your mouse turns are as your fishing view ([homeTurns]). */
        enum class HomeTurns {
            /** Your fishing view once they settle. */
            FISHING,
            /** Turns of a fight: never your fishing view. */
            FIGHT,
        }

        /**
         * Your mouse turns as your fishing view with creatures of yours [alive]: with the [killer] on, its fight (or one it
         * leaves alone); else where you fish. With the killer off the view is yours, fight or not: where your mouse rests a
         * second ([HomeWatch]) Auto Fishing fishes from then on, so its turns home never pull the view off your creature
         * back to a fishing view you left.
         */
        fun homeTurns(killer: Boolean, alive: Boolean) = if (killer && alive) HomeTurns.FIGHT else HomeTurns.FISHING

        /** What a Learned [plan] needs the view for: melee the crosshair, a teleport blade its pitch, an area weapon nothing, else its aim. */
        fun planNeed(plan: Plan) = when {
            plan.style == ClickStyle.LEFT -> Need.MELEE
            ! plan.viewPitch.isNaN() || plan.option in WeaponPlanner.TELEPORT_BLADES -> Need.PITCH
            plan.aimDeg >= 90.0 -> Need.NONE
            else -> Need.RANGED
        }

        fun pitchReached(xRot: Float, viewPitch: Float) = xRot >= viewPitch - PITCH_SLACK

        /**
         * A melee use that did not land counts towards escalation only as a real failure of the plan: the view is on the
         * creature ([onTarget]) and still, and the policy is not waiting to turn ([waiting]); then reach or height is what
         * is wrong, not the view.
         */
        fun countsUse(onTarget: Boolean, moving: Boolean, waiting: Boolean) = onTarget && ! moving && ! waiting

        /** A miss goes out next to the creature only: the view within this many of its box's angular half-sizes... */
        const val MISS_HALVES = 2.0

        /**
         * ...[error] degrees off its aim point with a box [half] degrees across, and the creature [distance] m from your
         * eye at most [BehaviorProfile.Profile.MISS_NEAR], where your misses were counted.
         */
        fun missNear(error: Double, half: Double, distance: Double) = error <= MISS_HALVES * half && distance <= BehaviorProfile.Profile.MISS_NEAR

        /** Your view turned at most this fast at your melee clicks (deg/s; 260 at the fastest rod hit, 300 with the claymore). */
        const val CLICK_TURN_DEG_S = 300.0

        /** A melee click may go out with the view having turned [deg] degrees in the last tick. */
        fun clickTurnOk(deg: Double) = deg * 1000.0 / TimingModel.TICK_MS <= CLICK_TURN_DEG_S

        /**
         * The gap from a target's death to the next creature of its group, after the clicks its plan made ([style]): one of
         * your gaps between single presses ([press]) or between holds ([hold]), else between melee clicks ([melee]); a melee
         * gap after a press plan came sooner than your press rhythm (p50 170 against 345).
         */
        fun nextTargetGap(style: ClickStyle?, melee: () -> Long, press: () -> Long, hold: () -> Long) = when (style) {
            ClickStyle.RIGHT -> press()
            ClickStyle.HOLD -> hold()
            ClickStyle.LEFT, null -> melee()
        }

        /** A copy of [state] to draw from without moving it on (a beat read off your rhythm for a click that may never go out). */
        fun peek(state: StreakState) = StreakState(state.stay).also {
            it.slow = state.slow
            it.high = state.high
        }

        /**
         * SWAP finds the fight over at once, at the death that left it so: no creature of the group is left
         * ([groupLeft]); after a loadout, which plans the lead target again from all of yours, none at all ([anyLeft]).
         * One of another group left is reacted to from this death ([afterKill], [freeHand]), never after the gap to a
         * creature of this group that no longer exists.
         */
        fun swapOver(replan: Boolean, groupLeft: Boolean, anyLeft: Boolean) = if (replan) ! anyLeft else ! groupLeft

        /**
         * A screen open now is one of yours, so its close waits your reactions ([ScreenGate]): any while no loadout switch
         * of the killer's is under way ([switching]), and during one any but its menu ([loadoutMenu]: chat, your inventory,
         * a menu you opened meanwhile). One still open once the switch is done is yours, whatever it is.
         */
        fun screenYours(switching: Boolean, loadoutMenu: Boolean) = ! switching || ! loadoutMenu

        /** A draw made on a [peek] copy ([trial]) turned out to be the one taken: [state] moves on as if it had been drawn on it. */
        fun carry(trial: StreakState, state: StreakState) {
            state.slow = trial.slow
            state.high = trial.high
        }

        /**
         * A click of the killer may go out in player tick [tick]: none went out in it already ([lastTick]), none waits for
         * the game to handle it ([meleePending], [usePending]: each click has its own guard, a second one in the tick would
         * go out unchecked), and [now] is past [notBefore] (a gap of yours after a click still on its way after a death).
         */
        fun clickFree(tick: Int, lastTick: Int, meleePending: Boolean, usePending: Boolean, now: Long, notBefore: Long) =
            tick != lastTick && ! meleePending && ! usePending && now >= notBefore

        /**
         * The use key the killer holds goes up now: at once when the item may not be used ([allowed]: another entity than
         * the creature under the crosshair, a teleport blade not looking down) or the hold's time is [due], else a reaction
         * later ([off], [draw]n at the first tick the view or the distance stopped keeping it, [keep]; drawn anew once it
         * kept it again).
         */
        fun holdEnds(allowed: Boolean, due: Boolean, keep: Boolean, off: Reaction, now: Long, draw: () -> Long) = ! allowed || due || off.ready(now, ! keep, draw)

        /**
         * A use of the killer's would interact with another entity: the game's pick found one ([entity]) that is neither the
         * creature it fights now ([fought]) nor any creature of yours, alive or dying ([own]: the one just killed stays
         * pickable while it dies, with no target after its death; your key stayed down through such deaths). Another
         * player's sea creature, a player, any other entity: never.
         */
        fun usesOnOther(entity: Boolean, fought: Boolean, own: Boolean = false) = entity && ! fought && ! own

        /**
         * How much wider than its pick an entity's box is checked on an ability's way: a [kind] that must never be hit by
         * [margin] (the projectile's own widening and then some), a creature of yours by nothing, so that it shields only
         * what really lies behind it.
         */
        fun hitMargin(kind: PathKind, margin: Double) = if (kind == PathKind.SHIELD) 0.0 else margin

        /** An area's step along its line ([inArea]): a box is found within half a step of its true distance. */
        private const val AREA_STEP = 0.25

        /**
         * [area] planned before the view looks down: around the line straight down, wider by how far sideways a teleport
         * along any look down a teleport blade is used with ([BLADE_PITCH] or more) may end from it, and by [margin].
         */
        fun plannedArea(area: WeaponPlanner.Area, margin: Double = 0.0) =
            WeaponPlanner.Area(area.length, area.radius + area.length * cos(Math.toRadians(BLADE_PITCH.toDouble())) + margin)

        /**
         * An area option left for something not yours in its planned area is taken back only once that area is clear by
         * this much more: your creatures moved this far or less in 2 s (about the time to the plan's next re-choose) 3
         * times in 4 (2368 positions 1.9-2.1 s apart in your rot lines: p50 0.65, p75 2.30, p90 4.18 m), so one wandering
         * at the edge does not swap the blade in and out at each re-choose.
         */
        const val RETAKE_MARGIN = 2.3

        /**
         * The options of [available] a creature's plan may use now: its [current] one unless its planned area holds
         * something not yours ([near]), any other only when its area is clear by [RETAKE_MARGIN] ([wide], which holds
         * every option of [near]).
         */
        fun usableFor(available: Set<String>, current: String?, near: Set<String>, wide: Set<String>): Set<String> =
            available.filterTo(LinkedHashSet()) { it !in (if (it == current) near else wide) }

        /**
         * A look down for an area ability ([held]: kept off so far, the view on the creature) is held at a tick: while
         * something not yours is in its area ([blocked]) it is held once your reaction to it coming in passed ([seen]: the
         * view never turns up in the tick it came in, a move timed to another player's creature crossing an unseen
         * radius) and no look down is under way ([downward]: the move it started, which goes on and is checked again where
         * it ends), a view already down there too; a held one goes only once that area came clear and a reaction of yours
         * passed ([clear]). An explicit rule's blade has nothing else to use: it never stares at the floor waiting for the
         * area, and Give Up After still counts.
         */
        fun lookDownHeld(held: Boolean, blocked: Boolean, downward: Boolean, clear: Boolean, seen: Boolean) = if (held) ! clear else blocked && seen && ! downward

        /**
         * What a use of an area ability is checked against at a tick ([AreaCheck]): before a look down reached its pitch
         * ([lookDown], [reached]) the area planned around it, never the near-level view it still turns from; held for
         * its area, nothing (the look down itself waits for it, the press follows it); otherwise the view itself.
         */
        fun useCheck(lookDown: Boolean, reached: Boolean, held: Boolean) = when {
            ! lookDown || reached -> AreaCheck.VIEW
            held -> AreaCheck.NONE
            else -> AreaCheck.PLANNED
        }

        /** What a use is checked against for something it must never touch ([useCheck]). */
        enum class AreaCheck { VIEW, PLANNED, NONE }

        /** [box] comes within [area]'s radius of its line from [from] along the unit vector [dir] (to half a step wider). */
        fun inArea(area: WeaponPlanner.Area, from: Vec3, dir: Vec3, box: AABB): Boolean {
            val n = max(1, ceil(area.length / AREA_STEP).toInt())
            val r = area.radius + AREA_STEP / 2
            for (i in 0 .. n) {
                val t = area.length * i / n
                val x = from.x + dir.x * t
                val y = from.y + dir.y * t
                val z = from.z + dir.z * t
                val dx = max(max(box.minX - x, x - box.maxX), 0.0)
                val dy = max(max(box.minY - y, y - box.maxY), 0.0)
                val dz = max(max(box.minZ - z, z - box.maxZ), 0.0)
                if (dx * dx + dy * dy + dz * dz <= r * r) return true
            }
            return false
        }

        /**
         * An entity in an area ability's reach must never be hit by it: anything [living] (another player's creature, a
         * player, a mob) that is no creature of yours nor the one fought ([mine]) and no armor stand ([stand]: a nametag).
         */
        fun areaBlocks(mine: Boolean, stand: Boolean, living: Boolean) = living && ! stand && ! mine

        /** What an entity on an ability's way along the view is to it ([pathBlocked]). */
        enum class PathKind {
            /** Nothing the ability meets: an armor stand (a nametag), an entity no projectile hits. */
            NONE,
            /** A creature of yours alive, or the one fought: it takes the hit, nothing behind it is met. */
            SHIELD,
            /** Anything else (another player's creature, a player, an NPC, a boat): never to be hit. */
            BLOCKER,
        }

        /**
         * What an entity on an ability's way is: a creature of yours or the one fought ([shield]) takes the hit, an armor
         * stand ([stand]: a nametag, yours or not) or an entity nothing would meet (not [hittable]) is nothing; anything else
         * must never be hit.
         */
        fun pathKind(shield: Boolean, stand: Boolean, hittable: Boolean) = when {
            stand -> PathKind.NONE
            shield -> PathKind.SHIELD
            ! hittable -> PathKind.NONE
            else -> PathKind.BLOCKER
        }

        /** The ability's way ([hits]: each entity's distance along it and what it is) meets something it must never hit before anything of yours. */
        fun pathBlocked(hits: List<Pair<Double, PathKind>>): Boolean {
            for ((_, kind) in hits.sortedBy { it.first }) when (kind) {
                PathKind.SHIELD -> return false
                PathKind.BLOCKER -> return true
                PathKind.NONE -> Unit
            }
            return false
        }

        /**
         * The item in hand now ([slot], [id]) is the one a click went out with ([clickedSlot], [clickedId]): the same hotbar
         * slot and the same item in it (a server or another feature may swap what the slot holds).
         */
        fun sameItem(slot: Int, id: String, clickedSlot: Int, clickedId: String) = slot == clickedSlot && id == clickedId

        /**
         * A creature whose fight is timed from [anchor] (its spawn, or its return in reach) was there already when your hand
         * was freed for it at [freedAt] (a death before): your reaction to it comes from that moment, its own long past.
         */
        fun olderThanFree(anchor: Long, freedAt: Long) = anchor < freedAt

        /**
         * When the fight on such a creature ([olderThanFree]) starts, at [now]: never while the use key held through that
         * moment is still down ([keyDown]: [Long.MAX_VALUE]); one of your swaps to another creature after a death ([swapAt],
         * [TimingModel.Action.DEATH_TO_SWAP], or from that key-up) when its rule takes another item or a loadout ([swaps]);
         * else no swap comes, and it is the next creature a [gap] of its plan's own clicks after [from] (that moment, or the
         * key-up after it), as one of the group after a death, drawn again until it falls after [now].
         */
        fun olderReact(swaps: Boolean, keyDown: Boolean, swapAt: Long, from: Long, now: Long, gap: () -> Long): Long = when {
            keyDown -> Long.MAX_VALUE
            swaps -> swapAt
            else -> reactAfter(from, now, gap, gap)
        }

        /**
         * A swing breaks what the crosshair is on at once: only a [block] pick can, at a destroy [progress] per tick of
         * one or more. A pick that found nothing in reach is no block, though its hit result names the air block at the
         * ray's end (air breaks "at once", its strength is 0).
         */
        fun breaksAtOnce(block: Boolean, progress: () -> Float): Boolean {
            if (! block) return false
            // a strengthless block with no speed of yours gives 0 / 0: not provably slow, so never swung at
            return ! (progress() < 1f)
        }

        /**
         * What a melee click does as the game handles it: with the item the killer clicked with in hand ([sameSlot]), an
         * [entity] under the crosshair hits when it is the [meant] creature and is cancelled otherwise; air or a block is
         * a miss, unless the swing [breaks] that block at once.
         */
        fun meleeOutcome(sameSlot: Boolean, entity: Boolean, meant: Boolean, breaks: () -> Boolean) = when {
            ! sameSlot -> MeleeOutcome.CANCELLED
            entity -> if (meant) MeleeOutcome.HIT else MeleeOutcome.OTHER_ENTITY
            breaks() -> MeleeOutcome.CANCELLED
            else -> MeleeOutcome.MISS
        }

        /**
         * REACT keeps waiting for Auto Fishing's recast after the catch at [now]: only while one is [pending], due at
         * [recastDue] no later than what it goes before ([leadAt]: the rod's first hit drawn from the spawn, a weapon's swap
         * your wait after that recast's due time), and only until then. Whichever is due first goes, the other waits for
         * the hand; no fixed wait. Your rod creatures with a recast 800 ms or more after the spawn were hit first 6 times of
         * 7 (p50 476 ms after the spawn), the 47 with none within 3 s all before it (p10 / p50 / p90 203 / 504 / 771), and
         * of your weapon fights every recast came before the swap (39 of 39). Auto Fishing times that recast from its reel
         * click, which the server's spawn always follows (yours came 35-452 ms before it), so one not pending at the spawn
         * never comes for that catch. One due before it that Auto Fishing holds past it (its flight's way, a screen) is waited
         * for up to [heldUntil] ([recastHeldUntil] for a weapon's swap; the rod's hit goes at its time).
         */
        fun waitsForRecast(now: Long, recastDue: Long, leadAt: Long, pending: Boolean, heldUntil: Long = leadAt) =
            pending && recastDue <= leadAt && now < maxOf(leadAt, heldUntil)

        /**
         * How long a weapon's swap due at [leadAt] waits for a recast Auto Fishing holds past it, for a creature that came
         * up at [anchor]: as long as a recast then is still the one after its catch ([RECAST_PENDING_MS], [recastFor]).
         * Auto Fishing's holds end by their own bounds within it (the cast's way at most 1.5 s after it was found
         * blocked, a re-aim a reaction later); a swap still waiting then goes a reaction of yours after it
         * ([swapAfterRecast]), never piled there.
         */
        fun recastHeldUntil(anchor: Long, leadAt: Long) = maxOf(leadAt, anchor + RECAST_PENDING_MS)

        /**
         * When a recast-first rod fight starts once REACT's wait for a pending recast is over at [now] ([nextAt]: your
         * reaction to the spawn drawn in REACT): the first hit drawn from the spawn taken as its first hit ([led], due at
         * [leadAt]) holds your reaction to the spawn already, so a recast held past it starts the fight at that hit's time
         * (never before that reaction, a hit drawn sooner than it), with no second reaction; otherwise a reaction that fell
         * before [now] is drawn again ([redraw]: after it).
         */
        fun rodReactAt(now: Long, nextAt: Long, led: Boolean, leadAt: Long, redraw: () -> Long) = when {
            led && now >= leadAt -> maxOf(nextAt, leadAt)
            now >= nextAt -> redraw()
            else -> nextAt
        }

        /**
         * A weapon's swap once REACT's wait for the recast after the catch is over: one of your waits after that recast
         * ([cast]; [gap]), else at its own time [leadAt], or once a recast Auto Fishing held past that time was waited for
         * in vain ([heldPast]) your reaction after that wait ([late]: never in its tick).
         */
        fun swapAfterRecast(cast: Long?, leadAt: Long, heldPast: Boolean, gap: () -> Long, late: () -> Long) = when {
            cast != null -> TimingModel.dueAt(cast, gap())
            heldPast -> late()
            else -> leadAt
        }

        /**
         * After a fight you took over and won, at [now] with the rod due back at [dueAt]: wait for it, take it back, or drop
         * it (a [newCreature]'s fight goes first; a slot you took meanwhile, not [sameSlot], stays in your hand).
         */
        fun wonBack(now: Long, dueAt: Long, newCreature: Boolean, sameSlot: Boolean) = when {
            newCreature -> WonBack.DROP
            now < dueAt -> WonBack.WAIT
            sameSlot -> WonBack.BACK
            else -> WonBack.DROP
        }

        /** A reaction is drawn again this often at most to fall after a wait ([reactAfter]). */
        const val REDRAW_TRIES = 32

        /**
         * A reaction timed from [from] that has to come after [now] (REACT's wait for a pending recast that never came ended then):
         * [draw]n again until it does, so none piles where that wait ended; past [REDRAW_TRIES] tries (a wait longer than
         * your reactions), a [late] one from [now].
         */
        fun reactAfter(from: Long, now: Long, draw: () -> Long, late: () -> Long): Long {
            repeat(REDRAW_TRIES) {
                val at = TimingModel.dueAt(from, draw())
                if (at > now) return at
            }
            return TimingModel.dueAt(now, late())
        }

        /**
         * The first rod hit on a creature that came up at [spawnedAt]: one of your first hits after a missed bobber from
         * Auto Fishing's recast after its catch ([recast]; yours were measured from that very right click, the switch to the
         * left button inside), else one of your first hits after a spawn ([firstHit], the catch's own reel and recast inside
         * it) from the spawn alone, drawn again only when it would fall before [after] (REACT's tick, [reactAfter]).
         */
        fun firstRodHit(spawnedAt: Long, recast: Long?, after: Long, bobberMiss: () -> Long, firstHit: () -> Long, late: () -> Long) =
            if (recast != null) TimingModel.dueAt(recast, bobberMiss()) else reactAfter(spawnedAt, after, firstHit, late)

        /**
         * When the clock of the first click on a creature started ([CrossGap]): the latest of when it came up for the killer
         * ([shown]: its spawn or its return from parking), the recast after its catch ([recast]), the killer's last swap
         * ([swapAt]) and its last left click ([lastClick]). Auto Fishing's rod clicks before then are inside your clocks
         * from there: your first hits from the recast or the spawn, the swap's first click.
         */
        fun firstClickFrom(shown: Long, recast: Long?, swapAt: Long, lastClick: Long) = maxOf(shown, recast ?: Long.MIN_VALUE, swapAt, lastClick)

        /**
         * What follows the close of the killer's loadout menu at [closedAt], [draw]n from the close like yours (your
         * confirmation came before your close 22 times of 22); the switch was over only at [doneAt] (Hypixel's
         * confirmation came after the bot's close): drawn again until it falls after that, never in its tick.
         */
        fun afterMenu(closedAt: Long, doneAt: Long, draw: () -> Long) = reactAfter(closedAt, doneAt, draw, draw)

        /**
         * When a loadout switch whose menu closed at [closedAt] was over, its callback at [done]: the close itself when the
         * callback came in the close's own tick ([closedTicksAgo] 0 or less: Hypixel's confirmation was there before it,
         * as yours always were), else [done]. Told by ticks, not milliseconds: a callback read a millisecond after the close
         * in the same tick is no late confirmation, and nothing timed from the close is drawn again for it.
         */
        fun menuDone(closedAt: Long, done: Long, closedTicksAgo: Int) = if (closedTicksAgo > 0) done else closedAt

        /**
         * How the killer lets go of a fight once Auto Fishing stopped ([OffExit]): at once, braking nothing, when you
         * [dead], the Fishing Recorder started ([recorder]: what follows is written as yours) or you took over
         * ([takeover]), else after your reaction to it with the move braking; the rod comes back for a fight that took
         * another item ([armed]) unless the Recorder or your hand has it, or you took another slot ([slotChanged]).
         */
        fun offExit(dead: Boolean, recorder: Boolean, takeover: Boolean, armed: Boolean, slotChanged: Boolean) =
            OffExit(dead || recorder || takeover, armed && ! recorder && ! takeover && ! slotChanged)

        /**
         * When the rod comes back after the killer let go at [now] once Auto Fishing stopped ([OffExit.restore]): with it, or
         * when the use key was [held] up to then and the exit came after your reaction (not [atOnce]), one of your waits
         * from a key-up to a swap later ([draw]: yours were 103-2649 ms), never key-up and swap in one tick.
         */
        fun offRodAt(now: Long, held: Boolean, atOnce: Boolean, draw: () -> Long) = if (held && ! atOnce) TimingModel.dueAt(now, draw()) else now

        /**
         * As [offRodAt], and with the killer's loadout menu closed at [now] ([menuClosed], the switch given up) one of your
         * waits from a menu closing to the rod ([menu]): the menu's close and the slot change never share a tick.
         */
        fun offRodAt(now: Long, held: Boolean, atOnce: Boolean, menuClosed: Boolean, keyUp: () -> Long, menu: () -> Long) = when {
            atOnce -> now
            held -> TimingModel.dueAt(now, keyUp())
            menuClosed -> TimingModel.dueAt(now, menu())
            else -> now
        }

        /**
         * How long a turn takes before the first rod hit of a recast-first fight: [typicalMs] (your hand's usual one), or 0
         * when no turn would come because the view is within [tolerance] of the creature ([error]) or it [falls] into the
         * crosshair by itself.
         */
        fun turnMs(error: Double, tolerance: Double, falls: Boolean, typicalMs: Double) = if (falls || error <= tolerance) 0L else typicalMs.toLong()

        /**
         * When the fight starts for the first rod hit of a recast-first fight at [hitAt], with a turn of [turnMs] (0: none
         * comes) to make first. Your first hits there were timed whole, turn and reaction to the crosshair on the creature
         * included, so a turn gets there an [entryReaction] before the hit (drawn only when a turn comes, and waited by
         * that hit: [BoxEntry]).
         */
        fun turnAtForHit(hitAt: Long, turnMs: Long, entryReaction: () -> Long) = if (turnMs <= 0L) hitAt else hitAt - turnMs - entryReaction()

        /**
         * When the fight on a creature already there starts with no swap, its first click due at [clickAt] (one of its plan's
         * own gaps after [from], the death or the key-up after it): a turn of [turnMs] (0: none comes) gets there an
         * [entryReaction] before the click ([turnAtForHit]), never before the hand's [reaction] to [from], drawn only when a
         * turn comes (yours to a death: p50 212 ms); the click waits for the view either way.
         */
        fun olderTurnAt(clickAt: Long, from: Long, turnMs: Long, entryReaction: () -> Long, reaction: () -> Long) =
            if (turnMs <= 0L) clickAt else maxOf(turnAtForHit(clickAt, turnMs, entryReaction), TimingModel.dueAt(from, reaction()))

        /**
         * The creature of [group] a fight picks: the one [prefer]red while it is still there (a first click and turn were
         * timed for it, [olderStart]; -1: none), else the nearest ([distance]).
         */
        fun <T> pick(group: List<T>, prefer: Int, id: (T) -> Int, distance: (T) -> Double): T? =
            group.firstOrNull { prefer >= 0 && id(it) == prefer } ?: group.minByOrNull(distance)

        /** The creature a start with no swap was [timed] for (-1: none) is no longer one to fight ([alive]): your hand is freed again. */
        fun olderGone(timed: Int, alive: (Int) -> Boolean) = timed >= 0 && ! alive(timed)

        /**
         * The earliest first turn after the killer's loadout menu, whose first click is due at [clickAt]: your first clicks
         * came 100-530 ms after the menu closed with any turn of yours inside that wait, a big one right as it closed, a
         * small correction 200-280 ms after it. A turn of [turnMs] (0: none comes) gets there an [entryReaction] before the
         * click ([turnAtForHit]), at once when that is before the close; [Long.MIN_VALUE] (none) without a turn, the
         * policy then times one on its own.
         */
        fun menuTurnAt(clickAt: Long, turnMs: Long, entryReaction: () -> Long) = if (turnMs <= 0L) Long.MIN_VALUE else turnAtForHit(clickAt, turnMs, entryReaction)

        /** A teleport blade's look-down from pitch [xRot] to [viewPitch] takes [typicalMs] (your hand's usual one); 0 with the pitch reached already. */
        fun lookDownMs(xRot: Float, viewPitch: Float, typicalMs: () -> Double) = if (pitchReached(xRot, viewPitch)) 0L else typicalMs().toLong()

        /** Your right-click error before one is learned: the middle of the 2.5-6° an explicit rule's ability draws its acceptance from. */
        const val EXPLICIT_ERR_DEG = 4.25
        /** An explicit rule's ability accepts your right-click errors within these degrees... */
        const val EXPLICIT_MIN_DEG = 1.5
        const val EXPLICIT_MAX_DEG = 12.0
        /** ...and anywhere within these without 8 of them. */
        private const val EXPLICIT_LO_DEG = 2.5
        private const val EXPLICIT_HI_DEG = 6.0

        /**
         * The error a ranged first turn closes the view to: a Learned plan's aim ([aimDeg]), else your usual right-click
         * error (the median of those of [errs] an explicit rule accepts, from 8 of them: [explicitAcceptance]), else
         * [EXPLICIT_ERR_DEG].
         */
        fun rangedTolerance(aimDeg: Double?, errs: List<Float>): Double {
            if (aimDeg != null) return aimDeg
            val within = explicitErrs(errs).sorted()
            return if (within.size >= TimingModel.MIN_SAMPLES) within[within.size / 2].toDouble() else EXPLICIT_ERR_DEG
        }

        /**
         * An explicit rule's ability's acceptance, drawn once per move: one of your right-click errors [errs] within
         * [EXPLICIT_MIN_DEG]..[EXPLICIT_MAX_DEG] jittered by up to 5 % and drawn again while outside them (clamped, the
         * casts and reels among your right clicks, 91 % of them far off any creature, piled the draws at 12°); with fewer
         * than 8 of them within, anywhere in 2.5-6°.
         */
        fun explicitAcceptance(errs: List<Float>, rng: Random): Double {
            val within = explicitErrs(errs)
            if (within.size < TimingModel.MIN_SAMPLES) return EXPLICIT_LO_DEG + (EXPLICIT_HI_DEG - EXPLICIT_LO_DEG) * rng.nextDouble()
            repeat(REDRAW_TRIES) {
                val v = within[rng.nextInt(within.size)] * (1.0 + JITTER * (2 * rng.nextDouble() - 1))
                if (v in EXPLICIT_MIN_DEG .. EXPLICIT_MAX_DEG) return v
            }
            return within[rng.nextInt(within.size)].toDouble()
        }

        private fun explicitErrs(errs: List<Float>) = errs.filter { it >= EXPLICIT_MIN_DEG && it <= EXPLICIT_MAX_DEG }

        /** A recorded share drawn again is jittered by up to this share of it. */
        private const val JITTER = 0.05
        /** A melee move comes to rest at this share of the creature's height at the least and at the most (well on its box)... */
        const val AIM_HEIGHT_MIN = 0.2
        const val AIM_HEIGHT_MAX = 0.95
        /** ...anywhere within these without 8 of your aim heights. */
        private const val AIM_HEIGHT_LO = 0.5
        private const val AIM_HEIGHT_HI = 0.8

        /**
         * The share of a creature's height a melee move comes to rest at: one of your aim heights ([samples], where your
         * hits met the side of its box) jittered by up to 5 %, drawn again while outside [AIM_HEIGHT_MIN]..
         * [AIM_HEIGHT_MAX] (clamped, the 9 of your 85 below it piled a tenth of the draws at its floor); with fewer than 8
         * samples, or none landing within, anywhere in 0.5-0.8.
         */
        fun aimHeight(samples: List<Float>, rng: Random): Double {
            if (samples.size >= TimingModel.MIN_SAMPLES) repeat(REDRAW_TRIES) {
                val v = samples[rng.nextInt(samples.size)] * (1.0 + JITTER * (2 * rng.nextDouble() - 1))
                if (v in AIM_HEIGHT_MIN .. AIM_HEIGHT_MAX) return v
            }
            return AIM_HEIGHT_LO + (AIM_HEIGHT_HI - AIM_HEIGHT_LO) * rng.nextDouble()
        }

        /**
         * Where on a creature's box (its feet [dx]/[dz] from your eye, [width] wide and [height] tall) the point [l] is,
         * from its feet (x, y, z): [ViewPolicy.Landing.side] shares of the half-width the box shows your eye beside its
         * centre line (a box seen at a slant shows more of it), at [ViewPolicy.Landing.up] of its height. Right above or
         * below your eye only the height.
         */
        fun landOffset(dx: Double, dz: Double, width: Double, height: Double, l: ViewPolicy.Landing): DoubleArray {
            val flat = hypot(dx, dz)
            if (flat < 1e-6) return doubleArrayOf(0.0, height * l.up, 0.0)
            val across = l.side * width / 2 * (abs(dx) + abs(dz)) / flat
            return doubleArrayOf(- dz / flat * across, height * l.up, dx / flat * across)
        }

        /** The view's bottom: looking straight down, past which no hand turns it. */
        const val BOTTOM_PITCH = 90.0
        /** Without 8 look-downs of yours: this share at the bottom (yours: 10 of your 31 first blade clicks)... */
        private const val BOTTOM_SHARE = 0.32
        /** ...the others a lognormal short of it, median and ln-sd in degrees (yours 0.15-2.55° short, p50 0.3). */
        private const val BOTTOM_SHORT_P50 = 0.35
        private const val BOTTOM_SHORT_SIGMA = 1.3
        /** A look-down of yours drawn again is jittered by up to one mouse step (at your sensitivity). */
        private const val LOOK_DOWN_JITTER = 0.15

        /**
         * Where a look-down for a plan used looking down at [viewPitch] (a teleport blade) comes to rest, drawn once per
         * move: one of your [pitches] at its clicks from 1° past where [viewPitch] counts as reached on ([PITCH_SLACK]),
         * one at the bottom of the view kept there (your hand went past it: 21 of your 75 blade clicks at exactly 90°) and
         * any other jittered by up to a mouse step, drawn again while past the bottom or short of that; with fewer than 8
         * of them a third at the bottom and the rest a lognormal short of it.
         */
        fun lookDownPitch(pitches: List<Float>, viewPitch: Float, rng: Random): Double {
            val lo = (viewPitch - PITCH_SLACK + 1f).toDouble()
            val rests = pitches.filter { it >= lo }
            if (rests.size >= TimingModel.MIN_SAMPLES) {
                val s = rests[rng.nextInt(rests.size)].toDouble()
                if (s >= BOTTOM_PITCH) return BOTTOM_PITCH
                repeat(REDRAW_TRIES) {
                    val v = s + LOOK_DOWN_JITTER * (2 * rng.nextDouble() - 1)
                    if (v >= lo && v < BOTTOM_PITCH) return v
                }
                return s
            }
            if (rng.nextDouble() < BOTTOM_SHARE) return BOTTOM_PITCH
            return BOTTOM_PITCH - TimingModel.lognormal(BOTTOM_SHORT_P50, BOTTOM_SHORT_SIGMA, rng, BOTTOM_PITCH - lo)
        }

        /**
         * The reaction a plan's first click waits once the view got where the plan [need]s it ([BoxEntry]): melee's to the
         * crosshair on the creature, a ranged plan's to the view within its aim, a look-down's to its pitch.
         */
        fun entryAction(need: Need) = when (need) {
            Need.RANGED, Need.NONE -> Action.AIM_ENTRY_PRESS
            Need.PITCH -> Action.LOOK_DOWN_CLICK
            Need.MELEE -> Action.BOX_ENTRY_CLICK
        }

        /**
         * A creature of yours that is not the target is one the plan turns to once it is ([ViewPolicy.Looks.Creature.planTurns]):
         * its plan [need]s melee, or it is a rod creature its recast came first for ([bobberRod]) whose bobber had its
         * chance ([chancePassed]) while the rod melees the target ([rodFight]): the rod's melee comes for it once it is
         * the target, and a look at it as well turned toward your rod melee creatures 1.3 times as often as you. While
         * the target is still the bobber's, looks go to them as they went to yours the bobber killed.
         */
        fun planTurns(need: Need, bobberRod: Boolean, chancePassed: Boolean, rodFight: Boolean) = need == Need.MELEE || bobberRod && chancePassed && rodFight

        /** Rod melee gaps a rhythm is learned from at the least (the timing model's own fit needs as many). */
        const val RHYTHM_GAPS = 30

        /** The chance your rod melee rhythm is kept, as the learner fitted it to your runs of hits; null with too few of them. */
        fun learnedRho(timing: BehaviorProfile.Timing) = timing.meleeRho.takeIf { timing.meleeGaps >= RHYTHM_GAPS && it.isFinite() && it in 0.0 .. 1.0 }

        /** The weapon:button class your recordings learn turns and looks for (`HYPERION:R`, `FLAMING_FLAY:Rh`, `<rod>:L`). */
        fun turnClass(item: String, style: ClickStyle) = item + ":" + when (style) {
            ClickStyle.LEFT -> "L"
            ClickStyle.RIGHT -> "R"
            ClickStyle.HOLD -> "Rh"
        }

        /** The wait from a swap to the first click with [id]: a teleport blade's, the Flay's or any other weapon's. */
        fun swapClickAction(id: String) = when (id) {
            in WeaponPlanner.TELEPORT_BLADES -> Action.SWAP_CLICK_HYPERION
            "FLAMING_FLAY" -> Action.SWAP_CLICK_FLAY
            else -> Action.SWAP_CLICK
        }

        /**
         * A cast from a view [distance] degrees from your fishing view lands in its water or lava: the bobber flight's
         * [verdict] ([BobberFlight.sameFluid]); when it cannot tell (no world, your fishing view's own cast lands in no
         * fluid), only a view within [FLUID_FALLBACK_DEG] of your fishing view counts.
         */
        fun landsInFluid(verdict: Boolean?, distance: Double) = verdict ?: (distance <= FLUID_FALLBACK_DEG)

        /**
         * How far short of your fishing view a turn back stops: your [shortBy] while it still casts where your fishing
         * view does ([lands]), else a draw from the same errors [under] the largest undershoot that does
         * ([BobberFlight.undershoot]), never home itself; when the flight cannot tell, only a view within
         * [FLUID_FALLBACK_DEG] counts: [shortBy] within it, a draw [under] it beyond (never piled there, where the smaller
         * of the two put half your turn backs; drawn, it keeps as much of your errors as the bound allows).
         */
        fun backShort(shortBy: Double, under: (Double) -> Double, lands: (Double) -> Boolean?): Double {
            BobberFlight.undershoot(shortBy, lands, under)?.let { return it }
            return if (shortBy < FLUID_FALLBACK_DEG) shortBy else under(FLUID_FALLBACK_DEG)
        }

        /** A right click with [id] in hand may go out at view pitch [xRot]: a teleport blade only looking well down. */
        fun bladeAllows(id: String, xRot: Float) = id !in WeaponPlanner.TELEPORT_BLADES || xRot >= BLADE_PITCH

        /** The killer never right clicks nor holds the use key with [id]: it moves you ([WeaponPlanner.MOBILITY]) or is a fishing tool ([WeaponPlanner.NOT_WEAPONS]). */
        fun neverUsed(id: String) = id in WeaponPlanner.MOBILITY || id in WeaponPlanner.NOT_WEAPONS

        /**
         * A killer right click or hold may go out with [id] in hand at view pitch [xRot]: never with a fishing rod you fish
         * with ([realRod], it would cast), an item that moves you or a fishing tool ([neverUsed]), nor a teleport blade
         * while not looking well down ([bladeAllows]).
         */
        fun useAllowed(id: String, realRod: Boolean, xRot: Float) = ! realRod && ! neverUsed(id) && bladeAllows(id, xRot)

        /**
         * The rod would take more than 8 hits on this creature (a mean share of max hp per hit under 12.5 % over 3 hits or
         * more) and your fights show another option of [available] for it at least twice.
         */
        fun rodTooSlow(habit: Habit, available: Set<String>): Boolean {
            val shares = habit.rodHitShare
            if (shares.size < ROD_SAMPLES || shares.average() >= SLOW_ROD_SHARE) return false
            val fights = HashMap<String, Int>()
            habit.choices.values.forEach { m -> m.forEach { (o, n) -> fights.merge(o, n, Int::plus) } }
            return fights.any { (o, n) -> o != ROD && o != BOBBER && o in available && n >= OTHER_FIGHTS }
        }

        /** The rule a plan fights with: the rod's Learned mode, or the plan's weapon with its click and [loadout]. */
        fun ruleForPlan(plan: Plan, loadout: Int): KillRule =
            if (plan.option == ROD) KillRule(KillAction.ROD, attack = AttackMode.AUTO)
            else KillRule(KillAction.WEAPON, loadout, plan.option, BehaviorLearner.prettyId(plan.option), if (plan.style == ClickStyle.LEFT) AttackMode.LEFT else AttackMode.RIGHT)

        /** The loadout a weapon plan equips: the creature's usual one (2 fights or more) when [option] is its usual weapon, else none. */
        fun learnedLoadout(habit: Habit?, option: String): Int {
            if (habit == null || option == ROD) return 0
            val loadout = habit.topLoadout
            return if (loadout != 0 && (habit.loadouts[loadout] ?: 0) >= 2 && habit.topWeapon == option) loadout else 0
        }

        /**
         * [other] is fought with the same equip as [group]: the same action, and for weapons the same item and loadout (a
         * Learned target, [otherLearned], whatever loadout it would bring).
         */
        fun sameGroup(group: KillRule, other: KillRule, otherLearned: Boolean) =
            other.action == group.action && (group.action != KillAction.WEAPON || other.weaponId == group.weaponId && (otherLearned || other.loadout == group.loadout))

        /**
         * How an explicit rule clicks with [id] in hand: Rod rules melee, and so does a Weapon rule with a fishing rod
         * ([realRod]), an item that moves you or a tool ([neverUsed]) or another item than its weapon in hand (its weapon
         * is not in your hotbar: never a right click with whatever you hold). Otherwise a Weapon rule's Left Click melees,
         * its Right Click holds the use key with an item you hold it with ([learned] style), its Learned click is the
         * item's learned style, else the creature's ([prefersRight]).
         */
        fun explicitStyle(r: KillRule, id: String, realRod: Boolean, learned: ClickStyle?, prefersRight: Boolean): ClickStyle = when {
            r.action != KillAction.WEAPON || realRod || neverUsed(id) || r.weaponId.isNotEmpty() && id != r.weaponId -> ClickStyle.LEFT
            r.attack == AttackMode.LEFT -> ClickStyle.LEFT
            r.attack == AttackMode.RIGHT -> if (learned == ClickStyle.HOLD) ClickStyle.HOLD else ClickStyle.RIGHT
            else -> learned ?: if (prefersRight) ClickStyle.RIGHT else ClickStyle.LEFT
        }

        /**
         * The rod slot remembered at a fight's start: the one in hand when it holds a fishing rod ([handIsRod]), else the
         * one remembered before while it still holds one ([prevIsRod]), else none. A fight started with the Flay in hand
         * never remembers the Flay as the rod.
         */
        fun rodSlotAtStart(handIsRod: Boolean, selected: Int, handId: String, prevSlot: Int, prevId: String, prevIsRod: Boolean): Pair<Int, String> = when {
            handIsRod -> selected to handId
            prevSlot >= 0 && prevIsRod -> prevSlot to prevId
            else -> - 1 to ""
        }

        /** Where a parked creature is, for Fight Messages: `26 m below`, `5 m above`, `12.5 m away`. */
        fun where(hd: Double, dy: Double) = when {
            dy < - 3.0 -> "%.0f m below".format(- dy)
            dy > 4.5 -> "%.0f m above".format(dy)
            else -> "%.1f m away".format(hd)
        }
    }
}

//#endif
