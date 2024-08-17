package com.laybalt.AutoFishing;

import com.laybalt.GUI.LBQConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.List;

public class AFishCombat {
    private final Minecraft mc = Minecraft.getMinecraft();
    private Vec3 originalPosition;
    private float originalYaw;
    private float originalPitch;
    private int originalSlot;
    private boolean inCombat = false;

    public boolean checkForHostileMobs() {
        if (mc.thePlayer == null || mc.theWorld == null) return false;

        double radius = LBQConfig.INSTANCE.getFishingMobDetectionRadius();
        AxisAlignedBB boundingBox = mc.thePlayer.getEntityBoundingBox().expand(radius, radius, radius);
        List<Entity> nearbyEntities = mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer, boundingBox);

        for (Entity entity : nearbyEntities) {
            if (entity instanceof EntityMob && ((EntityMob) entity).getAttackTarget() == mc.thePlayer) {
                return true;
            }
        }
        return false;
    }

    public void startCombat() {
        if (inCombat) return;
        inCombat = true;
        saveOriginalPosition();
        originalSlot = mc.thePlayer.inventory.currentItem;
        switchToSword();
    }

    public void endCombat() {
        if (!inCombat) return;
        inCombat = false;
        returnToOriginalPosition();
        switchToFishingRod();
    }

    private void saveOriginalPosition() {
        originalPosition = mc.thePlayer.getPositionVector();
        originalYaw = mc.thePlayer.rotationYaw;
        originalPitch = mc.thePlayer.rotationPitch;
    }

    private void switchToSword() {
        int swordSlot = LBQConfig.INSTANCE.getFishingSwordSlot() - 1;
        mc.thePlayer.inventory.currentItem = swordSlot;
    }

    private void switchToFishingRod() {
        int rodSlot = LBQConfig.INSTANCE.getFishingRodSlot() - 1;
        mc.thePlayer.inventory.currentItem = rodSlot;
    }

    private void returnToOriginalPosition() {
        mc.thePlayer.setPositionAndRotation(originalPosition.xCoord, originalPosition.yCoord, originalPosition.zCoord, originalYaw, originalPitch);
    }

    public void attackNearestHostileMob() {
        Entity target = findNearestHostileMob();
        if (target != null) {
            faceEntity(target);
            if (mc.thePlayer.getDistanceToEntity(target) <= 3) {
                mc.playerController.attackEntity(mc.thePlayer, target);
            } else {
                moveTowardsEntity(target);
            }
        }
    }

    private Entity findNearestHostileMob() {
        double radius = LBQConfig.INSTANCE.getFishingMobDetectionRadius();
        AxisAlignedBB boundingBox = mc.thePlayer.getEntityBoundingBox().expand(radius, radius, radius);
        List<Entity> nearbyEntities = mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer, boundingBox);
        Entity nearestMob = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : nearbyEntities) {
            if (entity instanceof EntityMob && ((EntityMob) entity).getAttackTarget() == mc.thePlayer) {
                double distance = mc.thePlayer.getDistanceToEntity(entity);
                if (distance < nearestDistance) {
                    nearestMob = entity;
                    nearestDistance = distance;
                }
            }
        }

        return nearestMob;
    }

    private void faceEntity(Entity entity) {
        double dx = entity.posX - mc.thePlayer.posX;
        double dy = entity.posY + entity.getEyeHeight() - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dz = entity.posZ - mc.thePlayer.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distance));

        smoothRotation(yaw, pitch);
    }

    private void smoothRotation(float targetYaw, float targetPitch) {
        float yaw = mc.thePlayer.rotationYaw;
        float pitch = mc.thePlayer.rotationPitch;

        float maxYawChange = 10f;
        float maxPitchChange = 10f;

        yaw = limitAngleChange(yaw, targetYaw, maxYawChange);
        pitch = limitAngleChange(pitch, targetPitch, maxPitchChange);

        mc.thePlayer.rotationYaw = yaw;
        mc.thePlayer.rotationPitch = pitch;
    }

    private float limitAngleChange(float current, float target, float maxChange) {
        float change = Math.abs(target - current);
        if (change > maxChange) {
            if (target > current) {
                return current + maxChange;
            } else {
                return current - maxChange;
            }
        }
        return target;
    }

    private void moveTowardsEntity(Entity entity) {
        double dx = entity.posX - mc.thePlayer.posX;
        double dz = entity.posZ - mc.thePlayer.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance > 3) {
            double speed = 0.15;
            mc.thePlayer.motionX = dx / distance * speed;
            mc.thePlayer.motionZ = dz / distance * speed;
        }
    }

    public boolean isInCombat() {
        return inCombat;
    }
}