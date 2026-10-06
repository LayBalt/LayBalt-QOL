package com.laybalt.skyblock.CustomESP;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.Main;
import com.laybalt.util.ChatUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CustomESP {
    private static final String PREFIX = "§5[CustomESP] §r";
    private static List<String> entityIds = new ArrayList<>();
    private boolean wasDown;
    private boolean enabled;
    // config/CustomESP.txt, resolved against the Fabric config directory instead of the working directory.
    private final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("CustomESP.txt");

    public CustomESP() {
        loadEntityIds();
        this.enabled = LBQConfig.INSTANCE.getCustomESP();
    }

    /** Hooks the Fabric events (replaces the Forge @SubscribeEvent methods). */
    public void register() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> onTick());
        LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register((context, hitResult) -> {
            if (!LBQConfig.INSTANCE.getCustomESP()) return;
            // Gizmos may only be created inside this callback (a collector is active for this frame).
            renderESP(context.level(), context.deltaTracker().getGameTimeDeltaPartialTick(true));
        });
    }

    private void onTick() {
        if (!LBQConfig.INSTANCE.getCustomESP()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Mouse.isButtonDown(2) -> the "pick item" key mapping, bound to the middle button by default.
        boolean isMiddleMouseDown = mc.options.keyPickItem.isDown();

        if (isMiddleMouseDown && mc.gui.screen() == null) {
            if (!wasDown) {
                HitResult mop = mc.hitResult;
                if (mop instanceof EntityHitResult ehr) {
                    Entity entity = ehr.getEntity();
                    if (entity instanceof LivingEntity) {
                        if ((entity instanceof Player && !LBQConfig.INSTANCE.getCustomESPPlayers()) ||
                                (!(entity instanceof Player) && !LBQConfig.INSTANCE.getCustomESPMobs())) {
                            sendMessage("§eESP for this entity type is disabled in settings");
                            wasDown = true;
                            return;
                        }

                        String entityId = getEntityIdentifier(entity);
                        if (!entityIds.contains(entityId)) {
                            entityIds.add(entityId);
                            saveEntityIds();
                            sendMessage("§aAdded §f" + entity.getName().getString() + "§a to your ESP list");
                        } else {
                            entityIds.remove(entityId);
                            saveEntityIds();
                            sendMessage("§cRemoved §f" + entity.getName().getString() + "§c from your ESP list");
                        }
                    } else if (entity instanceof ItemEntity && !LBQConfig.INSTANCE.getCustomESPItems()) {
                        sendMessage("§eESP for items is disabled in settings");
                    } else {
                        sendMessage("§eThis entity cannot be added to ESP list");
                    }
                }
            }
            wasDown = true;
        } else {
            wasDown = false;
        }
    }

    /**
     * Intentional change: the registry id of the entity type (e.g. {@code minecraft:zombie}) instead of
     * the Java class name, so the list stays stable across Minecraft versions / mappings.
     */
    private String getEntityIdentifier(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    private void renderESP(ClientLevel level, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (level == null || mc.player == null) return;

        // The GL state setup (blend, depth, line width) is now expressed through GizmoStyle / GizmoProperties.
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LivingEntity && entity != mc.player) {
                if ((entity instanceof Player && !LBQConfig.INSTANCE.getCustomESPPlayers()) ||
                        (!(entity instanceof Player) && !LBQConfig.INSTANCE.getCustomESPMobs())) {
                    continue;
                }
                String entityId = getEntityIdentifier(entity);
                if (entityIds.contains(entityId)) {
                    drawESPBox(entity, partialTicks);
                }
            } else if (entity instanceof ItemEntity && LBQConfig.INSTANCE.getCustomESPItems()) {
                drawESPBox(entity, partialTicks);
            }
        }
    }

    private void drawESPBox(Entity entity, float partialTicks) {
        // Interpolated position; the gizmo API works in world space, so no camera offset is needed.
        double x = Mth.lerp(partialTicks, entity.xo, entity.getX());
        double y = Mth.lerp(partialTicks, entity.yo, entity.getY());
        double z = Mth.lerp(partialTicks, entity.zo, entity.getZ());

        AABB bbox = entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ());

        Color espColor = LBQConfig.INSTANCE.getCustomESPColor();
        // getRGB() is ARGB, so the configured alpha is kept like the old GlStateManager.color(r, g, b, a).
        GizmoProperties props = Gizmos.cuboid(bbox, GizmoStyle.stroke(espColor.getRGB(), (float) LBQConfig.INSTANCE.getCustomESPLineWidth()));
        if (LBQConfig.INSTANCE.getCustomESPThroughWalls()) {
            props.setAlwaysOnTop(); // replaces GlStateManager.disableDepth()
        }
    }

    private void saveEntityIds() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_FILE)) {
                for (String id : entityIds) {
                    writer.write(id);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            Main.LOGGER.error("Could not save CustomESP list", e);
        }
    }

    private void loadEntityIds() {
        entityIds.clear();
        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                entityIds.add(line.trim());
            }
        } catch (IOException e) {
            // 123
        }
    }

    private void sendMessage(String message) {
        ChatUtil.sendMessage(PREFIX + message);
    }

    public void sendActionBar(String message) {
        ChatUtil.sendActionBar(Main.PREFIX + PREFIX + message);
    }

    public void toggle() {
        enabled = !enabled;
        LBQConfig.INSTANCE.setCustomESP(enabled);
        LBQConfig.INSTANCE.save();
        String status = enabled ? "§aon" : "§coff";
        sendMessage("§7CustomESP is now " + status);
    }

    public boolean isEnabled() {
        return LBQConfig.INSTANCE.getCustomESP();
    }
}
