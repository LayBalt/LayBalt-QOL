package com.laybalt;

import com.laybalt.AutoClicker.LeftClick.AClickerKeyBindLeft;
import com.laybalt.AutoClicker.LeftClick.AClickerLeft;
import com.laybalt.AutoClicker.LeftClick.AClickerMessageLeft;
import com.laybalt.AutoClicker.RightClick.AClickerKeyBindRight;
import com.laybalt.AutoClicker.RightClick.AClickerMessageRight;
import com.laybalt.AutoClicker.RightClick.AClickerRight;
import com.laybalt.AutoFishing.AFishMessage;
import com.laybalt.AutoFishing.AFish;
import com.laybalt.AutoFishing.AFishKeyBind;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = Main.MODID, version = Main.VERSION)
public class Main
{
    public static final String MODID = "laybalt";
    public static final String VERSION = "1.2-SNAPSHOT-1";

    @EventHandler
    public void init(FMLInitializationEvent event) {
        AFishKeyBind aFishKeyBind = new AFishKeyBind();
        AFishMessage aFishMessage = new AFishMessage();
        AFish aFish = new AFish();

        AClickerKeyBindLeft aClickerKeyBind = new AClickerKeyBindLeft();
        AClickerMessageLeft aClickerMessage = new AClickerMessageLeft();
        AClickerLeft aClicker = new AClickerLeft();

        AClickerKeyBindRight aClickerKeyBindRight = new AClickerKeyBindRight();
        AClickerMessageRight aClickerMessageRight = new AClickerMessageRight();
        AClickerRight aClickerRight = new AClickerRight();

        MinecraftForge.EVENT_BUS.register(aFishKeyBind);
        MinecraftForge.EVENT_BUS.register(aFishMessage);
        MinecraftForge.EVENT_BUS.register(aFish);

        MinecraftForge.EVENT_BUS.register(aClickerKeyBind);
        MinecraftForge.EVENT_BUS.register(aClickerMessage);
        MinecraftForge.EVENT_BUS.register(aClicker);

        MinecraftForge.EVENT_BUS.register(aClickerKeyBindRight);
        MinecraftForge.EVENT_BUS.register(aClickerMessageRight);
        MinecraftForge.EVENT_BUS.register(aClickerRight);
    }

    @EventHandler
    public void preInit(FMLInitializationEvent event) {
        if (AClickerLeft.isAutoClicking()) {
            AClickerLeft.toggleAutoClicking();
        }
        if (AClickerRight.isAutoClicking()) {
            AClickerRight.toggleAutoClicking();
        }
        if (AFish.isAutoFishing()) {
            AFish.toggleAutoFishing();
        }
    }
}