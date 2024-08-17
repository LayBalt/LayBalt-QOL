package com.laybalt.AutoClicker.LeftClick;//package com.laybalt.AutoClicker.LeftClick;
//
//import com.laybalt.GUI.LBQConfig;
//import net.minecraft.client.Minecraft;
//
//import java.lang.reflect.Method;
//import java.util.Random;
//import java.util.Timer;
//import java.util.TimerTask;
//
//public class AClickerLeft {
//    private long lastClickTime = 0;
//    private long clickInterval;
//    private final Random random = new Random();
//    private Timer timer;
//    private static AClickerLeft instance;
//
//    public AClickerLeft() {
//        clickInterval = getRandomClickInterval();
//    }
//
//    public static AClickerLeft getInstance() {
//        if (instance == null) {
//            instance = new AClickerLeft();
//        }
//        return instance;
//    }
//
//    private long getRandomClickInterval() {
//        int baseClicksPerSecond = LBQConfig.INSTANCE.getLeftClickNumber();
//        int minCPS = Math.max(baseClicksPerSecond - 2, 1);
//        int maxCPS = baseClicksPerSecond + 2;
//        int clicksPerSecond = random.nextInt((maxCPS - minCPS) + 1) + minCPS;
//        return 1000 / clicksPerSecond;
//    }
//
//    public void startAutoClick() {
//        timer = new Timer();
//        timer.scheduleAtFixedRate(new TimerTask() {
//            @Override
//            public void run() {
//                autoClick();
//            }
//        }, 0, 1);
//    }
//
//    public void stopAutoClick() {
//        if (timer != null) {
//            timer.cancel();
//        }
//    }
//
//    public synchronized void autoClick() {
//        long currentTime = System.currentTimeMillis();
//        if (LBQConfig.INSTANCE.getLeftClickerSwitch() && (currentTime - lastClickTime >= clickInterval)) {
//            Minecraft mc = Minecraft.getMinecraft();
//            if (mc != null && mc.thePlayer != null) {
//                try {
//                    Method leftClickMouse = Minecraft.class.getDeclaredMethod("func_147116_af"); // Update this line with the correct method name
//                    leftClickMouse.setAccessible(true);
//                    leftClickMouse.invoke(mc);
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//                lastClickTime = currentTime;
//                clickInterval = getRandomClickInterval();
//            }
//        }
//    }
//}