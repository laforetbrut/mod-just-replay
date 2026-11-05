package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Exposure photographs in recordings and replays.
 * <ul>
 *     <li>The client asks the server once for the pixels of a photograph and keeps them until it leaves the world, so a
 *     recording started later never sees the answer. Snapshots and checkpoints write every photograph the client holds
 *     as the answer the server would send, the ones in view first, and ask for those in view it does not hold yet: their
 *     answer is recorded when it arrives.</li>
 *     <li>Recorded packets that make the client act (take a picture, export files, apply a shader, build a chromatic
 *     exposure) belong to the recording player: they would run on the replay camera or the viewer's disk, and are
 *     dropped. Nothing the client asks of the replay host can be answered, so its Exposure packets are dropped too.</li>
 *     <li>Size. The 1 MiB limit of custom payloads belongs to payloads with no registered codec and the connection's
 *     packet splitter. A recording goes through neither, so a large photograph fits as one payload. What remains: the
 *     codec refuses more than 4 MiB of pixels, and a checkpoint repeats all photographs, so it has a budget.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads an Exposure class when the mod is absent.
 *
 * @author vyrriox
 */
public final class ExposureCompat {
    private static final String PKG = "io.github.mortuusars.exposure.";
    private static final String CLIENTBOUND = PKG + "network.packet.clientbound.";
    private static final String SERVERBOUND = PKG + "network.packet.serverbound.";
    /** Recorded payloads that make the client act instead of describing what happened. */
    private static final Set<String> ACTIONS = Set.of(CLIENTBOUND + "CaptureStartS2CP", CLIENTBOUND + "CaptureStartDebugRGBS2CP",
            CLIENTBOUND + "ExportS2CP", CLIENTBOUND + "ExportStopS2CP", CLIENTBOUND + "ShaderApplyS2CP",
            CLIENTBOUND + "CreateChromaticExposureS2CP");
    /** Largest pixel array of ExposureData's stream codec: above it, encoding throws. */
    private static final int MAX_PIXELS = 4194304;
    /** Most pixel bytes one snapshot or checkpoint writes; the replay buffer holds 1 GiB in all. */
    private static final long MAX_SNAPSHOT_BYTES = 64L << 20;
    private static final String TEST_ID = "jrtest";
    private static final byte[] TEST_PIXELS = {1, 2, 3, 4};

    private static Reflect reflect;
    private static boolean reflectFailed;
    private static int lastLoggedCount = -1;
    private static volatile int droppedActions;
    private static volatile int blockedRequests;

    private ExposureCompat() {
    }

