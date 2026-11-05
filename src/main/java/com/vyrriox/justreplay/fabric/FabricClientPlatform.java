package com.vyrriox.justreplay.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import com.vyrriox.justreplay.gui.JRConfigScreen;
import com.vyrriox.justreplay.platform.ClientPlatform;
import com.vyrriox.justreplay.platform.KeyScope;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/**
 * {@link ClientPlatform} on Fabric. Created by name.
 *
 * @author vyrriox
 */
public final class FabricClientPlatform implements ClientPlatform {
    private final RegistryIdsBackend registryIds = new FabricRegistryIds();

    @Override
    public void openConfigScreen(Screen parent) {
        Minecraft.getInstance().setScreen(new JRConfigScreen(parent));
    }

    /** Payloads are raw bytes on this version: a channel decodes when this client has a receiver for it. */
    @Override
    public boolean canDecodePayload(ResourceLocation id) {
        return "minecraft".equals(id.getNamespace()) || ClientPlayNetworking.getGlobalReceivers().contains(id);
    }

    /** Fabric has no extra spawn data packet. */
    @Nullable
    @Override
    public Packet<?> extraSpawnPacket(Entity entity) {
        return null;
    }

    /** Extended menus on Fabric go through the vanilla open screen packet. */
    @Override
    public boolean isPlayerOnlyPayload(ResourceLocation id, FriendlyByteBuf data) {
        return false;
    }

    @Override
    public RegistryIdsBackend registryIds() {
        return registryIds;
    }

    @Override
    public KeyMapping keyMapping(String name, KeyScope scope, InputConstants.Type type, int key, String category) {
        return switch (scope) {
            case IN_GAME -> new KeyMapping(name, type, key, category);
            case IN_REPLAY -> FabricReplayKeys.create(name, type, key, category);
        };
    }

    @Override
    public String keyModifierPrefix(KeyMapping key) {
        return "";
    }

    @Override
    public void disableTextShadow(EditBox box) {
    }
}
