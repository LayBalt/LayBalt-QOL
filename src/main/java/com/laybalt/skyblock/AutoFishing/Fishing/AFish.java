package com.laybalt.skyblock.AutoFishing.Fishing;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.Main;
import com.laybalt.util.ClientActions;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AFish {
    // Daemon thread so a scheduled task can never keep the game process alive on exit.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LBQ-AutoFishing");
        t.setDaemon(true);
        return t;
    });
    // These flags are read/written from both the client thread and the scheduler thread,
    // so they are volatile (the Forge version relied on plain fields).
    private volatile boolean isReelingScheduled = false;
    private volatile boolean isCastingScheduled = false;
    private volatile boolean isReeling = false;
    private volatile boolean isCasting = false;
    private volatile boolean isReelingInProgress = false;
    private volatile boolean isShakingHead = false;
    private volatile boolean isRotating = false;
    private boolean isSensitivityLowered = false;
    private double originalSensitivity; // Options.sensitivity() is an OptionInstance<Double> now
    private long lastFishingCheck = 0;
    private final Random random = new Random();

    public void register() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> onTick());
        // overlay == true is action-bar text; only real chat lines are inspected, like ClientChatReceivedEvent did.
        ClientReceiveMessageEvents.GAME.register((Component message, boolean overlay) -> {
            if (!overlay) {
                onChatMessage(message);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onClientDisconnect());
    }

    private boolean isGuiOpen() {
        return ClientActions.isScreenOpen();
    }

    private void disableMouseMovementCompletely() {
        if (!isSensitivityLowered) {
            OptionInstance<Double> sensitivity = Minecraft.getInstance().options.sensitivity();
            originalSensitivity = sensitivity.get();
            // 0.0 is the lowest legal value (0..1); it yields the same near-zero turn factor
            // the old Float.MIN_VALUE hack did (mouse movement practically disabled).
            sensitivity.set(0.0);
            isSensitivityLowered = true;
        }
    }

    private void restoreSensitivity() {
        if (isSensitivityLowered) {
            Minecraft.getInstance().options.sensitivity().set(originalSensitivity);
            isSensitivityLowered = false;
            Main.LOGGER.info("Original sensitivity {}", originalSensitivity);
        }
    }

    public void ReelRod() {
        if (isReelingInProgress) return;
        isReelingInProgress = true;

        // KeyBinding.onTick(useKey) -> one simulated right click on the client thread.
        ClientActions.rightClick();

        isReelingScheduled = false;
        isReeling = false;
        scheduler.schedule(() -> {
            isReelingInProgress = false;

        }, LBQConfig.INSTANCE.getFishingReelDelayNumber(), TimeUnit.MILLISECONDS);
    }

    public void CastRod() {
        // Runs from the scheduler thread; player state may only be touched on the client thread.
        ClientActions.runOnClientThread(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            FishingHook fishHook = player != null ? player.fishing : null;
            if (fishHook == null) {
                // The rightClickDelayTimer reflection hack is no longer needed: startUseItem()
                // (called by ClientActions.rightClick()) does not check the right click delay.
                ClientActions.rightClick();
            }
            isCastingScheduled = false;
            isCasting = false;
        });
    }

    private void checkFishingRodStatus() {
        ClientActions.runOnClientThread(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            FishingHook fishHook = player != null ? player.fishing : null;
            if (fishHook == null || !fishHook.isInWater()) {
                scheduler.schedule(this::CastRod, LBQConfig.INSTANCE.getFishingCastDelayNumber(), TimeUnit.MILLISECONDS);
            }
        });
    }

    private void onTick() {
        if (LBQConfig.INSTANCE.getFishingSwitch()) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player != null && mc.level != null) {
                if (isGuiOpen()) {
                    AFishMessage.sendGuiOpenMessage();
                    disableAutoFishing();
                    return;
                }
                if (LBQConfig.INSTANCE.getFishingStopMoveHead()) {
                    disableMouseMovementCompletely();
                }

                ItemStack itemStack = player.getMainHandItem();
                if (itemStack.getItem() == Items.FISHING_ROD) {
                    for (ArmorStand entity : mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(player.getX() - 15, player.getY() - 15, player.getZ() - 15, player.getX() + 15, player.getY() + 15, player.getZ() + 15))) {
                        // getCustomNameTag() returned "" when unnamed; getCustomName() is null instead.
                        if (entity.getCustomName() != null && entity.getCustomName().getString().contains("!!!")) {
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

    private void onChatMessage(Component component) {
        if (!LBQConfig.INSTANCE.getFishingSwitch()) {
            return;
        }

        // getUnformattedText() -> getString()
        String message = component.getString();

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

    private void onClientDisconnect() {
        Main.LOGGER.info("Client disconnected from server");
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
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        FishingHook fishHook = mc.player.fishing;
        // motionX/Y/Z -> getDeltaMovement()
        Vec3 motion = fishHook != null ? fishHook.getDeltaMovement() : null;
        if (fishHook == null || (motion.x == 0 && motion.y == 0 && motion.z == 0)) {
            // ItemStack is never null any more; an empty hand holds Items.AIR, which is != FISHING_ROD.
            ItemStack itemStack = mc.player.getMainHandItem();
            if (itemStack.getItem() != Items.FISHING_ROD) {
                ReelRod();
                scheduler.schedule(this::CastRod, LBQConfig.INSTANCE.getFishingCastDelayNumber(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private void shakeHead() {
        if (!LBQConfig.INSTANCE.getFishingSwitch()) return;

        // Runs from the scheduler thread; the initial rotation is read on the client thread.
        ClientActions.runOnClientThread(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                float initialYaw = player.getYRot();
                float initialPitch = player.getXRot();

                float targetYaw = initialYaw + (random.nextFloat() * 3 - 1.5f);
                float targetPitch = initialPitch + (random.nextFloat() * 3 - 1.5f);

                smoothRotation(initialYaw, initialPitch, targetYaw, targetPitch);

                scheduler.schedule(this::checkFishingRodStatus, 350, TimeUnit.MILLISECONDS);
            }
        });
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

            // Interpolation stays on the 16 ms executor; only the rotation write hops to the client thread.
            ClientActions.runOnClientThread(() -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null) {
                    player.setYRot(newYaw);
                    player.setXRot(newPitch);
                }
            });
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
