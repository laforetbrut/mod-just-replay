package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.Payloads;
import com.vyrriox.justreplay.record.Recorder;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Epic Fight (20.x, Forge 1.20.1) animations and battle mode in recordings and replays.
 * <ul>
 *     <li>Custom payloads are raw bytes in 1.20.1: every Epic Fight message rides its one Forge channel
 *     {@code epicfight:network_manager}, its message index first. The indexes follow the mod's registration order, so
 *     they are read from the running mod's channel, and messages are decoded and encoded by the mod's own code.</li>
 *     <li>Animations reach clients as {@code SPAnimatorControl} messages keyed by entity id, for players and patched
 *     mobs alike; living motions (idle, walk... per held weapon) as {@code SPChangeLivingMotion}; a player's mode
 *     (vanilla or battle) as {@code SPModifyPlayerData}. They are recorded and replayed as they are.</li>
 *     <li>What the recording player starts itself (attacks, dodges, the mode key, model yaw, inputs, animation
 *     variables) is played by its client and sent to the server, which forwards it to the players tracking it but not
 *     back to its sender. The recorder copies those requests as the messages the trackers receive, with the local
 *     player's entity id (the actor keeps it). Requests the server answers to the sender too are left to that
 *     answer: in 20.x such an answer is the whole message, animation variables travel apart.</li>
 *     <li>Snapshots and checkpoints write each patched entity's mode, living motions and running action animations,
 *     the latter as instant plays carrying their elapsed time in a marker that playback turns back into a time.</li>
 *     <li>A seek drops action animations already over at the target and starts the others at the time they would
 *     have reached, without their blend in.</li>
 *     <li>Messages that only concern the recording player's own skill book and screen are not replayed; Epic Fight's
 *     serverbound messages are dropped during a replay. The animator clock follows the replay (optional mixins).</li>
 * </ul>
 * Limits of 20.x: an animation is an int id on the wire, given by registration order and the server's datapack
 * animations, not a name. A recording therefore replays right only with the same Epic Fight version, addons and
 * datapacks as when it was made; ids are not remapped. The local player turning its model yaw lock off is not
 * copied for its actor: Epic Fight's client handler of that message falls through into the mode change with no
 * mode, so the actor keeps its last model yaw until its next one.
 * <p>
 * Everything is reached by reflection: nothing here loads an Epic Fight class when it is absent.
 *
 * @author vyrriox
 */
public final class EpicFightCompat {
    private static final String NET = "yesman.epicfight.network.";
    private static final String NAMESPACE = "epicfight";
    /** The Forge channel of every Epic Fight message. */
    private static final ResourceLocation CHANNEL = new ResourceLocation(NAMESPACE, "network_manager");
    /** Transition time modifiers at or below this mark a snapshot animation; the elapsed time is what lies beyond. */
    private static final float MARK = -10000.0F;
    /** Messages that act on the receiving client's own player (skill book, skill execution, UI sounds). */
    private static final Set<String> LOCAL_ONLY = Set.of(
            NET + "server.SPSkillExecutionFeedback", NET + "server.SPPlayUISound", NET + "server.SPAddLearnedSkill",
            NET + "server.SPRemoveSkillAndLearn", NET + "server.SPSetSkillContainerValue", NET + "server.SPInitSkills");

    private static boolean reflectFailed;
    private static boolean ready;

    private static final AtomicInteger LOCAL_ANIMATIONS = new AtomicInteger();
    private static final AtomicInteger LOCAL_MODES = new AtomicInteger();
    private static final AtomicInteger SNAPSHOT_ANIMATIONS = new AtomicInteger();
    private static final AtomicInteger SEEK_DROPPED = new AtomicInteger();

    /** Animations to move to a time once their play packet has been handled. Client thread only. */
    private static final List<Pending> PENDING = new ArrayList<>();

    private EpicFightCompat() {
    }

    private record Pending(Packet<?> packet, int entityId, Object animation, float baseSeconds, int lateTicks) {
    }

    /** The fields of a decoded {@code SPAnimatorControl}. */
    private record Control(Object action, int animationId, int entityId, float modifier, boolean pause, Object layer,
                           Object priority) {
    }

    // ---------------------------------------------------------------- reflection

