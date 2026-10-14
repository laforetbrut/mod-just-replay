package com.vyrriox.justreplay.editor;

import com.mojang.authlib.GameProfile;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.PlayerInfoPackets;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Spawns the actors of the project as client side entities and moves them along their keyframes. Actors are
 * rebuilt automatically when the replay world is reset by a seek or a dimension change.
 *
 * @author vyrriox
 */
public final class ActorManager {
    private final ReplaySession session;
    private final Map<String, Entity> spawned = new HashMap<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<String, CompletableFuture<Optional<GameProfile>>> lookups = new HashMap<>();
    private final Map<String, GameProfile> profiles = new HashMap<>();
    private int nextId = -1000;
    private final java.util.Set<String> failed = new java.util.HashSet<>();

    public ActorManager(ReplaySession session) {
        this.session = session;
    }

    /** True for entities spawned as actors; their own tick is skipped, animate() drives them. */
    public static boolean isActorEntity(Entity entity) {
        return entity.getId() <= -1000;
    }

    public Entity entityOf(ReplayProject.ActorData actor) {
        return spawned.get(actor.id);
    }

    /** Forces an actor to be rebuilt, after its type, skin or equipment changed. */
    public void refresh(ReplayProject.ActorData actor) {
        failed.remove(actor.id);
        Entity entity = spawned.remove(actor.id);
        if (entity != null && Minecraft.getInstance().level != null) {
            Minecraft.getInstance().level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        profiles.remove(actor.id);
        lookups.remove(actor.id);
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
        int cursor = session.playback().cursor();
        boolean paused = session.playback().isPaused();
        for (ReplayProject.ActorData actor : session.project().actors) {
            Entity entity = spawned.get(actor.id);
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
            animate(actor, entity, cursor, paused);
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
        entity.setNoGravity(true);
        if (entity instanceof LivingEntity living) {
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
        CompletableFuture<Optional<GameProfile>> lookup = lookups.get(actor.id);
        if (lookup == null && !actor.name.isBlank()) {
            lookup = SkullBlockEntity.fetchGameProfile(actor.name);
            lookups.put(actor.id, lookup);
            // Respawn the actor with its skin once the profile lookup completes.
            String id = actor.id;
            lookup.thenRun(() -> Minecraft.getInstance().execute(() -> {
                Entity e = spawned.remove(id);
                if (e != null && Minecraft.getInstance().level != null) {
                    Minecraft.getInstance().level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
                }
            }));
        }
        if (lookup == null) {
            profiles.put(actor.id, profile);
        } else if (lookup.isDone()) {
            lookup.getNow(Optional.empty()).ifPresent(p -> profile.getProperties().putAll(p.getProperties()));
            profiles.put(actor.id, profile);
        }
        return profile;
    }

    private void animate(ReplayProject.ActorData actor, Entity entity, int cursor, boolean paused) {
        Pose pose = parsePose(actor.pose);
        entity.setPose(pose);
        entity.setShiftKeyDown(pose == Pose.CROUCHING);
        if (actor.keyframes.isEmpty()) {
            entity.setOldPosAndRot();
            return;
        }
        double[] now = Interpolation.actor(actor.keyframes, cursor);
        double[] before = paused ? now : Interpolation.actor(actor.keyframes, cursor - 1);
        entity.xo = entity.xOld = before[0];
        entity.yo = entity.yOld = before[1];
        entity.zo = entity.zOld = before[2];
        entity.yRotO = (float) before[3];
        entity.xRotO = (float) before[4];
        entity.setPos(now[0], now[1], now[2]);
        entity.setYRot((float) now[3]);
        entity.setXRot((float) now[4]);
        entity.setDeltaMovement(0, 0, 0);
        if (entity instanceof LivingEntity living) {
            living.yHeadRotO = (float) before[3];
            living.yBodyRotO = (float) before[3];
            living.setYHeadRot((float) now[3]);
            living.yBodyRot = (float) now[3];
            double dx = now[0] - before[0];
            double dz = now[2] - before[2];
            float speed = (float) Math.min(1.0, Math.sqrt(dx * dx + dz * dz) * 4.0);
            living.walkAnimation.update(speed, 0.4F);
        }
    }

    private static Pose parsePose(String name) {
        try {
            return Pose.valueOf(name);
        } catch (Exception e) {
            return Pose.STANDING;
        }
    }
}
