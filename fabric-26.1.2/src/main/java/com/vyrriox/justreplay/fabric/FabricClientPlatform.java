package com.vyrriox.justreplay.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import com.vyrriox.justreplay.gui.JRConfigScreen;
import com.vyrriox.justreplay.platform.ClientPlatform;
import com.vyrriox.justreplay.platform.KeyScope;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * {@link ClientPlatform} on Fabric. Created by name.
 *
 * @author vyrriox
 */
public final class FabricClientPlatform implements ClientPlatform {
    /** Vanilla buffers depend on the registry access alone, so every connection shares this token. */
    private static final Object CONNECTION_TOKEN = new Object();

    private static final Map<String, KeyMapping.Category> CATEGORIES = new HashMap<>();

    private final RegistryIdsBackend registryIds = new FabricRegistryIds();

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

    @Override
    public boolean canDecodePayload(Identifier id) {
        return "minecraft".equals(id.getNamespace()) || PayloadTypeRegistryImpl.CLIENTBOUND_PLAY.get(id) != null;
    }

    /** Fabric has no extra spawn data packet. */
    @Nullable
    @Override
    public Packet<?> extraSpawnPacket(Entity entity) {
        return null;
    }

    /** Extended menus on Fabric go through the vanilla open screen packet. */
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
        KeyMapping.Category cat = CATEGORIES.computeIfAbsent(category, c -> KeyMapping.Category.register(
                JustReplay.id(c.substring(c.lastIndexOf('.') + 1))));
        return switch (scope) {
            case IN_GAME -> new KeyMapping(name, type, key, cat);
            case IN_REPLAY -> FabricReplayKeys.create(name, type, key, cat);
        };
    }

    @Override
    public String keyModifierPrefix(KeyMapping key) {
        return "";
    }

    @Override
    public void disableTextShadow(EditBox box) {
    }

    /** Both fields are opened by the class tweaker of this project. */
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
