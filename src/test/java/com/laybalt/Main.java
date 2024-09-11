package com.laybalt;

import com.laybalt.autoexperiment.AExperimentKeyBind;
import com.laybalt.autoexperiment.AExperimentMessage;
import com.laybalt.skyblock.AutoFishing.Fishing.AFishMessage;
import com.laybalt.skyblock.AutoFishing.Fishing.AFish;
import com.laybalt.skyblock.AutoFishing.Fishing.AFishKeyBind;
import com.laybalt.skyblock.AutoMelody.AMelody;
import com.laybalt.skyblock.AutoMelody.AMelodyKeyBind;
import com.laybalt.skyblock.AutoMelody.AMelodyMessage;
import com.laybalt.GUI.ExampleConfig;
import com.laybalt.GUI.LBQConfig;
import com.laybalt.autoexperiment.AExperiment;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = Main.MODID, name = Main.NAME, version = Main.VERSION)
public class Main {
    public static final String MODID = "laybalt";
    public static final String NAME = "LayBalt Mod";
    public static final String VERSION = "1.3.11";
    public static final String PREFIX = "§b§lLay§f§lB§c§lalt §8QOL §4>>> §r";

    public static String getModPrefix() {
        return PREFIX;
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);

        ExampleConfig.INSTANCE.initialize();
        LBQConfig.INSTANCE.initialize();

        AFishKeyBind aFishKeyBind = new AFishKeyBind();
        AFishMessage aFishMessage = new AFishMessage();
        AFish aFish = new AFish();

        AMelodyKeyBind aMelodyKeyBind = new AMelodyKeyBind();
        AMelodyMessage aMelodyMessage = new AMelodyMessage();
        AMelody aMelody = new AMelody();

        AExperimentKeyBind aExperimentKeyBind = new AExperimentKeyBind();
        AExperimentMessage aExperimentMessage = new AExperimentMessage();
        AExperiment aExperiment = new AExperiment(LBQConfig.INSTANCE);

        ConfigKeyBind configKeyBind = new ConfigKeyBind();

        MinecraftForge.EVENT_BUS.register(aFishKeyBind);
        MinecraftForge.EVENT_BUS.register(aFishMessage);
        MinecraftForge.EVENT_BUS.register(aFish);

        MinecraftForge.EVENT_BUS.register(aMelodyKeyBind);
        MinecraftForge.EVENT_BUS.register(aMelodyMessage);
        MinecraftForge.EVENT_BUS.register(aMelody);

        MinecraftForge.EVENT_BUS.register(aExperimentKeyBind);
        MinecraftForge.EVENT_BUS.register(aExperimentMessage);
        MinecraftForge.EVENT_BUS.register(aExperiment);

        MinecraftForge.EVENT_BUS.register(configKeyBind);
    }

    /*If you need ExapleGui just uncomment this lines.
    * You can open it by pressing B*/

//    @SubscribeEvent
//    public void onKeyInput(InputEvent.KeyInputEvent event) {
//        if (Keyboard.isKeyDown(Keyboard.KEY_B)) {
//            Minecraft.getMinecraft().displayGuiScreen(ExampleConfig.INSTANCE.gui());
//        }
//    }

    @Mod.EventHandler
    public void preInit(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }
}