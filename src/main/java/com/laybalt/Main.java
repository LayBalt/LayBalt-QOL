package com.laybalt;

import com.laybalt.AutoClicker.LeftClick.AClickerKeyBindLeft;
import com.laybalt.AutoClicker.LeftClick.AClickerLeft;
import com.laybalt.AutoClicker.LeftClick.AClickerMessageLeft;
import com.laybalt.AutoClicker.RightClick.AClickerKeyBindRight;
import com.laybalt.AutoClicker.RightClick.AClickerMessageRight;
import com.laybalt.AutoClicker.RightClick.AClickerRight;
import com.laybalt.AutoExperiments.AExperimentKeyBind;
import com.laybalt.AutoExperiments.AExperimentMessage;
import com.laybalt.AutoFishing.AFishMessage;
import com.laybalt.AutoFishing.AFish;
import com.laybalt.AutoFishing.AFishKeyBind;
import com.laybalt.AutoMelody.AMelody;
import com.laybalt.AutoMelody.AMelodyKeyBind;
import com.laybalt.AutoMelody.AMelodyMessage;
import com.laybalt.GUI.ExampleConfig;
import com.laybalt.GUI.LBQConfig;
import com.laybalt.experiments.AExperiment;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

@SuppressWarnings("InstantiationOfUtilityClass")
@Mod(modid = Main.MODID, version = Main.VERSION)
public class Main
{
    public static final String MODID = "laybalt";
    public static final String VERSION = "1.3.1";

    private static final int keyBinding = Keyboard.KEY_RSHIFT;


    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);

        ExampleConfig.INSTANCE.initialize();
        LBQConfig.INSTANCE.initialize();

        AFishKeyBind aFishKeyBind = new AFishKeyBind();
        AFishMessage aFishMessage = new AFishMessage();
        AFish aFish = new AFish();

        AClickerKeyBindLeft aClickerKeyBind = new AClickerKeyBindLeft();
        AClickerMessageLeft aClickerMessage = new AClickerMessageLeft();
        AClickerLeft aClicker = new AClickerLeft();

        AClickerKeyBindRight aClickerKeyBindRight = new AClickerKeyBindRight();
        AClickerMessageRight aClickerMessageRight = new AClickerMessageRight();
        AClickerRight aClickerRight = new AClickerRight();

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

        MinecraftForge.EVENT_BUS.register(aClickerKeyBind);
        MinecraftForge.EVENT_BUS.register(aClickerMessage);
        MinecraftForge.EVENT_BUS.register(aClicker);

        MinecraftForge.EVENT_BUS.register(aClickerKeyBindRight);
        MinecraftForge.EVENT_BUS.register(aClickerMessageRight);
        MinecraftForge.EVENT_BUS.register(aClickerRight);

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