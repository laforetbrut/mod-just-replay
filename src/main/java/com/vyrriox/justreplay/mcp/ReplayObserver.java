package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.editor.ActorManager;
import com.vyrriox.justreplay.editor.CameraRig;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.export.FrameGrab;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import com.vyrriox.justreplay.replay.WorldOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The replay world as the observation tools of {@code core} see it. Every method runs on the client thread. An
 * entity of the recording is given where the recording last put it (the position its packets carried), not where
 * the game is easing it to; an actor where the editor poses it.
 *
 * @author vyrriox
 */
final class ReplayObserver implements McpObserver {
    @Override
    public Replay replay() {
        ReplaySession session = ReplaySession.current();
        if (session == null || !ReplaySession.isRunning()) {
            return null;
        }
        Playback playback = session.playback();
        return new Replay(session.dir().getFileName().toString(), session.meta(), session.project(), playback.cursor(),
                playback.duration(), playback.isPaused(), playback.speed(), VideoExporter.isActive() || FrameGrab.isActive());
    }

    @Override
    public int seek(int tick) {
        ReplaySession session = ReplaySession.current();
        if (session == null) {
            return 0;
        }
        Playback playback = session.playback();
        if (tick != playback.cursor()) {
            playback.seek(tick);
            // Actors are posed at each client tick; they must stand where the new play head puts them now.
            session.actors().tick(Minecraft.getInstance());
        }
        return playback.cursor();
    }

    @Override
    public List<McpEntity> entities() {
        Minecraft mc = Minecraft.getInstance();
        List<McpEntity> out = new ArrayList<>();
        ClientLevel level = mc.level;
        ReplaySession session = ReplaySession.current();
        if (level == null || session == null) {
            return out;
        }
        Map<Entity, String> actors = actors(session);
        Entity recorder = session.findRecorder(mc);
        for (Entity e : level.entitiesForRendering()) {
            if (e == mc.player) {
                continue;
            }
            out.add(describe(e, actors.get(e), e == recorder));
        }
        return out;
    }

    @Override
    public McpEntity entity(String key) {
        for (McpEntity e : entities()) {
            if (e.matches(key)) {
                return e;
            }
        }
        return null;
    }

    private static Map<Entity, String> actors(ReplaySession session) {
        Map<Entity, String> out = new IdentityHashMap<>();
        for (ReplayProject.ActorData actor : session.project().actors) {
            Entity e = session.actors().entityOf(actor);
            if (e != null) {
                out.put(e, actor.id);
            }
        }
        return out;
    }