    private static Object channel;
    private static Method encodeMessage;
    private static final Map<Integer, Class<?>> BY_INDEX = new HashMap<>();
    private static final Set<Integer> LOCAL_ONLY_INDEXES = new HashSet<>();

    private static Class<?> spAnimator, cpAnimator, cpVariable, cpMode, cpYRot, cpInput, cpSync;
    private static Class<?> livingPatch, playerPatch;
    private static Method spAnimatorFrom, cpAnimatorFrom, cpVariableFrom, cpModeFrom, cpYRotFrom, cpInputFrom, cpSyncFrom;
    private static Field acAction, acAnimationId, acTransition, acPause, cpResponse, spEntityId, spLayer, spPriority;
    private static Constructor<?> spCreate, spCreateLayered;
    private static Object actionPlay, actionPlayInstantly;
    private static Field varAnimation, varKey, varValue, varAction;
    private static Constructor<?> spVariableCreate;
    private static Field cpModeMode, cpYRotValue, cpYRotDisable, cpInputForward, cpInputStrafe;
    private static Method setPlayerMode, setPlayerYRot;
    private static Constructor<?> spInput, spSyncCreate, livingMotionCreate;
    private static Field syncElapsed, syncPos, syncLerp;
    private static Method putPair;
    private static Method getPatch, getPlayerMode, getAnimator, getAllLayers, getLivingAnimations, getPlayerFor, byId;
    private static Field compositeLiving, layerPlayer;
    private static Method layerIsOff, playerIsEmpty, playerIsEnd, playerAnimation, playerRealAnimation, playerElapsed,
            playerSetElapsed;
    private static Method accGet, accInRegistry, accName, dynIsRepeat, dynTotalTime, dynPlaySpeed, dynId;

