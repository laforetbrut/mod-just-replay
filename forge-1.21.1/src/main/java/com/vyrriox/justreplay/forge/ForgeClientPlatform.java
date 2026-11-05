package com.vyrriox.justreplay.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import com.vyrriox.justreplay.gui.JRConfigScreen;
import com.vyrriox.justreplay.platform.ClientPlatform;
import com.vyrriox.justreplay.platform.KeyScope;
import com.vyrriox.justreplay.replay.ReplaySession;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.client.settings.KeyConflictContext;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * {@link ClientPlatform} on Forge. Created by name.
 *
 * @author vyrriox
 */
public final class ForgeClientPlatform implements ClientPlatform {
    /** Vanilla buffers depend on the registry access alone, so every connection shares this token. */
    private static final Object CONNECTION_TOKEN = new Object();

    /** Playback keys only act inside a replay, so they never steal keys from other mods in a normal game. */
    private static final IKeyConflictContext IN_REPLAY = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return ReplaySession.isRunning() && KeyConflictContext.IN_GAME.isActive();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return this == other;
        }
    };

    private final RegistryIdsBackend registryIds = new ForgeRegistryIds();

    @Override
    public void openConfigScreen(Screen parent) {
        Minecraft.getInstance().setScreen(new JRConfigScreen(parent));
    }

    @Override
    public Function<ByteBuf, RegistryFriendlyByteBuf> bufDecorator(ClientPacketListener listener) {
        return RegistryFriendlyByteBuf.decorator(listener.registryAccess());
    }

    @Override
    public Object connectionToken(ClientPacketListener listener) {
        return CONNECTION_TOKEN;
    }

    /** Assumes every payload can be decoded until the channel lookup exists. */
    @Override
    public boolean canDecodePayload(ResourceLocation id) {
        return true;
    }

    /** No extra spawn data is recorded until the Forge packet is wired. */
    @Nullable
    @Override
    public Packet<?> extraSpawnPacket(Entity entity) {
        return null;
    }

    @Override
    public boolean isPlayerOnlyPayload(Object payload) {
        return false;
    }

    @Override
    public RegistryIdsBackend registryIds() {
        return registryIds;
    }

    @Override
    public KeyMapping keyMapping(String name, KeyScope scope, InputConstants.Type type, int key, String category) {
        IKeyConflictContext context = switch (scope) {
            case IN_GAME -> KeyConflictContext.IN_GAME;
            case IN_REPLAY -> IN_REPLAY;
        };
        return new KeyMapping(name, context, type, key, category);
    }

    @Override
    public String keyModifierPrefix(KeyMapping key) {
        return switch (key.getKeyModifier()) {
            case CONTROL -> "Ctrl+";
            case SHIFT -> "Shift+";
            case ALT -> "Alt+";
            default -> "";
        };
    }

    /** Forge's text field has no shadow option. */
    @Override
    public void disableTextShadow(EditBox box) {
    }
}
