package com.vyrriox.justreplay.editor;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.PlayerInfoPackets;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spawns the actors of the project as client side entities and moves them along their keyframes. Actors are
 * rebuilt automatically when the replay world is reset by a seek or a dimension change.
 *
 * @author vyrriox
 */
public final class ActorManager {
    private final ReplaySession session;
    private final Map<String, Entity> spawned = new HashMap<>();
    /** Player actors whose name tag is hidden: players always show theirs, so the tag is cancelled when drawn. */
    private static final java.util.Set<Integer> HIDDEN_NAMES = new java.util.HashSet<>();
    /** Player actors drawn without the outer layer of their skin. */
    private static final java.util.Set<Integer> NO_SKIN_LAYERS = new java.util.HashSet<>();
    /** Body parts hidden on player actors, by entity id (see {@link BodyParts}). */
    private static final java.util.Map<Integer, Integer> HIDDEN_PARTS = new java.util.HashMap<>();
    private final Map<String, Integer> ids = new HashMap<>();
    /**
     * Player actors by entity id; read by the skin mixin. The actor data is kept live so its skin file and its timed
     * skin changes apply at every frame without a respawn.
     */
    private static final Map<Integer, ReplayProject.ActorData> CUSTOM_SKINS = new HashMap<>();
    /** At most this many Mojang profile lookups start per client tick: many at once get answered 429. */
    private static final int LOOKUPS_PER_TICK = 2;
    private static final long LOOKUP_RETRY_MS = 5000L;
    private final Map<String, Lookup> lookups = new HashMap<>();
    /** Lookups waiting for their turn (or for their retry delay), in request order. */
    private final List<Lookup> lookupQueue = new java.util.ArrayList<>();
    private final Map<String, GameProfile> profiles = new HashMap<>();
    private int nextId = -1000;
    private final java.util.Set<String> failed = new java.util.HashSet<>();
    private final ActorAnimator animator = new ActorAnimator();
    /** Actor shown at a placement preview instead of where its keyframes put it, or null. */
    private String previewId;
    private ActorPlacement.Spot preview;

    /** Profile lookup of a named player actor: queued, started when its turn comes, retried once on failure. */
    private static final class Lookup {
        final String actorId;
        final String name;
        int attempt;
        long notBefore;
        /** Account id learned by a first attempt that found the name but not the skin. */
        UUID knownId;
        boolean done;
        Optional<GameProfile> result = Optional.empty();

        Lookup(String actorId, String name) {
            this.actorId = actorId;
            this.name = name;
        }
    }

    public ActorManager(ReplaySession session) {
        this.session = session;
    }

    /** Shows an actor at a placement preview until {@link #clearPreview()}. */
    public void setPreview(ReplayProject.ActorData actor, ActorPlacement.Spot spot) {
        previewId = actor.id;
        preview = spot;
        showPreview();
    }

    public void clearPreview() {
        previewId = null;
        preview = null;
    }

    /** Puts the previewed actor at its preview spot; called every frame so the ghost follows the view smoothly. */
    public void showPreview() {
        if (previewId == null || preview == null) {
            return;
        }
        Entity entity = spawned.get(previewId);
        if (entity != null && !entity.isRemoved()) {
            ActorAnimator.showPreview(entity, preview.x(), preview.y(), preview.z(), preview.yaw(), preview.pose());
        }
    }

