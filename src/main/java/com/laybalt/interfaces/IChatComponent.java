package com.laybalt.interfaces;

import net.minecraft.client.multiplayer.chat.GuiMessage;

import java.util.List;

public interface IChatComponent {
    List<GuiMessage.Line> getVisibleMessages();

    double getLineIndex();
}