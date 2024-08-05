package com.laybalt.AutoClicker.RightClick;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class AClickerRight {
    private static boolean autoClicking = false;
    private static final int MIN_CLICKS_PER_SECOND = 13;
    private static final int MAX_CLICKS_PER_SECOND = 16;
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

    public static void toggleAutoClicking() {
        AClickerRight clicker = getInstance();
        autoClicking = !autoClicking;
        if (autoClicking) {
            clicker.startAutoClick();
        } else {
            clicker.stopAutoClick();
        }
    }

    public static boolean isAutoClicking() {
        return autoClicking;
    }

    private long getRandomClickInterval() {
        int clicksPerSecond = random.nextInt((MAX_CLICKS_PER_SECOND - MIN_CLICKS_PER_SECOND) + 1) + MIN_CLICKS_PER_SECOND;
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
        if (autoClicking && (currentTime - lastClickTime >= clickInterval)) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                try {
                    Method rightClickMouse = Minecraft.class.getDeclaredMethod("rightClickMouse");
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