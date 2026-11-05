package com.vyrriox.justreplay.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import com.vyrriox.justreplay.gui.JRConfigScreen;
import com.vyrriox.justreplay.platform.ClientPlatform;
import com.vyrriox.justreplay.platform.KeyScope;
import com.vyrriox.justreplay.replay.ReplaySession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.ForgePayload;
import net.minecraftforge.network.NetworkInitialization;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.packets.OpenContainer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * {@link ClientPlatform} on Forge. Created by name.
 *
 * @author vyrriox
 */
public final class ForgeClientPlatform implements ClientPlatform {
    /** Vanilla buffers depend on the registry access alone, so every connection shares this token. */
    private static final Object CONNECTION_TOKEN = new Object();
    private static final int NOT_READ = -2;

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

    private static final Map<String, KeyMapping.Category> CATEGORIES = new HashMap<>();

    private final RegistryIdsBackend registryIds = new ForgeRegistryIds();
    /** Message index of Forge's menu packet on its own channel, read once. Negative when unknown. */
    private int openContainerIndex = NOT_READ;

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

    /** The same test Forge's payload codec makes: a channel nobody registered decodes to a payload without its bytes. */
    @Override
    public boolean canDecodePayload(Identifier id) {
        return "minecraft".equals(id.getNamespace()) || NetworkRegistry.findTarget(id) != null;
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
     * raw bytes tells them apart. A payload built locally has no bytes, and is never a recorded one.
     */
    @Override
    public boolean isPlayerOnlyPayload(Object payload) {
        if (!(payload instanceof ForgePayload forge) || forge.data() == null || !forge.data().isReadable()
                || !NetworkInitialization.PLAY.getName().equals(forge.id())) {
            return false;
        }
        try {
            return new FriendlyByteBuf(forge.data().slice()).readVarInt() == openContainerIndex();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The channel keeps the indices to itself: they are read from what it writes for a menu packet. */
    private int openContainerIndex() {
        if (openContainerIndex == NOT_READ) {
            FriendlyByteBuf extra = new FriendlyByteBuf(Unpooled.buffer());
            RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            try {
                extra.writeByteArray(new byte[0]);
                NetworkInitialization.PLAY.encode(out, new OpenContainer(MenuType.GENERIC_9x1, 0, Component.empty(), extra));
                openContainerIndex = out.readVarInt();
            } catch (RuntimeException e) {
                JustReplay.LOGGER.warn("Cannot read the index of Forge's menu packet, a replay may open recorded menus", e);
                openContainerIndex = -1;
            } finally {
                extra.release();
                out.release();
            }
        }
        return openContainerIndex;
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
        KeyMapping.Category cat = CATEGORIES.computeIfAbsent(category, c -> KeyMapping.Category.register(
                JustReplay.id(c.substring(c.lastIndexOf('.') + 1))));
        return new KeyMapping(name, context, type, key, cat, 0);
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

    /** Both fields are opened by the access transformer of this project. */
    @Nullable
    @Override
    public ScreenRectangle guiScissor(GuiGraphicsExtractor graphics) {
        return graphics.scissorStack.peek();
    }

    @Override
    public void submitGuiElement(GuiGraphicsExtractor graphics, GuiElementRenderState element) {
        graphics.guiRenderState.addGuiElement(element);
    }
}
