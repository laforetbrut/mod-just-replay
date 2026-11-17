package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.audio.AudioPlayer;
import com.vyrriox.justreplay.export.ExportAudio;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.PacketStream;
import com.vyrriox.justreplay.format.RecordedPacket;
import com.vyrriox.justreplay.record.PacketCodec;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Drop the Beam (music, light shows, video screens). Its lights follow the level game time and need nothing; everything
 * that follows a song (the music itself, the light desk cues, the video screens, the sound reactive signals) follows
 * the server's {@code System.nanoTime} as its {@code ServerClock} estimates it from timestamped pings.
 * <ul>
 *     <li><b>Clock.</b> A replay has no server: the recorded {@code clock_pong} packets, and the clock samples the
 *     recorder adds every two seconds (a pong carrying a marker instead of a client instant, with the server instant
 *     the recording client estimated at that tick), give the server instant of each recorded tick. During a replay
 *     {@code ServerClock} answers with that mapping at the replay time instead (see the compat mixins): it pauses with
 *     the replay, follows its speed, and jumps with seeks. Recorded pongs never reach Drop the Beam, and its pings and
 *     requests are not sent to the replay host.</li>
 *     <li><b>Recording.</b> The server sends the music state, mixes, layers, song offers, shows and libraries only on a
 *     change or when a player comes near. The latest of each is kept per connection and written into every snapshot
 *     and checkpoint, so a recording started mid-session plays the music that was already running.</li>
 *     <li><b>Live audio.</b> Drop the Beam has its own OpenAL output. While the replay is paused, or plays at another
 *     speed than x1, its voices are fed silence exactly as when the game is paused: the songs are rendered at their
 *     natural pitch and would be cut into pieces when the clock runs faster or slower. Its music is a game sound: it
 *     follows the project's game volume, mute and ducking, live and in exports (its original file may be imported as
 *     an audio clip instead).</li>
 *     <li><b>Song starts.</b> From the recorded turntable states, {@link #songStartTicks()} gives the replay tick where
 *     each song is heard from its start (and every repeat of a song looping on its own), to line up actor dances or an
 *     imported copy of the song; they are logged once the replay's clock is known.</li>
 *     <li><b>Export.</b> The live output stops rendering for the whole export, and each voice is rendered offline for
 *     every exported frame, on the client thread, with the clock pinned to that frame's replay instant; the result is
 *     attenuated and panned relative to the camera like the game does, then mixed into the exported audio.</li>
 * </ul>
 * Everything is reached by reflection or through optional mixins: nothing here loads a Drop the Beam class when the
 * mod is missing. Its code is never copied.
 *
 * @author vyrriox
 */
public final class DropTheBeamCompat {
    private static final String NAMESPACE = "dropthebeam";
    private static final String PONG_CLASS = "thefricadelle.dropthebeam.network.MusicPayloads$Pong";
    private static final String AUDIO_OUTPUT = "thefricadelle.dropthebeam.client.audio.AudioOutput";
    private static final String CLOCK_CLASS = "thefricadelle.dropthebeam.client.audio.ServerClock";
    private static final ResourceLocation CLOCK_PONG = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "clock_pong");
    private static final String MUSIC_STATE = NAMESPACE + ":music_state";
    private static final String MUSIC_LIBRARY = NAMESPACE + ":library";
    private static final byte[] NAMESPACE_BYTES = (NAMESPACE + ":").getBytes(StandardCharsets.UTF_8);
    /** Time a turntable leaves after a song's last note before it starts the next one (or the same, looping one). */
    private static final long SONG_TAIL_NANOS = 1_500_000_000L;
    /** Marks the clock samples written by the recorder in place of a client instant. */
    public static final long SAMPLE_MARK = Long.MIN_VALUE + 0x4A52L;
    /** Recording ticks between two clock samples. */
    private static final int SAMPLE_TICKS = 40;
    /** Drop the Beam's sample rate, the same as the export mix. */
    private static final int RATE = 48_000;
    /** Samples per offline render call: the block size of Drop the Beam's own output thread. */
    private static final int BLOCK = RATE / 100;
    /** A gap between the audio clock and the replay larger than this is followed at once, a smaller one smoothly. */
    private static final long JUMP_NANOS = 30_000_000L;
    private static final long SLEW_NANOS = 500_000L;
    /** The synthetic clock of the offline export renders starts this far ahead of the real one. */
    private static final long EXPORT_CLOCK_LEAD = 3_600_000_000_000L;

    /**
     * Clientbound payloads whose latest value a snapshot replays, in the order they are written: settings and libraries
     * first, then songs and shows, the turntable states last.
     */
    private static final List<String> CACHED = List.of("features", "library", "image_library", "video_library", "song_offer",
            "song_analysis", "show_data", "music_mix", "music_layers", "music_state");
    private static final int MAX_CACHED = 4096;

    private DropTheBeamCompat() {
    }

    // ---------------------------------------------------------------- reflection

    private record Reflect(Constructor<?> pong, Method pongClient, Method pongServer, Method serverNanos, Method calibrated,
                           Field voices, Field renderer, Field x, Field y, Field z, Field range, Field gain, Field closed,
                           Method render) {
    }

    private static Reflect reflect;
    private static boolean reflectFailed;

    private static synchronized Reflect reflect() {
        if (reflect != null || reflectFailed) {
            return reflect;
        }
        if (!ReplayCompat.DROP_THE_BEAM) {
            reflectFailed = true;
            return null;
        }
        try {
            ClassLoader loader = DropTheBeamCompat.class.getClassLoader();
            Class<?> pong = Class.forName(PONG_CLASS, false, loader);
            Class<?> clock = Class.forName(CLOCK_CLASS, false, loader);
            Class<?> output = Class.forName(AUDIO_OUTPUT, false, loader);
            Class<?> voice = Class.forName(AUDIO_OUTPUT + "$Voice", false, loader);
            Class<?> renderer = Class.forName(AUDIO_OUTPUT + "$Renderer", false, loader);
            reflect = new Reflect(pong.getConstructor(long.class, long.class), pong.getMethod("clientNanos"), pong.getMethod("serverNanos"),
                    clock.getMethod("serverNanos", long.class), clock.getMethod("calibrated"),
                    open(output, "VOICES"), open(voice, "renderer"), open(voice, "x"), open(voice, "y"), open(voice, "z"),
                    open(voice, "range"), open(voice, "gain"), open(voice, "closed"),
                    renderer.getMethod("render", float[].class, int.class, long.class));
            JustReplay.LOGGER.info("Drop the Beam detected: its music, shows and screens follow the replay clock");
        } catch (Throwable t) {
            reflectFailed = true;
            ReplayCompat.warnOnce("dropthebeam.reflect", "Drop the Beam support disabled, its classes changed", t);
        }
        return reflect;
    }

    private static Field open(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    // ---------------------------------------------------------------- recording

    /** Latest cached payload per kind and key (turntable, desk, song), per connection. */
    private static final Map<String, Map<List<Object>, Packet<?>>> CACHE = new LinkedHashMap<>();

    private static boolean isOurs(Packet<?> packet) {
        return packet instanceof ClientboundCustomPayloadPacket custom && NAMESPACE.equals(custom.payload().type().id().getNamespace());
    }

    /** A new connection: nothing it received is kept. Network thread. */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /** Every clientbound packet of a live connection: the payloads a snapshot needs are kept. Network thread. */
    public static void track(Packet<?> packet) {
        if (!isOurs(packet)) {
            return;
        }
        CustomPacketPayload payload = ((ClientboundCustomPayloadPacket) packet).payload();
        String kind = payload.type().id().getPath();
        if (!CACHED.contains(kind)) {
            return;
        }
        List<Object> key = keyOf(payload);
        synchronized (CACHE) {
            Map<List<Object>, Packet<?>> byKey = CACHE.computeIfAbsent(kind, k -> new LinkedHashMap<>());
            byKey.remove(key);
            if (byKey.size() < MAX_CACHED) {
                byKey.put(key, packet);
            } else {
                ReplayCompat.warnOnce("dropthebeam.cache." + kind, "Too many Drop the Beam " + kind + " packets on this connection; "
                        + "later recordings may miss some", null);
            }
        }
    }

    /** What a payload describes: its block positions and songs, so a newer one replaces it. */
    private static List<Object> keyOf(CustomPacketPayload payload) {
        if (!payload.getClass().isRecord()) {
            return List.of();
        }
        List<Object> key = new ArrayList<>(2);
        try {
            for (RecordComponent component : payload.getClass().getRecordComponents()) {
                Class<?> type = component.getType();
                if (type == GlobalPos.class || "SongId".equals(type.getSimpleName())) {
                    key.add(component.getAccessor().invoke(payload));
                }
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dropthebeam.key", "Cannot read a Drop the Beam payload, only its latest one is kept", t);
            return List.of();
        }
        return key;
    }

    /** Snapshot and checkpoint packets: the cached state, then a clock sample. Client thread. */
    public static void writeSnapshot(Consumer<Packet<?>> out) {
        List<Packet<?>> packets = new ArrayList<>();
        synchronized (CACHE) {
            for (String kind : CACHED) {
                Map<List<Object>, Packet<?>> byKey = CACHE.get(kind);
                if (byKey != null) {
                    packets.addAll(byKey.values());
                }
            }
        }
        packets.forEach(out);
        Packet<?> sample = clockSample();
        if (sample != null) {
            out.accept(sample);
        }
    }

    /** A clock sample to record on this capture tick, or null. Client thread. */
    public static Packet<?> sampleForTick(int captureTick) {
        return captureTick % SAMPLE_TICKS == 0 ? clockSample() : null;
    }

    /** The server instant Drop the Beam estimates now, as a marked pong; null while its clock is not calibrated. */
    private static Packet<?> clockSample() {
        Reflect r = reflect();
        if (r == null) {
            return null;
        }
        try {
            if (!(boolean) r.calibrated.invoke(null)) {
                return null;
            }
            long server = (long) r.serverNanos.invoke(null, System.nanoTime());
            return new ClientboundCustomPayloadPacket((CustomPacketPayload) r.pong.newInstance(SAMPLE_MARK, server));
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dropthebeam.sample", "Cannot record the Drop the Beam clock", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- clock mapping

    /** Recorded tick to server nanoseconds: from the recorder's samples when the replay has any, else from the pongs. */
    private static final class ClockMap {
        private final TreeMap<Integer, Long> samples = new TreeMap<>();
        private final TreeMap<Integer, Long> pongs = new TreeMap<>();

        synchronized void add(int tick, long serverNanos, boolean sample) {
            (sample ? samples : pongs).put(tick, serverNanos);
        }

        synchronized int size() {
            return samples.isEmpty() ? pongs.size() : samples.size();
        }

        synchronized void clear() {
            samples.clear();
            pongs.clear();
        }

        /** Server nanoseconds at a replay time in ticks, or {@link Long#MIN_VALUE} without any sample. */
        synchronized long at(double ticks) {
            TreeMap<Integer, Long> map = samples.isEmpty() ? pongs : samples;
            if (map.isEmpty()) {
                return Long.MIN_VALUE;
            }
            int floor = (int) Math.floor(ticks);
            Map.Entry<Integer, Long> below = map.floorEntry(floor);
            Map.Entry<Integer, Long> above = map.higherEntry(floor);
            if (below != null && above != null) {
                double f = (ticks - below.getKey()) / (above.getKey() - below.getKey());
                return below.getValue() + Math.round((above.getValue() - below.getValue()) * f);
            }
            Map.Entry<Integer, Long> edge = below != null ? below : above;
            Map.Entry<Integer, Long> next = below != null ? map.lowerEntry(below.getKey()) : map.higherEntry(above.getKey());
            return edge.getValue() + Math.round((ticks - edge.getKey()) * slope(edge, next));
        }

        /** The replay time in ticks at a server instant: the inverse of {@link #at}; NaN without any sample. */
        synchronized double tickAt(long serverNanos) {
            TreeMap<Integer, Long> map = samples.isEmpty() ? pongs : samples;
            if (map.isEmpty()) {
                return Double.NaN;
            }
            Map.Entry<Integer, Long> previous = null;
            for (Map.Entry<Integer, Long> e : map.entrySet()) {
                if (e.getValue() >= serverNanos) {
                    if (previous == null || e.getValue().equals(previous.getValue())) {
                        Map.Entry<Integer, Long> next = map.higherEntry(e.getKey());
                        return e.getKey() + (serverNanos - e.getValue()) / slope(e, previous != null ? previous : next);
                    }
                    double f = (double) (serverNanos - previous.getValue()) / (e.getValue() - previous.getValue());
                    return previous.getKey() + f * (e.getKey() - previous.getKey());
                }
                previous = e;
            }
            return previous.getKey() + (serverNanos - previous.getValue()) / slope(previous, map.lowerEntry(previous.getKey()));
        }

        /**
         * Nanoseconds per tick beyond the samples: the recording's own pace from the two nearest samples when they are
         * far enough apart to be meaningful, else 50 ms per tick (20 ticks per second).
         */
        private static double slope(Map.Entry<Integer, Long> a, Map.Entry<Integer, Long> b) {
            if (b != null && Math.abs(a.getKey() - b.getKey()) >= 20) {
                double s = (double) (a.getValue() - b.getValue()) / (a.getKey() - b.getKey());
                if (s >= 10_000_000 && s <= 200_000_000) {
                    return s;
                }
            }
            return 50_000_000.0;
        }
    }

    private static final ClockMap CLOCK = new ClockMap();

    /** The audio thread's view of the clock: server instant at a client instant, and the rate it moves at. */
    private record Anchor(long client, long server, double speed) {
        long at(long clientNanos) {
            return server + Math.round((clientNanos - client) * speed);
        }
    }

    private static volatile boolean replaying;
    private static volatile Anchor anchor;
    private static volatile boolean muted;
    private static volatile Thread scan;

    /** The offline export render's clock: fixed on the client thread for the block being rendered. */
    private static volatile Anchor exportOverride;
    private static volatile Thread exportThread;

    private static volatile Path replayDir;
    private static volatile boolean scanDone;

    /** Replay connection up: the replay's clock samples are read in the background once playback has started. */
    static void onReplayStart(Path dir) {
        if (reflect() == null) {
            return;
        }
        CLOCK.clear();
        clearSongs();
        anchor = null;
        muted = false;
        liveGain = 1.0F;
        scanDone = false;
        replayDir = dir;
        replaying = true;
    }

    static void onReplayEnd() {
        replaying = false;
        scan = null;
        replayDir = null;
        anchor = null;
        muted = false;
        liveGain = 1.0F;
        endExport();
        CLOCK.clear();
        clearSongs();
    }

    /** Starts the background scan with the codec of the replay connection (its registry ids are applied by then). */
    private static void startScan(Minecraft mc) {
        Path dir = replayDir;
        replayDir = null;
        if (dir == null || mc.getConnection() == null) {
            return;
        }
        PacketCodec codec = PacketCodec.of(mc.getConnection());
        Thread thread = new Thread(() -> prescan(dir, codec), "JustReplay Drop the Beam clock scan");
        thread.setDaemon(true);
        scan = thread;
        thread.start();
    }

    /**
     * Reads every clock sample, turntable state and song library of the replay ahead. Only these packets are decoded:
     * each record is recognised by its payload id, written right after the packet id. A pong has a fixed shape
     * ({@code id, "dropthebeam:clock_pong", varlong, varlong}) and is read directly. What is met while playing is
     * added as well, so a failed scan only loses the look ahead.
     */
    private static void prescan(Path dir, PacketCodec codec) {
        long start = System.nanoTime();
        int found = 0;
        int states = 0;
        try {
            PacketStream stream = PacketStream.open(dir);
            RecordedPacket next;
            while (scan == Thread.currentThread() && (next = stream.peek()) != null) {
                stream.advance();
                byte[] data = next.data();
                int[] at = {0};
                String id = payloadId(data, at);
                if (id == null) {
                    continue;
                }
                if (id.equals(CLOCK_PONG.toString())) {
                    long client = readVarLong(data, at, 10);
                    long server = readVarLong(data, at, 10);
                    if (at[0] == data.length) {
                        CLOCK.add(next.tick(), server, client == SAMPLE_MARK);
                        found++;
                    }
                } else if (id.equals(MUSIC_STATE) || id.equals(MUSIC_LIBRARY)) {
                    try {
                        if (observe(codec.decode(data), next.tick())) {
                            states++;
                        }
                    } catch (Throwable t) {
                        ReplayCompat.warnOnce("dropthebeam.scan.decode", "Cannot read a recorded Drop the Beam state ahead", t);
                    }
                }
            }
            if (scan == Thread.currentThread()) {
                JustReplay.LOGGER.info("Drop the Beam clock: {} samples and {} turntable states read from the replay in {} ms", found, states,
                        (System.nanoTime() - start) / 1_000_000);
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dropthebeam.scan", "Cannot read the Drop the Beam clock ahead, it is built while playing", t);
        } finally {
            scanDone = true;
        }
    }

    /** The id of a Drop the Beam custom payload packet, the position left right after it; null for any other packet. */
    private static String payloadId(byte[] data, int[] at) {
        if (data.length < NAMESPACE_BYTES.length + 3) {
            return null;
        }
        if (readVarLong(data, at, 5) < 0) {
            return null;
        }
        long length = readVarLong(data, at, 5);
        if (length <= NAMESPACE_BYTES.length || length > 64 || at[0] + length > data.length
                || !Arrays.equals(data, at[0], at[0] + NAMESPACE_BYTES.length, NAMESPACE_BYTES, 0, NAMESPACE_BYTES.length)) {
            return null;
        }
        String id = new String(data, at[0], (int) length, StandardCharsets.UTF_8);
        at[0] += (int) length;
        return id;
    }

    /** A VarInt or VarLong; -1 (with the position past the end) when it is malformed. */
    private static long readVarLong(byte[] data, int[] at, int maxBytes) {
        long value = 0;
        for (int i = 0; i < maxBytes; i++) {
            if (at[0] >= data.length) {
                at[0] = data.length + 1;
                return -1;
            }
            byte b = data[at[0]++];
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        at[0] = data.length + 1;
        return -1;
    }

    /**
     * A recorded packet about to be handled: a clock pong (recorded or a sample) feeds the mapping and is dropped, its
     * client instant belongs to the recording process.
     */
    static boolean consumeRecorded(Packet<?> packet, int tick) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !NAMESPACE.equals(custom.payload().type().id().getNamespace())) {
            return false;
        }
        if (!CLOCK_PONG.equals(custom.payload().type().id())) {
            observe(packet, tick);
            return false;
        }
        Reflect r = reflect();
        if (r != null) {
            try {
                long client = (long) r.pongClient.invoke(custom.payload());
                long server = (long) r.pongServer.invoke(custom.payload());
                CLOCK.add(tick, server, client == SAMPLE_MARK);
            } catch (Throwable t) {
                ReplayCompat.warnOnce("dropthebeam.pong", "Cannot read a recorded Drop the Beam clock packet", t);
            }
        }
        return true;
    }

    /** Drop the Beam requests (pings, files, actions) never reach the replay host. */
    static boolean blockServerbound(Packet<?> packet) {
        return replaying && packet instanceof ServerboundCustomPayloadPacket custom
                && NAMESPACE.equals(custom.payload().type().id().getNamespace());
    }

    /** Replay time in ticks shown by the frame being rendered, from the cursor and the frame's partial tick. */
    private static double replayTime(Playback playback, float partialTick) {
        if (playback.isPaused()) {
            return playback.cursor();
        }
        int step = Math.max(0, playback.lastStep());
        return playback.cursor() - step + partialTick * step;
    }

    /** Rate the replay clock moves at against the real one: 0 while paused and during an export (fixed per frame). */
    private static double clockSpeed(Playback playback, Minecraft mc) {
        if (playback.isPaused() || mc.isPaused() || VideoExporter.isActive()) {
            return 0.0;
        }
        return playback.speed();
    }

    /** Every frame of a replay, before anything renders: the audio thread's clock is moved to this frame. */
    static void onFrame(float partialTick) {
        Playback playback = ReplayCompat.playback();
        if (!replaying || playback == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long target = CLOCK.at(replayTime(playback, partialTick));
        if (target == Long.MIN_VALUE) {
            anchor = null;
            return;
        }
        long now = System.nanoTime();
        double speed = clockSpeed(playback, mc);
        Anchor previous = anchor;
        long server = target;
        if (previous != null && speed > 0 && previous.speed == speed) {
            // Small differences (the partial tick's jitter, a new sample) are closed slowly, so the music never clicks.
            long predicted = previous.at(now);
            long gap = target - predicted;
            if (Math.abs(gap) <= JUMP_NANOS) {
                server = predicted + Math.max(-SLEW_NANOS, Math.min(SLEW_NANOS, gap));
            }
        }
        anchor = new Anchor(now, server, speed);
    }

    /**
     * Every client tick of a replay: the live output follows the project's game sound volume, mute and ducking (Drop the
     * Beam's music is a game sound, its original file may be imported as a clip instead), and is silent while paused or
     * away from x1.
     */
    static void tick(Minecraft mc, ReplaySession session) {
        Playback playback = session.playback();
        if (replayDir != null) {
            startScan(mc);
        }
        float gain = AudioPlayer.gameGain(session.project());
        liveGain = gain;
        muted = replaying && (playback.isPaused() || playback.speed() != 1.0F || gain <= 0.001F);
        if (scanDone && ++songCheck % 20 == 0) {
            logSongStarts(playback);
        }
    }

    /**
     * {@code ServerClock.serverNanos} during a replay, or {@link Long#MIN_VALUE} to let Drop the Beam answer. The client
     * thread gets the exact instant of the frame being rendered; other threads (the audio thread) get the anchor of
     * the last frame, moved on at the replay speed.
     */
    public static long serverNanos(long clientNanos) {
        if (!replaying) {
            return Long.MIN_VALUE;
        }
        Anchor override = exportOverride;
        if (override != null && Thread.currentThread() == exportThread) {
            return override.at(clientNanos);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            Playback playback = ReplayCompat.playback();
            if (playback != null) {
                long target = CLOCK.at(replayTime(playback, mc.getTimer().getGameTimeDeltaPartialTick(false)));
                if (target != Long.MIN_VALUE) {
                    return target + Math.round((clientNanos - System.nanoTime()) * clockSpeed(playback, mc));
                }
            }
        }
        Anchor a = anchor;
        return a == null ? Long.MIN_VALUE : a.at(clientNanos);
    }

    /** {@code ServerClock.calibrated} during a replay: true once the replay's clock is known; null outside a replay. */
    public static Boolean calibrated() {
        if (!replaying) {
            return null;
        }
        return serverNanos(System.nanoTime()) != Long.MIN_VALUE;
    }

    /** True while Drop the Beam must not ping or calibrate against the replay host. */
    public static boolean isReplaying() {
        return replaying;
    }

    // ---------------------------------------------------------------- live audio

    private static volatile boolean exportHold;
    private static volatile boolean inFeed;

    /**
     * Start of a voice's feed on Drop the Beam's audio thread: false to skip it (an export renders the voices itself).
     * Called by the compat mixin, which then mutes the pass when {@link #audioMuted()}.
     */
    public static boolean enterFeed() {
        inFeed = true;
        if (exportHold) {
            inFeed = false;
            return false;
        }
        return true;
    }

    public static void exitFeed() {
        inFeed = false;
    }

    /** True while the replay is paused, not at x1 or its game sounds muted: the live output is fed silence. */
    public static boolean audioMuted() {
        return muted;
    }

    private static volatile float liveGain = 1.0F;

    /** Drop the Beam's category volume for its live output, scaled by the project's game sound gain during a replay. */
    public static float liveCategory(float category) {
        return replaying ? category * liveGain : category;
    }

    // ---------------------------------------------------------------- song starts

    /** Where a turntable's song is at its start (position 0, as heard) in replay ticks; {@code loop} for a repeat. */
    public record SongStart(GlobalPos turntable, String title, double tick, boolean loop) {
    }

    /** A turntable state seen in the replay, at the recorded tick it arrived on. */
    private record StateSeen(GlobalPos turntable, int tick, String song, long originNanos, long pausedAtMicros, int delayMs,
                             float forcedBpm) {
    }

    /** A song of the server's library: title and length. */
    private record SongInfo(String title, long durationMicros) {
    }

    private static final Map<List<Object>, StateSeen> STATES = new LinkedHashMap<>();
    private static final Map<String, SongInfo> LIBRARY = new LinkedHashMap<>();
    private static List<SongStart> loggedStarts = List.of();
    private static int songCheck;

    private static void clearSongs() {
        synchronized (STATES) {
            STATES.clear();
            LIBRARY.clear();
        }
        loggedStarts = List.of();
    }

    /** Keeps a recorded turntable state or song library; true for a state. Any thread. */
    private static boolean observe(Packet<?> packet, int tick) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom)) {
            return false;
        }
        String id = custom.payload().type().id().toString();
        try {
            Object payload = custom.payload();
            if (id.equals(MUSIC_STATE)) {
                GlobalPos turntable = (GlobalPos) invoke(payload, "turntable");
                Object state = invoke(payload, "state");
                java.util.Optional<?> song = (java.util.Optional<?>) invoke(state, "song");
                StateSeen seen = new StateSeen(turntable, tick, song.map(DropTheBeamCompat::songHex).orElse(null),
                        (long) invoke(state, "originNanos"), (long) invoke(state, "pausedAtMicros"), (int) invoke(state, "delayMs"),
                        (float) invoke(state, "forcedBpm"));
                List<Object> key = Arrays.asList(turntable, seen.song(), seen.originNanos(), seen.pausedAtMicros());
                synchronized (STATES) {
                    STATES.merge(key, seen, (a, b) -> a.tick() <= b.tick() ? a : b);
                }
                return true;
            }
            if (id.equals(MUSIC_LIBRARY)) {
                for (Object entry : (List<?>) invoke(payload, "entries")) {
                    SongInfo info = new SongInfo((String) invoke(entry, "title"), (long) invoke(entry, "durationMicros"));
                    synchronized (STATES) {
                        LIBRARY.put(songHex(invoke(entry, "id")), info);
                    }
                }
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dropthebeam.state", "Cannot read a recorded Drop the Beam turntable state", t);
        }
        return false;
    }

    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static String songHex(Object songId) {
        try {
            return (String) invoke(songId, "hex");
        } catch (ReflectiveOperationException e) {
            return String.valueOf(songId);
        }
    }

    /**
     * Every song start of the running replay, in tick order: for each playing turntable state, the replay tick where its
     * song is heard at position 0 (before tick 0 for a song already playing when the recording started), then, for a
     * turntable set to repeat one song, every repeat until its next recorded state. Empty until the replay's clock is
     * known. Client thread (the turntables are read in the replay level).
     */
    public static List<SongStart> songStartTicks() {
        Playback playback = ReplayCompat.playback();
        if (!replaying || playback == null || CLOCK.size() == 0) {
            return List.of();
        }
        List<StateSeen> states;
        Map<String, SongInfo> library;
        synchronized (STATES) {
            states = new ArrayList<>(STATES.values());
            library = new LinkedHashMap<>(LIBRARY);
        }
        states.sort(java.util.Comparator.comparingInt(StateSeen::tick));
        List<SongStart> starts = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            StateSeen s = states.get(i);
            if (s.song() == null || s.pausedAtMicros() >= 0) {
                continue;
            }
            int until = playback.duration();
            for (int j = i + 1; j < states.size(); j++) {
                if (states.get(j).turntable().equals(s.turntable())) {
                    until = states.get(j).tick();
                    break;
                }
            }
            long heard = s.originNanos() + s.delayMs() * 1_000_000L;
            double tick = CLOCK.tickAt(heard);
            if (Double.isNaN(tick) || tick > until) {
                continue;
            }
            SongInfo info = library.get(s.song());
            Object turntable = turntableEntity(s.turntable());
            if (info == null) {
                info = discInfo(turntable, s.song());
            }
            String title = info != null && !info.title().isBlank() ? info.title() : s.song();
            starts.add(new SongStart(s.turntable(), title, tick, false));
            // A song repeated on its own (loop mode ONE) restarts after its length and a short tail; the server sends a
            // new state for each repeat, which takes over from these estimates when it was recorded.
            long duration = info != null ? info.durationMicros() : 0;
            if (duration > 0 && s.forcedBpm() == 0 && "ONE".equals(loopMode(turntable))) {
                long period = duration * 1000L + SONG_TAIL_NANOS;
                for (int k = 1; k < 10_000; k++) {
                    double repeat = CLOCK.tickAt(heard + k * period);
                    if (Double.isNaN(repeat) || repeat >= until - 10 || repeat > playback.duration()) {
                        break;
                    }
                    starts.add(new SongStart(s.turntable(), title, repeat, true));
                }
            }
        }
        starts.sort(java.util.Comparator.comparingDouble(SongStart::tick));
        // A state sent again for the same start (a setting changed, a player came near) is not a new start.
        List<SongStart> unique = new ArrayList<>();
        for (SongStart start : starts) {
            boolean duplicate = false;
            for (SongStart kept : unique) {
                if (kept.turntable().equals(start.turntable()) && Math.abs(kept.tick() - start.tick()) < 10) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(start);
            }
        }
        return unique;
    }

    /** The turntable's block entity in the replay level, or null while its chunk is not loaded. */
    private static Object turntableEntity(GlobalPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(pos.dimension()) || !mc.level.isLoaded(pos.pos())) {
            return null;
        }
        Object entity = mc.level.getBlockEntity(pos.pos());
        return entity != null && entity.getClass().getSimpleName().equals("TurntableBlockEntity") ? entity : null;
    }

    private static String loopMode(Object turntable) {
        try {
            return turntable == null ? null : String.valueOf(invoke(turntable, "loop"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Title and length from the disc the turntable plays, when it is the song asked for. */
    private static SongInfo discInfo(Object turntable, String song) {
        if (turntable == null) {
            return null;
        }
        try {
            int current = (int) invoke(turntable, "current");
            Object disc = turntable.getClass().getMethod("disc", int.class).invoke(turntable, current);
            if (disc != null && song.equals(songHex(invoke(disc, "id")))) {
                return new SongInfo((String) invoke(disc, "title"), (long) invoke(disc, "durationMicros"));
            }
        } catch (Throwable ignored) {
            // The disc is not known on this client: the song id stands for its title.
        }
        return null;
    }

    /** Logs the song starts whenever they change (once the scan is done, and as turntables load). */
    private static void logSongStarts(Playback playback) {
        List<SongStart> starts = songStartTicks();
        if (starts.equals(loggedStarts)) {
            return;
        }
        loggedStarts = starts;
        for (SongStart start : starts) {
            JustReplay.LOGGER.info("Drop the Beam: turntable {} {} \"{}\" at replay tick {}{}", start.turntable().pos().toShortString(),
                    start.loop() ? "repeats" : "starts", start.title(), String.format(java.util.Locale.ROOT, "%.2f", start.tick()),
                    start.tick() < 0 ? " (before the replay)" : "");
        }
    }

    // ---------------------------------------------------------------- export

    private static long exportBase;
    private static long renderedSamples;
    private static long mixedSamples;
    private static int mixedVoices;
    private static volatile long lastExportSamples;
    private static volatile int lastExportVoices;
    private static float[] block = new float[BLOCK];
    private static float[] blockLeft = new float[BLOCK];
    private static float[] blockRight = new float[BLOCK];

    /** An export starts: the live output stops rendering (waiting for a block in progress) before any offline render. */
    static void beginExport() {
        if (reflect() == null) {
            return;
        }
        exportHold = true;
        long deadline = System.nanoTime() + 200_000_000L;
        while (inFeed && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (inFeed) {
            ReplayCompat.warnOnce("dropthebeam.hold", "Drop the Beam's audio thread did not pause for the export", null);
        }
        exportBase = System.nanoTime() + EXPORT_CLOCK_LEAD;
        renderedSamples = 0;
        mixedSamples = 0;
        mixedVoices = 0;
    }

    static void endExport() {
        if (exportHold) {
            lastExportSamples = mixedSamples;
            lastExportVoices = mixedVoices;
            if (mixedSamples > 0) {
                JustReplay.LOGGER.info("Export audio: {} Drop the Beam samples mixed from {} voices", mixedSamples, mixedVoices);
            }
        }
        exportOverride = null;
        exportThread = null;
        exportHold = false;
    }

    /**
     * A frame was captured: Drop the Beam's voices are rendered up to the end of its stretch of the audio track, each
     * block with the clock pinned to the frame's replay instant, and mixed at their place relative to the camera.
     */
    static void onExportFrame(ExportAudio audio, int frame, int fps, double camX, double camY, double camZ, float yaw, float partialTick) {
        Reflect r = reflect();
        Playback playback = ReplayCompat.playback();
        if (r == null || playback == null || !exportHold) {
            return;
        }
        long frameStart = (long) frame * RATE / fps;
        long frameEnd = (long) (frame + 1) * RATE / fps;
        long frameServer = CLOCK.at(replayTime(playback, partialTick));
        if (frameServer == Long.MIN_VALUE) {
            renderedSamples = frameEnd;
            return;
        }
        if (renderedSamples < frameStart) {
            renderedSamples = frameStart;
        }
        List<?> voices;
        try {
            voices = List.copyOf((List<?>) r.voices.get(null));
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dropthebeam.voices", "Cannot read Drop the Beam's voices, the export has no Drop the Beam audio", t);
            return;
        }
        float category = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.RECORDS);
        exportThread = Thread.currentThread();
        try {
            while (renderedSamples < frameEnd) {
                long heard = exportBase + Math.round(renderedSamples * 1e9 / RATE);
                long server = frameServer + Math.round((renderedSamples - frameStart) * 1e9 / RATE);
                exportOverride = new Anchor(heard, server, 1.0);
                Arrays.fill(blockLeft, 0);
                Arrays.fill(blockRight, 0);
                int sounding = 0;
                for (Object voice : voices) {
                    if (renderVoice(r, voice, heard, category, camX, camY, camZ, yaw)) {
                        sounding++;
                    }
                }
                if (sounding > 0) {
                    audio.addRendered(renderedSamples, blockLeft, blockRight, BLOCK);
                    mixedSamples += BLOCK;
                    mixedVoices = Math.max(mixedVoices, sounding);
                }
                renderedSamples += BLOCK;
            }
        } finally {
            exportOverride = null;
        }
    }

    /** Renders one block of a voice into the stereo block; false when it is closed or out of range. */
    private static boolean renderVoice(Reflect r, Object voice, long heard, float category, double camX, double camY, double camZ, float yaw) {
        try {
            if (r.closed.getBoolean(voice)) {
                return false;
            }
            double dx = r.x.getFloat(voice) - camX;
            double dy = r.y.getFloat(voice) - camY;
            double dz = r.z.getFloat(voice) - camZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // As Drop the Beam sets its sources: linear to the range, full volume at the source, the jukebox category.
            double attenuation = Math.max(0.0, 1.0 - distance / Math.max(1.0F, r.range.getFloat(voice)));
            double gain = r.gain.getFloat(voice) * category * attenuation;
            if (gain <= 0.0001) {
                return false;
            }
            Arrays.fill(block, 0);
            r.render.invoke(r.renderer.get(voice), block, BLOCK, heard);
            double yawRad = Math.toRadians(yaw);
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double pan = horizontal < 1e-6 ? 0.0 : (dx * -Math.cos(yawRad) + dz * -Math.sin(yawRad)) / horizontal;
            float gainL = (float) (gain * Math.sqrt((1.0 - pan) / 2.0) * Math.sqrt(2.0));
            float gainR = (float) (gain * Math.sqrt((1.0 + pan) / 2.0) * Math.sqrt(2.0));
            for (int i = 0; i < BLOCK; i++) {
                float v = Math.max(-1.0F, Math.min(1.0F, block[i]));
                blockLeft[i] += v * gainL;
                blockRight[i] += v * gainR;
            }
            return true;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("dropthebeam.render", "Cannot render a Drop the Beam voice for the export", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /** Clock samples known to the running replay. */
    public static int clockSamples() {
        return CLOCK.size();
    }

    /** Server nanoseconds the replay clock maps a tick to, or {@link Long#MIN_VALUE}. */
    public static long mappedNanos(double tick) {
        return CLOCK.at(tick);
    }

    /** Drop the Beam voices open now, or -1. */
    public static int voiceCount() {
        Reflect r = reflect();
        if (r == null) {
            return -1;
        }
        try {
            return ((List<?>) r.voices.get(null)).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Samples of Drop the Beam audio mixed into the last export, and its most voices at once. */
    public static String lastExport() {
        return lastExportSamples + " samples, up to " + lastExportVoices + " voices";
    }

    public static long lastExportSamples() {
        return lastExportSamples;
    }
}
