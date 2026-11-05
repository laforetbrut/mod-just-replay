package com.vyrriox.justreplay.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Immersive Paintings custom images in recordings and replays.
 * <ul>
 *     <li>The painting list and the images are sent once, at login and on request, and kept in static client maps. A
 *     recording that starts after login would never contain them, and a replay opened in a fresh game session would
 *     show the default image on every custom painting. Snapshots and checkpoints therefore write the list and the
 *     images of the paintings in use, as the clientbound payloads the server would send.</li>
 *     <li>A recorded list that deletes a painting would delete the viewer's own cache file: deletions are removed
 *     from played lists. Screens and error notices the server sends are dropped, and the mod's serverbound payloads
 *     are never sent to the replay host.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads an Immersive Paintings class when the mod is absent.
 *
 * @author vyrriox
 */
public final class ImmersivePaintingsCompat {
    private static final String PKG = "net.conczin.immersive_paintings.";
    /** Payload data bytes per image segment, well below the clientbound custom payload limit. */
    private static final int SEGMENT_SIZE = 30_000;
    /** Paintings written per snapshot: those of entities in the level first, then the rest of the loaded gallery. */
    private static final int MAX_PAINTINGS = 64;

    /** PNG bytes of a texture, kept while the texture keeps the same pixels. */
    private record Encoded(Object pixels, byte[] png) {
    }

    private static final Map<String, Encoded> ENCODED = new ConcurrentHashMap<>();
    private static int lastLoggedCount = -1;
    private static Reflect reflect;
    private static boolean reflectFailed;

    private ImmersivePaintingsCompat() {
    }

