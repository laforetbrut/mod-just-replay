package com.vyrriox.justreplay.compat;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * Create (and its bundled Flywheel and Ponder libraries).
 * <ul>
 *     <li>Kinetic animations (shafts, cogs, belts, fans...) are driven by Ponder's {@code AnimationTickHolder} and by
 *     the level renderer clock read by Flywheel's shaders. Both counters advance on every client tick, paused or not,
 *     so during a replay they are set to the replay cursor (see the compat mixins): they stop with the replay, follow
 *     its speed and show the same angles after a seek and in exports.</li>
 *     <li>The train network (track graphs, edge groups, trains) is sent once, when the player joins, and then updated
 *     incrementally. A recording started later, an instant replay clip or a checkpoint would miss it, so the recorder
 *     keeps those packets for the whole connection and writes them again in every snapshot and checkpoint.</li>
 *     <li>Contraptions are entities with complex spawn data: the recorder's spawn cache already keeps their original
 *     spawn packets for snapshots and checkpoints, and block entity data travels in the chunk packets.</li>
 * </ul>
 *
 * @author vyrriox
 */
public final class CreateCompat {
    /** Create packets whose effect accumulates over the whole connection and that the server never sends again. */
    private static final Set<ResourceLocation> PERSISTENT = Set.of(
            ResourceLocation.fromNamespaceAndPath("create", "sync_rail_graph"),
            ResourceLocation.fromNamespaceAndPath("create", "sync_edge_group"),
            ResourceLocation.fromNamespaceAndPath("create", "add_train"),
            ResourceLocation.fromNamespaceAndPath("create", "remove_train"),
            ResourceLocation.fromNamespaceAndPath("create", "track_graph_roll_call"));

    private CreateCompat() {
    }

    /** True for a packet a snapshot must replay because the server sent it only once (see the class comment). */
    public static boolean isPersistent(Packet<?> packet) {
        return packet instanceof ClientboundCustomPayloadPacket custom && PERSISTENT.contains(custom.payload().type().id());
    }
}
