package com.vyrriox.justreplay.client;

import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Signed skin textures of the recording player. The game only shows the skin of another player (the replay's actor
 * is one) from a signed {@code textures} property. The tab list entry of the host carries the profile the game fetched
 * at start, which has none when that lookup failed: the actor came back as Steve. The signed property is asked once
 * per profile from the session server and written into recordings, and put back into replays made without it.
 *
 * @author vyrriox
 */
public final class SkinTextures {
    /** A public profile with a skin, used by the self test instead of its offline player (which has no skin). */
    public static final UUID SELFTEST_SOURCE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String TEXTURES = "textures";
    private static final Map<UUID, CompletableFuture<Property>> SIGNED = new ConcurrentHashMap<>();
    private static volatile boolean waited;

    private SkinTextures() {
    }

    /** The signed textures among {@code properties}, or null. */
    @Nullable
    public static Property signed(PropertyMap properties) {
        for (Property property : properties.get(TEXTURES)) {
            if (property.hasSignature()) {
                return property;
            }
        }
        return null;
    }

    /** The profile whose skin the player {@code uuid} wears: itself, or the stand-in of the self test. */
    public static UUID source(UUID uuid) {
        return SelfTest.isEnabled() ? SELFTEST_SOURCE : uuid;
    }

    /** Starts the lookup of the signed textures of {@code uuid} (once per game); offline profiles have none. */
    public static CompletableFuture<Property> lookup(@Nullable UUID uuid) {
        if (uuid == null || uuid.version() != 4) {
            return CompletableFuture.completedFuture(null);
        }
        return SIGNED.computeIfAbsent(uuid, id -> CompletableFuture.supplyAsync(() -> fetch(id), Util.backgroundExecutor()));
    }

    @Nullable
    private static Property fetch(UUID id) {
        try {
            com.mojang.authlib.yggdrasil.ProfileResult result = Minecraft.getInstance().services().sessionService().fetchProfile(id, true);
            Property found = result == null ? null : signed(result.profile().properties());
            JustReplay.LOGGER.debug("Signed skin textures of {}: {}", id, found != null ? "found" : "none");
            return found;
        } catch (RuntimeException e) {
            JustReplay.LOGGER.warn("Could not look up the skin of {}: {}", id, e.toString());
            return null;
        }
    }

    /** The signed textures of {@code uuid} if the lookup is over, else null (never waits). */
    @Nullable
    public static Property now(@Nullable UUID uuid) {
        return lookup(uuid).getNow(null);
    }

    /**
     * The signed textures of {@code source} when {@code properties} hold none, else null. Waits for the lookup once,
     * at most {@code waitMs}, then only takes what is there.
     */
    @Nullable
    private static Property take(PropertyMap properties, @Nullable UUID source, long waitMs) {
        if (signed(properties) != null) {
            return null;
        }
        CompletableFuture<Property> future = lookup(source);
        Property property = future.getNow(null);
        if (property == null && !future.isDone() && !waited && waitMs > 0) {
            waited = true;
            try {
                property = future.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                property = null;
            }
        }
        return property;
    }

    /** {@code properties} with the signed textures of {@code source} when they hold none (profiles are immutable here). */
    public static com.google.common.collect.Multimap<String, Property> withSigned(PropertyMap properties, @Nullable UUID source) {
        return with(properties, take(properties, source, 0L));
    }

    private static com.google.common.collect.Multimap<String, Property> with(PropertyMap properties, @Nullable Property textures) {
        com.google.common.collect.Multimap<String, Property> copy = com.google.common.collect.LinkedHashMultimap.create(properties);
        if (textures != null) {
            copy.removeAll(TEXTURES);
            copy.put(TEXTURES, textures);
        }
        return copy;
    }

    /**
     * Gives the actor of a replay the signed textures of its recording player when the recording has none (replays
     * before 1.3.0 while the game had no profile). Entries are records: the decoded list gets new ones.
     */
    public static ClientboundPlayerInfoUpdatePacket repair(ClientboundPlayerInfoUpdatePacket packet, @Nullable UUID actor, @Nullable UUID recorder) {
        if (actor == null || !packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            return packet;
        }
        java.util.List<ClientboundPlayerInfoUpdatePacket.Entry> entries = packet.entries();
        for (int i = 0; i < entries.size(); i++) {
            ClientboundPlayerInfoUpdatePacket.Entry e = entries.get(i);
            if (!actor.equals(e.profileId()) || e.profile() == null) {
                continue;
            }
            Property textures = take(e.profile().properties(), recorder == null ? null : source(recorder), 2000L);
            if (textures == null) {
                continue;
            }
            com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(e.profile().id(), e.profile().name(),
                    new PropertyMap(with(e.profile().properties(), textures)));
            try {
                entries.set(i, new ClientboundPlayerInfoUpdatePacket.Entry(e.profileId(), profile, e.listed(), e.latency(), e.gameMode(),
                        e.displayName(), e.showHat(), e.listOrder(), e.chatSession()));
                JustReplay.LOGGER.info("Actor {} had no signed skin in the recording: took the one of its player", actor);
            } catch (UnsupportedOperationException ignored) {
                // A packet built in memory, not read from the replay: nothing recorded to repair.
            }
        }
        return packet;
    }
}
