package com.laybalt.features

import com.laybalt.LayBaltQOL.MOD_NAME
import com.laybalt.config.ConfigHolder
import com.laybalt.config.SettingProvider
import com.laybalt.event.*
import com.laybalt.event.priority.EventPriority
import com.laybalt.features.annotations.AlwaysActive
import com.laybalt.ui.clickgui.enums.CategoryType
import com.laybalt.ui.hud.HudElement
import com.laybalt.ui.hud.HudProvider
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.spaceCaps
import net.minecraft.client.gui.GuiGraphicsExtractor

open class Feature(
    val description: String? = null,
    name: String? = null,
    toggled: Boolean = false,
    jsonName: String? = null,
): Shortcuts, SettingProvider, HudProvider {
    val name = name ?: this::class.simpleName.toString().spaceCaps()
    val jsonName = jsonName ?: this.name
    @JvmField var enabled = toggled
    open val category = initCategory()

    private val alwaysActive = this::class.java.isAnnotationPresent(AlwaysActive::class.java)

    /**
     * Work-in-progress features are shown greyed out in the click GUI and can never be enabled:
     * [toggle] refuses (and notifies), [ConfigManager][com.laybalt.config.ConfigManager] never enables them on load
     * and their listeners are never registered.
     */
    open val wip: Boolean = false

    override val configSettings = mutableSetOf<ConfigHolder<*>>()
    val listeners = mutableSetOf<EventListener<*>>()
    override val hudElements = mutableSetOf<HudElement>()

    fun initialize() {
        init()

        if (wip) enabled = false
        if ((enabled || alwaysActive) && ! wip) onEnable() else onDisable()
    }

    open fun init() = Unit
    open fun onEnable() {
        if (wip) return
        listeners.forEach(EventListener<*>::register)
    }

    open fun onDisable() {
        if (alwaysActive && ! wip) return
        listeners.forEach(EventListener<*>::unregister)
    }

    open fun toggle() {
        if (wip) {
            if (enabled) {
                enabled = false
                onDisable()
            }
            NotificationManager.push(MOD_NAME, "$name is work in progress")
            return
        }

        enabled = ! enabled
        if (enabled || alwaysActive) onEnable()
        else onDisable()
    }

    protected inline fun <reified T: Event> register(
        priority: EventPriority = EventPriority.NORMAL,
        receiveCancelled: Boolean = false,
        noinline block: EventContext<T>.() -> Unit
    ): EventListener<T> {
        val listener = EventBus.listener<T>(priority, receiveCancelled, block)
        listeners.add(listener)
        return listener
    }

    /**
     * Registers a HUD element. [name] is its config id (keep it stable, saved positions are keyed by it); [displayName]
     * is what the HUD editor shows (default: the feature name when [name] is the feature id, else [name] humanised).
     */
    fun hudElement(
        name: String = this@Feature.jsonName,
        enabled: () -> Boolean = { true },
        shouldDraw: () -> Boolean = { true },
        centered: () -> Boolean = { false },
        displayName: String? = null,
        render: (GuiGraphicsExtractor, Boolean) -> Pair<Number, Number>
    ) = object: HudElement() {
        override val name = name
        override val displayName = displayName
            ?: if (name == this@Feature.jsonName || name == this@Feature::class.simpleName) this@Feature.name else HudElement.humanize(name)
        override val centered = centered
        override val toggle get() = this@Feature.enabled && enabled.invoke()
        override val shouldDraw get() = shouldDraw.invoke()
        override fun draw(ctx: GuiGraphicsExtractor, example: Boolean) = render(ctx, example)
    }.also(hudElements::add)

    private fun initCategory(): CategoryType {
        val parts = this::class.java.`package` !!.name.split(".")
        val categoryName = parts[parts.indexOf("impl") + 1].uppercase()
        if (CategoryType.entries.none { it.name.equals(categoryName, true) }) error("Category does not exist: $categoryName")
        return CategoryType.valueOf(categoryName.uppercase())
    }
}