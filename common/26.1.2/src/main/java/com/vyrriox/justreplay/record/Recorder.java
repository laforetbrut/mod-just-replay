package com.vyrriox.justreplay.record;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.format.Checkpoints;
import com.vyrriox.justreplay.format.RegistryIds;
import com.vyrriox.justreplay.format.ReplayBuffer;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.format.ReplayWriter;
import com.vyrriox.justreplay.mixin.BossHealthOverlayAccessor;
import com.vyrriox.justreplay.mixin.ConnectionAccessor;
import com.vyrriox.justreplay.mixin.SynchedEntityDataAccessor;
import com.vyrriox.justreplay.mixin.PlayerTabOverlayAccessor;
import com.vyrriox.justreplay.replay.ReplaySession;
import com.vyrriox.justreplay.voice.VoiceRecorder;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import com.vyrriox.justreplay.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Captures every clientbound play packet of the current connection, plus synthetic packets describing the local
 * player (vanilla never sends the player its own movement), and feeds them to its outputs: a {@link Recording}
 * written to a replay folder, and the in-memory {@link ReplayBuffer} of the instant replay. Both share one capture,
 * so each packet is encoded once whatever runs. An output that joins mid-session starts with a snapshot of the
 * client state; the capture ends when it has no output left.
 * <p>
 * Ticks: the capture counts its own ticks; a recording stores them relative to its start, the buffer stores them
 * as they are and rebases them when a clip is saved.
 *
 * @author vyrriox
 */
public final class Recorder {
    private static volatile Recorder capture;
    private static volatile ClientboundLoginPacket lastLogin;
    /** Chunk radius the server uses now: from the login, then from every radius packet. */
    private static volatile int lastChunkRadius;

    /** The property only exists to test checkpoints on short recordings. */
    private static final Integer CHECKPOINT_OVERRIDE = Integer.getInteger("justreplay.checkpointTicks");
    private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

    /** A recording written to a replay folder. */
    public static final class Recording {
        private final Path dir;
        private final ReplayWriter writer;
        private final ReplayMeta meta;
        private volatile int startTick;
        private volatile int stopAfterTicks = -1;
        /** Recording tick of its last checkpoint; the start counts as one. */
        private int lastCheckpoint;

        private Recording(Path dir, ReplayWriter writer, ReplayMeta meta) {
            this.dir = dir;
            this.writer = writer;
            this.meta = meta;
        }

        /** Folder the recording is written to. */
        public Path dir() {
            return dir;
        }

        /** Capture tick written as tick 0 of this recording. */
        public int startTick() {
            return startTick;
        }

        private static Recording create(Connection connection, UUID actor) throws IOException {
            String stamp = timestamp();
            Path dir = uniqueDir(stamp);
            try {
                ReplayMeta meta = newMeta(connection, actor, stamp);
                ReplayStorage.writeMeta(dir, meta);
                RegistryIds.save(dir);
                if (connection.getPacketListener() instanceof ClientPacketListener listener) {
                    com.vyrriox.justreplay.format.SyncedRegistries.write(dir, com.vyrriox.justreplay.format.SyncedRegistries.encode(listener.registryAccess()));
                }
                return new Recording(dir, new ReplayWriter(dir.resolve(ReplayStorage.STREAM)), meta);
            } catch (Throwable t) {
                deleteQuietly(dir);
                throw t;
            }
        }
    }

    private final Connection connection;
    private final UUID actorUuid;
    /** UUID of the recording player's own profile, the one its actor's UUID is derived from. */
    private final UUID localUuid;
    private final Set<Object> failedTypes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Consumer<byte[]> sink = this::accept;
    private volatile boolean stopped;
    private volatile int tick;
    private volatile PacketCodec codec;
    /** Lowest tick a packet may be written with; raised past a checkpoint so later packets never share its tick. */
    private int floorTick;
    private volatile boolean checkpointPending;
    private int slowCheckpointStart;
    private int slowCheckpointTicks = -1;
    private int lastCheckpointTick;
    /** Chunks the recorder builds itself: extra cached chunks and checkpoint chunks, a few per tick. */
    private final ChunkCapture chunks = new ChunkCapture();
    /** Incremented under the recorder lock by every login or respawn packet captured. */
    private volatile int levelEpoch;
    private volatile Recording recording;
    private volatile ReplayBuffer buffer;
    /** Registry ids of the connection, saved with buffer clips. */
    private volatile byte[] registryIds;

    private ClientLevel lastLevel;
    private LocalPlayer lastPlayer;
    private int lastEntityId = Integer.MIN_VALUE;
    private Vec3 lastPos;
    private float lastYRot, lastXRot, lastHeadRot;
    private Object[] lastDataValues;
    /** Syncable attributes of the recording player, refreshed on respawn and now and then. */
    private List<net.minecraft.world.entity.ai.attributes.AttributeInstance> actorAttributes;
    private long lastAttributeHash;
    private int attributeRefresh;
    private final ItemStack[] lastEquipment = new ItemStack[SLOTS.length];
    private boolean lastSwinging;
    private int lastSwingTime;
    private final com.vyrriox.justreplay.compat.EmotecraftCompat.LocalEmotes localEmotes =
            new com.vyrriox.justreplay.compat.EmotecraftCompat.LocalEmotes();

    // Cost measurement: measured per second, logged once a minute at debug level through the logger only.
    private final LongAdder networkNanos = new LongAdder();
    private final LongAdder networkPackets = new LongAdder();
    /** Mod payloads this client could only read as raw bytes, by id: their content is not recorded. */
    private final java.util.Map<net.minecraft.resources.Identifier, LongAdder> discardedPayloads = new ConcurrentHashMap<>();
    private long clientNanos;
    /** Part of {@link #clientNanos} spent building chunks (extra chunks and checkpoints). */
    private long chunkNanos;
    private long clientMaxNanos;
    private int costTicks;
    private int costSeconds;
    private double minuteAvgSum;
    private double minuteWorstAvg;
    private long minuteMaxNanos;
    private double minuteNetworkMs;
    private volatile String lastCost = "";

