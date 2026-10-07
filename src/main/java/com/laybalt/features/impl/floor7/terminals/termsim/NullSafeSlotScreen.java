package com.laybalt.features.impl.floor7.terminals.termsim;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

/**
 * Base screen of the terminal simulator. Vanilla calls {@code slotClicked(null, -999, ...)} for clicks outside
 * the window (and from quick-craft/release paths), but a Kotlin override of {@code slotClicked} cannot declare
 * the slot nullable against the annotated 26.2 signature and would throw an NPE from its parameter check.
 * This Java layer drops those calls and forwards real slot clicks to {@link #onSlotClicked}.
 */
public abstract class NullSafeSlotScreen extends ContainerScreen {
    protected NullSafeSlotScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected final void slotClicked(@Nullable Slot slot, int slotId, int button, ContainerInput input) {
        if (slot == null || slotId == -999) return;
        onSlotClicked(slot, slotId, button, input);
    }

    protected abstract void onSlotClicked(Slot slot, int slotId, int button, ContainerInput input);
}
