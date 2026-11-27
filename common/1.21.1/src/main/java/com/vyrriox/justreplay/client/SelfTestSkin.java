package com.vyrriox.justreplay.client;

import com.mojang.authlib.GameProfile;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.record.PlayerInfoPackets;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;

import java.util.List;
import java.util.UUID;

/**
 * Self test of the actor's skin: the recording player wears the skin of {@link SkinTextures#SELFTEST_SOURCE} (the
 * offline player of a dev game has none), which the actor must keep when playing, after seeks and from a recording
 * that lost it.
 *
 * @author vyrriox
 */
final class SelfTestSkin {
    private static boolean faced;
    private static net.minecraft.world.phys.Vec3 savedPos;
    private static float savedYaw;
    private static float savedPitch;
    private static String pendingPhase;
    private static ReplaySession pendingSession;
    private static int pendingTicks;

    /** Every self test tick: a skin check still waiting for its texture is asked again. */
    static void tick(Minecraft mc) {
        if (pendingPhase != null) {
            check(mc, pendingSession, pendingPhase);
        }
    }

    private SelfTestSkin() {
    }

    /** Puts the paused replay camera 3.5 blocks in front of the recording player's actor, for a screenshot of its skin. */
    static void face(Minecraft mc, ReplaySession s) {
        faced = false;
        UUID actor = s == null ? null : s.actorUuid();
        if (actor == null || mc.player == null || mc.level == null) {
            return;
        }
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (actor.equals(e.getUUID())) {
                savedPos = mc.player.position();
                savedYaw = mc.player.getYRot();
                savedPitch = mc.player.getXRot();
                net.minecraft.world.phys.Vec3 look = net.minecraft.world.phys.Vec3.directionFromRotation(0, e.getYRot());
                mc.player.setPos(e.getX() + look.x * 3.5, e.getY(), e.getZ() + look.z * 3.5);
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, e.getEyePosition());
                log("skin: camera in front of the actor at {}", e.blockPosition());
                faced = true;
                return;
            }
        }
    }

    /** Puts the camera back where {@link #face} found it. */
    static void restore(Minecraft mc) {
        if (faced && mc.player != null && savedPos != null) {
            mc.player.setPos(savedPos.x, savedPos.y, savedPos.z);
            mc.player.setYRot(savedYaw);
            mc.player.setXRot(savedPitch);
        }
        faced = false;
    }

    static boolean faced() {
        return faced;
    }

    private static void log(String message, Object... args) {
        JustReplay.LOGGER.info("SELFTEST " + message, args);
    }

    /** False (and logged) when the stand-in's skin could not be fetched: no network, nothing to check. */
    private static boolean online(String phase) {
        if (SkinTextures.now(SkinTextures.SELFTEST_SOURCE) == null) {
            log("skin {}: no signed skin for the stand-in profile (offline or session server down) -> skipped", phase);
            return false;
        }
        return true;
    }

    static void check(Minecraft mc, ReplaySession s, String phase) {
        UUID actor = s == null ? null : s.actorUuid();
        if (actor == null || mc.getConnection() == null || !online(phase)) {
            return;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(actor);
        boolean signed = info != null && SkinTextures.signed(info.getProfile().getProperties()) != null;
        Object skin = info == null ? null : info.getSkin().texture();
        boolean own = skin != null && !skin.equals(DefaultPlayerSkin.get(actor).texture());
        if (signed && !own && pendingTicks < 60) {
            // The skin of a new tab entry loads on its own threads (on 1.20.1 only once it is first asked for).
            pendingPhase = phase;
            pendingSession = s;
            pendingTicks++;
            return;
        }
        pendingPhase = null;
        log("skin {}: actor's tab entry has signed textures={}, skin={} (after {} ticks) -> {}", phase, signed, skin, pendingTicks,
                signed && own ? "OK" : "FAIL");
        pendingTicks = 0;
    }

    /** An actor entry recorded without textures (replays of 1.2.0 made in singleplayer) gets its player's skin back. */
    static void checkRepair(Minecraft mc, ReplaySession s) {
        UUID actor = s == null ? null : s.actorUuid();
        if (actor == null || mc.getConnection() == null || !online("repair")) {
            return;
        }
        ClientboundPlayerInfoUpdatePacket bare = PlayerInfoPackets.build(mc.getConnection(), List.of(
                new PlayerInfoPackets.Info(actor, new GameProfile(actor, "SkinTest"), GameType.SURVIVAL, false, 0, null)));
        boolean before = SkinTextures.signed(bare.entries().get(0).profile().getProperties()) != null;
        bare = SkinTextures.repair(bare, actor, s.recorderUuid());
        boolean after = SkinTextures.signed(bare.entries().get(0).profile().getProperties()) != null;
        log("skin repair of an actor entry recorded without textures: signed before={}, after={} -> {}", before, after,
                !before && after ? "OK" : "FAIL");
    }
}