    private static McpEntity describe(Entity e, String actorId, boolean recorder) {
        McpEntity m = new McpEntity();
        boolean actor = actorId != null || ActorManager.isActorEntity(e);
        m.id = e.getId();
        m.uuid = e.getUUID().toString();
        m.type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        m.name = e.getName().getString();
        m.customName = e.getCustomName() == null ? "" : e.getCustomName().getString();
        m.source = actor ? McpEntity.ACTOR : McpEntity.RECORDED;
        m.actorId = actorId == null ? "" : actorId;
        m.recorder = recorder;
        m.player = e instanceof net.minecraft.world.entity.player.Player;
        Vec3 at = position(e, actor);
        m.x = at.x;
        m.y = at.y;
        m.z = at.z;
        // An entity eases to the rotation the recording gave: that one counts, not where it is on the way.
        net.minecraft.world.entity.InterpolationHandler easing = actor ? null : e.getInterpolation();
        boolean eased = easing != null && easing.hasActiveInterpolation();
        m.yaw = eased ? easing.yRot() : e.getYRot();
        m.pitch = eased ? easing.xRot() : e.getXRot();
        m.headYaw = e.getYHeadRot();
        // What the game moved it by on its last tick; the tools replace it with the recorded motion when they can.
        m.vx = e.getX() - e.xo;
        m.vy = e.getY() - e.yo;
        m.vz = e.getZ() - e.zo;
        m.pose = e.getPose().name();
        m.onGround = e.onGround();
        m.sneaking = e.isShiftKeyDown();
        m.sprinting = e.isSprinting();
        m.swimming = e.isSwimming();
        m.invisible = e.isInvisible();
        m.glowing = e.isCurrentlyGlowing();
        m.onFire = e.isOnFire();
        m.width = e.getBbWidth();
        m.height = e.getBbHeight();
        m.eyeHeight = e.getEyeHeight();
        if (e instanceof LivingEntity living) {
            m.living = true;
            m.health = living.getHealth();
            m.maxHealth = living.getMaxHealth();
            m.baby = living.isBaby();
            m.sleeping = living.isSleeping();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    m.equipment.add(item(slot.getName(), stack));
                }
            }
            // Hats, backpacks, wings and the like worn in the slots of a mod (Curios).
            com.vyrriox.justreplay.compat.ReplayCompat.describeWorn(living, (slot, stack) -> m.equipment.add(item(slot, stack)));
        }
        Entity vehicle = e.getVehicle();
        m.vehicle = vehicle == null ? McpEntity.NONE : vehicle.getId();
        for (Entity passenger : e.getPassengers()) {
            m.passengers.add(passenger.getId());
        }
        return m;
    }

    private static McpEntity.Item item(String slot, ItemStack stack) {
        return new McpEntity.Item(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(),
                stack.getHoverName().getString());
    }

    /**
     * Where the recording puts an entity at the play head: the position its last packet carried, which is where the
     * game eases it to. A teleport packet no longer moves the base of the position codec in this version (the
     * recording player is moved that way), so the base is not read. Riders and actors have no position of their
     * own: their vehicle or the editor places them.
     */
    private static Vec3 position(Entity e, boolean actor) {
        if (!actor && !e.isPassenger()) {
            net.minecraft.world.entity.InterpolationHandler easing = e.getInterpolation();
            if (easing != null && easing.hasActiveInterpolation()) {
                return easing.position();
            }
        }
        return e.position();
    }

    @Override
    public World world() {
        Minecraft mc = Minecraft.getInstance();
        World w = new World();
        ClientLevel level = mc.level;
        if (level == null) {
            return w;
        }
        w.dimension = level.dimension().identifier().toString();
        w.dayTime = level.getDefaultClockTime();
        w.recordedDayTime = WorldOverride.recordedDay();
        w.gameTime = level.getGameTime();
        w.raining = level.isRaining();
        w.thundering = level.isThundering();
        w.rainLevel = level.getRainLevel(1.0F);
        w.thunderLevel = level.getThunderLevel(1.0F);
        w.difficulty = level.getDifficulty().getSerializedName();
        w.entities = Math.max(0, level.getEntityCount() - (mc.player == null ? 0 : 1));
        w.loadedChunks = level.getChunkSource().getLoadedChunksCount();
        int players = 0;
        for (net.minecraft.world.entity.player.Player p : level.players()) {
            if (p != mc.player) {
                players++;
            }
        }
        w.players = players;
        return w;
    }

    @Override
    public Block block(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        Block b = new Block();
        ClientLevel level = mc.level;
        BlockPos pos = new BlockPos(x, y, z);
        if (level == null || level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) {
            return b;
        }
        b.loaded = true;
        BlockState state = level.getBlockState(pos);
        b.id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        b.air = state.isAir();
        Map<String, String> properties = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            properties.put(property.getName(), value(state, property));
        }
        b.properties = properties;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            b.fluid = BuiltInRegistries.FLUID.getKey(fluid.getType()).toString();
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            b.blockEntity = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));
        }
        b.biome = level.getBiome(pos).unwrapKey().map(key -> key.identifier().toString()).orElse("");
        b.blockLight = level.getBrightness(LightLayer.BLOCK, pos);
        b.skyLight = level.getBrightness(LightLayer.SKY, pos);
        return b;
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    @Override
    public List<Player> tabList() {
        Minecraft mc = Minecraft.getInstance();
        List<Player> out = new ArrayList<>();
        if (mc.getConnection() == null) {
            return out;
        }
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            if (mc.player != null && info.getProfile().id().equals(mc.player.getUUID())) {
                continue;
            }
            out.add(new Player(info.getProfile().name(), info.getProfile().id().toString(),
                    info.getGameMode() == null ? "" : info.getGameMode().getName(), info.getLatency()));
        }
        return out;
    }

    @Override
    public McpCamera currentCamera() {
        return FrameGrab.camera(Minecraft.getInstance(), "current");
    }

    @Override
    public McpCamera projectCamera(int tick) {
        ReplaySession session = ReplaySession.current();
        if (session == null) {
            return null;
        }
        double[] s = CameraRig.sampleProject(session.project(), tick, 0.0F, false);
        return s == null ? null : new McpCamera(s[0], s[1] + CameraRig.EYE_HEIGHT, s[2], s[3], s[4], s[5], s[6], "project");
    }

    @Override
    public int[] windowSize() {
        Minecraft mc = Minecraft.getInstance();
        return new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()};
    }

    @Override
    public CompletableFuture<Frame> render(FrameRequest request) throws McpArgs.Invalid {
        return FrameGrab.start(request);
    }
}
