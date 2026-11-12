package com.laybalt.mixin;

import com.laybalt.event.EventBus;
import com.laybalt.event.impl.MainThreadPacketReceivedEvent;
import com.laybalt.event.impl.MessageSentEvent;
import com.laybalt.features.impl.general.CommandShortcuts;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @WrapOperation(
        method = "handleBundlePacket",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"
        )
    )
    private void wrapPacketHandle(Packet packet, PacketListener listener, Operation<Void> original) {
        if (EventBus.post(new MainThreadPacketReceivedEvent.Pre(packet))) return;
        original.call(packet, listener);
        EventBus.post(new MainThreadPacketReceivedEvent.Post(packet));
    }

    // NoammAddons 375e7e53: TimeChanger no longer cancels the time packets; it changes the time the sky is sampled at
    // (MixinAttributeTrackSampler), so the server's clock keeps running and the sky no longer jitters.

    @WrapMethod(method = "handleCommands")
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void onHandleCommands(ClientboundCommandsPacket packet, Operation<Void> original) {
        original.call(packet);
        CommandShortcuts.INSTANCE.build((CommandDispatcher) ((ClientPacketListener) (Object) this).getCommands());
    }

    @WrapMethod(method = "handleCommandSuggestions")
    private void onHandleCommandSuggestions(ClientboundCommandSuggestionsPacket packet, Operation<Void> original) {
        original.call(CommandShortcuts.INSTANCE.restoreSuggestionPacket(packet));
    }

    @WrapMethod(method = "sendChat")
    private void onSendChat(String content, Operation<Void> original) {
        var event = new MessageSentEvent(content);
        if (EventBus.post(event)) return;
        original.call(event.getMessage());
    }

    @WrapMethod(method = "sendCommand")
    private void onSendCommand(String command, Operation<Void> original) {
        var event = new MessageSentEvent(command, true);
        if (EventBus.post(event)) return;
        original.call(CommandShortcuts.INSTANCE.rewriteCommand(event.getMessage()));
    }
}