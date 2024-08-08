package com.laybalt.AutoFishing;

import com.laybalt.GUI.LBQConfig;
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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AFish {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private boolean isReelingScheduled = false;
    private boolean isCastingScheduled = false;
    private int casttimer = 0;
    private boolean isReeling = false;
    private boolean isCasting = false;
    private boolean isReelingInProgress = false;

    public void ReelRod() {
        if (isReelingInProgress) return;
        isReelingInProgress = true;
        ItemStack itemStack = Minecraft.getMinecraft().thePlayer.getHeldItem();
        Minecraft.getMinecraft().playerController.sendUseItem(Minecraft.getMinecraft().thePlayer, Minecraft.getMinecraft().theWorld, itemStack);
        isReelingScheduled = false;
        isReeling = false;
        scheduler.schedule(() -> isReelingInProgress = false, LBQConfig.INSTANCE.getFishingReelDelayNumber(), TimeUnit.MILLISECONDS);
    }

    public void CastRod() {
        EntityFishHook fishHook = Minecraft.getMinecraft().thePlayer.fishEntity;
        casttimer++;
        if (casttimer >= 20 && fishHook == null) {
            ItemStack itemStack = Minecraft.getMinecraft().thePlayer.getHeldItem();
            Minecraft.getMinecraft().playerController.sendUseItem(Minecraft.getMinecraft().thePlayer, Minecraft.getMinecraft().theWorld, itemStack);
            casttimer = 0;
        }
        isCastingScheduled = false;
        isCasting = false;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (LBQConfig.INSTANCE.getFishingSwitch()) {
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
                                isReeling = true;
                                if (!isReelingScheduled && !isCasting) {
                                    isReelingScheduled = true;
                                    scheduler.schedule(this::ReelRod, LBQConfig.INSTANCE.getFishingReelDelayNumber(), TimeUnit.MILLISECONDS);
                                }
                                break;
                            }
                        }
                        if (isReeling) {
                            isReeling = false;
                            if (!isCastingScheduled && !isReelingScheduled) {
                                isCastingScheduled = true;
                                isCasting = true;
                                scheduler.schedule(this::CastRod, LBQConfig.INSTANCE.getFishingCastDelayNumber(), TimeUnit.MILLISECONDS);
                            }
                        }
                    }
                }
            }
        }
    }
}