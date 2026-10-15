package com.vyrriox.justreplay.record;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.format.Checkpoints;
import com.vyrriox.justreplay.format.RegistryIds;
import com.vyrriox.justreplay.format.ReplayMeta;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.format.ReplayWriter;
import com.vyrriox.justreplay.mixin.BossHealthOverlayAccessor;
import com.vyrriox.justreplay.mixin.PlayerTabOverlayAccessor;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
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
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Records every clientbound play packet of the current connection, plus synthetic packets describing the
 * local player (vanilla never sends the player its own movement), into a replay folder.
 *
 * @author vyrriox
 */
public final class Recorder {
    private static volatile Recorder active;
    private static volatile ClientboundLoginPacket lastLogin;

    private enum State { STARTING, RUNNING, STOPPED }

    /** Recorded ticks between two checkpoints (one minute). */
    private static final int CHECKPOINT_INTERVAL_TICKS = 1200;

    private final Connection connection;
    private final Path dir;
    private final ReplayWriter writer;
    private final ReplayMeta meta;
    private final List<byte[]> pending = new ArrayList<>();
    private final Set<Object> failedTypes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile State state;
    private volatile int tick;
    private volatile PacketCodec codec;
    /** Lowest tick a packet may be written with; raised past a checkpoint so later packets never share its tick. */
    private int floorTick;
    private volatile boolean checkpointPending;

    private UUID actorUuid;
    private ClientLevel lastLevel;
    private LocalPlayer lastPlayer;
    private int lastEntityId = Integer.MIN_VALUE;
    private Vec3 lastPos;
    private float lastYRot, lastXRot, lastHeadRot;
    private List<SynchedEntityData.DataValue<?>> lastData;
    private final ItemStack[] lastEquipment = new ItemStack[EquipmentSlot.values().length];
    private boolean lastSwinging;
    private int lastSwingTime;

    private Recorder(Connection connection, Path dir, ReplayWriter writer, ReplayMeta meta, State state) {
        this.connection = connection;
        this.dir = dir;
        this.writer = writer;
        this.meta = meta;
        this.state = state;
    }

    public static Recorder active() {
        return active;
    }

    public static boolean isRecording() {
        return active != null;
    }

    public int tick() {
        return tick;
    }

    // ---------------------------------------------------------------- network thread

    /** Called by the connection mixin for every packet the client receives, before it is handled. */
    public static void onPacketReceived(Connection connection, Packet<?> packet) {
        if (packet instanceof ClientboundLoginPacket login) {
            lastLogin = login;
            if (active == null && !ReplaySession.isReplayConnection(connection) && shouldAutoRecord(connection)) {
                startFromLogin(connection);
            }
        }
        if (!ReplaySession.isReplayConnection(connection)) {
            trackSpawns(connection, packet);
        }
        Recorder recorder = active;
        if (recorder != null && recorder.connection == connection) {
            recorder.capture(packet);
        }
    }

    // ---------------------------------------------------------------- spawn cache

    /** Original spawn packets (with their bundle: NeoForge spawn data, entity data, attributes...) per entity id. */
    private static final java.util.Map<Integer, List<Packet<?>>> SPAWNS = new ConcurrentHashMap<>();
    private static volatile Connection spawnConnection;

