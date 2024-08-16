package com.laybalt.AutoFishing;

import com.laybalt.GUI.LBQConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AFish {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private boolean isReelingScheduled = false;
    private boolean isCastingScheduled = false;
    private int casttimer = 0;
    private boolean isReeling = false;
    private boolean isCasting = false;
    private boolean isReelingInProgress = false;
    private boolean isShakingHead = false;
    private boolean isRotating = false;
    private final Random random = new Random();

    public void ReelRod() {
        if (isReelingInProgress) return;
        isReelingInProgress = true;

        // Использование обфусцированного метода для нажатия правой кнопки мыши
        KeyBinding.onTick(Minecraft.getMinecraft().gameSettings.keyBindUseItem.getKeyCode());

        isReelingScheduled = false;
        isReeling = false;
        scheduler.schedule(() -> isReelingInProgress = false, LBQConfig.INSTANCE.getFishingReelDelayNumber(), TimeUnit.MILLISECONDS);
    }

    public void CastRod() {
        EntityFishHook fishHook = Minecraft.getMinecraft().thePlayer.fishEntity;
        casttimer++;
        if (casttimer >= 20 && fishHook == null) {
            // Использование обфусцированного метода для нажатия правой кнопки мыши
            ObfuscationReflectionHelper.setPrivateValue(Minecraft.class, Minecraft.getMinecraft(), true, "field_71425_J", "rightClickDelayTimer");
            KeyBinding.onTick(Minecraft.getMinecraft().gameSettings.keyBindUseItem.getKeyCode());
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

                if (LBQConfig.INSTANCE.getFishingShakeHead() && !isShakingHead && !isRotating) {
                    isShakingHead = true;
                    scheduler.schedule(this::shakeHead, 20 + random.nextInt(11), TimeUnit.SECONDS);
                }
            }
        } else {
            isShakingHead = false;
            isRotating = false;
        }
    }

    private void shakeHead() {
        if (!LBQConfig.INSTANCE.getFishingSwitch()) return;

        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            float initialYaw = player.rotationYaw;
            float initialPitch = player.rotationPitch;

            float targetYaw = initialYaw + (random.nextFloat() * 3 - 1.5f);
            float targetPitch = initialPitch + (random.nextFloat() * 3 - 1.5f);

            smoothRotation(initialYaw, initialPitch, targetYaw, targetPitch);
        }
    }

    private void smoothRotation(float startYaw, float startPitch, float endYaw, float endPitch) {
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (long) 500;
        isRotating = true;

        final ScheduledFuture<?>[] rotationTaskRef = new ScheduledFuture<?>[1];

        rotationTaskRef[0] = scheduler.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime >= endTime || !isRotating) {
                isRotating = false;
                scheduler.schedule(() -> isShakingHead = false, 500, TimeUnit.MILLISECONDS);
                rotationTaskRef[0].cancel(false);
                return;
            }

            float progress = (float) (currentTime - startTime) / (long) 500;
            float easedProgress = easeInOutQuad(progress);

            float newYaw = interpolateAngle(startYaw, endYaw, easedProgress);
            float newPitch = startPitch + (endPitch - startPitch) * easedProgress;

            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player != null) {
                player.rotationYaw = newYaw;
                player.rotationPitch = newPitch;
            }
        }, 0, 16, TimeUnit.MILLISECONDS);
    }

    private float easeInOutQuad(float t) {
        return t < 0.5f ? 2 * t * t : -1 + (4 - 2 * t) * t;
    }

    private float interpolateAngle(float a, float b, float t) {
        float diff = b - a;
        while (diff < -180) diff += 360;
        while (diff >= 180) diff -= 360;
        return a + diff * t;
    }
}