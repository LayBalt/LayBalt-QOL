package com.laybalt;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.autoexperiment.AExperiment;
import com.laybalt.autoexperiment.AExperimentKeyBind;
import com.laybalt.skyblock.AutoClicker.LeftClick.AClickerKeyBindLeft;
import com.laybalt.skyblock.AutoClicker.LeftClick.AClickerLeft;
import com.laybalt.skyblock.AutoClicker.RightClick.AClickerKeyBindRight;
import com.laybalt.skyblock.AutoClicker.RightClick.AClickerRight;
import com.laybalt.skyblock.AutoFishing.Fishing.AFish;
import com.laybalt.skyblock.AutoFishing.Fishing.AFishKeyBind;
import com.laybalt.skyblock.AutoMelody.AMelody;
import com.laybalt.skyblock.AutoMelody.AMelodyKeyBind;
import com.laybalt.skyblock.CustomESP.CustomESP;
import com.laybalt.vamphelper.TitleChecker;
import com.laybalt.vamphelper.VHelper;
import com.laybalt.vamphelper.VHelperKeyBind;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint of LayBalt QOL (Fabric).
 *
 * <p>Every feature is a small class that registers its own key mapping in its constructor and hooks
 * the Fabric events it needs in {@code register()}. This replaces the Forge event bus used by the
 * 1.8.9 version of the mod.
 */
public class Main implements ClientModInitializer {
    public static final String MODID = "lbqmod";
    public static final String NAME = "LayBalt Mod";
    public static final String PREFIX = "§b§lLay§f§lB§c§lalt §8QOL §4>>> §r";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public static String getModPrefix() {
        return PREFIX;
    }

    @Override
    public void onInitializeClient() {
        LBQConfig.INSTANCE.load();

        // Fishing
        new AFishKeyBind().register();
        new AFish().register();

        // Melody
        new AMelodyKeyBind().register();
        new AMelody().register();

        // Auto clickers
        AClickerLeft.getInstance().register();
        AClickerRight.getInstance().register();
        new AClickerKeyBindLeft().register();
        new AClickerKeyBindRight().register();

        // ESP
        new CustomESP().register();

        // Experiments
        new AExperimentKeyBind().register();
        new AExperiment(LBQConfig.INSTANCE).register();

        // Vampire slayer
        new VHelper().register();
        new VHelperKeyBind().register();
        new TitleChecker().register();

        // Config screen key
        new ConfigKeyBind().register();

        LOGGER.info("{} loaded", NAME);
    }
}
