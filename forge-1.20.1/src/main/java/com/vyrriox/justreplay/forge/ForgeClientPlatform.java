package com.vyrriox.justreplay.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import com.vyrriox.justreplay.gui.JRConfigScreen;
import com.vyrriox.justreplay.platform.ClientPlatform;
import com.vyrriox.justreplay.forge.mixin.NetworkRegistryInvoker;
import com.vyrriox.justreplay.platform.KeyScope;
import com.vyrriox.justreplay.replay.ReplaySession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;

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
    /** Forge's play channel and the index it registers its menu message under. */
    private static final ResourceLocation PLAY_CHANNEL = new ResourceLocation("fml", "play");
    private static final int OPEN_CONTAINER = 1;

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

    /** The test Forge's own dispatch makes: a payload of a channel nobody registered only logs a warning. */
    @Override
    public boolean canDecodePayload(ResourceLocation id) {
        return "minecraft".equals(id.getNamespace()) || NetworkRegistryInvoker.justreplay$findTarget(id).isPresent();
    }

    /** On Forge the extra spawn data comes with the entity itself, in {@link #wholeSpawnPacket}. */
    @Nullable
    @Override
    public Packet<?> extraSpawnPacket(Entity entity) {
        return null;
    }

    /**
     * Forge's spawn packet creates the entity, so it cannot follow a vanilla add packet. An entity that cannot write its
     * spawn data on the client throws here, and the caller then records it with the vanilla packet alone.
     */
    @Nullable
    @Override
    public Packet<?> wholeSpawnPacket(Entity entity) {
        if (!(entity instanceof IEntityAdditionalSpawnData spawnData)) {
            return null;
        }
        FriendlyByteBuf probe = new FriendlyByteBuf(Unpooled.buffer());
        try {
            spawnData.writeSpawnData(probe);
        } finally {
            probe.release();
        }
        return ForgeHooks.getEntitySpawnPacket(entity);
    }

    /**
     * Forge's menu packet shares a channel with its spawn packet, which a replay needs, so only the message index in the
     * raw bytes tells them apart: the channel writes it as one byte first.
     */
    @Override
    public boolean isPlayerOnlyPayload(ResourceLocation id, FriendlyByteBuf data) {
        return PLAY_CHANNEL.equals(id) && data.isReadable() && data.getUnsignedByte(data.readerIndex()) == OPEN_CONTAINER;
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
