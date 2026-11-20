package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.Payloads;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Immersive Vehicles (mod id {@code mts}) vehicles, parts and blocks in recordings and replays, on its Forge 1.20.1
 * build ({@code mcinterface1201}).
 * <ul>
 *     <li>A vehicle (or an Immersive Vehicles block) reaches the client as an empty shell that asks the server for its
 *     data once. A vehicle already in view when the recording starts, or one rebuilt by a seek, never gets an answer in
 *     a replay: snapshots and checkpoints write its data as the server answers that request.</li>
 *     <li>Immersive Vehicles draws everything it has through one invisible entity that follows the local player, and
 *     draws only when that entity follows the player of this client. In a replay the recorded one follows the
 *     recording player, or nobody: the replay gets its own, on the client only, that follows the camera player.</li>
 *     <li>Requests to the replay host are blocked: it has none of these vehicles.</li>
 * </ul>
 * Everything is reached by reflection, Forge's channel included: nothing here loads an Immersive Vehicles or a Forge
 * class when the mod is absent, so the Fabric build compiles it too. Only Immersive Vehicles members are named, never a
 * game method, since those carry other names outside development on 1.20.1.
 *
 * @author vyrriox
 */
public final class ImmersiveVehiclesCompat {
    private static final ResourceLocation CHANNEL = new ResourceLocation("mts", "main");
    /** Entity id of the replay's own draw entity, far from server ids and from actor ids. */
    private static final int FORWARDER_ID = -0x4A5201;
    private static Reflect reflect;
    private static boolean reflectFailed;
    private static Entity forwarder;

    private ImmersiveVehiclesCompat() {
    }

