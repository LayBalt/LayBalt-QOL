package com.laybalt.skyblock.CustomESP;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.io.*;

public class CustomESP {
    private static final String PREFIX = "§5[CustomESP] §r";
    private static List<String> entityIds = new ArrayList<>();
    private boolean wasDown;
    private boolean enabled;
    private final String CONFIG_FILE = "config/CustomESP.txt";

    public CustomESP() {
        loadEntityIds();
        this.enabled = LBQConfig.INSTANCE.getCustomESP();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (!LBQConfig.INSTANCE.getCustomESP()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;

        boolean isMiddleMouseDown = Mouse.isButtonDown(2);

        if (isMiddleMouseDown && mc.currentScreen == null) {
            if (!wasDown) {
                MovingObjectPosition mop = mc.objectMouseOver;
                if (mop != null && mop.entityHit != null) {
                    Entity entity = mop.entityHit;
                    if (entity instanceof EntityLivingBase) {
                        if ((entity instanceof EntityPlayer && !LBQConfig.INSTANCE.getCustomESPPlayers()) ||
                                (!(entity instanceof EntityPlayer) && !LBQConfig.INSTANCE.getCustomESPMobs())) {
                            sendMessage("§eESP for this entity type is disabled in settings");
                            wasDown = true;
                            return;
                        }

                        String entityId = getEntityIdentifier(entity);
                        if (!entityIds.contains(entityId)) {
                            entityIds.add(entityId);
                            saveEntityIds();
                            sendMessage("§aAdded §f" + entity.getName() + "§a to your ESP list");
                        } else {
                            entityIds.remove(entityId);
                            saveEntityIds();
                            sendMessage("§cRemoved §f" + entity.getName() + "§c from your ESP list");
                        }
                    } else if (entity instanceof EntityItem && !LBQConfig.INSTANCE.getCustomESPItems()) {
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

    private String getEntityIdentifier(Entity entity) {
        return entity.getClass().getName();
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!LBQConfig.INSTANCE.getCustomESP()) return;

        renderESP(event.partialTicks);
    }

    private void renderESP(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableCull();

        GL11.glLineWidth(LBQConfig.INSTANCE.getCustomESPLineWidth());

        if (!LBQConfig.INSTANCE.getCustomESPThroughWalls()) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityLivingBase && entity != mc.thePlayer) {
                if ((entity instanceof EntityPlayer && !LBQConfig.INSTANCE.getCustomESPPlayers()) ||
                        (!(entity instanceof EntityPlayer) && !LBQConfig.INSTANCE.getCustomESPMobs())) {
                    continue;
                }
                String entityId = getEntityIdentifier(entity);
                if (entityIds.contains(entityId)) {
                    drawESPBox(entity, partialTicks);
                }
            } else if (entity instanceof EntityItem && LBQConfig.INSTANCE.getCustomESPItems()) {
                drawESPBox(entity, partialTicks);
            }
        }

        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.popMatrix();
    }

    private void drawESPBox(Entity entity, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();

        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - mc.getRenderManager().viewerPosX;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - mc.getRenderManager().viewerPosY;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - mc.getRenderManager().viewerPosZ;

        AxisAlignedBB bbox = new AxisAlignedBB(
                x - entity.width / 2, y, z - entity.width / 2,
                x + entity.width / 2, y + entity.height, z + entity.width / 2
        );

        Color espColor = LBQConfig.INSTANCE.getCustomESPColor();
        float r = espColor.getRed() / 255f;
        float g = espColor.getGreen() / 255f;
        float b = espColor.getBlue() / 255f;
        float a = espColor.getAlpha() / 255f;

        GlStateManager.color(r, g, b, a);
        RenderGlobal.drawOutlinedBoundingBox(bbox, (int)(r*255), (int)(g*255), (int)(b*255), (int)(a*255));
    }

    private void saveEntityIds() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(CONFIG_FILE))) {
            for (String id : entityIds) {
                writer.println(id);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadEntityIds() {
        entityIds.clear();
        try (BufferedReader reader = new BufferedReader(new FileReader(CONFIG_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                entityIds.add(line.trim());
            }
        } catch (IOException e) {
            // 123
        }
    }

    private void sendMessage(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(Main.PREFIX + PREFIX + message));
        }
    }

    public void sendActionBar(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null) {
            mc.ingameGUI.setRecordPlaying(Main.PREFIX + PREFIX + message, false);
        }
    }

    public void toggle() {
        enabled = !enabled;
        LBQConfig.INSTANCE.setCustomESP(enabled);
        String status = enabled ? "§aon" : "§coff";
        sendMessage("§7CustomESP is now " + status);
    }

    public boolean isEnabled() {
        return LBQConfig.INSTANCE.getCustomESP();
    }
}