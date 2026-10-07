package com.laybalt.event.impl

import com.laybalt.event.Event
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.world.entity.Entity

/**
 * Posted right after [net.minecraft.client.renderer.entity.EntityRenderDispatcher.extractEntity] filled the render state
 * of [entity] for this frame. Listeners may mutate [renderState] (e.g. `isInvisible`, `CreeperRenderState.isPowered`);
 * the change only affects how the entity is drawn this frame.
 */
class EntityRenderStateEvent(val entity: Entity, val renderState: EntityRenderState): Event(cancelable = false)