    private static Field declared(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static boolean reflect() {
        if (ready || reflectFailed || !Integrations.EPIC_FIGHT.on()) {
            return ready;
        }
        try {
            ClassLoader loader = EpicFightCompat.class.getClassLoader();
            Class<?> control = Class.forName(NET + "common.AnimatorControlPacket", false, loader);
            Class<?> action = Class.forName(NET + "common.AnimatorControlPacket$Action", false, loader);
            Class<?> layer = Class.forName(NET + "common.AnimatorControlPacket$Layer", false, loader);
            Class<?> priority = Class.forName(NET + "common.AnimatorControlPacket$Priority", false, loader);
            Class<?> variable = Class.forName(NET + "common.AnimationVariablePacket", false, loader);
            Class<?> variableAction = Class.forName(NET + "common.AnimationVariablePacket$Action", false, loader);
            Class<?> syncPosition = Class.forName(NET + "common.SyncAnimationPositionPacket", false, loader);
            Class<?> playerData = Class.forName(NET + "server.SPModifyPlayerData", false, loader);
            Class<?> livingMotion = Class.forName(NET + "server.SPChangeLivingMotion", false, loader);
            Class<?> accessor = Class.forName("yesman.epicfight.api.asset.AssetAccessor", false, loader);
            Class<?> dynamic = Class.forName("yesman.epicfight.api.animation.types.DynamicAnimation", false, loader);
            Class<?> motion = Class.forName("yesman.epicfight.api.animation.LivingMotion", false, loader);
            Class<?> variableKey = Class.forName("yesman.epicfight.api.animation.SynchedAnimationVariableKey", false, loader);
            Class<?> mode = Class.forName("yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch$PlayerMode", false, loader);
            spAnimator = Class.forName(NET + "server.SPAnimatorControl", false, loader);
            cpAnimator = Class.forName(NET + "client.CPAnimatorControl", false, loader);
            cpVariable = Class.forName(NET + "client.CPAnimationVariablePacket", false, loader);
            cpMode = Class.forName(NET + "client.CPChangePlayerMode", false, loader);
            cpYRot = Class.forName(NET + "client.CPModifyEntityModelYRot", false, loader);
            cpInput = Class.forName(NET + "client.CPUpdatePlayerInput", false, loader);
            cpSync = Class.forName(NET + "client.CPSyncPlayerAnimationPosition", false, loader);
            livingPatch = Class.forName("yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch", false, loader);
            playerPatch = Class.forName("yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch", false, loader);

            // Forge's message codec holds the index of every message class it was given.
            channel = Class.forName(NET + "EpicFightNetworkManager", false, loader).getField("INSTANCE").get(null);
            if (channel == null) {
                throw new IllegalStateException("no network channel");
            }
            encodeMessage = channel.getClass().getMethod("encodeMessage", Object.class, FriendlyByteBuf.class);
            Object codec = declared(channel.getClass(), "indexedCodec").get(channel);
            for (Map.Entry<?, ?> e : ((Map<?, ?>) declared(codec.getClass(), "types").get(codec)).entrySet()) {
                Class<?> type = (Class<?>) e.getKey();
                int index = declared(e.getValue().getClass(), "index").getInt(e.getValue());
                BY_INDEX.put(index, type);
                if (LOCAL_ONLY.contains(type.getName())) {
                    LOCAL_ONLY_INDEXES.add(index);
                }
            }
            for (Class<?> needed : List.of(spAnimator, cpAnimator, cpVariable, cpMode, cpYRot, cpInput, cpSync, playerData,
                    livingMotion)) {
                if (!BY_INDEX.containsValue(needed)) {
                    throw new IllegalStateException(needed.getSimpleName() + " is not registered on the channel");
                }
            }

            spAnimatorFrom = spAnimator.getMethod("fromBytes", FriendlyByteBuf.class);
            cpAnimatorFrom = cpAnimator.getMethod("fromBytes", FriendlyByteBuf.class);
            cpVariableFrom = cpVariable.getMethod("fromBytes", FriendlyByteBuf.class);
            cpModeFrom = cpMode.getMethod("fromBytes", FriendlyByteBuf.class);
            cpYRotFrom = cpYRot.getMethod("fromBytes", FriendlyByteBuf.class);
            cpInputFrom = cpInput.getMethod("fromBytes", FriendlyByteBuf.class);
            cpSyncFrom = cpSync.getMethod("fromBytes", FriendlyByteBuf.class);

            acAction = declared(control, "action");
            acAnimationId = declared(control, "animationId");
            acTransition = declared(control, "transitionTimeModifier");
            acPause = declared(control, "pause");
            cpResponse = declared(cpAnimator, "responseToSender");
            spEntityId = declared(spAnimator, "entityId");
            spLayer = declared(spAnimator, "layer");
            spPriority = declared(spAnimator, "priority");
            spCreate = spAnimator.getConstructor(action, int.class, int.class, float.class, boolean.class);
            spCreateLayered = spAnimator.getConstructor(action, int.class, int.class, float.class, boolean.class, layer, priority);
            actionPlay = action.getField("PLAY").get(null);
            actionPlayInstantly = action.getField("PLAY_INSTANTLY").get(null);

            varAnimation = declared(variable, "animation");
            varKey = declared(variable, "animationVariableKey");
            varValue = declared(variable, "value");
            varAction = declared(variable, "action");
            spVariableCreate = Class.forName(NET + "server.SPAnimationVariablePacket", false, loader)
                    .getConstructor(int.class, variableKey, accessor, Object.class, variableAction);

            cpModeMode = declared(cpMode, "mode");
            cpYRotValue = declared(cpYRot, "modelYRot");
            cpYRotDisable = declared(cpYRot, "disable");
            cpInputForward = declared(cpInput, "forward");
            cpInputStrafe = declared(cpInput, "strafe");
            setPlayerMode = playerData.getMethod("setPlayerMode", int.class, mode);
            setPlayerYRot = playerData.getMethod("setPlayerYRot", int.class, float.class);
            spInput = Class.forName(NET + "server.SPUpdatePlayerInput", false, loader).getConstructor(int.class, float.class, float.class);
            syncElapsed = declared(syncPosition, "elapsedTime");
            syncPos = declared(syncPosition, "position");
            syncLerp = declared(syncPosition, "lerpSteps");
            spSyncCreate = Class.forName(NET + "server.SPSyncAnimationPosition", false, loader)
                    .getConstructor(int.class, float.class, Vec3.class, int.class);
            livingMotionCreate = livingMotion.getConstructor(int.class, boolean.class);
            putPair = livingMotion.getMethod("putPair", motion, accessor);

            getPatch = Class.forName("yesman.epicfight.world.capabilities.EpicFightCapabilities", false, loader)
                    .getMethod("getUnparameterizedEntityPatch", Entity.class, Class.class);
            getPlayerMode = playerPatch.getMethod("getPlayerMode");
            getAnimator = livingPatch.getMethod("getClientAnimator");
            byId = Class.forName("yesman.epicfight.api.animation.AnimationManager", false, loader).getMethod("byId", int.class);
            Class<?> clientAnimator = Class.forName("yesman.epicfight.api.client.animation.ClientAnimator", false, loader);
            Class<?> animator = Class.forName("yesman.epicfight.api.animation.Animator", false, loader);
            getAllLayers = clientAnimator.getMethod("getAllLayers");
            getLivingAnimations = animator.getMethod("getLivingAnimations");
            getPlayerFor = animator.getMethod("getPlayerFor", accessor);
            compositeLiving = declared(clientAnimator, "compositeLivingAnimations");
            Class<?> layerClass = Class.forName("yesman.epicfight.api.client.animation.Layer", false, loader);
            layerPlayer = layerClass.getField("animationPlayer");
            layerIsOff = layerClass.getMethod("isOff");
            Class<?> player = Class.forName("yesman.epicfight.api.animation.AnimationPlayer", false, loader);
            playerIsEmpty = player.getMethod("isEmpty");
            playerIsEnd = player.getMethod("isEnd");
            playerAnimation = player.getMethod("getAnimation");
            playerRealAnimation = player.getMethod("getRealAnimation");
            playerElapsed = player.getMethod("getElapsedTime");
            playerSetElapsed = player.getMethod("setElapsedTime", float.class);
            accGet = accessor.getMethod("get");
            accInRegistry = accessor.getMethod("inRegistry");
            accName = accessor.getMethod("registryName");
            dynIsRepeat = dynamic.getMethod("isRepeat");
            dynTotalTime = dynamic.getMethod("getTotalTime");
            dynPlaySpeed = dynamic.getMethod("getPlaySpeed", livingPatch, dynamic);
            dynId = dynamic.getMethod("getId");
            ready = true;
            JustReplay.LOGGER.info("Epic Fight detected: the local player's animations and mode are recorded for its actor");
        } catch (Throwable t) {
            reflectFailed = true;
            Integrations.EPIC_FIGHT.fail("reflect", t);
        }
        return ready;
    }

    private static boolean isEpicFight(Packet<?> packet) {
        ResourceLocation id = Payloads.idOf(packet);
        return id != null && NAMESPACE.equals(id.getNamespace());
    }

    /** Message index of an Epic Fight packet of either direction, -1 for any other packet. */
    private static int indexOf(Packet<?> packet) {
        if (!CHANNEL.equals(Payloads.idOf(packet))) {
            return -1;
        }
        FriendlyByteBuf data = Payloads.bytes(packet);
        if (data == null) {
            return -1;
        }
        try {
            return data.isReadable() ? data.getUnsignedByte(data.readerIndex()) : -1;
        } finally {
            data.release();
        }
    }

    /** The message of an Epic Fight packet decoded by the mod when its class is {@code type}, else null. */
    private static Object decode(Packet<?> packet, Class<?> type, Method fromBytes) throws Exception {
        if (BY_INDEX.get(indexOf(packet)) != type) {
            return null;
        }
        FriendlyByteBuf data = Payloads.bytes(packet);
        try {
            data.readUnsignedByte();
            return fromBytes.invoke(null, data);
        } finally {
            data.release();
        }
    }

    /** A message of the mod as the clientbound packet its server sends. */
    private static Packet<?> encode(Object message) throws Exception {
        FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
        encodeMessage.invoke(channel, message, bytes);
        return Payloads.make(CHANNEL, bytes);
    }

    private static Control control(Packet<?> packet) throws Exception {
        Object msg = decode(packet, spAnimator, spAnimatorFrom);
        if (msg == null) {
            return null;
        }
        return new Control(acAction.get(msg), acAnimationId.getInt(msg), spEntityId.getInt(msg), acTransition.getFloat(msg),
                acPause.getBoolean(msg), spLayer.get(msg), spPriority.get(msg));
    }

    /** The registry accessor of an animation id, null when the running setup has none. */
    private static Object animation(int id) {
        try {
            return byId.invoke(null, id);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- recording

    /** See {@link ReplayCompat#onLiveServerbound}: what the local player sends is recorded as its trackers receive it. */
    static void onLiveServerbound(Packet<?> packet) throws Exception {
        if (!Payloads.isServerbound(packet) || !CHANNEL.equals(Payloads.idOf(packet)) || Recorder.capturing() == null
                || !reflect()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // Built now: the request may be handled by the integrated server before the client thread runs.
        List<Packet<?>> copies = forTrackers(packet, player.getId());
        if (copies.isEmpty()) {
            return;
        }
        Runnable write = () -> {
            for (Packet<?> copy : copies) {
                Recorder.recordLocalEffect(copy);
            }
        };
        if (mc.isSameThread()) {
            write.run();
        } else {
            // Runs later on the client thread, out of the caller's guard.
            mc.execute(() -> Integrations.EPIC_FIGHT.run("recordLocalRequest", write::run));
        }
    }

    /** The packets the server sends to the players tracking the sender of this request (none for the others). */
    private static List<Packet<?>> forTrackers(Packet<?> packet, int id) throws Exception {
        List<Packet<?>> out = new ArrayList<>(1);
        Class<?> type = BY_INDEX.get(indexOf(packet));
        if (type == null) {
            return out;
        }
        if (type == cpAnimator) {
            Object msg = decode(packet, cpAnimator, cpAnimatorFrom);
            // When the server answers the sender with the same animation, that answer is recorded already.
            if (!cpResponse.getBoolean(msg)) {
                out.add(encode(spCreate.newInstance(acAction.get(msg), acAnimationId.getInt(msg), id,
                        acTransition.getFloat(msg), acPause.getBoolean(msg))));
            }
            LOCAL_ANIMATIONS.incrementAndGet();
        } else if (type == cpVariable) {
            Object msg = decode(packet, cpVariable, cpVariableFrom);
            out.add(encode(spVariableCreate.newInstance(id, varKey.get(msg), varAnimation.get(msg), varValue.get(msg),
                    varAction.get(msg))));
        } else if (type == cpMode) {
            Object msg = decode(packet, cpMode, cpModeFrom);
            out.add(encode(setPlayerMode.invoke(null, id, cpModeMode.get(msg))));
            LOCAL_MODES.incrementAndGet();
        } else if (type == cpYRot) {
            Object msg = decode(packet, cpYRot, cpYRotFrom);
            // Turning the lock off is not copied: Epic Fight's client handler of it also sets a null mode.
            if (!cpYRotDisable.getBoolean(msg)) {
                out.add(encode(setPlayerYRot.invoke(null, id, cpYRotValue.getFloat(msg))));
            }
        } else if (type == cpInput) {
            Object msg = decode(packet, cpInput, cpInputFrom);
            out.add(encode(spInput.newInstance(id, cpInputForward.getFloat(msg), cpInputStrafe.getFloat(msg))));
        } else if (type == cpSync) {
            Object msg = decode(packet, cpSync, cpSyncFrom);
            out.add(encode(spSyncCreate.newInstance(id, syncElapsed.getFloat(msg), syncPos.get(msg), syncLerp.getInt(msg))));
        }
        return out;
    }

    // ---------------------------------------------------------------- snapshots

    /** See {@link ReplayCompat#writePlayerState}: mode, living motions and running animations of a player. */
    static void writeCurrent(Player player, Consumer<Packet<?>> out) {
        if (!reflect()) {
            return;
        }
        try {
            Object patch = patch(player, playerPatch);
            if (patch == null) {
                return;
            }
            Object mode = getPlayerMode.invoke(patch);
            if (mode != null) {
                out.accept(encode(setPlayerMode.invoke(null, player.getId(), mode)));
            }
            writeAnimator(player, patch, out);
        } catch (Throwable t) {
            ReplayCompat.warnOnce("epicfight.snapshot.player", "Cannot write a player's Epic Fight state into the snapshot", t);
        }
    }

    /** Living motions and running animations of every patched mob (players are written by {@link #writeCurrent}). */
    static void writeEntities(ClientLevel level, Consumer<Packet<?>> out) {
        if (!reflect() || level == null) {
            return;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity) || entity instanceof Player) {
                continue;
            }
            try {
                Object patch = patch(entity, livingPatch);
                if (patch != null) {
                    writeAnimator(entity, patch, out);
                }
            } catch (Throwable t) {
                ReplayCompat.warnOnce("epicfight.snapshot.entity", "Cannot write an entity's Epic Fight state into the snapshot", t);
            }
        }
    }

    private static Object patch(Entity entity, Class<?> type) throws Exception {
        Object result = getPatch.invoke(null, entity, type);
        return result instanceof Optional<?> optional ? optional.orElse(null) : null;
    }

    @SuppressWarnings("unchecked")
    private static void writeAnimator(Entity entity, Object patch, Consumer<Packet<?>> out) throws Exception {
        Object animator = getAnimator.invoke(patch);
        if (animator == null) {
            return;
        }
        Map<Object, Object> living = (Map<Object, Object>) getLivingAnimations.invoke(animator);
        Map<Object, Object> composite = (Map<Object, Object>) compositeLiving.get(animator);
        Object motions = livingMotionCreate.newInstance(entity.getId(), false);
        boolean anyMotion = false;
        Set<Object> livingNames = new HashSet<>();
        for (Map<Object, Object> map : java.util.Arrays.asList(living, composite)) {
            if (map == null) {
                continue;
            }
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && (boolean) accInRegistry.invoke(entry.getValue())) {
                    putPair.invoke(motions, entry.getKey(), entry.getValue());
                    anyMotion = true;
                    livingNames.add(accName.invoke(entry.getValue()));
                }
            }
        }
        if (anyMotion) {
            out.accept(encode(motions));
        }
        for (Object layer : (Collection<Object>) getAllLayers.invoke(animator)) {
            if (layer == null || (boolean) layerIsOff.invoke(layer)) {
                continue;
            }
            Object player = layerPlayer.get(layer);
            if (player == null || (boolean) playerIsEmpty.invoke(player) || (boolean) playerIsEnd.invoke(player)) {
                continue;
            }
            Object real = playerRealAnimation.invoke(player);
            if (real == null || !(boolean) accInRegistry.invoke(real) || livingNames.contains(accName.invoke(real))) {
                continue;
            }
            Object realAnimation = accGet.invoke(real);
            if (realAnimation == null || (boolean) dynIsRepeat.invoke(realAnimation)) {
                continue;
            }
            int animationId = (int) dynId.invoke(realAnimation);
            if (animationId < 0) {
                continue;
            }
            Object playing = playerAnimation.invoke(player);
            // During the blend in (a link animation), the elapsed time is the link's: the action starts at 0.
            float elapsed = playing != null && accGet.invoke(playing) == realAnimation ? (float) playerElapsed.invoke(player) : 0.0F;
            out.accept(encode(spCreate.newInstance(actionPlayInstantly, animationId, entity.getId(),
                    MARK - Math.max(0.0F, elapsed), false)));
            SNAPSHOT_ANIMATIONS.incrementAndGet();
        }
    }

