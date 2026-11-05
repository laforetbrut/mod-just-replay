package com.vyrriox.justreplay.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import com.vyrriox.justreplay.gui.JRConfigScreen;
import com.vyrriox.justreplay.platform.ClientPlatform;
import com.vyrriox.justreplay.platform.KeyScope;
import com.vyrriox.justreplay.replay.ReplaySession;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import net.neoforged.neoforge.network.payload.AdvancedContainerSetDataPayload;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link ClientPlatform} on NeoForge. Created by name.
 *
 * @author vyrriox
 */
public final class NeoForgeClientPlatform implements ClientPlatform {
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

    private final RegistryIdsBackend registryIds = new NeoForgeRegistryIds();

    @Override
    public void openConfigScreen(Screen parent) {
        Minecraft.getInstance().setScreen(new JRConfigScreen(parent));
    }

    @Override
    public Function<ByteBuf, RegistryFriendlyByteBuf> bufDecorator(ClientPacketListener listener) {
        return RegistryFriendlyByteBuf.decorator(listener.registryAccess(), listener.getConnectionType());
    }

    @Override
    public Object connectionToken(ClientPacketListener listener) {
        return listener.getConnectionType();
    }

    @Override
    public boolean canDecodePayload(Identifier id) {
        return PayloadIds.receivable(id);
    }

    @Nullable
    @Override
    public Packet<?> extraSpawnPacket(Entity entity) {
        return entity instanceof IEntityWithComplexSpawn ? new ClientboundCustomPayloadPacket(new AdvancedAddEntityPayload(entity)) : null;
    }

    @Override
    public boolean isPlayerOnlyPayload(Object payload) {
        return payload instanceof AdvancedOpenScreenPayload || payload instanceof AdvancedContainerSetDataPayload;
    }

    @Override
    public RegistryIdsBackend registryIds() {
        return registryIds;
    }

    private static final java.util.Map<String, net.minecraft.client.KeyMapping.Category> CATEGORIES = new java.util.HashMap<>();

    @Override
    public KeyMapping keyMapping(String name, KeyScope scope, InputConstants.Type type, int key, String category) {
        IKeyConflictContext context = switch (scope) {
            case IN_GAME -> KeyConflictContext.IN_GAME;
            case IN_REPLAY -> IN_REPLAY;
        };
        net.minecraft.client.KeyMapping.Category cat = CATEGORIES.computeIfAbsent(category,
                c -> new net.minecraft.client.KeyMapping.Category(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        com.vyrriox.justreplay.JustReplay.MOD_ID, c.substring(c.lastIndexOf('.') + 1))));
        return new KeyMapping(name, context, type, key, cat);
    }

    @Override
    public void disableTextShadow(net.minecraft.client.gui.components.EditBox box) {
        box.setTextShadow(false);
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

    /** Mod payload ids the client can decode, read from NeoForge's payload registry. */
    private static final class PayloadIds {
        private static Set<Identifier> known;
        private static boolean failed;

        private PayloadIds() {
        }

        static synchronized boolean receivable(Identifier id) {
            if ("minecraft".equals(id.getNamespace())) {
                return true;
            }
            if (known == null && !failed) {
                try {
                    known = load();
                } catch (Throwable t) {
                    failed = true;
                    JustReplay.LOGGER.debug("Payload registry not readable: {}", t.toString());
                }
            }
            return known == null || known.contains(id);
        }

        private static Set<Identifier> load() throws ReflectiveOperationException {
            Class<?> registry = NetworkRegistry.class;
            Set<Identifier> ids = new HashSet<>();
            Field builtin = registry.getDeclaredField("BUILTIN_PAYLOADS");
            builtin.setAccessible(true);
            for (Object key : ((Map<?, ?>) builtin.get(null)).keySet()) {
                ids.add((Identifier) key);
            }
            Field field = registry.getDeclaredField("PAYLOAD_REGISTRATIONS");
            field.setAccessible(true);
            Object play = ((Map<?, ?>) field.get(null)).get(ConnectionProtocol.PLAY);
            if (play instanceof Map<?, ?> registrations) {
                for (Map.Entry<?, ?> entry : registrations.entrySet()) {
                    Method flowOf = entry.getValue().getClass().getMethod("flow");
                    flowOf.setAccessible(true);
                    Object flow = flowOf.invoke(entry.getValue());
                    if (flow instanceof Optional<?> optional && optional.isPresent() && optional.get() != PacketFlow.CLIENTBOUND) {
                        continue;
                    }
                    ids.add((Identifier) entry.getKey());
                }
            }
            return ids;
        }
    }
}