    private Recorder(Connection connection) {
        this.connection = connection;
        GameProfile profile = Minecraft.getInstance().getGameProfile();
        this.localUuid = profile.getId();
        this.actorUuid = UUID.nameUUIDFromBytes(("justreplay:actor:" + profile.getId()).getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- queries

    /** The capture while a recording runs, else null. */
    public static Recorder active() {
        Recorder c = capture;
        return c != null && c.recording != null ? c : null;
    }

    /** The capture of the current connection, whatever it feeds (recording, buffer or both), or null. */
    public static Recorder capturing() {
        return capture;
    }

    public static boolean isRecording() {
        return active() != null;
    }

    public static boolean isBuffering() {
        Recorder c = capture;
        return c != null && c.buffer != null;
    }

    /**
     * A sound or a level event the client played for its own player: its footsteps, the blocks it places and breaks,
     * its swings. The server sends those to every other player but never back to the one who caused them, so they
     * are written here as the packets the others received. Client thread only.
     */
    public static void recordLocalEffect(Packet<?> packet) {
        Recorder c = capture;
        if (c == null || (c.recording == null && c.buffer == null) || com.vyrriox.justreplay.replay.ReplaySession.isRunning()) {
            return;
        }
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener != null && c.connection.getPacketListener() == listener) {
            c.write(listener, packet);
        }
    }

    /**
     * A clientbound packet the client received outside of its connection (Sable's sub-level poses, sent over UDP or
     * handed over directly in singleplayer), recorded as if it had come through the connection. Any thread.
     */
    public static void recordSideChannel(Packet<?> packet) {
        Recorder c = capture;
        if (c == null || (c.recording == null && c.buffer == null) || ReplaySession.current() != null) {
            return;
        }
        c.capture(packet);
    }

    /** Tick of the running recording (0 at its start), or 0. */
    public int tick() {
        Recording r = recording;
        return r == null ? 0 : Math.max(0, tick - r.startTick);
    }

    /** Tick of the capture, the clock of voice frames and of the buffer. */
    public int captureTick() {
        return tick;
    }

    /** Folder of the running recording, or null. */
    public Path dir() {
        Recording r = recording;
        return r == null ? null : r.dir;
    }

    public Recording recording() {
        return recording;
    }

    public ReplayBuffer buffer() {
        return buffer;
    }

    /** UUID of the actor standing for the recording player in the replay. */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** Cost of the last second, as logged at debug level. */
    public String lastCost() {
        return lastCost;
    }

    /** True once a timed recording reached its length; the caller stops it. */
    public boolean timedRecordingFinished() {
        Recording r = recording;
        return r != null && r.stopAfterTicks > 0 && tick - r.startTick >= r.stopAfterTicks;
    }

    // ---------------------------------------------------------------- network thread

    /** Called by the connection mixin for every packet the client receives, before it is handled. */
    public static void onPacketReceived(Connection connection, Packet<?> packet) {
        if (packet instanceof ClientboundLoginPacket login) {
            lastLogin = login;
            lastChunkRadius = login.chunkRadius();
            if (capture == null && !ReplaySession.isReplayConnection(connection) && ReplaySession.current() == null) {
                boolean record = connection.isMemoryConnection() ? JRConfig.AUTO_RECORD_SINGLEPLAYER.get() : JRConfig.AUTO_RECORD_MULTIPLAYER.get();
                boolean buffer = JRConfig.REPLAY_BUFFER.get();
                if (record || buffer) {
                    startFromLogin(connection, record, buffer);
                }
            }
        }
        if (packet instanceof ClientboundSetChunkCacheRadiusPacket radius) {
            lastChunkRadius = radius.getRadius();
        }
        if (!ReplaySession.isReplayConnection(connection)) {
            trackSpawns(connection, packet);
        }
        Recorder c = capture;
        if (c != null && c.connection == connection) {
            c.capture(packet);
        }
    }

    // ---------------------------------------------------------------- spawn cache

    /** Original spawn packets (with their bundle: NeoForge spawn data, entity data, attributes...) per entity id. */
    private static final java.util.Map<Integer, List<Packet<?>>> SPAWNS = new ConcurrentHashMap<>();
    private static volatile Connection spawnConnection;

    /**
     * Mod packets the server sends once per connection and then updates incrementally (Create's train network), in
     * arrival order: snapshots and checkpoints replay them, since a stream starting later never sees them.
     */
    private static final List<Packet<?>> PERSISTENT = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_PERSISTENT = 20000;
    private static final Set<String> FAILED_GLOBAL = ConcurrentHashMap.newKeySet();

    private static void trackSpawns(Connection connection, Packet<?> packet) {
        boolean newConnection = connection != spawnConnection;
        if (newConnection) {
            SPAWNS.clear();
            PERSISTENT.clear();
            spawnConnection = connection;
        }
        com.vyrriox.justreplay.compat.ReplayCompat.onLivePacket(packet, newConnection);
        if (com.vyrriox.justreplay.compat.CreateCompat.isPersistent(packet)) {
            if (PERSISTENT.size() < MAX_PERSISTENT) {
                PERSISTENT.add(packet);
            } else if (PERSISTENT.size() == MAX_PERSISTENT && FAILED_GLOBAL.add("persistent")) {
                JustReplay.LOGGER.warn("Too many train network packets on this connection; later snapshots may miss trains");
            }
        }
        if (packet instanceof ClientboundLoginPacket || packet instanceof net.minecraft.network.protocol.game.ClientboundRespawnPacket) {
            SPAWNS.clear();
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle) {
            List<Packet<?>> subs = new ArrayList<>();
            bundle.subPackets().forEach(subs::add);
            for (Packet<?> sub : subs) {
                if (sub instanceof ClientboundAddEntityPacket add) {
                    SPAWNS.put(add.getId(), subs);
                } else if (sub instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove) {
                    remove.getEntityIds().forEach(SPAWNS::remove);
                }
            }
        } else if (packet instanceof ClientboundAddEntityPacket add) {
            SPAWNS.put(add.getId(), List.of(packet));
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket remove) {
            remove.getEntityIds().forEach(SPAWNS::remove);
        }
    }

    private void capture(Packet<?> packet) {
        if (!(connection.getPacketListener() instanceof ClientPacketListener listener)) {
            return;
        }
        long start = System.nanoTime();
        PacketCodec c = codec;
        if (c == null || !c.matches(listener)) {
            c = codec = newCodec(listener);
        }
        if (packet instanceof ClientboundLoginPacket || packet instanceof net.minecraft.network.protocol.game.ClientboundRespawnPacket) {
            synchronized (this) {
                levelEpoch++;
            }
        }
        chunks.onPacket(packet);
        countDiscarded(packet);
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket forget) {
            // Queued from the network thread ahead of the packet's own handling, so it runs while the chunk is intact.
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    if (mc.level != null) {
                        chunks.beforeForget(mc.level, forget.pos(), (built, out) -> write(listener, built, out));
                    }
                } catch (Throwable t) {
                    if (failedTypes.add(ChunkCapture.class)) {
                        JustReplay.LOGGER.warn("Cannot build a chunk the server forgets: {}", t.toString());
                    }
                }
            });
        }
        try {
            if (!com.vyrriox.justreplay.compat.ReplayCompat.skipRecording(packet)) {
                c.encode(com.vyrriox.justreplay.compat.ReplayCompat.rewriteRecorded(packet, localUuid, actorUuid), sink);
            }
        } catch (Throwable t) {
            logUnrecordable(packet, t);
        }
        networkNanos.add(System.nanoTime() - start);
        networkPackets.increment();
    }

    /** Counts a payload the client had no codec for. Its bytes are gone once decoded, so the replay lacks it. */
    private void countDiscarded(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                && custom.payload() instanceof net.minecraft.network.protocol.common.custom.DiscardedPayload) {
            net.minecraft.resources.Identifier id = custom.payload().type().id();
            if (discardedPayloads.computeIfAbsent(id, k -> new LongAdder()).sum() == 0) {
                JustReplay.LOGGER.warn("Payload {} has no codec on this client, its content is not recorded", id);
            }
            discardedPayloads.get(id).increment();
        }
    }

    /** Logs how many payloads of each id were not recorded, then forgets them. */
    private void logDiscarded() {
        discardedPayloads.forEach((id, count) ->
                JustReplay.LOGGER.info("Payload {}: {} packets without a codec were not recorded", id, count.sum()));
        discardedPayloads.clear();
    }

    private PacketCodec newCodec(ClientPacketListener listener) {
        PacketCodec c = PacketCodec.of(listener);
        c.onSubPacketFailure(this::logUnrecordable);
        return c;
    }

    /** Logs once per packet type (custom payload id when it has one) a packet that cannot be encoded. */
    private void logUnrecordable(Packet<?> packet, Throwable t) {
        Object type = packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                ? custom.payload().type().id() : packet.getClass();
        if (failedTypes.add(type)) {
            JustReplay.LOGGER.warn("Cannot record {}, it will be skipped: {}", type, t.toString(),
                    t.getCause() != null ? t.getCause() : t);
        }
    }

    /** Hands an encoded packet to every output, at the current tick. */
    private synchronized void accept(byte[] data) {
        if (stopped) {
            return;
        }
        int at = Math.max(tick, floorTick);
        Recording r = recording;
        if (r != null) {
            r.writer.write(at - r.startTick, data);
        }
        ReplayBuffer b = buffer;
        if (b != null) {
            b.write(at, data);
        }
    }

    /**
     * Writes data built on the client thread from the level of a given epoch; dropped when a login or respawn was
     * captured since, so a chunk of the previous level never follows the packet that replaced it.
     */
    private synchronized void acceptIfEpoch(int epoch, byte[] data) {
        if (levelEpoch == epoch) {
            accept(data);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
    }

    /** A new replay folder, created at once so two saves in the same second never share it. */
    private static Path uniqueDir(String name) throws IOException {
        Path dir = ReplayStorage.root().resolve(name);
        int n = 1;
        while (Files.exists(dir)) {
            dir = ReplayStorage.root().resolve(name + "_" + n++);
        }
        Files.createDirectories(dir);
        return dir;
    }

    private static void deleteQuietly(Path dir) {
        try {
            ReplayStorage.deleteRecursively(dir);
        } catch (IOException e) {
            JustReplay.LOGGER.warn("Cannot remove the folder of a failed recording {}: {}", dir, e.toString());
        }
    }

    private static ReplayMeta newMeta(Connection connection, UUID actor, String name) {
        Minecraft mc = Minecraft.getInstance();
        ReplayMeta meta = new ReplayMeta();
        meta.name = name;
        meta.startedAt = System.currentTimeMillis();
        meta.singleplayer = connection.isMemoryConnection();
        if (mc.getCurrentServer() != null) {
            meta.server = mc.getCurrentServer().name;
        } else if (mc.getSingleplayerServer() != null) {
            meta.server = mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        meta.minecraftVersion = SharedConstants.getCurrentVersion().getName();
        meta.loader = Platform.get().loaderName();
        meta.mc = meta.minecraftVersion;
        meta.modVersion = Platform.get().modVersion();
        meta.mods.addAll(Platform.get().modList());
        GameProfile profile = mc.getGameProfile();
        meta.recorderName = profile.getName();
        meta.recorderUuid = profile.getId().toString();
        meta.actorUuid = actor.toString();
        com.vyrriox.justreplay.compat.ReplayCompat.describeLevel(meta, mc.level, true);
        return meta;
    }

    /** Ticks kept by the buffer; the property only exists to test buffer checkpoints in a short session. */
    private static int bufferWindowTicks() {
        Integer override = Integer.getInteger("justreplay.bufferWindowTicks");
        return override != null ? Math.max(20, override) : JRConfig.BUFFER_MINUTES.get() * 1200;
    }

    private static int bufferCheckpointInterval(int windowTicks) {
        return CHECKPOINT_OVERRIDE != null ? recordingCheckpointInterval() : ReplayBuffer.checkpointInterval(windowTicks);
    }

    /** Ticks between two checkpoints of a recording on disk. */
    private static int recordingCheckpointInterval() {
        return CHECKPOINT_OVERRIDE != null ? Math.max(20, CHECKPOINT_OVERRIDE) : JRConfig.CHECKPOINT_MINUTES.get() * 1200;
    }

    /** Radius of the chunks recorded beyond the server view distance. */
    private static int extraChunkRadius(Minecraft mc) {
        return Math.min(mc.options.renderDistance().get(), JRConfig.EXTRA_CHUNK_RADIUS.get());
    }

    /** Login of a new connection: outputs started here need no snapshot, the stream holds everything from the start. */
    private static void startFromLogin(Connection connection, boolean record, boolean buffer) {
        Recorder c = null;
        try {
            c = new Recorder(connection);
            if (record) {
                c.recording = Recording.create(connection, c.actorUuid);
                JustReplay.LOGGER.info("Recording started automatically into {}", c.recording.dir);
            }
            if (buffer) {
                c.registryIds = RegistryIds.encode();
                c.buffer = new ReplayBuffer(0, bufferWindowTicks());
                JustReplay.LOGGER.info("Replay buffer started ({} s)", c.buffer.windowTicks() / 20);
            }
            capture = c;
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot start recording", t);
            if (c != null) {
                if (c.recording != null) {
                    c.recording.writer.close();
                    deleteQuietly(c.recording.dir);
                    c.recording = null;
                }
                if (c.buffer != null) {
                    c.buffer.close();
                    c.buffer = null;
                }
            }
        }
    }

    /** The capture of the current connection, or a new unpublished one. */
    private static Recorder captureFor(ClientPacketListener listener) {
        Recorder c = capture;
        if (c != null && !c.stopped && c.connection == listener.getConnection()) {
            return c;
        }
        if (c != null) {
            // Left over from another connection.
            endCapture();
        }
        return new Recorder(listener.getConnection());
    }

    /** Whether an output can join now: in a normal world, with the login seen. */
    private static boolean canAttach(Minecraft mc) {
        ClientPacketListener listener = mc.getConnection();
        if (listener == null || mc.level == null || mc.player == null || ReplaySession.current() != null
                || ReplaySession.isReplayConnection(listener.getConnection())) {
            return false;
        }
        if (lastLogin == null) {
            JustReplay.LOGGER.warn("No login packet seen on this connection, cannot snapshot the world");
            return false;
        }
        return true;
    }

    /** Starts a recording in the middle of a session: the current client state is written first. */
    public static boolean startManual() {
        Minecraft mc = Minecraft.getInstance();
        if (isRecording() || !canAttach(mc)) {
            return false;
        }
        ClientPacketListener listener = mc.getConnection();
        Recorder c = captureFor(listener);
        Recording r;
        try {
            r = Recording.create(listener.getConnection(), c.actorUuid);
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot start recording", t);
            return false;
        }
        try {
            c.attach(mc, listener, at -> {
                r.startTick = at;
                r.meta.startGameTime = gameTime(mc);
                return data -> r.writer.write(0, data);
            }, () -> {
                c.recording = r;
                if (c.buffer == null) {
                    // Checkpoints follow this recording's own clock.
                    c.lastCheckpointTick = c.tick;
                }
            });
            return true;
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot start recording", t);
            r.writer.close();
            deleteQuietly(r.dir);
            c.endIfIdle();
            return false;
        }
    }

    /** Game time of the integrated server (all its levels share it), or of the client level, or -1. */
    public static long gameTime(Minecraft mc) {
        net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && server.overworld() != null) {
            return server.overworld().getGameTime();
        }
        return mc.level == null ? -1 : mc.level.getGameTime();
    }

    /** Scene jobs: notes in the running recording the game time its commands ran at (see {@link ReplayMeta#sceneCommandsTick}). */
    public static void noteSceneCommands(long gameTime) {
        Recorder c = capture;
        Recording r = c == null ? null : c.recording;
        if (r != null && gameTime >= 0 && r.meta.startGameTime >= 0) {
            r.meta.sceneCommandsTick = (int) (gameTime - r.meta.startGameTime);
            ReplayStorage.writeMeta(r.dir, r.meta);
        }
    }

    /** Starts a recording that stops by itself after the given number of ticks (see {@link #timedRecordingFinished()}). */
    public static boolean startTimed(int ticks) {
        if (!startManual()) {
            return false;
        }
        Recorder c = capture;
        if (c != null && c.recording != null) {
            c.recording.stopAfterTicks = Math.max(1, ticks);
        }
        return true;
    }

    /** Starts the instant replay buffer in the middle of a session, from a snapshot of the client state. */
    private static boolean startBuffer(Minecraft mc) {
        if (!canAttach(mc)) {
            return false;
        }
        ClientPacketListener listener = mc.getConnection();
        Recorder c = captureFor(listener);
        ReplayBuffer[] created = new ReplayBuffer[1];
        try {
            byte[] ids = RegistryIds.encode();
            int window = bufferWindowTicks();
            c.attach(mc, listener, at -> {
                created[0] = new ReplayBuffer(at, window);
                return data -> created[0].write(at, data);
            }, () -> {
                c.registryIds = ids;
                c.buffer = created[0];
            });
            JustReplay.LOGGER.info("Replay buffer started ({} s)", window / 20);
            return true;
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot start the replay buffer", t);
            if (created[0] != null && c.buffer != created[0]) {
                created[0].close();
            }
            c.endIfIdle();
            return false;
        }
    }

    /**
     * Adds an output: under the capture lock (packets arriving meanwhile wait and follow the snapshot), the output
     * opens at the current tick, receives a snapshot of the client state, then joins the capture.
     *
     * @param open    receives the start tick, returns the sink of the snapshot
     * @param publish makes the output visible to {@link #accept}
     */
    private void attach(Minecraft mc, ClientPacketListener listener, java.util.function.IntFunction<Consumer<byte[]>> open,
                        Runnable publish) {
        synchronized (this) {
            boolean fresh = capture != this;
            if (fresh) {
                codec = newCodec(listener);
                capture = this;
            }
            Consumer<byte[]> out = open.apply(Math.max(tick, floorTick));
            if (fresh) {
                chunkScanPlayer = mc.player;
                chunkScanEpoch = levelEpoch;
            } else {
                chunks.rescanExtraChunks();
            }
            // A running capture already spawned the actor in the other outputs: give the new one its full state.
            boolean actorKnown = !fresh && lastPlayer == mc.player && lastLevel == mc.level;
            writeSnapshot(mc, listener, out);
            if (actorKnown) {
                writeActorState(listener, mc.player, out);
            }
            publish.run();
        }
    }

    private static volatile boolean lastStopIncomplete;

    /** Whether the recording ended by the last {@link #stop()} could not be written completely (disk error or timeout). */
    public static boolean lastStopIncomplete() {
        return lastStopIncomplete;
    }

    /** Whether the running recording lost data to a disk error; the caller should stop it and tell the user. */
    public static boolean recordingFailed() {
        Recorder c = capture;
        Recording r = c == null ? null : c.recording;
        return r != null && r.writer.failed();
    }

    public static Path stop() {
        Recorder c = capture;
        Recording r = c == null ? null : c.recording;
        if (r == null) {
            return null;
        }
        // Voice frames heard up to now still go to this recording's track, then the track is completed.
        VoiceRecorder.onRecordingStopped(Minecraft.getInstance(), c, r);
        synchronized (c) {
            c.recording = null;
        }
        r.writer.close();
        r.meta.durationTicks = Math.max(0, c.tick - r.startTick);
        boolean complete = r.writer.closedCleanly();
        lastStopIncomplete = !complete;
        if (!complete) {
            // The stream stops where the disk failed: do not claim a longer replay than the file holds.
            r.meta.durationTicks = Math.min(r.meta.durationTicks, Math.max(0, r.writer.lastTick()));
        }
        ReplayStorage.writeMeta(r.dir, r.meta);
        c.logDiscarded();
        if (complete) {
            JustReplay.LOGGER.info("Recording saved to {} ({} ticks)", r.dir, r.meta.durationTicks);
        } else {
            JustReplay.LOGGER.error("Recording in {} is incomplete, it holds {} ticks", r.dir, r.meta.durationTicks);
        }
        c.endIfIdle();
        return r.dir;
    }

    /**
     * Saves the instant replay buffer as a replay folder, off the client thread. Null when the buffer is not running.
     */
    public static CompletableFuture<ReplayBuffer.Saved> saveBuffer() {
        Recorder c = capture;
        ReplayBuffer b = c == null ? null : c.buffer;
        if (b == null) {
            return null;
        }
        try {
            String stamp = timestamp();
            Path dir = uniqueDir(stamp + "_buffer");
            ReplayMeta meta = newMeta(c.connection, c.actorUuid, stamp);
            meta.startedAt = System.currentTimeMillis();
            String synced = c.connection.getPacketListener() instanceof ClientPacketListener listener
                    ? com.vyrriox.justreplay.format.SyncedRegistries.encode(listener.registryAccess()) : null;
            return b.save(dir, meta, c.registryIds).thenApply(saved -> {
                com.vyrriox.justreplay.format.SyncedRegistries.write(dir, synced);
                return saved;
            });
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    /** The connection is closing: completes the recording, drops the buffer and ends the capture. */
    public static Path endCapture() {
        Recorder c = capture;
        if (c == null) {
            return null;
        }
        Path saved = stop();
        c.end();
        return saved;
    }

    private void detachBuffer() {
        ReplayBuffer b;
        synchronized (this) {
            b = buffer;
            buffer = null;
        }
        if (b != null) {
            b.close();
            JustReplay.LOGGER.info("Replay buffer stopped");
        }
        endIfIdle();
    }

    private void endIfIdle() {
        if (recording == null && buffer == null) {
            end();
        }
    }

    private void end() {
        ReplayBuffer b;
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
            b = buffer;
            buffer = null;
            if (capture == this) {
                capture = null;
            }
        }
        if (b != null) {
            b.close();
        }
        try {
            chunks.cancel();
        } catch (Throwable t) {
            JustReplay.LOGGER.debug("Cannot cancel the pending checkpoint", t);
        }
        VoiceRecorder.onCaptureEnded(this);
    }

    // ---------------------------------------------------------------- client thread

    private static int bufferCheck;
    private static int bufferRetry;

    /** Called at the end of every client tick: follows the buffer setting and ticks the capture. */
    public static void clientTickAll(Minecraft mc) {
        if (++bufferCheck >= 10) {
            bufferCheck = 0;
            updateBuffer(mc);
        }
        Recorder c = capture;
        if (c != null) {
            c.clientTick(mc);
        }
    }

    /** Starts or stops the buffer when the setting changes in game, and follows its length. */
    private static void updateBuffer(Minecraft mc) {
        Recorder c = capture;
        boolean wanted = JRConfig.REPLAY_BUFFER.get() && ReplaySession.current() == null;
        if (!wanted) {
            if (c != null && c.buffer != null) {
                c.detachBuffer();
            }
            bufferRetry = 0;
            return;
        }
        ReplayBuffer b = c == null ? null : c.buffer;
        if (b != null) {
            b.setWindowTicks(bufferWindowTicks());
            return;
        }
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            return;
        }
        if (bufferRetry > 0) {
            bufferRetry--;
            return;
        }
        if (!startBuffer(mc)) {
            // Retry in about ten seconds rather than snapshotting every tick.
            bufferRetry = 20;
        }
    }

    private void clientTick(Minecraft mc) {
        if (stopped) {
            return;
        }
        if (connection.isMemoryConnection() && mc.isPaused()) {
            // A paused singleplayer game sends nothing; do not record the pause as frozen footage.
            return;
        }
        long start = System.nanoTime();
        LocalPlayer player = mc.player;
        ClientPacketListener listener = mc.getConnection();
        if (player != null && mc.level != null && listener != null && listener.getConnection() == connection) {
            try {
                recordLocalPlayer(mc, listener, player);
            } catch (Throwable t) {
                if (failedTypes.add(LocalPlayer.class)) {
                    JustReplay.LOGGER.warn("Cannot record the local player", t);
                }
            }
            Recording described = recording;
            if (described != null) {
                // Each dimension visited: where its Distant Horizons LODs live, so the replay can show them.
                com.vyrriox.justreplay.compat.ReplayCompat.describeLevel(described.meta, mc.level, false);
            }
            Packet<?> sample = com.vyrriox.justreplay.compat.ReplayCompat.recordTick(tick);
            if (sample != null) {
                write(listener, sample);
            }
            try {
                long chunkStart = System.nanoTime();
                tickChunks(mc, listener, player);
                chunkNanos += System.nanoTime() - chunkStart;
            } catch (Throwable t) {
                if (failedTypes.add(ChunkCapture.class)) {
                    JustReplay.LOGGER.warn("Cannot record extra chunks: {}", t.toString());
                }
            }
        }
        VoiceRecorder.clientTick(mc, this);
        tick++;
        Recording r = recording;
        if (r != null) {
            int relative = tick - r.startTick;
            if (relative > 0 && relative % 600 == 0) {
                // Crash safety, rare enough to spare the drive: flush every 30 s, meta.json once a minute.
                r.writer.flush();
                if (relative % 1200 == 0) {
                    r.meta.durationTicks = relative;
                    r.writer.execute(() -> ReplayStorage.writeMeta(r.dir, r.meta));
                }
            }
        }
        int interval = checkpointInterval();
        if (interval > 0 && tick - lastCheckpointTick >= interval) {
            lastCheckpointTick = tick;
            requestCheckpoint(mc);
        }
        addCost(System.nanoTime() - start, true);
    }

    /** Main-thread cost of the capture, logged once per second of recording. */
    private void addCost(long nanos, boolean endOfTick) {
        clientNanos += nanos;
        clientMaxNanos = Math.max(clientMaxNanos, nanos);
        if (!endOfTick || ++costTicks < 20) {
            return;
        }
        long network = networkNanos.sumThenReset();
        long packets = networkPackets.sumThenReset();
        double avg = clientNanos / 1e6 / costTicks;
        lastCost = String.format(java.util.Locale.ROOT,
                "client thread %.3f ms/tick avg (chunks %.3f), %.3f ms max; network thread %.3f ms/s for %d packets",
                avg, chunkNanos / 1e6 / costTicks, clientMaxNanos / 1e6, network / 1e6, packets);
        chunkNanos = 0;
        minuteAvgSum += avg;
        minuteWorstAvg = Math.max(minuteWorstAvg, avg);
        minuteMaxNanos = Math.max(minuteMaxNanos, clientMaxNanos);
        minuteNetworkMs += network / 1e6;
        if (++costSeconds >= 60) {
            JustReplay.LOGGER.debug(String.format(java.util.Locale.ROOT,
                    "Recording cost over the last minute: client thread %.3f ms/tick avg (worst second %.3f), %.3f ms max tick; network thread %.3f ms/s",
                    minuteAvgSum / costSeconds, minuteWorstAvg, minuteMaxNanos / 1e6, minuteNetworkMs / costSeconds));
            costSeconds = 0;
            minuteAvgSum = 0;
            minuteWorstAvg = 0;
            minuteMaxNanos = 0;
            minuteNetworkMs = 0;
        }
        clientNanos = 0;
        clientMaxNanos = 0;
        costTicks = 0;
    }

    // ---------------------------------------------------------------- checkpoints

    /** Ticks between checkpoints for the running outputs, or 0 when none needs any. */
    private int checkpointInterval() {
        int interval = 0;
        if (recording != null) {
            interval = recordingCheckpointInterval();
        }
        ReplayBuffer b = buffer;
        if (b != null) {
            int forBuffer = bufferCheckpointInterval(b.windowTicks());
            interval = interval == 0 ? forBuffer : Math.min(interval, forBuffer);
        }
        return interval;
    }

    /**
     * Schedules a checkpoint. A barrier task on the connection's network thread picks the checkpoint tick: every
     * packet captured before it is already queued for the client thread, so the snapshot it queues there sees
     * exactly the packets written up to that tick, and packets captured after it are written with a later tick.
     */
    private void requestCheckpoint(Minecraft mc) {
        if (checkpointPending) {
            return;
        }
        checkpointPending = true;
        try {
            ((ConnectionAccessor) connection).justreplay$getChannel().eventLoop().execute(() -> {
                int at;
                synchronized (this) {
                    if (stopped) {
                        checkpointPending = false;
                        return;
                    }
                    at = Math.max(tick, floorTick);
                    floorTick = at + 1;
                }
                mc.execute(() -> writeCheckpoint(mc, at));
            });
        } catch (Throwable t) {
            checkpointPending = false;
            if (failedTypes.add(Checkpoints.class)) {
                JustReplay.LOGGER.warn("Cannot schedule a replay checkpoint: {}", t.toString());
            }
        }
    }

    /**
     * Snapshots the client state for the outputs: {@code checkpoints/<tick>.bin.gz} of the recording and a buffer
     * checkpoint; never touches the main stream. Everything but the chunks is taken at once; the chunks are built
     * over the next ticks (see {@link ChunkCapture}) and the checkpoint is handed out when the last one is done.
     */
    private void writeCheckpoint(Minecraft mc, int at) {
        boolean started = false;
        long start = System.nanoTime();
        try {
            ClientPacketListener listener = mc.getConnection();
            if (stopped || lastLogin == null || listener == null || listener.getConnection() != connection
                    || mc.level == null || mc.player == null) {
                return;
            }
            List<byte[]> head = new ArrayList<>();
            writeSnapshotHead(mc, listener, head::add);
            List<byte[]> tail = new ArrayList<>();
            writeSnapshotTail(mc, listener, tail::add);
            writeActorState(listener, mc.player, tail::add);
            int radius = lastChunkRadius + 1;
            if (JRConfig.CAPTURE_EXTRA_CHUNKS.get()) {
                radius = Math.max(radius, extraChunkRadius(mc));
            }
            chunks.startCheckpoint(at, levelEpoch, mc.level, mc.player.chunkPosition().x, mc.player.chunkPosition().z, radius, head, tail,
                    records -> deliverCheckpoint(at, records));
            started = true;
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot take a replay checkpoint at tick {}: {}", at, t.toString());
        } finally {
            if (!started) {
                checkpointPending = false;
            }
            addCost(System.nanoTime() - start, false);
        }
    }

    /** Self test: forces slow frames and asks for a checkpoint; see {@link #slowCheckpointTicks()}. */
    public void startSlowCheckpointTest() {
        ChunkCapture.forcedFrameNanos = 100_000_000L;
        slowCheckpointStart = tick;
        slowCheckpointTicks = -1;
        requestCheckpoint(Minecraft.getInstance());
    }

    /** Self test: ends the forced slow frames; ticks the checkpoint took under them, or -1 when it did not finish. */
    public int slowCheckpointTicks() {
        ChunkCapture.forcedFrameNanos = 0L;
        return slowCheckpointTicks;
    }

    private void deliverCheckpoint(int at, List<byte[]> records) {
        checkpointPending = false;
        if (records != null && ChunkCapture.forcedFrameNanos > 0L && slowCheckpointTicks < 0) {
            slowCheckpointTicks = tick - slowCheckpointStart;
        }
        if (records == null || stopped) {
            JustReplay.LOGGER.debug("Checkpoint at tick {} dropped (level changed or capture stopped)", at);
            return;
        }
        Recording r = recording;
        int interval = recordingCheckpointInterval();
        // A recording gets one checkpoint per interval from its own start: none at all when it is shorter.
        if (r != null && at - r.startTick - r.lastCheckpoint >= interval - interval / 10) {
            int relative = at - r.startTick;
            r.lastCheckpoint = relative;
            Util.ioPool().execute(() -> {
                try {
                    Checkpoints.write(r.dir, relative, records);
                } catch (Throwable t) {
                    JustReplay.LOGGER.warn("Cannot write the replay checkpoint at tick {}: {}", relative, t.toString());
                }
            });
        }
        ReplayBuffer b = buffer;
        if (b != null) {
            b.addCheckpoint(at, records, bufferCheckpointInterval(b.windowTicks()));
        }
    }

    // ---------------------------------------------------------------- recorder-built chunks

    private LocalPlayer chunkScanPlayer;
    private int chunkScanEpoch = Integer.MIN_VALUE;

    /**
     * Runs the incremental chunk work of this tick. After a login or respawn is captured, nothing is built until the
     * client has handled it (a new local player exists), so chunks always come from the level the stream describes.
     */
    private void tickChunks(Minecraft mc, ClientPacketListener listener, LocalPlayer player) {
        int epoch = levelEpoch;
        if (epoch != chunkScanEpoch) {
            if (player == chunkScanPlayer) {
                return;
            }
            chunkScanEpoch = epoch;
        }
        chunkScanPlayer = player;
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        int scanRadius = JRConfig.CAPTURE_EXTRA_CHUNKS.get() ? extraChunkRadius(mc) : -1;
        chunks.tick(level, player.chunkPosition().x, player.chunkPosition().z, scanRadius, epoch, mc.getFrameTimeNs(),
                (packet, out) -> write(listener, packet, out), data -> acceptIfEpoch(epoch, data));
    }

    /** The recording player's actor as a fresh spawn with its current state. */
    private void writeActorState(ClientPacketListener listener, LocalPlayer player, Consumer<byte[]> sink) {
        writeActorSpawn(listener, player, sink);
        write(listener, new ClientboundTeleportEntityPacket(player), sink);
        write(listener, new ClientboundRotateHeadPacket(player, (byte) Mth.floor(player.getYHeadRot() * 256.0F / 360.0F)), sink);
        List<SynchedEntityData.DataValue<?>> data = player.getEntityData().getNonDefaultValues();
        if (data != null) {
            write(listener, new ClientboundSetEntityDataPacket(player.getId(), data), sink);
        }
        List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
        for (EquipmentSlot slot : SLOTS) {
            equipment.add(Pair.of(slot, player.getItemBySlot(slot).copy()));
        }
        write(listener, new ClientboundSetEquipmentPacket(player.getId(), equipment), sink);
        writeAttributes(listener, player, sink);
        com.vyrriox.justreplay.compat.ReplayCompat.writePlayerState(player, actorUuid, packet -> write(listener, packet, sink));
    }

    // ---------------------------------------------------------------- packet writing

    private void write(ClientPacketListener listener, Packet<?> packet) {
        write(listener, packet, sink);
    }

    private void write(ClientPacketListener listener, Packet<?> packet, Consumer<byte[]> out) {
        PacketCodec c = codec;
        if (c == null || !c.matches(listener)) {
            c = codec = newCodec(listener);
        }
        try {
            c.encode(packet, out);
        } catch (Throwable t) {
            if (failedTypes.add(packet.getClass())) {
                JustReplay.LOGGER.warn("Cannot record synthetic packet {}: {}", packet.getClass().getName(), t.toString());
            }
        }
    }

    /**
     * True when the entity data changed since the last call. Values are compared by reference first: the data only
     * replaces a value when it differs, so an idle player costs one array walk and no allocation.
     */
    private boolean entityDataChanged(SynchedEntityData data) {
        SynchedEntityData.DataItem<?>[] items;
        try {
            items = ((SynchedEntityDataAccessor) data).justreplay$getItemsById();
        } catch (Throwable t) {
            return true;
        }
        Object[] last = lastDataValues;
        if (last == null || last.length != items.length) {
            last = lastDataValues = new Object[items.length];
            for (int i = 0; i < items.length; i++) {
                last[i] = items[i] == null ? null : items[i].getValue();
            }
            return true;
        }
        boolean changed = false;
        for (int i = 0; i < items.length; i++) {
            Object value = items[i] == null ? null : items[i].getValue();
            if (value != last[i]) {
                if (!Objects.equals(value, last[i])) {
                    changed = true;
                }
                last[i] = value;
            }
        }
        return changed;
    }

    private void recordLocalPlayer(Minecraft mc, ClientPacketListener listener, LocalPlayer player) {
        boolean respawned = mc.level != lastLevel || player.getId() != lastEntityId || player != lastPlayer;
        if (respawned) {
            lastPlayer = player;
            lastLevel = mc.level;
            lastEntityId = player.getId();
            lastDataValues = null;
            java.util.Arrays.fill(lastEquipment, null);
            actorAttributes = null;
            writeActorSpawn(listener, player, sink);
            // A form the server sent before this spawn (joining a world morphed) is never sent again.
            com.vyrriox.justreplay.compat.ReplayCompat.writeActorForm(player, actorUuid, packet -> write(listener, packet));
        }

        Vec3 pos = player.position();
        if (respawned || !pos.equals(lastPos) || player.getYRot() != lastYRot || player.getXRot() != lastXRot) {
            write(listener, new ClientboundTeleportEntityPacket(player));
            lastPos = pos;
            lastYRot = player.getYRot();
            lastXRot = player.getXRot();
        }
        if (respawned || player.getYHeadRot() != lastHeadRot) {
            write(listener, new ClientboundRotateHeadPacket(player, (byte) Mth.floor(player.getYHeadRot() * 256.0F / 360.0F)));
            lastHeadRot = player.getYHeadRot();
        }

        // The items are read directly so an unchanged player costs no allocation per tick.
        if (entityDataChanged(player.getEntityData())) {
            List<SynchedEntityData.DataValue<?>> data = player.getEntityData().getNonDefaultValues();
            if (data != null) {
                write(listener, new ClientboundSetEntityDataPacket(player.getId(), data));
            }
        }

        // Copies only on change; the packet is encoded at once, so it can hold the kept copies.
        boolean equipmentChanged = false;
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack stack = player.getItemBySlot(SLOTS[i]);
            ItemStack previous = lastEquipment[i];
            if (previous == null || !ItemStack.matches(previous, stack)) {
                equipmentChanged = true;
                lastEquipment[i] = stack.copy();
            }
        }
        if (equipmentChanged) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>(SLOTS.length);
            for (int i = 0; i < SLOTS.length; i++) {
                equipment.add(Pair.of(SLOTS[i], lastEquipment[i]));
            }
            write(listener, new ClientboundSetEquipmentPacket(player.getId(), equipment));
        }

        pollAttributes(listener, player, respawned);

        boolean swingStarted = player.swinging && (!lastSwinging || player.swingTime < lastSwingTime);
        if (swingStarted) {
            write(listener, new ClientboundAnimatePacket(player, player.swingingArm == InteractionHand.MAIN_HAND ? 0 : 3));
        }
        lastSwinging = player.swinging;
        lastSwingTime = player.swingTime;

        // The server never sends a player its own emotes: they are written as another client receives them.
        localEmotes.poll(player, actorUuid, respawned, packet -> write(listener, packet));
    }

    /** Base values and modifiers of the entity's syncable attributes, as the server sends them on spawn. */
    private void writeAttributes(ClientPacketListener listener, LivingEntity living, Consumer<byte[]> out) {
        java.util.Collection<net.minecraft.world.entity.ai.attributes.AttributeInstance> attributes = living.getAttributes().getSyncableAttributes();
        if (!attributes.isEmpty()) {
            write(listener, new ClientboundUpdateAttributesPacket(living.getId(), attributes), out);
        }
    }

    /** Writes the actor's attributes when a syncable value changed. No allocation while nothing changes. */
    private void pollAttributes(ClientPacketListener listener, LocalPlayer player, boolean respawned) {
        // Instances are created lazily, so the list is rebuilt now and then to catch new ones.
        if (actorAttributes == null || --attributeRefresh <= 0) {
            actorAttributes = new ArrayList<>(player.getAttributes().getSyncableAttributes());
            attributeRefresh = 100;
        }
        List<net.minecraft.world.entity.ai.attributes.AttributeInstance> attributes = actorAttributes;
        long hash = attributes.size();
        for (int i = 0; i < attributes.size(); i++) {
            net.minecraft.world.entity.ai.attributes.AttributeInstance instance = attributes.get(i);
            hash = hash * 31 + instance.getAttribute().hashCode();
            hash = hash * 31 + Double.doubleToLongBits(instance.getValue());
        }
        if (respawned || hash != lastAttributeHash) {
            lastAttributeHash = hash;
            if (!attributes.isEmpty()) {
                write(listener, new ClientboundUpdateAttributesPacket(player.getId(), attributes));
            }
        }
    }

    /** Tab list entry and entity of the actor standing for the recording player (vanilla never sends it its own). */
    private void writeActorSpawn(ClientPacketListener listener, LocalPlayer player, Consumer<byte[]> out) {
        GameProfile source = player.getGameProfile();
        PlayerInfo info = listener.getPlayerInfo(source.getId());
        GameProfile actorProfile = new GameProfile(actorUuid, source.getName());
        actorProfile.getProperties().putAll((info != null ? info.getProfile() : source).getProperties());
        write(listener, PlayerInfoPackets.build(listener, List.of(
                new PlayerInfoPackets.Info(actorUuid, actorProfile, GameType.SURVIVAL, false, 0, null))), out);
        write(listener, new ClientboundAddEntityPacket(player.getId(), actorUuid, player.getX(), player.getY(), player.getZ(),
                player.getXRot(), player.getYRot(), player.getType(), 0, Vec3.ZERO, player.getYHeadRot()), out);
        if (player.getVehicle() != null) {
            write(listener, new ClientboundSetPassengersPacket(player.getVehicle()), out);
        }
    }

    /** The "data" field vanilla puts in an add-entity packet, rebuilt from client state. */
    private static int spawnData(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity hanging) {
            return hanging.getDirection().get3DDataValue();
        }
        if (entity instanceof net.minecraft.world.entity.item.FallingBlockEntity falling) {
            return net.minecraft.world.level.block.Block.getId(falling.getBlockState());
        }
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile && projectile.getOwner() != null) {
            return projectile.getOwner().getId();
        }
        return 0;
    }

    /**
     * Writes packets that rebuild the current client world into a sink: login, tab list, scoreboard, boss bars,
     * time, weather, chunks, entities. The recording player's own actor is not included.
     */
    private void writeSnapshot(Minecraft mc, ClientPacketListener listener, Consumer<byte[]> sink) {
        writeSnapshotHead(mc, listener, sink);
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        int radius = lastChunkRadius + 1;
        int cx = player.chunkPosition().x;
        int cz = player.chunkPosition().z;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null && !chunk.isEmpty()) {
                    try {
                        write(listener, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null), sink);
                        chunks.markWritten(x, z);
                    } catch (Throwable t) {
                        JustReplay.LOGGER.warn("Skipping chunk {} in the snapshot: {}", chunk.getPos(), t.toString());
                    }
                }
            }
        }
        writeSnapshotTail(mc, listener, sink);
    }

    /** Snapshot packets that come before the chunks: login, tab list, scoreboard, boss bars, time, weather, chunk center. */
    private void writeSnapshotHead(Minecraft mc, ClientPacketListener listener, Consumer<byte[]> sink) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        ClientboundLoginPacket login = lastLogin;
        CommonPlayerSpawnInfo spawn = login.commonPlayerSpawnInfo();
        GameType mode = mc.gameMode != null ? mc.gameMode.getPlayerMode() : spawn.gameType();
        write(listener, new ClientboundLoginPacket(player.getId(), login.hardcore(), listener.levels(), login.maxPlayers(),
                lastChunkRadius, login.simulationDistance(), login.reducedDebugInfo(), login.showDeathScreen(),
                login.doLimitedCrafting(), new CommonPlayerSpawnInfo(level.dimensionTypeRegistration(), level.dimension(),
                spawn.seed(), mode, spawn.previousGameType(), spawn.isDebug(), spawn.isFlat(), spawn.lastDeathLocation(), 0),
                login.enforcesSecureChat()), sink);

        List<PlayerInfoPackets.Info> infos = new ArrayList<>();
        var listed = listener.getListedOnlinePlayers();
        for (PlayerInfo info : listener.getOnlinePlayers()) {
            infos.add(new PlayerInfoPackets.Info(info.getProfile().getId(), info.getProfile(), info.getGameMode(),
                    listed.contains(info), info.getLatency(), info.getTabListDisplayName()));
        }
        if (!infos.isEmpty()) {
            write(listener, PlayerInfoPackets.build(listener, infos), sink);
        }
        try {
            writeTabListText(mc, listener, sink);
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Skipping the tab list header and footer in the snapshot: {}", t.toString());
        }
        try {
            writeTeams(listener, sink);
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Skipping teams in the snapshot: {}", t.toString());
        }
        try {
            writeObjectives(listener, sink);
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Skipping scoreboard objectives in the snapshot: {}", t.toString());
        }
        try {
            writeBossBars(mc, listener, sink);
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Skipping boss bars in the snapshot: {}", t.toString());
        }

        write(listener, new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)), sink);
        write(listener, new ClientboundInitializeBorderPacket(level.getWorldBorder()), sink);
        if (level.isRaining()) {
            write(listener, new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F), sink);
            write(listener, new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1.0F)), sink);
            write(listener, new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1.0F)), sink);
        }

        write(listener, new ClientboundSetChunkCacheRadiusPacket(lastChunkRadius), sink);
        write(listener, new ClientboundSetChunkCacheCenterPacket(player.chunkPosition().x, player.chunkPosition().z), sink);
    }

    /** Snapshot packets that come after the chunks: end of the initial chunk load and every entity. */
    private void writeSnapshotTail(Minecraft mc, ClientPacketListener listener, Consumer<byte[]> sink) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        List<Packet<?>> persistent;
        synchronized (PERSISTENT) {
            persistent = new ArrayList<>(PERSISTENT);
        }
        for (Packet<?> packet : persistent) {
            write(listener, packet, sink);
        }
        com.vyrriox.justreplay.compat.ReplayCompat.writeSnapshot(packet -> write(listener, packet, sink));
        writeMaps(level, listener, sink);
        write(listener, new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F), sink);

        List<Entity> withPassengers = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == player) {
                continue;
            }
            try {
                writeEntitySnapshot(listener, entity, withPassengers, sink);
                if (!(entity instanceof net.minecraft.world.entity.player.Player)) {
                    com.vyrriox.justreplay.compat.ReplayCompat.writeEntityState(entity, packet -> write(listener, packet, sink));
                }
            } catch (Throwable t) {
                JustReplay.LOGGER.warn("Skipping entity {} in the snapshot: {}", entity, t.toString());
            }
        }
        for (Entity vehicle : withPassengers) {
            write(listener, new ClientboundSetPassengersPacket(vehicle), sink);
        }
        // Emotes and parkour actions in progress: their start packets lie before the snapshot.
        for (net.minecraft.world.entity.player.Player other : level.players()) {
            if (other != player) {
                com.vyrriox.justreplay.compat.ReplayCompat.writePlayerState(other, other.getUUID(), packet -> write(listener, packet, sink));
            }
        }
        com.vyrriox.justreplay.compat.ReplayCompat.writeEntityState(level.entitiesForRendering(), packet -> write(listener, packet, sink));
        com.vyrriox.justreplay.compat.ReplayCompat.writeEntityStates(level, packet -> write(listener, packet, sink));
    }

    /** Map contents: the server only sends them when they change or on first sight, and a login forgets them. */
    private void writeMaps(ClientLevel level, ClientPacketListener listener, Consumer<byte[]> sink) {
        for (var entry : java.util.Map.copyOf(((com.vyrriox.justreplay.mixin.ClientLevelAccessor) level).justreplay$getMapData()).entrySet()) {
            try {
                net.minecraft.world.level.saveddata.maps.MapItemSavedData map = entry.getValue();
                List<net.minecraft.world.level.saveddata.maps.MapDecoration> decorations = new ArrayList<>();
                map.getDecorations().forEach(decorations::add);
                write(listener, new net.minecraft.network.protocol.game.ClientboundMapItemDataPacket(entry.getKey(), map.scale, map.locked, decorations,
                        new net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch(0, 0, 128, 128, map.colors)), sink);
            } catch (Throwable t) {
                JustReplay.LOGGER.warn("Skipping map {} in the snapshot: {}", entry.getKey(), t.toString());
            }
        }
    }

    private void writeTabListText(Minecraft mc, ClientPacketListener listener, Consumer<byte[]> sink) {
        PlayerTabOverlayAccessor tab = (PlayerTabOverlayAccessor) mc.gui.getTabList();
        Component header = tab.justreplay$getHeader();
        Component footer = tab.justreplay$getFooter();
        if (header != null || footer != null) {
            write(listener, new ClientboundTabListPacket(header != null ? header : Component.empty(),
                    footer != null ? footer : Component.empty()), sink);
        }
    }

    private void writeTeams(ClientPacketListener listener, Consumer<byte[]> sink) {
        for (PlayerTeam team : List.copyOf(listener.scoreboard().getPlayerTeams())) {
            write(listener, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true), sink);
        }
    }

    private void writeObjectives(ClientPacketListener listener, Consumer<byte[]> sink) {
        Scoreboard scoreboard = listener.scoreboard();
        for (Objective objective : List.copyOf(scoreboard.getObjectives())) {
            write(listener, new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD), sink);
            for (PlayerScoreEntry score : scoreboard.listPlayerScores(objective)) {
                write(listener, new ClientboundSetScorePacket(score.owner(), objective.getName(), score.value(),
                        Optional.ofNullable(score.display()), Optional.ofNullable(score.numberFormatOverride())), sink);
            }
        }
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective objective = scoreboard.getDisplayObjective(slot);
            if (objective != null) {
                write(listener, new ClientboundSetDisplayObjectivePacket(slot, objective), sink);
            }
        }
    }

    private void writeBossBars(Minecraft mc, ClientPacketListener listener, Consumer<byte[]> sink) {
        for (LerpingBossEvent event : List.copyOf(((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).justreplay$getEvents().values())) {
            write(listener, ClientboundBossEventPacket.createAddPacket(event), sink);
        }
    }

    /** Writes the loader's own spawn packet for this entity if it has one that replaces the vanilla add packet. */
    private boolean writeWholeSpawn(ClientPacketListener listener, Entity entity, Consumer<byte[]> sink) {
        try {
            Packet<?> whole = com.vyrriox.justreplay.platform.ClientPlatform.get().wholeSpawnPacket(entity);
            if (whole != null) {
                write(listener, whole, sink);
                return true;
            }
        } catch (Throwable t) {
            JustReplay.LOGGER.debug("No spawn data for {}", entity, t);
        }
        return false;
    }

    private void writeEntitySnapshot(ClientPacketListener listener, Entity entity, List<Entity> withPassengers, Consumer<byte[]> sink) {
        List<Packet<?>> original = SPAWNS.get(entity.getId());
        if (original != null) {
            // The server's own spawn packets carry what client state cannot rebuild (spawn data, NeoForge payloads).
            for (Packet<?> spawnPacket : original) {
                write(listener, spawnPacket, sink);
                if (spawnPacket instanceof ClientboundSetPassengersPacket riders) {
                    // That list may be out of date by now: the final pass writes the vehicle's current one.
                    Entity vehicle = entity.level().getEntity(riders.getVehicle());
                    if (vehicle != null && !withPassengers.contains(vehicle)) {
                        withPassengers.add(vehicle);
                    }
                }
            }
            write(listener, new ClientboundTeleportEntityPacket(entity), sink);
        } else if (!writeWholeSpawn(listener, entity, sink)) {
            write(listener, new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
                    entity.getXRot(), entity.getYRot(), entity.getType(), spawnData(entity), entity.getDeltaMovement(), entity.getYHeadRot()), sink);
            try {
                Packet<?> spawnData = com.vyrriox.justreplay.platform.ClientPlatform.get().extraSpawnPacket(entity);
                if (spawnData != null) {
                    write(listener, spawnData, sink);
                }
            } catch (Throwable t) {
                JustReplay.LOGGER.debug("No spawn data for {}", entity, t);
            }
        }
        // The spawn packets carry the state at spawn: values back to their default must be written as well.
        List<SynchedEntityData.DataValue<?>> data = original != null ? allDataValues(entity.getEntityData()) : entity.getEntityData().getNonDefaultValues();
        if (data != null) {
            write(listener, new ClientboundSetEntityDataPacket(entity.getId(), data), sink);
        }
        if (entity instanceof LivingEntity living) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (original != null || !stack.isEmpty()) {
                    equipment.add(Pair.of(slot, stack.copy()));
                }
            }
            if (!equipment.isEmpty()) {
                write(listener, new ClientboundSetEquipmentPacket(entity.getId(), equipment), sink);
            }
            writeAttributes(listener, living, sink);
        }
        if (!entity.getPassengers().isEmpty() && !withPassengers.contains(entity)) {
            withPassengers.add(entity);
        }
    }

    /** Every entity data value, default or not; the non-default ones when the items cannot be read. */
    private static List<SynchedEntityData.DataValue<?>> allDataValues(SynchedEntityData data) {
        try {
            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
            for (SynchedEntityData.DataItem<?> item : ((SynchedEntityDataAccessor) data).justreplay$getItemsById()) {
                if (item != null) {
                    values.add(item.value());
                }
            }
            return values.isEmpty() ? null : values;
        } catch (Throwable t) {
            return data.getNonDefaultValues();
        }
    }
}
