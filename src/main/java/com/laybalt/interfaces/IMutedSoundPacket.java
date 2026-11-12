package com.laybalt.interfaces;

/** A sound packet whose sound is skipped when it is handled (see MixinClientboundSoundPacket). */
public interface IMutedSoundPacket {
    void laybalt$mute();
}
