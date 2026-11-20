package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.format.ServerPackState;
import com.vyrriox.justreplay.gui.ui.GlassToast;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DownloadedPackSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundResourcePackPacket;
import net.minecraft.server.packs.repository.PackSource;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resource pack of the recorded server, during playback. In 1.20.1 a server has a single pack, and a new one
 * replaces it. The recorded pack packets never reach the client (it would ask the player or answer a server that is
 * not there); they set the wanted pack here, and once per tick a change goes to the game's server pack source, from
 * its cache ({@code server-resource-packs/<sha1 of the address>}, checked against the pack's hash) or, with
 * {@link JRConfig.ServerResourcePacks#DOWNLOAD}, from the server's address. Seeking rebuilds the wanted pack from the
 * checkpoint, so the pack changes only when it really differs. Client thread.
 *
 * @author vyrriox
 */
public final class ReplayPacks {
    private static final ServerPackState WANTED = new ServerPackState();
    private static final List<ServerPackState.Pack> APPLIED = new ArrayList<>();
    private static final Set<UUID> REPORTED = new HashSet<>();
    /** Cached files already checked against a hash: path and hash to the result, so seeks never hash them again. */
    private static final Map<String, Boolean> CHECKED = new HashMap<>();
    private static boolean dirty;
    private static JRConfig.ServerResourcePacks appliedMode;

    private ReplayPacks() {
    }

    /** A recorded packet reached playback: true when it was the resource pack one, kept from the client. */
    public static boolean consume(Packet<?> packet) {
        if (packet instanceof ClientboundResourcePackPacket push) {
            WANTED.replaceAll(new ServerPackState.Pack(ServerPackState.idOf(push.getUrl()), push.getUrl(), push.getHash(), push.isRequired()));
            dirty = true;
            return true;
        }
        return false;
    }

    /** The world is rebuilt from a checkpoint or from the start: the pack it brings is the whole state. */
    public static void onWorldRebuild() {
        WANTED.clear();
        dirty = true;
    }

    /** A new replay starts: nothing wanted, nothing applied. */
    public static void reset() {
        WANTED.clear();
        APPLIED.clear();
        REPORTED.clear();
        CHECKED.clear();
        dirty = false;
        appliedMode = null;
    }

    /** The packs applied now, for the self test. */
    public static List<ServerPackState.Pack> applied() {
        return List.copyOf(APPLIED);
    }

    /** Applies the wanted pack once per tick, when it or the setting changed. */
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
            JustReplay.LOGGER.warn("Cannot apply the server resource pack of the replay: {}", t.toString());
        }
    }

    /** The replay closes: its pack goes, as when leaving a server. */
    public static void close(Minecraft mc) {
        try {
            if (!APPLIED.isEmpty()) {
                mc.getDownloadedPackSource().clearServerPack();
            }
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot remove the server resource pack of the replay: {}", t.toString());
        }
        reset();
    }

    private static void apply(Minecraft mc, JRConfig.ServerResourcePacks mode) {
        List<ServerPackState.Pack> wanted = mode == JRConfig.ServerResourcePacks.OFF ? List.of() : WANTED.packs();
        if (ServerPackState.diff(APPLIED, wanted).isEmpty()) {
            return;
        }
        DownloadedPackSource source = mc.getDownloadedPackSource();
        ServerPackState.Pack pack = wanted.isEmpty() ? null : wanted.get(wanted.size() - 1);
        Path cached = pack == null ? null : cachedFile(mc, pack);
        URL url = pack != null && cached == null && mode == JRConfig.ServerResourcePacks.DOWNLOAD ? parse(pack.url()) : null;
        if (cached != null) {
            // Replaces the previous pack, as a new pack from a server does.
            source.setServerPack(cached.toFile(), PackSource.SERVER);
            APPLIED.clear();
            APPLIED.add(pack);
            JustReplay.LOGGER.info("Replay server resource pack applied from the cache ({})", cached.getFileName());
            return;
        }
        if (url != null) {
            // Never "required": a failed download must not close the replay.
            source.downloadAndSelectResourcePack(url, pack.hash(), false);
            APPLIED.clear();
            APPLIED.add(pack);
            JustReplay.LOGGER.info("Replay server resource pack downloading from {}", pack.url());
            return;
        }
        if (!APPLIED.isEmpty()) {
            source.clearServerPack();
            APPLIED.clear();
            JustReplay.LOGGER.info("Replay server resource pack removed");
        }
        if (pack != null && REPORTED.add(pack.id())) {
            JustReplay.LOGGER.warn("Replay server resource pack {} is not in the cache; {}", pack.url(),
                    mode == JRConfig.ServerResourcePacks.DOWNLOAD ? "its address cannot be read" : "set serverResourcePacks to DOWNLOAD to fetch it");
            GlassToast.show(Icon.CUBE, Theme.WARNING, Component.translatable("justreplay.toast.server_pack_missing"),
                    Component.translatable(mode == JRConfig.ServerResourcePacks.DOWNLOAD
                            ? "justreplay.toast.server_pack_missing.bad_url" : "justreplay.toast.server_pack_missing.hint"));
        }
    }

    /**
     * The cached file of a pack: {@code server-resource-packs/<sha1 of its address>} in the game folder, the name the
     * game gives it. With a hash the file must match it (an address can serve a newer pack); null when none fits.
     */
    static Path cachedFile(Minecraft mc, ServerPackState.Pack pack) {
        Path file = mc.gameDirectory.toPath().resolve("server-resource-packs").resolve(sha1(pack.url().getBytes(StandardCharsets.UTF_8)));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String hash = ServerPackState.cacheName(pack.hash());
        if (hash == null) {
            return file;
        }
        Boolean ok = CHECKED.computeIfAbsent(file + "@" + hash, key -> hash.equals(sha1(file)));
        return ok ? file : null;
    }

    private static String sha1(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha1(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
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
