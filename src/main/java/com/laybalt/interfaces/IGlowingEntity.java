package com.laybalt.interfaces;

import java.awt.*;

public interface IGlowingEntity {
    Color lbqmod$glowColor();

    void lbqmod$glowColor(Color color);

    boolean lbqmod$isGlowing();

    void lbqmod$isGlowing(boolean value);

    // NoammAddons 3549d1fc: the glow check is posted once per entity per tick, these hold its result for the tick.
    int lbqmod$glowCheckTick();

    void lbqmod$glowCheckTick(int tick);

    boolean lbqmod$glowCancelled();

    void lbqmod$glowCancelled(boolean value);
}
