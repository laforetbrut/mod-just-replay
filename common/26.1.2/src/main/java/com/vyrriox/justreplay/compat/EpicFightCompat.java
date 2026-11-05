package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Epic Fight animations and battle mode in recordings and replays.
 * <ul>
 *     <li>Animations reach clients as {@code SPAnimatorControl} payloads keyed by entity id, for players and patched
 *     mobs alike; living motions (idle, walk... per held weapon) as {@code SPChangeLivingMotion}; a player's mode
 *     (vanilla or battle) as {@code SetPlayerMode}. They are recorded and replayed as they are.</li>
 *     <li>What the recording player starts itself (attacks, dodges, the mode key, model yaw, inputs) is played by its
 *     client and sent to the server, which forwards it to the players tracking it but not back to its sender. The
 *     recorder copies those requests as the payloads the trackers receive, with the local player's entity id (the
 *     actor keeps it). Requests the server answers to the sender too are left to that answer.</li>
 *     <li>Snapshots and checkpoints write each patched entity's mode, living motions and running action animations,
 *     the latter as instant plays carrying their elapsed time in a marker that playback turns back into a time.</li>
 *     <li>A seek drops action animations already over at the target and starts the others at the time they would
 *     have reached, without their blend in.</li>
 *     <li>Payloads that only concern the recording player's own skill book and screen are not replayed; Epic Fight's
 *     serverbound payloads are dropped during a replay. The animator clock follows the replay (optional mixins).</li>
 * </ul>
 * Everything is reached by reflection: nothing here loads an Epic Fight class when it is absent.
 *
 * @author vyrriox
 */
