package com.laybalt.skyblock.AutoFishing.Fishing;

import com.laybalt.GUI.LBQConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AFish {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private boolean isReelingScheduled = false;
    private boolean isCastingScheduled = false;
    private boolean isReeling = false;
    private boolean isCasting = false;
    private boolean isReelingInProgress = false;
    private boolean isShakingHead = false;
    private boolean isRotating = false;
    private boolean isSensitivityLowered = false;
    private float originalSensitivity;
    private long lastFishingCheck = 0;
    private final Random random = new Random();
    private boolean isGuiOpen() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.currentScreen != null;
    }
    private void disableMouseMovementCompletely() {
        if (!isSensitivityLowered) {
            GameSettings settings = Minecraft.getMinecraft().gameSettings;
            originalSensitivity = settings.mouseSensitivity;
            settings.mouseSensitivity = Float.MIN_VALUE; // Practically disable mouse movement
            isSensitivityLowered = true;
        }
    }

    private void restoreSensitivity() {
        if (isSensitivityLowered) {
            Minecraft.getMinecraft().gameSettings.mouseSensitivity = originalSensitivity;
            isSensitivityLowered = false;
            System.out.println("Original sensitivity " + originalSensitivity);
        }
    }

    public void ReelRod() {
        if (isReelingInProgress) return;
        isReelingInProgress = true;

        KeyBinding.onTick(Minecraft.getMinecraft().gameSettings.keyBindUseItem.getKeyCode());

        isReelingScheduled = false;
        isReeling = false;
        scheduler.schedule(() -> {
            isReelingInProgress = false;

        }, LBQConfig.INSTANCE.getFishingReelDelayNumber(), TimeUnit.MILLISECONDS);
    }

    public void CastRod() {
        EntityFishHook fishHook = Minecraft.getMinecraft().thePlayer.fishEntity;
        if (fishHook == null) {
            ObfuscationReflectionHelper.setPrivateValue(Minecraft.class, Minecraft.getMinecraft(), true, "field_71425_J", "rightClickDelayTimer");
            KeyBinding.onTick(Minecraft.getMinecraft().gameSettings.keyBindUseItem.getKeyCode());
        }
        isCastingScheduled = false;
        isCasting = false;
    }

    private void checkFishingRodStatus() {
        EntityFishHook fishHook = Minecraft.getMinecraft().thePlayer.fishEntity;
        if (fishHook == null || !fishHook.isInWater()) {
            scheduler.schedule(this::CastRod, LBQConfig.INSTANCE.getFishingCastDelayNumber(), TimeUnit.MILLISECONDS);
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (LBQConfig.INSTANCE.getFishingSwitch()) {
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player != null) {
                if (isGuiOpen()) {
                    AFishMessage.sendGuiOpenMessage();
                    disableAutoFishing();
                    return;
                }
                if (LBQConfig.INSTANCE.getFishingStopMoveHead()){
                    disableMouseMovementCompletely();
                }

                ItemStack itemStack = player.getHeldItem();
                if (itemStack != null && itemStack.getItem() == Items.fishing_rod) {
                    for (Entity entity : Minecraft.getMinecraft().theWorld.getEntitiesWithinAABB(EntityArmorStand.class, new AxisAlignedBB(player.posX - 15, player.posY - 15, player.posZ - 15, player.posX + 15, player.posY + 15, player.posZ + 15))) {
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

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFishingCheck > 10000) {
                checkFishingState();
                lastFishingCheck = currentTime;
            }

        } else {
            isShakingHead = false;
            isRotating = false;
            restoreSensitivity();
        }
    }

    @SubscribeEvent
    public void onChatMessage(ClientChatReceivedEvent event) {
        if (!LBQConfig.INSTANCE.getFishingSwitch()) {
            return;
        }

        String message = event.message.getUnformattedText();

        if (message.contains("You") && (
                message.contains("fell into the void") ||
                        message.contains("was thrown into the void by") ||
                        message.contains("was slain by") ||
                        message.contains("burned to death") ||
                        message.contains("fell to death") ||
                        message.contains("fell to their death with help from") ||
                        message.contains("suffocated.") ||
                        message.contains("drowned.") ||
                        message.contains("was pricked to death by a cactus.") ||
                        message.contains("died.") ||
                        message.contains("were killed by")
        )) {
            disableAutoFishing();
            AFishMessage.sendDead(message);
        }

        if (message.contains("Sending to server")) {
            disableAutoFishing();
            AFishMessage.sendWarped();
        }
    }

    @SubscribeEvent
    public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        System.out.println("Client disconnected from server");
        disableAutoFishing();
    }

    private void disableAutoFishing() {
        LBQConfig.INSTANCE.setFishingSwitch(false);
        isReelingScheduled = false;
        isCastingScheduled = false;
        isReeling = false;
        isCasting = false;
        isReelingInProgress = false;
        isShakingHead = false;
        isRotating = false;
    }

    private void checkFishingState() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;

        EntityFishHook fishHook = mc.thePlayer.fishEntity;
        if (fishHook == null || (fishHook.motionX == 0 && fishHook.motionY == 0 && fishHook.motionZ == 0)) {
            ItemStack itemStack = mc.thePlayer.getHeldItem();
            if (itemStack == null || itemStack.getItem() != Items.fishing_rod) {
                ReelRod();
                scheduler.schedule(this::CastRod, LBQConfig.INSTANCE.getFishingCastDelayNumber(), TimeUnit.MILLISECONDS);
            }
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

            scheduler.schedule(this::checkFishingRodStatus, 350, TimeUnit.MILLISECONDS);
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