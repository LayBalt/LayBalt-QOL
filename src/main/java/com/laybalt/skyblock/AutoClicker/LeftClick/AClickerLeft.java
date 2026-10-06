package com.laybalt.skyblock.AutoClicker.LeftClick;

import com.laybalt.GUI.LBQConfig;
import com.laybalt.util.ClientActions;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class AClickerLeft {
    private long lastClickTime = 0;
    private long clickInterval;
    private final Random random = new Random();
    private Timer timer;
    private boolean lastSwitch;
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

    /**
     * Keeps the timer in sync with the config switch, so toggling the clicker from the
     * config screen (not only via the key bind) starts / stops it as well. New in the Fabric port.
     * Edge-triggered: a switch persisted as true from a previous session does NOT auto-start the
     * clicker on launch (like the original, it only starts on an actual toggle).
     */
    public void register() {
        lastSwitch = LBQConfig.INSTANCE.getLeftClickerSwitch();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean now = LBQConfig.INSTANCE.getLeftClickerSwitch();
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
        int baseClicksPerSecond = LBQConfig.INSTANCE.getLeftClickNumber();
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
        timer = new Timer("LBQ-AutoClicker-Left", true);
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
        if (LBQConfig.INSTANCE.getLeftClickerSwitch() && (currentTime - lastClickTime >= clickInterval)) {
            // Replaces the reflective func_147116_af (clickMouse) call; ClientActions runs the
            // attack on the client thread and skips it when there is no player / world or a screen is open.
            ClientActions.leftClick();
            lastClickTime = currentTime;
            clickInterval = getRandomClickInterval();
        }
    }
}
