package com.laybalt.skyblock.AutoClicker.RightClick;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.ClientActions;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class AClickerRight {
    private long lastClickTime = 0;
    private long clickInterval;
    private final Random random = new Random();
    private Timer timer;
    private boolean lastSwitch;
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

    /**
     * Keeps the timer in sync with the config switch, so toggling the clicker from the
     * config screen (not only via the key bind) starts / stops it as well. New in the Fabric port.
     * Edge-triggered: a switch persisted as true from a previous session does NOT auto-start the
     * clicker on launch (like the original, it only starts on an actual toggle).
     */
    public void register() {
        lastSwitch = LBQConfig.INSTANCE.getRightClickerSwitch();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean now = LBQConfig.INSTANCE.getRightClickerSwitch();
            if (now != lastSwitch) {
                lastSwitch = now;
                if (now) {
                    startAutoClick();
                } else {
                    stopAutoClick();
                }
            }
        });
    }

    private long getRandomClickInterval() {
        int baseClicksPerSecond = LBQConfig.INSTANCE.getRightClickNumber();
        int minCPS = Math.max(baseClicksPerSecond - 2, 1);
        int maxCPS = baseClicksPerSecond + 2;
        int clicksPerSecond = random.nextInt((maxCPS - minCPS) + 1) + minCPS;
        return 1000 / clicksPerSecond;
    }

    /** Idempotent: does nothing if the timer is already running. */
    public synchronized void startAutoClick() {
        if (timer != null) {
            return;
        }
        // Daemon thread so a running clicker never keeps the JVM alive on exit.
        timer = new Timer("LBQ-AutoClicker-Right", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                autoClick();
            }
        }, 0, 1);
    }

    /** Idempotent: cancels and clears the timer if one is running. */
    public synchronized void stopAutoClick() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    public synchronized void autoClick() {
        long currentTime = System.currentTimeMillis();
        // INTENTIONAL FIX: the Forge original checked getLeftClickerSwitch() and invoked the LEFT
        // click (func_147116_af) here - a copy-paste bug. This is the right clicker, so it is gated
        // on the right switch and performs a right click (use item / interact).
        if (LBQConfig.INSTANCE.getRightClickerSwitch() && (currentTime - lastClickTime >= clickInterval)) {
            ClientActions.rightClick();
            lastClickTime = currentTime;
            clickInterval = getRandomClickInterval();
        }
    }
}