    // ---------------------------------------------------------------- playback

    /** See {@link ReplayCompat#filterPlayback}: local-only messages are dropped, snapshot animations get their time. */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (!Payloads.isClientbound(packet) || !isEpicFight(packet) || !reflect()) {
            return packet;
        }
        try {
            int index = indexOf(packet);
            if (LOCAL_ONLY_INDEXES.contains(index)) {
                return null;
            }
            if (BY_INDEX.get(index) != spAnimator) {
                return packet;
            }
            Control c = control(packet);
            if (c == null || c.modifier > MARK / 2 || c.action != actionPlayInstantly) {
                return packet;
            }
            Packet<?> clean = encode(spCreateLayered.newInstance(actionPlayInstantly, c.animationId, c.entityId, 0.0F, c.pause,
                    c.layer, c.priority));
            Object animation = animation(c.animationId);
            if (animation != null) {
                PENDING.add(new Pending(clean, c.entityId, animation, MARK - c.modifier, 0));
            }
            return clean;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("epicfight.marker", "Cannot read an Epic Fight snapshot animation", t);
            return packet;
        }
    }

    /**
     * See {@link ReplayCompat#adjustSkipped}: an action animation over at the seek target is dropped; one still running
     * plays at once (no blend in) and is moved to the time it would have reached.
     */
    static Packet<?> adjustSkipped(Packet<?> packet, int late) {
        if (!Payloads.isClientbound(packet) || !CHANNEL.equals(Payloads.idOf(packet)) || !reflect()) {
            return packet;
        }
        try {
            Control c = control(packet);
            if (c == null || c.action != actionPlay && c.action != actionPlayInstantly) {
                return packet;
            }
            Object accessor = animation(c.animationId);
            Object animation = accessor == null ? null : accGet.invoke(accessor);
            ClientLevel level = Minecraft.getInstance().level;
            Entity entity = level == null ? null : level.getEntity(c.entityId);
            Object patch = entity == null ? null : patch(entity, livingPatch);
            if (animation == null || patch == null) {
                return packet;
            }
            // A snapshot animation already has its time queued by filterPlayback: the skipped ticks are added to it.
            float base = 0.0F;
            Pending marker = PENDING.isEmpty() ? null : PENDING.get(PENDING.size() - 1);
            if (marker != null && marker.packet == packet) {
                base = marker.baseSeconds;
                PENDING.remove(PENDING.size() - 1);
            }
            float reached = base + late * 0.05F * playSpeed(patch, animation);
            if (!(boolean) dynIsRepeat.invoke(animation) && reached >= (float) dynTotalTime.invoke(animation)) {
                SEEK_DROPPED.incrementAndGet();
                return null;
            }
            Packet<?> instant = packet;
            if (c.action == actionPlay) {
                instant = encode(spCreateLayered.newInstance(actionPlayInstantly, c.animationId, c.entityId, 0.0F, c.pause,
                        c.layer, c.priority));
            }
            PENDING.add(new Pending(instant, c.entityId, accessor, base, late));
            return instant;
        } catch (Throwable t) {
            ReplayCompat.warnOnce("epicfight.skip", "Cannot adjust a skipped Epic Fight animation", t);
            return packet;
        }
    }

    private static float playSpeed(Object patch, Object animation) {
        try {
            float speed = (float) dynPlaySpeed.invoke(animation, patch, animation);
            return Float.isFinite(speed) && speed > 0.0F ? speed : 1.0F;
        } catch (Throwable t) {
            return 1.0F;
        }
    }

    /**
     * Moves the animations of the packets handled since the last call to their time. Called before each recorded
     * packet (the previous one has been handled by then) and every replay tick.
     */
    static void applyPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Pending> due = new ArrayList<>(PENDING);
        PENDING.clear();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !reflect()) {
            return;
        }
        for (Pending pending : due) {
            try {
                Entity entity = level.getEntity(pending.entityId);
                Object patch = entity == null ? null : patch(entity, livingPatch);
                Object animator = patch == null ? null : getAnimator.invoke(patch);
                Object player = animator == null ? null : getPlayerFor.invoke(animator, pending.animation);
                if (player == null) {
                    continue;
                }
                Object playing = playerAnimation.invoke(player);
                ResourceLocation name = playing == null ? null : (ResourceLocation) accName.invoke(playing);
                if (name == null || !name.equals(accName.invoke(pending.animation))) {
                    continue;
                }
                Object animation = accGet.invoke(pending.animation);
                float time = pending.baseSeconds + pending.lateTicks * 0.05F * playSpeed(patch, animation);
                float total = (float) dynTotalTime.invoke(animation);
                if ((boolean) dynIsRepeat.invoke(animation) && total > 0.0F) {
                    time %= total;
                } else if (total > 0.0F) {
                    time = Math.min(time, total);
                }
                if (time > 0.0F) {
                    playerSetElapsed.invoke(player, time);
                }
            } catch (Throwable t) {
                ReplayCompat.warnOnce("epicfight.time", "Cannot move an Epic Fight animation to its time", t);
            }
        }
    }

    static void onReplayEnd() {
        PENDING.clear();
    }

    /** Epic Fight's requests to the replay host (skills, animations, inputs) have no server to go to. */
    static boolean blockServerbound(Packet<?> packet) {
        return Payloads.isServerbound(packet) && isEpicFight(packet);
    }

    // ---------------------------------------------------------------- diagnostics

    /** "animations/modes" requests of the recording player written for its actor since the game started. */
    public static String localRequests() {
        return LOCAL_ANIMATIONS.get() + "/" + LOCAL_MODES.get();
    }

    /** Running animations written into snapshots, and skipped animations dropped by seeks. */
    public static String snapshotAndSeek() {
        return SNAPSHOT_ANIMATIONS.get() + "/" + SEEK_DROPPED.get();
    }
}