    private record Reflect(Class<?> builder, Class<?> tile, Class<?> forwarder, Constructor<?> forwarderNew,
                           Field following, Field loaded, Constructor<?> nbt, Constructor<?> answerEntity,
                           Constructor<?> answerTile, Constructor<?> wrapper, Object channel, Method encode) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !Integrations.IMMERSIVE_VEHICLES.on()) {
            return reflect;
        }
        try {
            ClassLoader loader = ImmersiveVehiclesCompat.class.getClassLoader();
            Class<?> builder = Class.forName("mcinterface1201.ABuilderEntityBase", false, loader);
            Class<?> tile = Class.forName("mcinterface1201.BuilderTileEntity", false, loader);
            Class<?> forwarder = Class.forName("mcinterface1201.BuilderEntityRenderForwarder", false, loader);
            Class<?> nbtWrapper = Class.forName("mcinterface1201.WrapperNBT", false, loader);
            Class<?> nbtApi = Class.forName("minecrafttransportsimulator.mcinterface.IWrapperNBT", false, loader);
            Class<?> answer = Class.forName("mcinterface1201.PacketEntityCSHandshakeServer", false, loader);
            Class<?> packetBase = Class.forName("minecrafttransportsimulator.packets.components.APacketBase", false, loader);
            Class<?> wrapper = Class.forName("mcinterface1201.InterfacePacket$WrapperPacket", false, loader);
            Class<?> network = Class.forName("mcinterface1201.InterfacePacket", false, loader);

            Field following = forwarder.getDeclaredField("playerFollowing");
            following.setAccessible(true);
            Field loaded = builder.getField("loadedFromSavedNBT");
            Constructor<?> nbt = nbtWrapper.getDeclaredConstructor(CompoundTag.class);
            nbt.setAccessible(true);
            // Its Forge SimpleChannel: the channel writes the message index before the message, as the server does.
            Field channelField = network.getDeclaredField("network");
            channelField.setAccessible(true);
            Object channel = channelField.get(null);
            Method encode = channel.getClass().getMethod("encodeMessage", Object.class, FriendlyByteBuf.class);
            reflect = new Reflect(builder, tile, forwarder, forwarder.getConstructor(Player.class), following, loaded, nbt,
                    answer.getConstructor(builder, nbtApi), answer.getConstructor(tile, nbtApi),
                    wrapper.getConstructor(packetBase), channel, encode);
            JustReplay.LOGGER.info("Immersive Vehicles detected: vehicles and their blocks are recorded and replayed");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            Integrations.IMMERSIVE_VEHICLES.fail("reflect", t);
        }
        return reflect;
    }

    private static Packet<?> answer(Reflect r, Constructor<?> answer, Object holder, CompoundTag tag) throws Exception {
        Object data = r.nbt.newInstance(tag);
        FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
        r.encode.invoke(r.channel, r.wrapper.newInstance(answer.newInstance(holder, data)), bytes);
        return Payloads.make(CHANNEL, bytes);
    }

    /** Writes the data of a vehicle, part or seat (snapshots and checkpoints), as the server answers its request. */
    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || entity == null || !r.builder.isInstance(entity) || r.forwarder.isInstance(entity)) {
            return;
        }
        try {
            if (!r.loaded.getBoolean(entity)) {
                // Its data has not arrived yet: the server's answer is still to come, in the stream.
                return;
            }
            out.accept(answer(r, r.answerEntity, entity, entity.saveWithoutId(new CompoundTag())));
        } catch (Throwable t) {
            ReplayCompat.warnOnce("vehicles.snapshot", "Cannot record the Immersive Vehicles data of " + entity, t);
        }
    }

    /** Writes the data of the Immersive Vehicles blocks of a chunk just written into a snapshot or checkpoint. */
    static void writeChunk(LevelChunk chunk, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || chunk == null) {
            return;
        }
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!r.tile.isInstance(blockEntity)) {
                continue;
            }
            try {
                CompoundTag tag = blockEntity.saveWithoutMetadata();
                if (tag.contains("packID")) {
                    out.accept(answer(r, r.answerTile, blockEntity, tag));
                }
            } catch (Throwable t) {
                ReplayCompat.warnOnce("vehicles.block", "Cannot record the Immersive Vehicles block at " + blockEntity.getBlockPos(), t);
            }
        }
    }

    /**
     * Every replay tick: keeps one draw entity that follows the camera player, and stops recorded ones from drawing
     * the vehicles a second time when they name the same player.
     */
    static void tick(Minecraft mc) {
        Reflect r = reflect();
        ClientLevel level = mc.level;
        if (r == null || level == null || mc.player == null) {
            return;
        }
        try {
            if (forwarder == null || forwarder.isRemoved() || forwarder.level() != level
                    || r.following.get(forwarder) != mc.player) {
                if (forwarder != null && !forwarder.isRemoved() && forwarder.level() == level) {
                    forwarder.discard();
                }
                Entity created = (Entity) r.forwarderNew.newInstance(mc.player);
                r.loaded.setBoolean(created, true);
                created.setId(FORWARDER_ID);
                level.putNonPlayerEntity(FORWARDER_ID, created);
                forwarder = created;
            }
            for (Entity entity : level.entitiesForRendering()) {
                if (entity != forwarder && r.forwarder.isInstance(entity) && r.following.get(entity) == mc.player) {
                    r.following.set(entity, null);
                }
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("vehicles.draw", "Cannot draw Immersive Vehicles vehicles in the replay", t);
        }
    }

    /** The replay's own draw entity, or null (self test). */
    public static Entity replayForwarder() {
        return forwarder;
    }

    /** True when the entity is an Immersive Vehicles vehicle or part whose data has arrived on this client. */
    public static boolean hasData(Entity entity) {
        Reflect r = reflect();
        if (r == null || entity == null || !r.builder.isInstance(entity) || r.forwarder.isInstance(entity)) {
            return false;
        }
        try {
            return r.loaded.getBoolean(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the entity is an Immersive Vehicles vehicle, part or seat (not a draw entity). */
    public static boolean isVehicle(Entity entity) {
        Reflect r = reflect();
        return r != null && entity != null && r.builder.isInstance(entity) && !r.forwarder.isInstance(entity);
    }

    static void onReplayEnd() {
        forwarder = null;
    }

    /** Its requests (data handshakes, controls) go to a replay host that has no such vehicle. */
    static boolean blockServerbound(Packet<?> packet) {
        return Payloads.isServerbound(packet) && CHANNEL.equals(Payloads.idOf(packet));
    }
}
