package com.laybalt.skyblock.AutoMelody;

import com.laybalt.GUI.LBQConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

public class AMelody {
    private static final List<String> HARP_MELODIES = Arrays.asList(
            "Hymn to the Joy", "Frère Jacques", "Amazing Grace", "Brahms' Lullaby",
            "Happy Birthday to You", "Greensleeves", "Geothermy?", "Minuet",
            "Joy to the World", "Godly Imagination", "La Vie en Rose", "Through the Campfire", "Pachelbel"
    );
    private static final String COLOR_CODES = "dcaebf123456789klmnor";
    private long lastClickTime = 0;

    /** Fabric replacement for the Forge {@code TickEvent.ClientTickEvent} (Phase.START) handler. */
    public void register() {
        ClientTickEvents.START_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft mc) {
        if (!LBQConfig.INSTANCE.getMelodySwitch()) return;

        // 26.2: the current screen lives on the Gui (mc.gui.screen()); GuiChest -> ContainerScreen.
        if (!(mc.gui.screen() instanceof ContainerScreen chest)) return;
        if (mc.gameMode == null || mc.player == null) return;

        ChestMenu container = chest.getMenu();
        // Old getLowerChestInventory() -> menu.getContainer(); chest name comes from the screen title.
        Container lowerChest = container.getContainer();
        String chestName = stripColor(chest.getTitle().getString());

        if (!isHarpMelody(chestName)) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < LBQConfig.INSTANCE.getMelodyDelay()) return;

        for (int i = 37; i <= 43; i++) {
            if (i >= lowerChest.getContainerSize()) break;
            ItemStack itemStack = lowerChest.getItem(i);
            // ItemStack is never null any more -> isEmpty(); Item.getItemFromBlock(Blocks.quartz_block) -> Items.QUARTZ_BLOCK
            if (!itemStack.isEmpty() && itemStack.getItem() == Items.QUARTZ_BLOCK) {
                String itemName = stripColor(itemStack.getHoverName().getString());
                if (itemName.equals("| Click!")) {
                    // windowClick(windowId, slot, 0, 0, player) -> handleContainerInput(..., ContainerInput.PICKUP, player)
                    mc.gameMode.handleContainerInput(
                            container.containerId,
                            i,
                            0,
                            ContainerInput.PICKUP,
                            mc.player
                    );
                    lastClickTime = currentTime;
                    return;
                }
            }
        }
    }

    private boolean isHarpMelody(String chestName) {
        if (!chestName.startsWith("Harp - ")) return false;
        String melodyName = chestName.substring(7);
        return HARP_MELODIES.contains(melodyName);
    }

    private String stripColor(String input) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '§' && i + 1 < input.length() && COLOR_CODES.indexOf(input.charAt(i + 1)) != -1) {
                i++;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