    private record Reflect(Field paintings, Field textureMap, Object full, Object thumbnail,
                           Class<?> syncClass, Constructor<?> syncNew, Method syncPaintings,
                           Class<?> imageClass, Constructor<?> imageNew, Class<?> entityClass, Method entityMotive,
                           Class<?> openGui, Class<?> registerError) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.IMMERSIVE_PAINTINGS) {
            return reflect;
        }
        try {
            ClassLoader cl = ImmersivePaintingsCompat.class.getClassLoader();
            Class<?> manager = Class.forName(PKG + "ClientPaintingManager", false, cl);
            Class<?> size = Class.forName(PKG + "Painting$Size", false, cl);
            Class<?> sync = Class.forName(PKG + "network.payload.s2c.PaintingSyncPayload", false, cl);
            Class<?> image = Class.forName(PKG + "network.payload.s2c.ImageResponsePayload", false, cl);
            Class<?> entity = Class.forName(PKG + "entity.ImmersivePaintingEntity", false, cl);
            Field paintings = accessible(manager.getDeclaredField("paintings"));
            Field textureMap = accessible(manager.getDeclaredField("textureMap"));
            reflect = new Reflect(paintings, textureMap, enumValue(size, "FULL"), enumValue(size, "THUMBNAIL"),
                    sync, sync.getConstructor(Map.class, boolean.class), sync.getMethod("paintings"),
                    image, image.getConstructor(Identifier.class, boolean.class, byte[].class, int.class, int.class),
                    entity, entity.getMethod("getMotive"),
                    Class.forName(PKG + "network.payload.s2c.OpenGuiPayload", false, cl),
                    Class.forName(PKG + "network.payload.s2c.PaintingRegisterErrorPayload", false, cl));
            JustReplay.LOGGER.info("Immersive Paintings detected: custom paintings are recorded and served to replays");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("paintings.reflect", "Immersive Paintings support disabled, its classes changed", t);
        }
        return reflect;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T member) {
        member.setAccessible(true);
        return member;
    }

    // ---------------------------------------------------------------- recording

    /** PNG bytes of the texture an image lives in, or null when it is not a loaded dynamic texture. */
    private static byte[] png(String key, Identifier location) throws Exception {
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
        if (!(texture instanceof DynamicTexture dynamic)) {
            return null;
        }
        NativeImage pixels = dynamic.getPixels();
        if (pixels == null) {
            return null;
        }
        Encoded known = ENCODED.get(key);
        if (known != null && known.pixels == pixels) {
            return known.png;
        }
        // NativeImage has no in-memory encoder on 26.1; the PNG goes through a short-lived temp file.
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("justreplay-painting", ".png");
        byte[] bytes;
        try {
            pixels.writeToFile(tmp);
            bytes = java.nio.file.Files.readAllBytes(tmp);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
        ENCODED.put(key, new Encoded(pixels, bytes));
        return bytes;
    }

    /** Ids of the paintings of the entities in the level, in render order. */
    private static Set<Identifier> inUse(Reflect r) {
        Set<Identifier> ids = new LinkedHashSet<>();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return ids;
        }
        for (Entity entity : level.entitiesForRendering()) {
            try {
                if (r.entityClass.isInstance(entity) && r.entityMotive.invoke(entity) instanceof Identifier id) {
                    ids.add(id);
                }
            } catch (Throwable t) {
                ReplayCompat.warnOnce("paintings.entity", "Cannot read the Immersive Paintings motive of an entity", t);
            }
        }
        return ids;
    }

    /**
     * Snapshots and checkpoints: the list of the paintings in use and their loaded images, as the server sends them.
     * Written every time so a rolling buffer keeps them; the encoded images are reused while a texture is unchanged.
     */
    static void writeSnapshot(Consumer<Packet<?>> out) throws Exception {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        Map<?, ?> paintings = (Map<?, ?>) r.paintings.get(null);
        Map<?, ?> textures = (Map<?, ?>) r.textureMap.get(null);
        Set<Identifier> chosen = new LinkedHashSet<>();
        for (Identifier id : inUse(r)) {
            if (paintings.containsKey(id)) {
                chosen.add(id);
            }
        }
        for (Object key : new ArrayList<>(textures.keySet())) {
            if (chosen.size() >= MAX_PAINTINGS) {
                break;
            }
            if (key instanceof Identifier id && paintings.containsKey(id)) {
                chosen.add(id);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        writePaintings(r, chosen, paintings, textures, out);
    }

    private static void writePaintings(Reflect r, Set<Identifier> chosen, Map<?, ?> paintings, Map<?, ?> textures,
                                       Consumer<Packet<?>> out) throws Exception {
        Map<Identifier, Optional<Object>> list = new LinkedHashMap<>();
        for (Identifier id : chosen) {
            list.put(id, Optional.of(paintings.get(id)));
        }
        Method split = r.syncClass.getMethod("splitPaintings", Map.class, boolean.class);
        for (Object payload : (List<?>) split.invoke(null, list, false)) {
            out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) payload));
        }
        int images = 0;
        for (Identifier id : chosen) {
            if (!(textures.get(id) instanceof Map<?, ?> sizes)) {
                continue;
            }
            for (Object size : new Object[]{r.full, r.thumbnail}) {
                Identifier location = (Identifier) sizes.get(size);
                if (location == null) {
                    continue;
                }
                try {
                    byte[] data = png(id + "#" + size, location);
                    if (data != null) {
                        writeImage(r, id, size == r.thumbnail, data, out);
                        images++;
                    }
                } catch (Throwable t) {
                    ReplayCompat.warnOnce("paintings.image." + id, "Cannot record the Immersive Paintings image " + id, t);
                }
            }
        }
        if (images != lastLoggedCount) {
            lastLoggedCount = images;
            JustReplay.LOGGER.info("Immersive Paintings: snapshot wrote {} paintings and {} images", chosen.size(), images);
        }
    }

    /** An image as the segmented answer a server sends for it. */
    private static void writeImage(Reflect r, Identifier id, boolean thumbnail, byte[] data, Consumer<Packet<?>> out)
            throws Exception {
        int total = Math.max(1, (data.length + SEGMENT_SIZE - 1) / SEGMENT_SIZE);
        for (int segment = 0; segment < total; segment++) {
            byte[] part = Arrays.copyOfRange(data, segment * SEGMENT_SIZE, Math.min(data.length, (segment + 1) * SEGMENT_SIZE));
            out.accept(new ClientboundCustomPayloadPacket(
                    (CustomPacketPayload) r.imageNew.newInstance(id, thumbnail, part, segment, total)));
        }
    }

    // ---------------------------------------------------------------- playback

    /**
     * A recorded Immersive Paintings payload about to be handled: deletions are removed from painting lists (they
     * delete the viewer's cache files and the snapshot's paintings), screens and error notices are dropped.
     */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom)) {
            return packet;
        }
        Reflect r = reflect();
        if (r == null) {
            return packet;
        }
        CustomPacketPayload payload = custom.payload();
        Class<?> type = payload.getClass();
        if (type == r.openGui || type == r.registerError) {
            return null;
        }
        if (type != r.syncClass) {
            return packet;
        }
        try {
            Map<?, ?> all = (Map<?, ?>) r.syncPaintings.invoke(payload);
            Map<Object, Object> kept = new HashMap<>();
            for (Map.Entry<?, ?> e : all.entrySet()) {
                if (e.getValue() instanceof Optional<?> value && value.isPresent()) {
                    kept.put(e.getKey(), value);
                }
            }
            if (kept.isEmpty()) {
                return null;
            }
            return new ClientboundCustomPayloadPacket((CustomPacketPayload) r.syncNew.newInstance(kept, false));
        } catch (Throwable t) {
            ReplayCompat.warnOnce("paintings.filter", "Cannot filter an Immersive Paintings list", t);
            return packet;
        }
    }

    /** A packet the client is about to send to the replay host: the mod's requests and edits go nowhere. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom
                && custom.payload().getClass().getName().startsWith(PKG + "network.payload.c2s.");
    }

    // ---------------------------------------------------------------- self test

    /**
     * Self test hook: writes a snapshot of the painting {@code id} and returns its packets, or null when the mod is
     * absent or the painting is unknown.
     */
    public static List<Packet<?>> snapshotOf(Identifier id) throws Exception {
        Reflect r = reflect();
        if (r == null) {
            return null;
        }
        Map<?, ?> paintings = (Map<?, ?>) r.paintings.get(null);
        if (!paintings.containsKey(id)) {
            return null;
        }
        List<Packet<?>> packets = new ArrayList<>();
        writePaintings(r, new LinkedHashSet<>(List.of(id)), paintings, (Map<?, ?>) r.textureMap.get(null), packets::add);
        return packets;
    }

    /** Self test hook: registers a small generated painting in the client maps, as the server's list and answer would. */
    public static boolean registerTestPainting(Identifier id) throws Exception {
        Reflect r = reflect();
        if (r == null) {
            return false;
        }
        ClassLoader cl = ImmersivePaintingsCompat.class.getClassLoader();
        Class<?> painting = Class.forName(PKG + "Painting", false, cl);
        Class<?> type = Class.forName(PKG + "Painting$Type", false, cl);
        Class<?> flag = Class.forName(PKG + "Painting$Flag", false, cl);
        Class<?> manager = Class.forName(PKG + "ClientPaintingManager", false, cl);
        Object value = painting.getConstructor(int.class, int.class, int.class, int.class, String.class, String.class,
                        java.util.UUID.class, type, java.util.EnumSet.class, String.class)
                .newInstance(1, 1, 1, 16, "jrtest", "jrtest", new java.util.UUID(0, 0), enumValue(type, "PAINTING"),
                        java.util.EnumSet.noneOf((Class) flag), "");
        manager.getMethod("registerPainting", Identifier.class, painting).invoke(null, id, value);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                image.setRGB(x, y, 0xFF000000 | (x * 16) << 16 | (y * 16) << 8 | 0x40);
            }
        }
        manager.getMethod("registerImage", Identifier.class, java.awt.image.BufferedImage.class, boolean.class)
                .invoke(null, id, image, false);
        return true;
    }

    /** Self test hook: whether a list that only deletes a painting is dropped by the playback filter. */
    public static boolean deletionListDropped(Identifier id) throws Exception {
        Reflect r = reflect();
        if (r == null) {
            return false;
        }
        Object payload = r.syncNew.newInstance(Map.of(id, Optional.empty()), true);
        return filterPlayback(new ClientboundCustomPayloadPacket((CustomPacketPayload) payload)) == null;
    }
}