public final class EpicFightCompat {
    private static final String NET = "yesman.epicfight.network.";
    private static final String NAMESPACE = "epicfight";
    /** Transition time modifiers at or below this mark a snapshot animation; the elapsed time is what lies beyond. */
    private static final float MARK = -10000.0F;
    /** Payloads that act on the receiving client's own player (skill book, skill execution, UI sounds). */
    private static final Set<String> LOCAL_ONLY = Set.of(
            NET + "server.SPSkillFeedback", NET + "server.SPPlayUISound", NET + "server.SPAddLearnedSkill",
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

    private record Pending(Object payload, int entityId, Object animation, float baseSeconds, int lateTicks) {
    }

    // ---------------------------------------------------------------- reflection

    private static Class<?> spAnimator, cpAnimator, variable, cpMode, cpYRot, cpInput, syncPosition;
    private static Class<?> livingPatch, playerPatch;
    private static Method acAction, acAnimation, acTransition, acPause, acVariables, cpClientOnly, cpResponse;
    private static Method spEntityId, spLayer, spPriority;
    private static Constructor<?> spCreate, spCreateLayered;
    private static Object actionPlay, actionPlayInstantly;
    private static Method varAction, varAnimation, varKey, varBuf;
    private static Constructor<?> varCreate;
    private static Method cpModeMode, cpYRotValue, cpYRotDisable, cpInputForward, cpInputStrafe;
    private static Constructor<?> setPlayerMode, setPlayerYRot, disablePlayerYRot, spInput;
    private static Method syncElapsed, syncPos, syncLerp;
    private static Constructor<?> syncCreate, livingMotionCreate;
    private static Method getPatch, getPlayerMode, getAnimator, getAllLayers, getLivingAnimations, getPlayerFor;
    private static Field compositeLiving, layerPlayer;
    private static Method layerIsOff, playerIsEmpty, playerIsEnd, playerAnimation, playerRealAnimation, playerElapsed,
            playerSetElapsed;
    private static Method accGet, accInRegistry, accName, dynIsRepeat, dynTotalTime, dynPlaySpeed;

    private static boolean reflect() {
        if (ready || reflectFailed || !ReplayCompat.EPIC_FIGHT) {
            return ready;
        }
        try {
            ClassLoader loader = EpicFightCompat.class.getClassLoader();
            Class<?> abstractControl = Class.forName(NET + "common.AbstractAnimatorControl", false, loader);
            Class<?> action = Class.forName(NET + "common.AbstractAnimatorControl$Action", false, loader);
            Class<?> layer = Class.forName(NET + "common.AbstractAnimatorControl$Layer", false, loader);
            Class<?> priority = Class.forName(NET + "common.AbstractAnimatorControl$Priority", false, loader);
            Class<?> accessor = Class.forName("yesman.epicfight.api.asset.AssetAccessor", false, loader);
            Class<?> dynamic = Class.forName("yesman.epicfight.api.animation.types.DynamicAnimation", false, loader);
            Class<?> mode = Class.forName("yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch$PlayerMode", false, loader);
            spAnimator = Class.forName(NET + "server.SPAnimatorControl", false, loader);
            cpAnimator = Class.forName(NET + "client.CPAnimatorControl", false, loader);
            variable = Class.forName(NET + "common.BiDirectionalAnimationVariable", false, loader);
            Class<?> variableAction = Class.forName(NET + "common.BiDirectionalAnimationVariable$Action", false, loader);
            cpMode = Class.forName(NET + "client.CPChangePlayerMode", false, loader);
            cpYRot = Class.forName(NET + "client.CPModifyEntityModelYRot", false, loader);
            cpInput = Class.forName(NET + "client.CPUpdatePlayerInput", false, loader);
            syncPosition = Class.forName(NET + "common.BiDirectionalSyncAnimationPositionPacket", false, loader);
            livingPatch = Class.forName("yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch", false, loader);
            playerPatch = Class.forName("yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch", false, loader);

            acAction = abstractControl.getMethod("action");
            acAnimation = abstractControl.getMethod("animation");
            acTransition = abstractControl.getMethod("transitionTimeModifier");
            acPause = abstractControl.getMethod("pause");
            acVariables = abstractControl.getMethod("animationVariables");
            cpClientOnly = cpAnimator.getMethod("isClientOnly");
            cpResponse = cpAnimator.getMethod("responseToSender");
            spEntityId = spAnimator.getMethod("entityId");
            spLayer = spAnimator.getMethod("layer");
            spPriority = spAnimator.getMethod("priority");
            spCreate = spAnimator.getConstructor(action, accessor, int.class, float.class, boolean.class, List.class);
            spCreateLayered = spAnimator.getConstructor(action, accessor, int.class, float.class, boolean.class, List.class,
                    layer, priority);
            actionPlay = action.getField("PLAY").get(null);
            actionPlayInstantly = action.getField("PLAY_INSTANTLY").get(null);

            varAction = variable.getMethod("action");
            varAnimation = variable.getMethod("animation");
            varKey = variable.getMethod("synchedAnimationVariableKey");
            varBuf = variable.getMethod("buf");
            varCreate = variable.getConstructor(variableAction, accessor, int.class, net.minecraft.core.Holder.class, byte[].class);

            cpModeMode = cpMode.getMethod("mode");
            cpYRotValue = cpYRot.getMethod("modelYRot");
            cpYRotDisable = cpYRot.getMethod("disable");
            cpInputForward = cpInput.getMethod("forward");
            cpInputStrafe = cpInput.getMethod("strafe");
            setPlayerMode = Class.forName(NET + "server.SPModifyPlayerData$SetPlayerMode", false, loader).getConstructor(int.class, mode);
            setPlayerYRot = Class.forName(NET + "server.SPModifyPlayerData$SetPlayerYRot", false, loader).getConstructor(int.class, float.class);
            disablePlayerYRot = Class.forName(NET + "server.SPModifyPlayerData$DisablePlayerYRot", false, loader).getConstructor(int.class);
            spInput = Class.forName(NET + "server.SPUpdatePlayerInput", false, loader).getConstructor(int.class, float.class, float.class);
            syncElapsed = syncPosition.getMethod("elapsedTime");
            syncPos = syncPosition.getMethod("position");
            syncLerp = syncPosition.getMethod("lerpSteps");
            syncCreate = syncPosition.getConstructor(int.class, float.class, Vec3.class, int.class);
            livingMotionCreate = Class.forName(NET + "server.SPChangeLivingMotion", false, loader)
                    .getConstructor(List.class, List.class, int.class, boolean.class);

            getPatch = Class.forName("yesman.epicfight.world.capabilities.EpicFightCapabilities", false, loader)
                    .getMethod("getUnparameterizedEntityPatch", Entity.class, Class.class);
            getPlayerMode = playerPatch.getMethod("getPlayerMode");
            getAnimator = livingPatch.getMethod("getClientAnimator");
            Class<?> clientAnimator = Class.forName("yesman.epicfight.api.client.animation.ClientAnimator", false, loader);
            Class<?> animator = Class.forName("yesman.epicfight.api.animation.Animator", false, loader);
            getAllLayers = clientAnimator.getMethod("getAllLayers");
            getLivingAnimations = animator.getMethod("getLivingAnimations");
            getPlayerFor = animator.getMethod("getPlayerFor", accessor);
            compositeLiving = clientAnimator.getDeclaredField("compositeLivingAnimations");
            compositeLiving.setAccessible(true);
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
            ready = true;
            JustReplay.LOGGER.info("Epic Fight detected: the local player's animations and mode are recorded for its actor");
        } catch (Throwable t) {
            reflectFailed = true;
            ReplayCompat.warnOnce("epicfight.reflect", "Epic Fight support disabled, its classes changed", t);
        }
        return ready;
    }

    private static boolean isEpicFight(CustomPacketPayload payload) {
        return payload.getClass().getName().startsWith("yesman.epicfight.")
                || NAMESPACE.equals(payload.type().id().getNamespace());
    }

    // ---------------------------------------------------------------- recording

    /** See {@link ReplayCompat#onLiveServerbound}: what the local player sends is recorded as its trackers receive it. */
    static void onLiveServerbound(Packet<?> packet) throws Exception {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !isEpicFight(custom.payload())
                || Recorder.capturing() == null || !reflect()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // Built now: the request may be handled by the integrated server (or encoded) before the client thread runs.
        List<CustomPacketPayload> copies = forTrackers(custom.payload(), player.getId());
        if (copies.isEmpty()) {
            return;
        }
        Runnable write = () -> {
            for (CustomPacketPayload copy : copies) {
                Recorder.recordLocalEffect(new ClientboundCustomPayloadPacket(copy));
            }
        };
        if (mc.isSameThread()) {
            write.run();
        } else {
            mc.execute(write);
        }
    }

    /** The payloads the server sends to the players tracking the sender of this request (none for the others). */
    private static List<CustomPacketPayload> forTrackers(CustomPacketPayload payload, int id) throws Exception {
        List<CustomPacketPayload> out = new ArrayList<>(2);
        Class<?> type = payload.getClass();
        if (type == cpAnimator) {
            List<Object> variables = new ArrayList<>();
            for (Object v : (List<?>) acVariables.invoke(payload)) {
                variables.add(copyVariable(v, id));
            }
            if ((boolean) cpResponse.invoke(payload)) {
                // The server answers the sender with the same animation: only the variables are missing from it.
                for (Object v : variables) {
                    out.add((CustomPacketPayload) v);
                }
            } else {
                out.add((CustomPacketPayload) spCreate.newInstance(acAction.invoke(payload), acAnimation.invoke(payload), id,
                        acTransition.invoke(payload), acPause.invoke(payload), variables));
            }
            LOCAL_ANIMATIONS.incrementAndGet();
        } else if (type == variable) {
            out.add((CustomPacketPayload) copyVariable(payload, id));
        } else if (type == cpMode) {
            out.add((CustomPacketPayload) setPlayerMode.newInstance(id, cpModeMode.invoke(payload)));
            LOCAL_MODES.incrementAndGet();
        } else if (type == cpYRot) {
            out.add((CustomPacketPayload) ((boolean) cpYRotDisable.invoke(payload)
                    ? disablePlayerYRot.newInstance(id)
                    : setPlayerYRot.newInstance(id, cpYRotValue.invoke(payload))));
        } else if (type == cpInput) {
            out.add((CustomPacketPayload) spInput.newInstance(id, cpInputForward.invoke(payload), cpInputStrafe.invoke(payload)));
        } else if (type == syncPosition) {
            out.add((CustomPacketPayload) syncCreate.newInstance(id, syncElapsed.invoke(payload), syncPos.invoke(payload),
                    syncLerp.invoke(payload)));
        }
        return out;
    }

    /** An animation variable aimed at an entity id; its bytes are copied (the codec writes the buffer's array). */
    private static Object copyVariable(Object v, int id) throws Exception {
        net.minecraft.network.FriendlyByteBuf buf = (net.minecraft.network.FriendlyByteBuf) varBuf.invoke(v);
        byte[] bytes = buf == null ? new byte[0] : buf.array().clone();
        return varCreate.newInstance(varAction.invoke(v), varAnimation.invoke(v), id, varKey.invoke(v), bytes);
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
                out.accept(payload(setPlayerMode.newInstance(player.getId(), mode)));
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

    private static Packet<?> payload(Object payload) {
        return new ClientboundCustomPayloadPacket((CustomPacketPayload) payload);
    }

    @SuppressWarnings("unchecked")
    private static void writeAnimator(Entity entity, Object patch, Consumer<Packet<?>> out) throws Exception {
        Object animator = getAnimator.invoke(patch);
        if (animator == null) {
            return;
        }
        Map<Object, Object> living = (Map<Object, Object>) getLivingAnimations.invoke(animator);
        Map<Object, Object> composite = (Map<Object, Object>) compositeLiving.get(animator);
        List<Object> motions = new ArrayList<>();
        List<Object> animations = new ArrayList<>();
        Set<Object> livingNames = new java.util.HashSet<>();
        for (Map<Object, Object> map : List.of(living, composite)) {
            if (map == null) {
                continue;
            }
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && (boolean) accInRegistry.invoke(entry.getValue())) {
                    motions.add(entry.getKey());
                    animations.add(entry.getValue());
                    livingNames.add(accName.invoke(entry.getValue()));
                }
            }
        }
        if (!motions.isEmpty()) {
            out.accept(payload(livingMotionCreate.newInstance(motions, animations, entity.getId(), false)));
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
            Object playing = playerAnimation.invoke(player);
            // During the blend in (a link animation), the elapsed time is the link's: the action starts at 0.
            float elapsed = playing != null && accGet.invoke(playing) == realAnimation ? (float) playerElapsed.invoke(player) : 0.0F;
            out.accept(payload(spCreate.newInstance(actionPlayInstantly, real, entity.getId(), MARK - Math.max(0.0F, elapsed),
                    false, new ArrayList<>())));
            SNAPSHOT_ANIMATIONS.incrementAndGet();
        }
    }

