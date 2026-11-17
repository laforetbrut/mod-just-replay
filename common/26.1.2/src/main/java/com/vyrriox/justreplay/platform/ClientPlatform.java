package com.vyrriox.justreplay.platform;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * What the mod needs from the loader on the physical client only. Never touch this class on a dedicated server.
 *
 * @author vyrriox
 */
public interface ClientPlatform {
    static ClientPlatform get() {
        return Holder.INSTANCE;
    }

    /** Opens the settings screen of the mod, going back to {@code parent} when it closes. */
    void openConfigScreen(Screen parent);

    /** Wraps a buffer the way the network stack of this connection does, for the codecs of its packets. */
    Function<ByteBuf, RegistryFriendlyByteBuf> bufDecorator(ClientPacketListener listener);

    /** What, beside the registry access, makes {@link #bufDecorator} differ between connections. Compared by identity. */
    Object connectionToken(ClientPacketListener listener);

    /** False when a recorded mod payload cannot be decoded here: its mod is absent or does not receive it on the client. */
    boolean canDecodePayload(Identifier id);

    /** The packet the loader sends after the spawn packet of this entity for its extra spawn data, or null. */
    @Nullable
    Packet<?> extraSpawnPacket(Entity entity);

    /** The packet that spawns this entity with its extra data, on a loader where it replaces the vanilla add packet, or null. */
    @Nullable
    default Packet<?> wholeSpawnPacket(Entity entity) {
        return null;
    }

    /** True for the loader's own payloads that open a menu or update one, which only concern the recording player. */
    boolean isPlayerOnlyPayload(Object payload);

    RegistryIdsBackend registryIds();

    KeyMapping keyMapping(String name, KeyScope scope, InputConstants.Type type, int key, String category);

    /** The modifier of a binding as a prefix of its name ("Ctrl+"), empty without one or on a loader that has none. */
    String keyModifierPrefix(KeyMapping key);

    /** Draws the text of a field without a shadow; a loader without that option keeps the vanilla look. */
    void disableTextShadow(net.minecraft.client.gui.components.EditBox box);

    /** The scissor area in force on this extractor, or null. */
    @Nullable
    net.minecraft.client.gui.navigation.ScreenRectangle guiScissor(net.minecraft.client.gui.GuiGraphicsExtractor graphics);

    /** Adds a custom element to the GUI render state behind this extractor. */
    void submitGuiElement(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.renderer.state.gui.GuiElementRenderState element);

    /** Initialized on first use. */
    final class Holder {
        private static final ClientPlatform INSTANCE = LoaderGlue.create(ClientPlatform.class, "ClientPlatform");

        private Holder() {
        }
    }
}
