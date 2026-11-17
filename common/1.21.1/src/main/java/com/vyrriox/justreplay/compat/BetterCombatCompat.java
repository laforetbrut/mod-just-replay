package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Better Combat attack animations in recordings and replays.
 * <ul>
 *     <li>A client that starts (or cancels) an attack plays its animation and sends it to the server
 *     ({@code bettercombat:attack_animation}); the server sends it on to the players tracking the attacker, with the
 *     attacker's entity id, and their clients play it on that player. Attacks of other players are recorded and
 *     replayed as they are.</li>
 *     <li>The server never sends an attack back to its attacker: the recorder copies the one the client sends, as
 *     another client receives it. The actor keeps the recording player's entity id, so the copy needs no mapping.</li>
 *     <li>Attacks are one-shots: an attack already over at a seek target is dropped.</li>
 *     <li>Weapon poses follow the held item and need nothing recorded. Pause and speed are handled by the player
 *     animation library mixins (see {@code PlayerAnimStackMixin}).</li>
 *     <li>During a replay, Better Combat's requests to the replay host are dropped.</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads a Better Combat class when it is absent.
 *
 * @author vyrriox
 */
public final class BetterCombatCompat {
    private static final String NAMESPACE = "bettercombat";
    private static final ResourceLocation ATTACK_ANIMATION = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "attack_animation");
    private static Reflect reflect;
    private static boolean reflectFailed;
    /** Attacks (and cancelled attacks) of the recording player written for its actor. */
    private static final AtomicInteger LOCAL_ATTACKS = new AtomicInteger();
    private static final AtomicInteger LOCAL_STOPS = new AtomicInteger();
    /** Attack animations handled by the running replay, and those of the recording player's actor. */
    private static final AtomicInteger REPLAYED = new AtomicInteger();
    private static final AtomicInteger REPLAYED_ACTOR = new AtomicInteger();

    private BetterCombatCompat() {
    }

    private record Reflect(Class<?> attackAnimation, Constructor<?> create, Method[] accessors, int playerIdIndex,
                           Method playerId, Method animationName, Method length, String stopSymbol) {
    }

    private static Reflect reflect() {
        if (reflect != null || reflectFailed || !ReplayCompat.BETTER_COMBAT) {
            return reflect;
        }
        try {
            Class<?> attack = Class.forName("net.bettercombat.network.Packets$AttackAnimation", false,
                    BetterCombatCompat.class.getClassLoader());
            RecordComponent[] components = attack.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Method[] accessors = new Method[components.length];
            int playerIdIndex = -1;
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                accessors[i] = components[i].getAccessor();
                if ("playerId".equals(components[i].getName()) && types[i] == int.class) {
                    playerIdIndex = i;
                }
            }
            if (playerIdIndex < 0) {
                throw new IllegalStateException("no player id in the attack animation");
            }
            Field stop = attack.getField("StopSymbol");
            reflect = new Reflect(attack, attack.getDeclaredConstructor(types), accessors, playerIdIndex,
                    attack.getMethod("playerId"), attack.getMethod("animationName"), attack.getMethod("length"),
                    (String) stop.get(null));
            JustReplay.LOGGER.info("Better Combat detected: the local player's attack animations are recorded for its actor");
        } catch (Throwable t) {
            reflectFailed = true;
            ReplayCompat.warnOnce("bettercombat.reflect", "Better Combat support disabled, its classes changed", t);
        }
        return reflect;
    }

    private static boolean isAttack(CustomPacketPayload payload) {
        return ATTACK_ANIMATION.equals(payload.type().id());
    }

    // ---------------------------------------------------------------- recording

    /** See {@link ReplayCompat#onLiveServerbound}: an attack the local player sends is recorded for its actor. */
    static void onLiveServerbound(Packet<?> packet) throws Exception {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !isAttack(custom.payload())
                || Recorder.capturing() == null) {
            return;
        }
        Reflect r = reflect();
        if (r == null || !r.attackAnimation.isInstance(custom.payload())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            record(r, mc, custom.payload());
        } else {
            mc.execute(() -> {
                try {
                    record(r, mc, custom.payload());
                } catch (Throwable t) {
                    ReplayCompat.warnOnce("bettercombat.serverbound", "Cannot record a Better Combat attack", t);
                }
            });
        }
    }

    /** Writes the attack as the server sends it to the players tracking the attacker (its entity id, set by the server). */
    private static void record(Reflect r, Minecraft mc, CustomPacketPayload payload) throws Exception {
        LocalPlayer player = mc.player;
        if (player == null || ReplaySession.isRunning()) {
            return;
        }
        Object[] args = new Object[r.accessors.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = r.accessors[i].invoke(payload);
        }
        args[r.playerIdIndex] = player.getId();
        Recorder.recordLocalEffect(new ClientboundCustomPayloadPacket((CustomPacketPayload) r.create.newInstance(args)));
        if (r.stopSymbol.equals(r.animationName.invoke(payload))) {
            LOCAL_STOPS.incrementAndGet();
        } else {
            LOCAL_ATTACKS.incrementAndGet();
        }
    }

    // ---------------------------------------------------------------- replay

    static void onReplayStart() {
        REPLAYED.set(0);
        REPLAYED_ACTOR.set(0);
    }

    /** A recorded packet handled by the replay: attack animations are counted for the diagnostics. */
    static void onRecorded(Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !isAttack(custom.payload())) {
            return;
        }
        Reflect r = reflect();
        if (r == null || !r.attackAnimation.isInstance(custom.payload())) {
            return;
        }
        REPLAYED.incrementAndGet();
        try {
            ReplaySession session = ReplaySession.current();
            Entity recorder = session == null ? null : session.findRecorder(Minecraft.getInstance());
            if (recorder != null && recorder.getId() == (int) r.playerId.invoke(custom.payload())) {
                REPLAYED_ACTOR.incrementAndGet();
            }
        } catch (Throwable t) {
            ReplayCompat.warnOnce("bettercombat.count", "Cannot read a replayed Better Combat attack", t);
        }
    }

    /** See {@link ReplayCompat#adjustSkipped}: an attack over before the seek target is dropped. */
    static Packet<?> adjustSkipped(Packet<?> packet, int late) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !isAttack(custom.payload())) {
            return packet;
        }
        Reflect r = reflect();
        if (r == null || !r.attackAnimation.isInstance(custom.payload())) {
            return packet;
        }
        try {
            float length = (float) r.length.invoke(custom.payload());
            return late >= Math.max(1.0F, length) ? null : packet;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Better Combat's requests to the replay host (attacks, hits) have no server to go to. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom && NAMESPACE.equals(custom.payload().type().id().getNamespace());
    }

    // ---------------------------------------------------------------- diagnostics

    /** Attacks of the recording player written for its actor since the game started. */
    public static int localAttacks() {
        return LOCAL_ATTACKS.get();
    }

    /** Cancelled attacks among the recorded ones. */
    public static int localStops() {
        return LOCAL_STOPS.get();
    }

    /** Attack animations handled by the running replay ("all/actor"). */
    public static String replayed() {
        return REPLAYED.get() + "/" + REPLAYED_ACTOR.get();
    }

    /** "attacking" while a player plays an attack animation, "idle", or "n/a" without Better Combat. */
    public static String describe(Entity entity) {
        if (reflect() == null || entity == null) {
            return "n/a";
        }
        try {
            for (Class<?> type = entity.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!field.getType().getName().equals("net.bettercombat.client.animation.AttackAnimationSubStack")) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object stack = field.get(entity);
                    Object layer = stack == null ? null : stack.getClass().getField("base").get(stack);
                    if (layer == null) {
                        return "idle";
                    }
                    return (boolean) layer.getClass().getMethod("isActive").invoke(layer) ? "attacking" : "idle";
                }
            }
            return "n/a";
        } catch (Throwable t) {
            return "error " + (t.getCause() != null ? t.getCause() : t);
        }
    }
}
