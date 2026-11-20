package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.format.ServerPackState;
import com.vyrriox.justreplay.gui.ui.GlassToast;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Resource packs of the recorded server, during playback. The recorded push and pop packets never reach the client
 * (it would ask the player or answer a server that is not there); they change the wanted set here, and once per
 * tick the difference with the applied set goes to the game's server pack source, from its cache of server packs
 * ({@code downloads/<pack id>/<sha1>}) or, with {@link JRConfig.ServerResourcePacks#DOWNLOAD}, from the server's
 * address. Seeking rebuilds the wanted set from the checkpoint, so packs change only when the set really differs.
 * Client thread.
 *
 * @author vyrriox
 */
public final class ReplayPacks {
    private static final ServerPackState WANTED = new ServerPackState();
    private static final List<ServerPackState.Pack> APPLIED = new ArrayList<>();
    /** Packs already reported missing, so a seek does not show the toast again. */
    private static final Set<UUID> REPORTED = new HashSet<>();
    private static boolean dirty;
    private static JRConfig.ServerResourcePacks appliedMode;

    private ReplayPacks() {
    }

    /** A recorded packet reached playback: true when it was a resource pack one, kept from the client. */
    public static boolean consume(Packet<?> packet) {
        if (packet instanceof ClientboundResourcePackPushPacket push) {
            WANTED.push(new ServerPackState.Pack(push.id(), push.url(), push.hash(), push.required()));
            dirty = true;
            return true;
        }
        if (packet instanceof ClientboundResourcePackPopPacket pop) {
            WANTED.pop(pop.id().orElse(null));
            dirty = true;
            return true;
        }
        return false;
    }

    /** The world is rebuilt from a checkpoint or from the start: the packs it brings are the whole set. */
    public static void onWorldRebuild() {
        WANTED.clear();
        dirty = true;
    }

    /** A new replay starts: nothing wanted, nothing applied. */
    public static void reset() {
        WANTED.clear();
        APPLIED.clear();
        REPORTED.clear();
        dirty = false;
        appliedMode = null;
    }

    /** The packs applied now, for the self test. */
    public static List<ServerPackState.Pack> applied() {
        return List.copyOf(APPLIED);
    }

    /** Applies the wanted set once per tick, when it or the setting changed. */
    public static void tick(Minecraft mc) {
        JRConfig.ServerResourcePacks mode = JRConfig.SERVER_RESOURCE_PACKS.get();
        if (!dirty && mode == appliedMode) {
            return;
        }
        dirty = false;
        appliedMode = mode;
        try {
            apply(mc, mode);
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot apply the server resource packs of the replay: {}", t.toString());
        }
    }

    /** The replay closes: its packs go, as when leaving a server (the disconnect removes them too). */
    public static void close(Minecraft mc) {
        try {
            DownloadedPackSource source = mc.getDownloadedPackSource();
            for (ServerPackState.Pack pack : APPLIED) {
                source.popPack(pack.id());
            }
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot remove the server resource packs of the replay: {}", t.toString());
        }
        reset();
    }

    private static void apply(Minecraft mc, JRConfig.ServerResourcePacks mode) {
        List<ServerPackState.Pack> wanted = mode == JRConfig.ServerResourcePacks.OFF ? List.of() : WANTED.packs();
        ServerPackState.Diff diff = ServerPackState.diff(APPLIED, wanted);
        if (diff.isEmpty()) {
            return;
        }
        DownloadedPackSource source = mc.getDownloadedPackSource();
        for (ServerPackState.Pack pack : diff.remove()) {
            source.popPack(pack.id());
            APPLIED.removeIf(applied -> applied.id().equals(pack.id()));
            JustReplay.LOGGER.info("Replay server resource pack {} removed", pack.id());
        }
        for (ServerPackState.Pack pack : diff.add()) {
            Path cached = cachedFile(mc, pack);
            if (cached != null) {
                source.allowServerPacks();
                source.pushLocalPack(pack.id(), cached);
                APPLIED.add(pack);
                JustReplay.LOGGER.info("Replay server resource pack {} applied from the cache ({})", pack.id(), cached.getFileName());
                continue;
            }
            URL url = mode == JRConfig.ServerResourcePacks.DOWNLOAD ? parse(pack.url()) : null;
            if (url != null) {
                source.allowServerPacks();
                source.pushPack(pack.id(), url, pack.hash());
                APPLIED.add(pack);
                JustReplay.LOGGER.info("Replay server resource pack {} downloading from {}", pack.id(), pack.url());
                continue;
            }
            if (REPORTED.add(pack.id())) {
                JustReplay.LOGGER.warn("Replay server resource pack {} ({}) is not in the cache; {}", pack.id(), pack.url(),
                        mode == JRConfig.ServerResourcePacks.DOWNLOAD ? "its address cannot be read" : "set serverResourcePacks to DOWNLOAD to fetch it");
                GlassToast.show(Icon.CUBE, Theme.WARNING, Component.translatable("justreplay.toast.server_pack_missing"),
                        Component.translatable(mode == JRConfig.ServerResourcePacks.DOWNLOAD
                                ? "justreplay.toast.server_pack_missing.bad_url" : "justreplay.toast.server_pack_missing.hint"));
            }
        }
    }

    /**
     * The cached file of a pack: {@code downloads/<id>/<sha1>} in the game folder, or without a hash the newest file
     * of that pack's folder; null when there is none.
     */
    static Path cachedFile(Minecraft mc, ServerPackState.Pack pack) {
        Path dir = mc.gameDirectory.toPath().resolve("downloads").resolve(pack.id().toString());
        String name = ServerPackState.cacheName(pack.hash());
        if (name != null) {
            Path file = dir.resolve(name);
            return Files.isRegularFile(file) ? file : null;
        }
        if (!pack.hash().isEmpty() || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> Files.isRegularFile(f) && ServerPackState.isSha1(f.getFileName().toString()))
                    .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static URL parse(String url) {
        try {
            URL parsed = new java.net.URI(url).toURL();
            String protocol = parsed.getProtocol();
            return "http".equals(protocol) || "https".equals(protocol) ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }
}
