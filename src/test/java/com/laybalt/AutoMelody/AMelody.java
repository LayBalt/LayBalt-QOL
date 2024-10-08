package com.laybalt.AutoMelody;

import com.laybalt.GUI.LBQConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.*;

public class AMelody {
    private static final List<String> HARP_MELODIES = Arrays.asList(
            "Hymn to the Joy", "Frère Jacques", "Amazing Grace", "Brahms' Lullaby",
            "Happy Birthday to You", "Greensleeves", "Geothermy?", "Minuet",
            "Joy to the World", "Godly Imagination", "La Vie en Rose"
    );
    private static final String COLOR_CODES = "dcaebf123456789klmnor";
    private long lastClickTime = 0;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !LBQConfig.INSTANCE.getMelodySwitch()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof GuiChest)) return;

        GuiChest chest = (GuiChest) mc.currentScreen;
        ContainerChest container = (ContainerChest) chest.inventorySlots;
        String chestName = stripColor(container.getLowerChestInventory().getDisplayName().getUnformattedText());

        if (!isHarpMelody(chestName)) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < LBQConfig.INSTANCE.getMelodyDelay()) return;

        for (int i = 37; i <= 43; i++) {
            ItemStack itemStack = container.getLowerChestInventory().getStackInSlot(i);
            if (itemStack != null && Item.getItemFromBlock(Blocks.quartz_block) == itemStack.getItem()) {
                String itemName = stripColor(itemStack.getDisplayName());
                if (itemName.equals("| Click!")) {
                    mc.playerController.windowClick(
                            container.windowId,
                            i,
                            0,
                            0,
                            mc.thePlayer
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