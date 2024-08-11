package com.laybalt.AutoClicker.RightClick;

import com.laybalt.GUI.LBQConfig;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class AClickerRight {
    private long lastClickTime = 0;
    private long clickInterval;
    private final Random random = new Random();
    private Timer timer;
    private static AClickerRight instance;

    public AClickerRight() {
        clickInterval = getRandomClickInterval();
    }

    public static AClickerRight getInstance() {
        if (instance == null) {
            instance = new AClickerRight();
        }
        return instance;
    }

    private long getRandomClickInterval() {
        int baseClicksPerSecond = LBQConfig.INSTANCE.getRightClickNumber();
        int minCPS = Math.max(baseClicksPerSecond - 2, 1);
        int maxCPS = baseClicksPerSecond + 2;
        int clicksPerSecond = random.nextInt((maxCPS - minCPS) + 1) + minCPS;
        return 1000 / clicksPerSecond;
    }

    public void startAutoClick() {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                autoClick();
            }
        }, 0, 1);
    }

    public void stopAutoClick() {
        if (timer != null) {
            timer.cancel();
        }
    }

    public void autoClick() {
        long currentTime = System.currentTimeMillis();
        if (LBQConfig.INSTANCE.getRightClickerSwitch() && (currentTime - lastClickTime >= clickInterval)) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                try {
                    Method rightClickMouse = Minecraft.class.getDeclaredMethod("func_147121_ag");
                    rightClickMouse.setAccessible(true);
                    rightClickMouse.invoke(mc);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                lastClickTime = currentTime;
                clickInterval = getRandomClickInterval();
            }
        }
    }
}