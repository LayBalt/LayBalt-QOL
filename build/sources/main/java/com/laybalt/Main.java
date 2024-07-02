package com.laybalt;

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
    public static final String VERSION = "1.1";

    @EventHandler
    public void init(FMLInitializationEvent event) {
        AFishKeyBind aFishKeyBind = new AFishKeyBind();
        AFishMessage aFishMessage = new AFishMessage();
        AFish aFish = new AFish();

        MinecraftForge.EVENT_BUS.register(aFishKeyBind);
        MinecraftForge.EVENT_BUS.register(aFishMessage);
        MinecraftForge.EVENT_BUS.register(aFish);
    }
}
