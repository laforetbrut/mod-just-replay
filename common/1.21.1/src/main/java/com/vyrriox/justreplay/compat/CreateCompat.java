package com.vyrriox.justreplay.compat;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import com.vyrriox.justreplay.editor.ArmorSlots;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

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
 *     <li>A backtank worn in the chest slot (and what extends it, such as the jetpack of Create Jetpack) has its tank
 *     drawn by a layer of Create's own, which the hidden armor hooks of the vanilla layers never see: an optional mixin
 *     skips that layer while the replay hides the chestplate of the entity drawn.</li>
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

    private static int backtankSkipped;

    private CreateCompat() {
    }

    /**
     * Render hook of the tank Create draws for a worn backtank: true to skip it, when the running replay hides this
     * entity's chestplate.
     */
    public static boolean hidesBacktank(LivingEntity entity) {
        return Integrations.CREATE.on() && Integrations.CREATE.test("hidesBacktank", () -> {
            if (!ArmorSlots.hides(entity, EquipmentSlot.CHEST)) {
                return false;
            }
            backtankSkipped++;
            ArmorSlots.countSkipped();
            return true;
        });
    }

    /** How many times the tank of a backtank was skipped since the game started (self test). */
    public static int backtankSkipped() {
        return backtankSkipped;
    }

    /** True for a packet a snapshot must replay because the server sent it only once (see the class comment). */
    public static boolean isPersistent(Packet<?> packet) {
        return packet instanceof ClientboundCustomPayloadPacket custom && PERSISTENT.contains(custom.payload().type().id());
    }
}