    /** The actor entity whose bounding box the ray crosses first, or null. */
    public ReplayProject.ActorData pick(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        ReplayProject.ActorData best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ReplayProject.ActorData actor : session.project().actors) {
            Entity entity = spawned.get(actor.id);
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            net.minecraft.world.phys.AABB box = entity.getBoundingBox().inflate(0.15);
            if (actor.isModel()) {
                // The invisible armor stand carries the model: grow its box with the model scale.
                box = box.inflate(Math.max(0.0, actor.modelScale - 1.0) * 0.5, Math.max(0.0, actor.modelScale - 1.0), Math.max(0.0, actor.modelScale - 1.0) * 0.5);
            }
            var hit = box.contains(from) ? java.util.Optional.of(from) : box.clip(from, to);
            if (hit.isPresent()) {
                double d = hit.get().distanceToSqr(from);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = actor;
                }
            }
        }
        return best;
    }

    /** True for entities spawned as actors; their own tick is skipped, animate() drives them. */
    public static boolean isActorEntity(Entity entity) {
        return entity.getId() <= -1000;
    }

    public Entity entityOf(ReplayProject.ActorData actor) {
        return spawned.get(actor.id);
    }

    /** Forces an actor to be rebuilt, after its type, skin, model or equipment changed. */
    public void refresh(ReplayProject.ActorData actor) {
        failed.remove(actor.id);
        Entity entity = spawned.remove(actor.id);
        if (entity != null) {
            forgetEntity(entity);
            if (Minecraft.getInstance().level != null) {
                Minecraft.getInstance().level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
        profiles.remove(actor.id);
        lookups.remove(actor.id);
        animator.forget(actor);
        if (actor.hasSkinFile()) {
            // Picks up a skin file edited meanwhile.
            SkinLibrary.reload(actor.skin);
        }
        if (actor.isModel()) {
            // Picks up a model file edited in Blockbench meanwhile.
            com.vyrriox.justreplay.editor.model.ModelLibrary.reload(actor.model);
        }
    }

    /** Body parts hidden on a player actor (see {@link BodyParts}). */
    public static int hiddenParts(Entity entity) {
        Integer mask = HIDDEN_PARTS.get(entity.getId());
        return mask == null ? 0 : mask;
    }

    /** True for a player actor whose outer skin layer is switched off. */
    public static boolean hidesSkinLayers(Entity entity) {
        return isActorEntity(entity) && NO_SKIN_LAYERS.contains(entity.getId());
    }

    /** True for a player actor whose name tag is switched off. */
    public static boolean hidesName(Entity entity) {
        return isActorEntity(entity) && HIDDEN_NAMES.contains(entity.getId());
    }

    /**
     * The skin of a player actor wearing a skin file, or null to keep the vanilla skin (not an actor, no skin file,
     * or a file that cannot be loaded).
     */
    public static PlayerSkin customSkin(Entity entity) {
        if (!isActorEntity(entity)) {
            return null;
        }
        ReplayProject.ActorData actor = CUSTOM_SKINS.get(entity.getId());
        if (actor == null) {
            return null;
        }
        if (!actor.skinChanges.isEmpty()) {
            ReplayProject.SkinChange change = ReplayProject.SkinChange.at(actor.skinChanges, EntityEdits.time());
            if (change != null && !change.file.isBlank()) {
                PlayerSkin skin = fileSkin(change.file, change.slim);
                if (skin != null) {
                    return skin;
                }
            }
        }
        return actor.hasSkinFile() ? fileSkin(actor.skin, actor.slim) : null;
    }

    /** The skin of a file of the skins folder, or null when it cannot be loaded. */
    public static PlayerSkin fileSkin(String file, boolean slim) {
        ResourceLocation texture = SkinLibrary.get(file);
        if (texture == null) {
            return null;
        }
        return new PlayerSkin(texture, null, null, null, slim ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, true);
    }

    static boolean hasCustomSkins() {
        return !CUSTOM_SKINS.isEmpty();
    }

    static void clearCustomSkins() {
        CUSTOM_SKINS.clear();
    }

    private static void forgetEntity(Entity entity) {
        HIDDEN_NAMES.remove(entity.getId());
        CUSTOM_SKINS.remove(entity.getId());
        ActorAnimator.forget(entity);
        com.vyrriox.justreplay.editor.model.ModelRenderer.unregister(entity.getId());
    }

    public void remove(ReplayProject.ActorData actor) {
        refresh(actor);
        ids.remove(actor.id);
    }

    public void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        pumpLookups();
        int cursor = session.playback().cursor();
        boolean paused = session.playback().isPaused();
        for (ReplayProject.ActorData actor : session.project().actors) {
            Entity entity = spawned.get(actor.id);
            if (entity != null && (entity.isRemoved() || entity.level() != level)) {
                // The world was reset by a seek or a dimension change: the old entity is gone.
                forgetEntity(entity);
                spawned.remove(actor.id);
                entity = null;
            }
            if (!actor.visible) {
                if (entity != null) {
                    refresh(actor);
                }
                continue;
            }
            if (entity == null || entity.isRemoved() || entity.level() != level) {
                if (failed.contains(actor.id)) {
                    continue;
                }
                try {
                    entity = spawn(mc, level, actor);
                } catch (Throwable t) {
                    // A modded entity that cannot live client side: report once, never retry every tick.
                    failed.add(actor.id);
                    JustReplay.LOGGER.warn("Actor {} ({}) cannot be spawned: {}", actor.name, actor.type, t.toString());
                    entity = null;
                }
                if (entity == null) {
                    continue;
                }
                spawned.put(actor.id, entity);
            }
            if (actor.isPlayer()) {
                // Skin changes added in the editor apply at once; the entry follows the live actor data.
                if (actor.hasSkinFile() || !actor.skinChanges.isEmpty()) {
                    CUSTOM_SKINS.put(entity.getId(), actor);
                } else {
                    CUSTOM_SKINS.remove(entity.getId());
                }
            }
            fillMoveTargets(mc, actor);
            animate(actor, entity, cursor, paused);
            if (actor.id.equals(previewId)) {
                showPreview();
            }
        }
    }

    private Entity spawn(Minecraft mc, ClientLevel level, ReplayProject.ActorData actor) {
        Entity entity;
        if (actor.isPlayer()) {
            GameProfile profile = resolveProfile(actor);
            ClientPacketListener listener = mc.getConnection();
            if (listener != null) {
                // ADD_PLAYER keeps an existing entry, so drop it first to pick up the resolved skin.
                listener.handlePlayerInfoRemove(new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(List.of(profile.getId())));
                listener.handlePlayerInfoUpdate(PlayerInfoPackets.build(listener.registryAccess(), listener.getConnectionType(),
                        List.of(new PlayerInfoPackets.Info(profile.getId(), profile, GameType.SURVIVAL, false, 0, null))));
            }
            entity = new RemotePlayer(level, profile);
        } else if (actor.isModel()) {
            // An invisible armor stand carries the Blockbench model; ModelRenderer draws the model instead of it.
            net.minecraft.world.entity.decoration.ArmorStand stand = new net.minecraft.world.entity.decoration.ArmorStand(EntityType.ARMOR_STAND, level);
            stand.setInvisible(true);
            stand.setNoBasePlate(true);
            stand.noCulling = true;
            entity = stand;
        } else {
            Optional<EntityType<?>> type = EntityType.byString(actor.type);
            if (type.isEmpty()) {
                return null;
            }
            try {
                entity = type.get().create(level);
            } catch (Throwable t) {
                JustReplay.LOGGER.warn("Cannot create actor of type {}", actor.type, t);
                return null;
            }
            if (entity == null) {
                return null;
            }
            if (!actor.name.isBlank()) {
                entity.setCustomName(Component.literal(actor.name));
                entity.setCustomNameVisible(actor.showName);
            }
        }
        entity.setId(ids.computeIfAbsent(actor.id, k -> nextId--));
        if (actor.isPlayer() && (actor.hiddenParts & BodyParts.ALL) != 0) {
            HIDDEN_PARTS.put(entity.getId(), actor.hiddenParts & BodyParts.ALL);
        } else {
            HIDDEN_PARTS.remove(entity.getId());
        }
        if (actor.isPlayer() && actor.hideSkinLayers) {
            NO_SKIN_LAYERS.add(entity.getId());
        } else {
            NO_SKIN_LAYERS.remove(entity.getId());
        }
        if (actor.isPlayer() && !actor.showName) {
            HIDDEN_NAMES.add(entity.getId());
        } else {
            HIDDEN_NAMES.remove(entity.getId());
        }
        if (actor.hasSkinFile() || (actor.isPlayer() && !actor.skinChanges.isEmpty())) {
            CUSTOM_SKINS.put(entity.getId(), actor);
        } else {
            CUSTOM_SKINS.remove(entity.getId());
        }
        entity.setNoGravity(true);
        if (entity instanceof LivingEntity living && !actor.isModel()) {
            equip(living, EquipmentSlot.MAINHAND, actor.mainHand);
            equip(living, EquipmentSlot.OFFHAND, actor.offHand);
            equip(living, EquipmentSlot.HEAD, actor.head);
            equip(living, EquipmentSlot.CHEST, actor.chest);
            equip(living, EquipmentSlot.LEGS, actor.legs);
            equip(living, EquipmentSlot.FEET, actor.feet);
        }
        double[] state = Interpolation.actor(actor.keyframes, session.playback().cursor());
        if (state != null) {
            entity.moveTo(state[0], state[1], state[2], (float) state[3], (float) state[4]);
        } else if (mc.player != null) {
            entity.moveTo(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), 0);
        }
        level.addEntity(entity);
        if (actor.isModel()) {
            com.vyrriox.justreplay.editor.model.ModelRenderer.register(entity.getId(), actor);
        }
        return entity;
    }

    private static void equip(LivingEntity living, EquipmentSlot slot, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId.trim());
        if (id == null) {
            return;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item != Items.AIR) {
            living.setItemSlot(slot, new ItemStack(item));
        }
    }

    private GameProfile resolveProfile(ReplayProject.ActorData actor) {
        GameProfile resolved = profiles.get(actor.id);
        if (resolved != null) {
            return resolved;
        }
        UUID uuid = UUID.nameUUIDFromBytes(("justreplay:actor:" + actor.id).getBytes(StandardCharsets.UTF_8));
        String name = actor.name.isBlank() ? "Actor" : actor.name;
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        GameProfile profile = new GameProfile(uuid, name);
        if (actor.hasSkinFile() || !StringUtil.isValidPlayerName(actor.name)) {
            // A skin file needs no account; a name that cannot be an account has nothing to look up.
            profiles.put(actor.id, profile);
            return profile;
        }
        Lookup lookup = lookups.get(actor.id);
        if (lookup == null) {
            // Queued: the actor wears the default skin until its lookup had its turn.
            lookup = new Lookup(actor.id, actor.name);
            lookups.put(actor.id, lookup);
            lookupQueue.add(lookup);
        }
        if (lookup.done) {
            lookup.result.ifPresent(p -> profile.getProperties().putAll(p.getProperties()));
            profiles.put(actor.id, profile);
        }
        return profile;
    }

    /** Starts the queued profile lookups whose turn has come, at most {@link #LOOKUPS_PER_TICK} per tick. */
    private void pumpLookups() {
        if (lookupQueue.isEmpty()) {
            return;
        }
        long now = Util.getMillis();
        int started = 0;
        Iterator<Lookup> it = lookupQueue.iterator();
        while (it.hasNext() && started < LOOKUPS_PER_TICK) {
            Lookup lookup = it.next();
            if (lookups.get(lookup.actorId) != lookup) {
                // The actor was refreshed or removed meanwhile.
                it.remove();
                continue;
            }
            if (lookup.notBefore > now) {
                continue;
            }
            it.remove();
            start(lookup);
            started++;
        }
    }

    private void start(Lookup lookup) {
        CompletableFuture<Optional<GameProfile>> future;
        try {
            future = lookup.attempt == 0 ? SkullBlockEntity.fetchGameProfile(lookup.name) : retryLookup(lookup.name, lookup.knownId);
        } catch (Throwable t) {
            future = CompletableFuture.failedFuture(t);
        }
        future.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> complete(lookup, result, error)));
    }

    /**
     * Second attempt, past the caches of the first one (they keep a failed answer for minutes): the account id by
     * name unless the first attempt found it, then the profile with its skin. Runs off the main thread.
     */
    private static CompletableFuture<Optional<GameProfile>> retryLookup(String name, UUID knownId) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        GameProfileRepository repository = server == null ? null : server.getProfileRepository();
        MinecraftSessionService sessions = mc.getMinecraftSessionService();
        return CompletableFuture.supplyAsync(() -> {
            UUID id = knownId;
            if (id == null && repository != null) {
                AtomicReference<GameProfile> found = new AtomicReference<>();
                repository.findProfilesByNames(new String[]{name}, new ProfileLookupCallback() {
                    @Override
                    public void onProfileLookupSucceeded(GameProfile profile) {
                        found.set(profile);
                    }

                    @Override
                    public void onProfileLookupFailed(String profileName, Exception exception) {
                        found.set(null);
                    }
                });
                id = found.get() == null ? null : found.get().getId();
            }
            if (id == null) {
                return Optional.<GameProfile>empty();
            }
            ProfileResult result = sessions.fetchProfile(id, true);
            return Optional.ofNullable(result).map(ProfileResult::profile);
        }, Util.backgroundExecutor());
    }

    /** Main thread: keeps the profile and respawns the actor with its skin, or schedules the retry. */
    private void complete(Lookup lookup, Optional<GameProfile> result, Throwable error) {
        if (lookups.get(lookup.actorId) != lookup) {
            return;
        }
        boolean found = error == null && result != null && result.isPresent()
                && result.get().getProperties().containsKey("textures");
        if (!found && lookup.attempt == 0) {
            lookup.attempt = 1;
            lookup.notBefore = Util.getMillis() + LOOKUP_RETRY_MS;
            if (error == null && result != null && result.isPresent() && result.get().getId().version() == 4) {
                // The name was found (an offline profile has a version 3 id), only the skin request failed.
                lookup.knownId = result.get().getId();
            }
            lookupQueue.add(lookup);
            JustReplay.LOGGER.debug("Skin lookup of actor {} failed ({}), retrying in {} ms", lookup.name,
                    error != null ? error.toString() : "no skin", LOOKUP_RETRY_MS);
            return;
        }
        lookup.done = true;
        if (!found) {
            // The actor keeps the default skin it already wears: nothing to respawn.
            JustReplay.LOGGER.warn("Cannot fetch the skin of {} for an actor, it keeps the default skin{}", lookup.name,
                    error != null ? ": " + error : "");
            return;
        }
        lookup.result = result;
        profiles.remove(lookup.actorId);
        // Respawn the actor with its skin.
        Entity e = spawned.remove(lookup.actorId);
        if (e != null) {
            forgetEntity(e);
            if (Minecraft.getInstance().level != null) {
                Minecraft.getInstance().level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
    }

    private void animate(ReplayProject.ActorData actor, Entity entity, int cursor, boolean paused) {
        animator.apply(actor, session.project(), entity, cursor, paused);
    }

    /** A WALK_TO / RUN_TO added without a destination walks to where the camera stood when it was added. */
    private void fillMoveTargets(Minecraft mc, ReplayProject.ActorData actor) {
        if (mc.player == null) {
            return;
        }
        for (ReplayProject.ActorAction action : actor.actions) {
            ActionType type = action.actionType();
            if ((type == ActionType.WALK_TO || type == ActionType.RUN_TO) && ActorAnimator.parseVector(action.param) == null) {
                action.param = String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f",
                        mc.player.getX(), ActorAnimator.groundY(mc.player.getX(), mc.player.getY(), mc.player.getZ()), mc.player.getZ());
                session.saveProject();
            }
        }
    }

    /** Removes every spawned actor; they are spawned again from the project on the next tick. */
    public void clearAll() {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        for (Entity entity : spawned.values()) {
            if (level != null) {
                level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
        spawned.clear();
        failed.clear();
    }
}