    // ---------------------------------------------------------------- playback

    /** See {@link ReplayCompat#filterPlayback}: local-only payloads are dropped, snapshot animations get their time. */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !isEpicFight(custom.payload())) {
            return packet;
        }
        CustomPacketPayload payload = custom.payload();
        if (LOCAL_ONLY.contains(payload.getClass().getName())) {
            return null;
        }
        if (!reflect() || payload.getClass() != spAnimator) {
            return packet;
        }
        try {
            float modifier = (float) acTransition.invoke(payload);
            if (modifier > MARK / 2 || acAction.invoke(payload) != actionPlayInstantly) {
                return packet;
            }
            Object clean = spCreateLayered.newInstance(actionPlayInstantly, acAnimation.invoke(payload), spEntityId.invoke(payload),
                    0.0F, acPause.invoke(payload), acVariables.invoke(payload), spLayer.invoke(payload), spPriority.invoke(payload));
            PENDING.add(new Pending(clean, (int) spEntityId.invoke(payload), acAnimation.invoke(payload), MARK - modifier, 0));
            return payload(clean);
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
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !reflect() || custom.payload().getClass() != spAnimator) {
            return packet;
        }
        CustomPacketPayload payload = custom.payload();
        try {
            Object action = acAction.invoke(payload);
            if (action != actionPlay && action != actionPlayInstantly) {
                return packet;
            }
            int id = (int) spEntityId.invoke(payload);
            Object accessor = acAnimation.invoke(payload);
            Object animation = accGet.invoke(accessor);
            ClientLevel level = Minecraft.getInstance().level;
            Entity entity = level == null ? null : level.getEntity(id);
            Object patch = entity == null ? null : patch(entity, livingPatch);
            if (animation == null || patch == null) {
                return packet;
            }
            // A snapshot animation already has its time queued by filterPlayback: the skipped ticks are added to it.
            float base = 0.0F;
            Pending marker = PENDING.isEmpty() ? null : PENDING.get(PENDING.size() - 1);
            if (marker != null && marker.payload == payload) {
                base = marker.baseSeconds;
                PENDING.remove(PENDING.size() - 1);
            }
            float reached = base + late * 0.05F * playSpeed(patch, animation);
            if (!(boolean) dynIsRepeat.invoke(animation) && reached >= (float) dynTotalTime.invoke(animation)) {
                SEEK_DROPPED.incrementAndGet();
                return null;
            }
            Object instant = payload;
            if (action == actionPlay) {
                instant = spCreateLayered.newInstance(actionPlayInstantly, accessor, id, 0.0F, acPause.invoke(payload),
                        acVariables.invoke(payload), spLayer.invoke(payload), spPriority.invoke(payload));
            }
            PENDING.add(new Pending(instant, id, accessor, base, late));
            return instant == payload ? packet : payload(instant);
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
                Identifier name = playing == null ? null : (Identifier) accName.invoke(playing);
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
        return packet instanceof ServerboundCustomPayloadPacket custom && isEpicFight(custom.payload());
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
