package com.laybalt.AutoClicker.LeftClick;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class AClickerLeft {
    private static boolean autoClicking = false;
    private static final int MIN_CLICKS_PER_SECOND = 13;
    private static final int MAX_CLICKS_PER_SECOND = 16;
    private long lastClickTime = 0;
    private long clickInterval;
    private final Random random = new Random();
    private Timer timer;
    private static AClickerLeft instance;

    public AClickerLeft() {
        clickInterval = getRandomClickInterval();
    }

    public static AClickerLeft getInstance() {
        if (instance == null) {
            instance = new AClickerLeft();
        }
        return instance;
    }

    public static void toggleAutoClicking() {
        AClickerLeft clicker = getInstance();
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
                    Method clickMouse = Minecraft.class.getDeclaredMethod("clickMouse");
                    clickMouse.setAccessible(true);
                    clickMouse.invoke(mc);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                lastClickTime = currentTime;
                clickInterval = getRandomClickInterval();
            }
        }
    }
}