    private record Reflect(Object store, Field exposures, Method getOrRequest, Method receive, Method clear, Method success,
                           Method getStatus, Method getData, Method getPixels, Constructor<?> response, Class<?> photograph,
                           Method getFrame, Method identifier, Method getId, Class<?> frameEntity, Method frameItem,
                           Class<?> stacked, Method getPhotographs, Method photographs) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.EXPOSURE) {
            return reflect;
        }
        try {
            ClassLoader cl = ExposureCompat.class.getClassLoader();
            Class<?> client = Class.forName(PKG + "ExposureClient", false, cl);
            Class<?> storeClass = Class.forName(PKG + "client.ExposureStore", false, cl);
            Class<?> result = Class.forName(PKG + "world.level.storage.RequestedPalettedExposure", false, cl);
            Class<?> data = Class.forName(PKG + "world.level.storage.ExposureData", false, cl);
            Class<?> response = Class.forName(CLIENTBOUND + "ExposureDataResponseS2CP", false, cl);
            Class<?> photograph = Class.forName(PKG + "world.item.PhotographItem", false, cl);
            Class<?> frame = Class.forName(PKG + "world.camera.frame.Frame", false, cl);
            Class<?> identifier = Class.forName(PKG + "world.level.storage.ExposureIdentifier", false, cl);
            Class<?> frameEntity = Class.forName(PKG + "world.entity.PhotographFrameEntity", false, cl);
            Class<?> stacked = Class.forName(PKG + "world.item.StackedPhotographsItem", false, cl);
            Method getPhotographs = stacked.getMethod("getPhotographs", ItemStack.class);
            Field exposures = storeClass.getDeclaredField("exposures");
            exposures.setAccessible(true);
            reflect = new Reflect(client.getMethod("exposureStore").invoke(null), exposures,
                    storeClass.getMethod("getOrRequest", String.class), storeClass.getMethod("receive", String.class, result),
                    storeClass.getMethod("clear"), result.getMethod("success", data), result.getMethod("getStatus"),
                    result.getMethod("getData"), data.getMethod("getPixels"), response.getConstructor(String.class, result),
                    photograph, photograph.getMethod("getFrame", ItemStack.class), frame.getMethod("identifier"),
                    identifier.getMethod("getId"), frameEntity, frameEntity.getMethod("getItem"), stacked, getPhotographs,
                    getPhotographs.getReturnType().getMethod("photographs"));
            JustReplay.LOGGER.info("Exposure detected: photographs are recorded and served to replays");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("exposure.reflect", "Exposure support disabled, its classes changed", t);
        }
        return reflect;
    }

    // ---------------------------------------------------------------- recording

    /** Snapshots and checkpoints: the photographs the client holds, as the answers of a server. */
    static void writeSnapshot(Consumer<Packet<?>> out) throws Exception {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        Map<?, ?> held = (Map<?, ?>) r.exposures.get(r.store);
        Set<String> ids = new LinkedHashSet<>();
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            for (Entity entity : level.entitiesForRendering()) {
                collect(r, entity, ids);
            }
        }
        List<String> absent = new ArrayList<>();
        for (String id : ids) {
            if (!held.containsKey(id)) {
                absent.add(id);
            }
        }
        for (Object id : held.keySet()) {
            ids.add((String) id);
        }
        int written = 0;
        int skipped = 0;
        long bytes = 0;
        for (String id : ids) {
            try {
                byte[] pixels = usablePixels(r, held.get(id));
                if (pixels == null) {
                    continue;
                }
                if (pixels.length > MAX_PIXELS || bytes + pixels.length > MAX_SNAPSHOT_BYTES) {
                    skipped++;
                    continue;
                }
                Object data = r.getData.invoke(held.get(id));
                Object answer = r.success.invoke(null, ((Optional<?>) data).orElseThrow());
                out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.response.newInstance(id, answer)));
                bytes += pixels.length;
                written++;
            } catch (Throwable t) {
                ReplayCompat.warnOnce("exposure.photo." + id, "Cannot record the Exposure photograph " + id, t);
            }
        }
        for (String id : absent) {
            try {
                // Does nothing while an answer is awaited; the answer is recorded when it arrives.
                r.getOrRequest.invoke(r.store, id);
            } catch (Throwable t) {
                ReplayCompat.warnOnce("exposure.request." + id, "Cannot ask for the Exposure photograph " + id, t);
            }
        }
        if (skipped > 0) {
            ReplayCompat.warnOnce("exposure.size", skipped + " Exposure photographs left out of a snapshot, over "
                    + (MAX_PIXELS >> 20) + " MiB each or over the " + (MAX_SNAPSHOT_BYTES >> 20) + " MiB of a snapshot", null);
        }
        if (written != lastLoggedCount) {
            lastLoggedCount = written;
            JustReplay.LOGGER.info("Exposure: snapshot wrote {} photographs ({} KiB), {} in view not held yet, {} left out",
                    written, bytes >> 10, absent.size(), skipped);
        }
    }

    /** The pixels of a store entry holding usable data, or null. */
    private static byte[] usablePixels(Reflect r, Object entry) throws Exception {
        if (entry == null) {
            return null;
        }
        String status = ((Enum<?>) r.getStatus.invoke(entry)).name();
        if (!"SUCCESS".equals(status) && !"NEEDS_REFRESH".equals(status)) {
            return null;
        }
        Optional<?> data = (Optional<?>) r.getData.invoke(entry);
        return data.isPresent() ? (byte[]) r.getPixels.invoke(data.get()) : null;
    }

    /** Photograph ids an entity shows: a frame's content, a dropped item, what a living entity holds or wears. */
    private static void collect(Reflect r, Entity entity, Set<String> ids) {
        try {
            if (r.frameEntity.isInstance(entity)) {
                collect(r, (ItemStack) r.frameItem.invoke(entity), ids);
            } else if (entity instanceof ItemFrame frame) {
                collect(r, frame.getItem(), ids);
            } else if (entity instanceof ItemEntity item) {
                collect(r, item.getItem(), ids);
            } else if (entity instanceof LivingEntity living) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    collect(r, living.getItemBySlot(slot), ids);
                }
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("exposure.scan", "Cannot read the Exposure photographs in view", t);
        }
    }

    private static void collect(Reflect r, ItemStack stack, Set<String> ids) throws Exception {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (r.photograph.isInstance(stack.getItem())) {
            Object id = r.getId.invoke(r.identifier.invoke(r.getFrame.invoke(stack.getItem(), stack)));
            if (id instanceof Optional<?> optional && optional.orElse(null) instanceof String text && !text.isBlank()) {
                ids.add(text);
            }
        } else if (r.stacked.isInstance(stack.getItem())) {
            Object photographs = r.photographs.invoke(r.getPhotographs.invoke(stack.getItem(), stack));
            for (Object inner : (List<?>) photographs) {
                collect(r, (ItemStack) inner, ids);
            }
        }
    }

    // ---------------------------------------------------------------- playback

    /** See {@link ReplayCompat#filterPlayback}: packets that would act on the viewer's client are dropped. */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket custom && ACTIONS.contains(custom.payload().getClass().getName())) {
            droppedActions++;
            return null;
        }
        return packet;
    }

    /** See {@link ReplayCompat#blockServerbound}: the replay host has no photographs to give. */
    static boolean blockServerbound(Packet<?> packet) {
        if (packet instanceof ServerboundCustomPayloadPacket custom && custom.payload().getClass().getName().startsWith(SERVERBOUND)) {
            blockedRequests++;
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- self test

    /** Puts a small photograph in the store, as if the server had answered. */
    public static void selfTestSeed() throws Exception {
        Reflect r = reflect();
        ClassLoader cl = ExposureCompat.class.getClassLoader();
        Class<?> dataClass = Class.forName(PKG + "world.level.storage.ExposureData", false, cl);
        Class<?> tag = Class.forName(PKG + "world.level.storage.ExposureData$Tag", false, cl);
        Object empty = dataClass.getField("EMPTY").get(null);
        Object data = dataClass.getConstructor(int.class, int.class, byte[].class, ResourceLocation.class, tag)
                .newInstance(2, 2, TEST_PIXELS.clone(), dataClass.getMethod("getPaletteId").invoke(empty),
                        dataClass.getMethod("getTag").invoke(empty));
        r.receive.invoke(r.store, TEST_ID, r.success.invoke(null, data));
    }

    /** A capture start as a server sends it when the player takes a picture. */
    public static Packet<?> selfTestCapture() throws Exception {
        ClassLoader cl = ExposureCompat.class.getClassLoader();
        Class<?> paramsClass = Class.forName(PKG + "world.camera.capture.CaptureParameters", false, cl);
        Class<?> film = Class.forName(PKG + "world.camera.film.properties.FilmProperties", false, cl);
        Class<?> extra = Class.forName(PKG + "util.ExtraData", false, cl);
        Object params = paramsClass.getDeclaredConstructors()[0].newInstance("jrcapture", Optional.empty(), Optional.empty(),
                Optional.empty(), 1.0F, Optional.empty(), Optional.empty(), Optional.empty(), film.getField("EMPTY").get(null),
                extra.getField("EMPTY").get(null));
        Object start = Class.forName(CLIENTBOUND + "CaptureStartS2CP", false, cl)
                .getConstructor(ResourceLocation.class, paramsClass)
                .newInstance(new ResourceLocation("exposure", "camera"), params);
        return new ClientboundCustomPayloadPacket((CustomPacketPayload) start);
    }

    public static void selfTestClear() throws Exception {
        Reflect r = reflect();
        r.clear.invoke(r.store);
    }

    /** True when the store holds the seeded photograph with its status good and its pixels equal. */
    public static boolean selfTestHeld() throws Exception {
        Reflect r = reflect();
        byte[] pixels = usablePixels(r, r.getOrRequest.invoke(r.store, TEST_ID));
        return java.util.Arrays.equals(pixels, TEST_PIXELS);
    }

    /** Asks for a photograph nobody has: returns the requests blocked by this call. */
    public static int selfTestRequest() throws Exception {
        Reflect r = reflect();
        int before = blockedRequests;
        r.getOrRequest.invoke(r.store, "jrabsent");
        return blockedRequests - before;
    }

    public static int droppedActions() {
        return droppedActions;
    }
}