    private static void trackSpawns(Connection connection, Packet<?> packet) {
        if (connection != spawnConnection) {
            SPAWNS.clear();
            spawnConnection = connection;
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

    private static boolean shouldAutoRecord(Connection connection) {
        if (ReplaySession.current() != null) {
            return false;
        }
        return connection.isMemoryConnection() ? JRConfig.AUTO_RECORD_SINGLEPLAYER.get() : JRConfig.AUTO_RECORD_MULTIPLAYER.get();
    }

    private void capture(Packet<?> packet) {
        if (!(connection.getPacketListener() instanceof ClientPacketListener listener)) {
            return;
        }
        PacketCodec c = codec;
        if (c == null || !c.matches(listener)) {
            c = codec = PacketCodec.of(listener);
        }
        try {
            c.encode(packet, this::accept);
        } catch (Throwable t) {
            Object type = packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                    ? custom.payload().type().id() : packet.getClass();
            if (failedTypes.add(type)) {
                JustReplay.LOGGER.warn("Cannot record {}, it will be skipped: {}", type, t.toString(),
                        t.getCause() != null ? t.getCause() : t);
            }
        }
    }

    private synchronized void accept(byte[] data) {
        switch (state) {
            case STARTING -> pending.add(data);
            case RUNNING -> writer.write(Math.max(tick, floorTick), data);
            case STOPPED -> {
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private static Recorder create(Connection connection, State state) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        Path dir = ReplayStorage.root().resolve(stamp);
        int n = 1;
        while (dir.toFile().exists()) {
            dir = ReplayStorage.root().resolve(stamp + "_" + n++);
        }
        ReplayMeta meta = new ReplayMeta();
        meta.name = stamp;
        meta.startedAt = System.currentTimeMillis();
        meta.singleplayer = connection.isMemoryConnection();
        if (mc.getCurrentServer() != null) {
            meta.server = mc.getCurrentServer().name;
        } else if (mc.getSingleplayerServer() != null) {
            meta.server = mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        meta.minecraftVersion = SharedConstants.getCurrentVersion().getName();
        meta.modVersion = ModList.get().getModContainerById(JustReplay.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("");
        ModList.get().getMods().forEach(info -> meta.mods.add(info.getModId() + "@" + info.getVersion()));
        GameProfile profile = mc.getGameProfile();
        meta.recorderName = profile.getName();
        meta.recorderUuid = profile.getId().toString();
        UUID actor = UUID.nameUUIDFromBytes(("justreplay:actor:" + profile.getId()).getBytes(StandardCharsets.UTF_8));
        meta.actorUuid = actor.toString();
        ReplayStorage.writeMeta(dir, meta);
        RegistryIds.save(dir);
        Recorder recorder = new Recorder(connection, dir, new ReplayWriter(dir.resolve(ReplayStorage.STREAM)), meta, state);
        recorder.actorUuid = actor;
        return recorder;
    }

    private static void startFromLogin(Connection connection) {
        try {
            active = create(connection, State.RUNNING);
            JustReplay.LOGGER.info("Recording started automatically into {}", active.dir);
        } catch (IOException e) {
            JustReplay.LOGGER.error("Cannot start recording", e);
        }
    }

    /** Starts a recording in the middle of a session: the current client state is written first. */
    public static boolean startManual() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (active != null || listener == null || mc.level == null || mc.player == null || ReplaySession.current() != null) {
            return false;
        }
        if (lastLogin == null) {
            JustReplay.LOGGER.warn("No login packet seen on this connection, cannot snapshot the world");
            return false;
        }
        try {
            Recorder recorder = create(listener.getConnection(), State.STARTING);
            active = recorder;
            synchronized (recorder) {
                recorder.codec = PacketCodec.of(listener);
                recorder.writeSnapshot(mc, listener, recorder::accept);
                for (byte[] data : recorder.pending) {
                    recorder.writer.write(0, data);
                }
                recorder.pending.clear();
                recorder.state = State.RUNNING;
            }
            return true;
        } catch (Throwable t) {
            JustReplay.LOGGER.error("Cannot start recording", t);
            active = null;
            return false;
        }
    }

    public static Path stop() {
        Recorder recorder = active;
        if (recorder == null) {
            return null;
        }
        active = null;
        synchronized (recorder) {
            recorder.state = State.STOPPED;
        }
        recorder.writer.close();
        recorder.meta.durationTicks = recorder.tick;
        ReplayStorage.writeMeta(recorder.dir, recorder.meta);
        JustReplay.LOGGER.info("Recording saved to {} ({} ticks)", recorder.dir, recorder.tick);
        return recorder.dir;
    }

    // ---------------------------------------------------------------- client thread

    /** Called at the end of every client tick. */
    public void clientTick(Minecraft mc) {
        if (state != State.RUNNING) {
            return;
        }
        if (connection.isMemoryConnection() && mc.isPaused()) {
            // A paused singleplayer game sends nothing; do not record the pause as frozen footage.
            return;
        }
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
        }
        tick++;
        if (tick % 200 == 0) {
            // Crash safety: flush the stream and keep the duration in meta.json current.
            writer.flush();
            meta.durationTicks = tick;
            ReplayStorage.writeMeta(dir, meta);
        }
        if (tick % CHECKPOINT_INTERVAL_TICKS == 0) {
            requestCheckpoint(mc);
        }
    }

    // ---------------------------------------------------------------- checkpoints

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
            connection.channel().eventLoop().execute(() -> {
                int at;
                synchronized (this) {
                    if (state != State.RUNNING) {
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

    /** Snapshots the client state into {@code checkpoints/<tick>.bin.gz}; never touches the main stream. */
    private void writeCheckpoint(Minecraft mc, int at) {
        try {
            ClientPacketListener listener = mc.getConnection();
            if (state != State.RUNNING || lastLogin == null || listener == null || listener.getConnection() != connection
                    || mc.level == null || mc.player == null) {
                return;
            }
            List<byte[]> records = new ArrayList<>();
            Consumer<byte[]> sink = records::add;
            writeSnapshot(mc, listener, sink);
            writeActorState(listener, mc.player, sink);
            Util.ioPool().execute(() -> {
                try {
                    Checkpoints.write(dir, at, records);
                } catch (Throwable t) {
                    JustReplay.LOGGER.warn("Cannot write the replay checkpoint at tick {}: {}", at, t.toString());
                }
            });
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot take a replay checkpoint at tick {}: {}", at, t.toString());
        } finally {
            checkpointPending = false;
        }
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
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            equipment.add(Pair.of(slot, player.getItemBySlot(slot).copy()));
        }
        write(listener, new ClientboundSetEquipmentPacket(player.getId(), equipment), sink);
    }

    // ---------------------------------------------------------------- packet writing

    private void write(ClientPacketListener listener, Packet<?> packet) {
        write(listener, packet, this::accept);
    }

    private void write(ClientPacketListener listener, Packet<?> packet, Consumer<byte[]> sink) {
        PacketCodec c = codec;
        if (c == null || !c.matches(listener)) {
            c = codec = PacketCodec.of(listener);
        }
        try {
            c.encode(packet, sink);
        } catch (Throwable t) {
            if (failedTypes.add(packet.getClass())) {
                JustReplay.LOGGER.warn("Cannot record synthetic packet {}: {}", packet.getClass().getName(), t.toString());
            }
        }
    }

    private void recordLocalPlayer(Minecraft mc, ClientPacketListener listener, LocalPlayer player) {
        boolean respawned = mc.level != lastLevel || player.getId() != lastEntityId || player != lastPlayer;
        if (respawned) {
            lastPlayer = player;
            lastLevel = mc.level;
            lastEntityId = player.getId();
            lastData = null;
            java.util.Arrays.fill(lastEquipment, null);
            writeActorSpawn(listener, player, this::accept);
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

        List<SynchedEntityData.DataValue<?>> data = player.getEntityData().getNonDefaultValues();
        if (data != null && !data.equals(lastData)) {
            write(listener, new ClientboundSetEntityDataPacket(player.getId(), data));
            lastData = data;
        }

        boolean equipmentChanged = false;
        List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            ItemStack previous = lastEquipment[slot.ordinal()];
            if (previous == null || !ItemStack.matches(previous, stack)) {
                equipmentChanged = true;
                lastEquipment[slot.ordinal()] = stack.copy();
            }
            equipment.add(Pair.of(slot, stack.copy()));
        }
        if (equipmentChanged) {
            write(listener, new ClientboundSetEquipmentPacket(player.getId(), equipment));
        }

        boolean swingStarted = player.swinging && (!lastSwinging || player.swingTime < lastSwingTime);
        if (swingStarted) {
            write(listener, new ClientboundAnimatePacket(player, player.swingingArm == InteractionHand.MAIN_HAND ? 0 : 3));
        }
        lastSwinging = player.swinging;
        lastSwingTime = player.swingTime;
    }

    /** Tab list entry and entity of the actor standing for the recording player (vanilla never sends it its own). */
    private void writeActorSpawn(ClientPacketListener listener, LocalPlayer player, Consumer<byte[]> sink) {
        GameProfile source = player.getGameProfile();
        PlayerInfo info = listener.getPlayerInfo(source.getId());
        GameProfile actorProfile = new GameProfile(actorUuid, source.getName());
        actorProfile.getProperties().putAll((info != null ? info.getProfile() : source).getProperties());
        write(listener, PlayerInfoPackets.build(listener.registryAccess(), listener.getConnectionType(), List.of(
                new PlayerInfoPackets.Info(actorUuid, actorProfile, GameType.SURVIVAL, false, 0, null))), sink);
        write(listener, new ClientboundAddEntityPacket(player.getId(), actorUuid, player.getX(), player.getY(), player.getZ(),
                player.getXRot(), player.getYRot(), player.getType(), 0, Vec3.ZERO, player.getYHeadRot()), sink);
        if (player.getVehicle() != null) {
            write(listener, new ClientboundSetPassengersPacket(player.getVehicle()), sink);
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
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        ClientboundLoginPacket login = lastLogin;
        CommonPlayerSpawnInfo spawn = login.commonPlayerSpawnInfo();
        GameType mode = mc.gameMode != null ? mc.gameMode.getPlayerMode() : spawn.gameType();
        write(listener, new ClientboundLoginPacket(player.getId(), login.hardcore(), listener.levels(), login.maxPlayers(),
                login.chunkRadius(), login.simulationDistance(), login.reducedDebugInfo(), login.showDeathScreen(),
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
            write(listener, PlayerInfoPackets.build(listener.registryAccess(), listener.getConnectionType(), infos), sink);
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

        int radius = login.chunkRadius() + 1;
        int cx = player.chunkPosition().x;
        int cz = player.chunkPosition().z;
        write(listener, new ClientboundSetChunkCacheRadiusPacket(login.chunkRadius()), sink);
        write(listener, new ClientboundSetChunkCacheCenterPacket(cx, cz), sink);
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null && !chunk.isEmpty()) {
                    try {
                        write(listener, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null), sink);
                    } catch (Throwable t) {
                        JustReplay.LOGGER.warn("Skipping chunk {} in the snapshot: {}", chunk.getPos(), t.toString());
                    }
                }
            }
        }
        write(listener, new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F), sink);

        List<Entity> withPassengers = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == player) {
                continue;
            }
            try {
                writeEntitySnapshot(listener, entity, withPassengers, sink);
            } catch (Throwable t) {
                JustReplay.LOGGER.warn("Skipping entity {} in the snapshot: {}", entity, t.toString());
            }
        }
        for (Entity vehicle : withPassengers) {
            write(listener, new ClientboundSetPassengersPacket(vehicle), sink);
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

    private void writeEntitySnapshot(ClientPacketListener listener, Entity entity, List<Entity> withPassengers, Consumer<byte[]> sink) {
        List<Packet<?>> original = SPAWNS.get(entity.getId());
        if (original != null) {
            // The server's own spawn packets carry what client state cannot rebuild (spawn data, NeoForge payloads).
            for (Packet<?> spawnPacket : original) {
                write(listener, spawnPacket, sink);
            }
            write(listener, new ClientboundTeleportEntityPacket(entity), sink);
        } else {
            write(listener, new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
                    entity.getXRot(), entity.getYRot(), entity.getType(), spawnData(entity), entity.getDeltaMovement(), entity.getYHeadRot()), sink);
            if (entity instanceof net.neoforged.neoforge.entity.IEntityWithComplexSpawn) {
                try {
                    write(listener, new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                            new net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload(entity)), sink);
                } catch (Throwable t) {
                    JustReplay.LOGGER.debug("No spawn data for {}", entity, t);
                }
            }
        }
        List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
        if (data != null) {
            write(listener, new ClientboundSetEntityDataPacket(entity.getId(), data), sink);
        }
        if (entity instanceof LivingEntity living) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    equipment.add(Pair.of(slot, stack.copy()));
                }
            }
            if (!equipment.isEmpty()) {
                write(listener, new ClientboundSetEquipmentPacket(entity.getId(), equipment), sink);
            }
        }
        if (!entity.getPassengers().isEmpty()) {
            withPassengers.add(entity);
        }
    }
}
