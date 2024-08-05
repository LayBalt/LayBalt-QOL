package com.laybalt.AutoFishing;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class AFish {
    private static boolean isAutoFishing = false;

    public static void toggleAutoFishing() {
        isAutoFishing = !isAutoFishing;
    }

    public static boolean isAutoFishing() {
        return isAutoFishing;
    }
    private int casttimer = 0;
    private boolean isReeling = false;

    public void ReelRod() {
        // minecraft reel the rod
        ItemStack itemStack = Minecraft.getMinecraft().thePlayer.getHeldItem();
        Minecraft.getMinecraft().playerController.sendUseItem(Minecraft.getMinecraft().thePlayer, Minecraft.getMinecraft().theWorld, itemStack);
    }

    public void CastRod() {
        EntityFishHook fishHook = Minecraft.getMinecraft().thePlayer.fishEntity;
        casttimer++;
        if (casttimer >= 20 && fishHook == null) {
            ItemStack itemStack = Minecraft.getMinecraft().thePlayer.getHeldItem();
            Minecraft.getMinecraft().playerController.sendUseItem(Minecraft.getMinecraft().thePlayer, Minecraft.getMinecraft().theWorld, itemStack);
            casttimer = 0;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (isAutoFishing) {
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player != null) {
                ItemStack itemStack = Minecraft.getMinecraft().thePlayer.getHeldItem();
                if (itemStack != null && itemStack.getItem() == Items.fishing_rod) {
                    EntityFishHook fishHook = Minecraft.getMinecraft().thePlayer.fishEntity;
                    if (fishHook != null && fishHook.isInWater()) {
                        double x = fishHook.posX;
                        double y = fishHook.posY;
                        double z = fishHook.posZ;

                        for (Entity entity : Minecraft.getMinecraft().theWorld.getEntitiesWithinAABB(EntityArmorStand.class, new AxisAlignedBB(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1))) {
                            if (entity instanceof EntityArmorStand && entity.getCustomNameTag().contains("!!!")) {
                                ReelRod();
                                isReeling = true;
                                break;
                            }
                        }
                        if (isReeling) {
                            CastRod();
                            isReeling = false;
                        }
                    }
                }
            }
        }
    }
}