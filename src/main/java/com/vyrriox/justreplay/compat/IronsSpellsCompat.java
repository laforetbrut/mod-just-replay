package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Iron's Spells 'n Spellbooks casts in recordings and replays.
 * <ul>
 *     <li>Cast starts and ends name their caster by UUID and start its player animation. Those of the recording player
 *     are written for its actor's UUID; the packet the live client handles is untouched.</li>
 *     <li>Payloads aimed at the receiving client's own player (cast bar, client cast effects, impulses, mana, cooldowns,
 *     targeting...) would act on the replay camera and are not replayed.</li>
 *     <li>A player's synced spell data is keyed by entity id, shared with the actor, in a static map no connection ever
 *     clears. Snapshots and checkpoints write the current data, and the map is emptied whenever the world is rebuilt.</li>
 *     <li>Spell casting mobs hold their data on the entity: snapshots write it after their spawn.</li>
 *     <li>A seek drops cast starts and ends older than {@link #SKIPPED_CAST_LIMIT}: a longer cast cut by a seek has its
 *     casting state back but no animation until its next change.</li>
 *     <li>Serverbound payloads are dropped during a replay.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads an Iron's class when the mod is absent.
 *
 * @author vyrriox
 */
public final class IronsSpellsCompat {
    private static final String NAMESPACE = "irons_spellbooks";
    private static final String NET = "io.redspace.ironsspellbooks.network.";
    /** In ticks. Beyond this a cast animation caught by a seek has most likely ended at the target. */
    private static final int SKIPPED_CAST_LIMIT = 40;
    /** Payloads that act on the receiving client's own player. */
    private static final Set<String> LOCAL_ONLY = Set.of(
            NET + "casting.UpdateCastingStatePacket", NET + "casting.OnClientCastPacket", NET + "SyncManaPacket",
            NET + "casting.SyncCooldownPacket", NET + "casting.SyncCooldownsPacket", NET + "casting.SyncRecastPacket",
            NET + "casting.SyncRecastsPacket", NET + "casting.RemoveRecastPacket", NET + "casting.SyncTargetingDataPacket",
            NET + "casting.CastErrorPacket", NET + "AddMotionToPlayerPacket", NET + "OpenEldritchScreenPacket",
            NET + "EquipmentChangedPacket");

    private static Reflect reflect;
    private static boolean reflectFailed;
    /** Cast starts and ends of the recording player written for its actor. */
    private static final AtomicInteger LOCAL_CASTS = new AtomicInteger();

    private IronsSpellsCompat() {
    }

    private record Reflect(Class<?> started, Field startedSpell, Field startedLevel, Field startedCaster, Constructor<?> startedNew,
                           Class<?> finished, Field finishedSpell, Field finishedCaster, Field finishedCancelled,
                           Constructor<?> finishedNew, Method syncedOf, Method dataOwner, Method dataCasting,
                           Constructor<?> playerPacketNew, Class<?> magicEntity, Constructor<?> entityPacketNew,
                           Field lookup, Method clientCasting, Method resetCast) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.IRONS_SPELLS) {
            return reflect;
        }
        try {
            ClassLoader loader = IronsSpellsCompat.class.getClassLoader();
            Class<?> started = Class.forName(NET + "casting.OnCastStartedPacket", false, loader);
            Class<?> finished = Class.forName(NET + "casting.OnCastFinishedPacket", false, loader);
            Class<?> playerPacket = Class.forName(NET + "casting.SyncPlayerDataPacket", false, loader);
            Class<?> entityPacket = Class.forName(NET + "casting.SyncEntityDataPacket", false, loader);
            Class<?> synced = Class.forName("io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData", false, loader);
            Class<?> magicEntity = Class.forName("io.redspace.ironsspellbooks.api.entity.IMagicEntity", false, loader);
            Class<?> client = Class.forName("io.redspace.ironsspellbooks.player.ClientMagicData", false, loader);
            Field startedSpell = started.getDeclaredField("spellId");
            Field startedLevel = started.getDeclaredField("spellLevel");
            Field startedCaster = started.getDeclaredField("castingEntityId");
            Field finishedSpell = finished.getDeclaredField("spellId");
            Field finishedCaster = finished.getDeclaredField("castingEntityId");
            Field finishedCancelled = finished.getDeclaredField("cancelled");
            Field lookup = client.getDeclaredField("playerSyncedDataLookup");
            for (Field field : new Field[]{startedSpell, startedLevel, startedCaster, finishedSpell, finishedCaster, finishedCancelled, lookup}) {
                field.setAccessible(true);
            }
            reflect = new Reflect(started, startedSpell, startedLevel, startedCaster,
                    started.getConstructor(UUID.class, String.class, int.class),
                    finished, finishedSpell, finishedCaster, finishedCancelled,
                    finished.getConstructor(UUID.class, String.class, boolean.class),
                    client.getMethod("getSyncedSpellData", LivingEntity.class), synced.getMethod("getServerPlayerId"),
                    synced.getMethod("isCasting"), playerPacket.getConstructor(synced), magicEntity,
                    entityPacket.getConstructor(synced, magicEntity), lookup, client.getMethod("isCasting"),
                    client.getMethod("resetClientCastState", UUID.class));
            JustReplay.LOGGER.info("Iron's Spellbooks detected: the local player's casts are recorded for its actor");
        } catch (Throwable t) {
            reflectFailed = true;
            reflect = null;
            ReplayCompat.warnOnce("irons.reflect", "Iron's Spellbooks support disabled, its classes changed", t);
        }
        return reflect;
    }

    private static boolean isIrons(CustomPacketPayload payload) {
        return NAMESPACE.equals(payload.type().id().getNamespace());
    }

    // ---------------------------------------------------------------- recording

    /** See {@link ReplayCompat#rewriteRecorded}: cast starts and ends of the recording player name its actor. */
    static Packet<?> rewriteRecorded(Packet<?> packet, UUID localUuid, UUID actorUuid) throws Exception {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !isIrons(custom.payload())) {
            return packet;
        }
        Reflect r = reflect();
        if (r == null) {
            return packet;
        }
        CustomPacketPayload payload = custom.payload();
        if (r.started.isInstance(payload) && localUuid.equals(r.startedCaster.get(payload))) {
            LOCAL_CASTS.incrementAndGet();
            return new ClientboundCustomPayloadPacket((CustomPacketPayload) r.startedNew.newInstance(actorUuid,
                    r.startedSpell.get(payload), r.startedLevel.get(payload)));
        }
        if (r.finished.isInstance(payload) && localUuid.equals(r.finishedCaster.get(payload))) {
            LOCAL_CASTS.incrementAndGet();
            return new ClientboundCustomPayloadPacket((CustomPacketPayload) r.finishedNew.newInstance(actorUuid,
                    r.finishedSpell.get(payload), r.finishedCancelled.get(payload)));
        }
        return packet;
    }

    /**
     * A player's synced spell data now (snapshots, checkpoints, the actor's spawn). The data carries the player's entity
     * id, which the actor keeps. A player nothing was synced for reads as Iron's default entry and is skipped.
     */
    static void writeCurrent(Player player, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || player == null) {
            return;
        }
        try {
            Object data = r.syncedOf.invoke(null, player);
            if (data != null && (int) r.dataOwner.invoke(data) == player.getId()) {
                out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.playerPacketNew.newInstance(data)));
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("irons.snapshot", "Cannot record the spell data of " + player.getName().getString(), t);
        }
    }

    /** The spell data of a casting mob, written after its spawn. */
    static void writeCurrent(Entity entity, Consumer<Packet<?>> out) {
        Reflect r = reflect();
        if (r == null || !r.magicEntity.isInstance(entity)) {
            return;
        }
        try {
            Object data = r.syncedOf.invoke(null, entity);
            if (data != null) {
                out.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.entityPacketNew.newInstance(data, entity)));
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("irons.entity", "Cannot record the spell data of a casting mob", t);
        }
    }

    // ---------------------------------------------------------------- replay

    /** Payloads aimed at the receiving client's own player would act on the camera. */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket custom && isIrons(custom.payload())
                && LOCAL_ONLY.contains(custom.payload().getClass().getName())) {
            return null;
        }
        return packet;
    }

    /** See {@link ReplayCompat#adjustSkipped}: a cast start or end long before the seek target is dropped. */
    static Packet<?> adjustSkipped(Packet<?> packet, int late) {
        if (late < SKIPPED_CAST_LIMIT || !(packet instanceof ClientboundCustomPayloadPacket custom) || !isIrons(custom.payload())) {
            return packet;
        }
        Reflect r = reflect();
        if (r != null && (r.started.isInstance(custom.payload()) || r.finished.isInstance(custom.payload()))) {
            return null;
        }
        return packet;
    }

    /** Iron's requests to the replay host (casts, cancels, selections) have no server to go to. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom && isIrons(custom.payload());
    }

    /**
     * The replay world is about to be rebuilt, started or left: forgets the data of every player, which nothing else
     * clears, and any cast state of the client's own player.
     */
    static void clearState() {
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        try {
            ((Map<?, ?>) r.lookup.get(null)).clear();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && (boolean) r.clientCasting.invoke(null)) {
                r.resetCast.invoke(null, mc.player.getUUID());
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("irons.clear", "Cannot reset the Iron's Spellbooks client state", t);
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /** Cast starts and ends of the recording player written for its actor since the game started. */
    public static int localCasts() {
        return LOCAL_CASTS.get();
    }

    /** True when an entity's synced spell data says it is casting. */
    public static boolean isCasting(Entity entity) {
        Reflect r = reflect();
        if (r == null || !(entity instanceof LivingEntity)) {
            return false;
        }
        try {
            Object data = r.syncedOf.invoke(null, entity);
            return data != null && (boolean) r.dataCasting.invoke(data);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the client's own player is in a cast. */
    public static boolean clientCasting() {
        Reflect r = reflect();
        try {
            return r != null && (boolean) r.clientCasting.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when Iron's animation layer of a player is playing something. */
    public static boolean animating(Entity entity) {
        if (reflect() == null || !(entity instanceof AbstractClientPlayer player)) {
            return false;
        }
        try {
            ClassLoader loader = IronsSpellsCompat.class.getClassLoader();
            ResourceLocation id = (ResourceLocation) Class.forName("io.redspace.ironsspellbooks.api.spells.SpellAnimations", false, loader)
                    .getField("ANIMATION_RESOURCE").get(null);
            Class<?> access = Class.forName("dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess", false, loader);
            Object data = access.getMethod("getPlayerAssociatedData", AbstractClientPlayer.class).invoke(null, player);
            Object layer = data.getClass().getMethod("get", ResourceLocation.class).invoke(data, id);
            if (layer == null) {
                return false;
            }
            Object animation = Class.forName("dev.kosmx.playerAnim.api.layered.ModifierLayer", false, loader)
                    .getMethod("getAnimation").invoke(layer);
            return animation != null && (boolean) Class.forName("dev.kosmx.playerAnim.api.layered.IAnimation", false, loader)
                    .getMethod("isActive").invoke(animation);
        } catch (Throwable t) {
            JustReplay.LOGGER.warn("Cannot read the Iron's Spellbooks animation layer: {}", t.toString());
            return false;
        }
    }
}
