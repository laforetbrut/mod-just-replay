package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.editor.WorldSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.GameRules;

/**
 * Applies the time and weather of the project to the replayed world at every rendered frame, after the recorded
 * packets of the tick are applied, with the replay time of the frame (partial tick included), so playback, previews
 * and the fixed timestep export show the same thing. The values are written into the vanilla level state (day time,
 * rain and thunder levels), so the sky, sky light, rain particles and sounds and shader packs all follow.
 * <p>
 * The recorded values are kept aside while an override is on: a value that changed since the last write came from
 * a packet (or from the day clock tick) and becomes the new recorded value, which is written back once the override
 * is cleared.
 *
 * @author vyrriox
 */
public final class WorldOverride {
    private static ClientLevel level;
    private static boolean applied;
    private static long recDay, recGame, lastDay;
    private static float recRain, recThunder, lastRain, lastThunder;

    private WorldOverride() {
    }

    public static void onRenderFrame(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        ReplaySession session = ReplaySession.current();
        ClientLevel lvl = mc.level;
        if (session == null || !ReplaySession.isRunning() || lvl == null) {
            level = null;
            applied = false;
            return;
        }
        WorldSettings world = session.project().world;
        apply(lvl, world, CameraController.frameTime(session, partialTick));
    }

    /** Writes the settings at a replay time into the level, or restores the recorded values when nothing is set. */
    public static void apply(ClientLevel lvl, WorldSettings world, double time) {
        ClientLevel.ClientLevelData data = lvl.getLevelData();
        if (lvl != level) {
            level = lvl;
            applied = false;
        }
        long game = data.getGameTime();
        if (!applied) {
            recDay = data.getDayTime();
            recRain = lvl.rainLevel;
            recThunder = lvl.thunderLevel;
        } else {
            long day = data.getDayTime();
            if (day != lastDay) {
                boolean cycle = lvl.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
                long ticks = game - recGame;
                // Only the day clock moved: the recorded time moves along. Anything else is a recorded SetTime.
                recDay = cycle && day - lastDay == ticks ? recDay + ticks : day;
            }
            if (lvl.rainLevel != lastRain) {
                recRain = lvl.rainLevel;
            }
            if (lvl.thunderLevel != lastThunder) {
                recThunder = lvl.thunderLevel;
            }
        }
        recGame = game;
        if (world == null || !world.active()) {
            if (applied) {
                data.setDayTime(recDay);
                lvl.setRainLevel(recRain);
                lvl.setThunderLevel(recThunder);
                applied = false;
            }
            return;
        }
        long day;
        if (world.timeOverridden()) {
            long t = (long) Math.floor(world.timeAt(time));
            // Whole days of the keyframes keep counting so the moon phase follows a day that passes.
            day = world.moonPhase >= 0 ? world.moonPhase * (long) WorldSettings.DAY + Math.floorMod(t, WorldSettings.DAY) : t;
        } else if (world.moonPhase >= 0) {
            day = world.moonPhase * (long) WorldSettings.DAY + Math.floorMod(recDay, WorldSettings.DAY);
        } else {
            day = recDay;
        }
        float rain = recRain, thunder = recThunder;
        switch (world.weather) {
            case WorldSettings.WEATHER_CLEAR -> {
                rain = 0.0F;
                thunder = 0.0F;
            }
            case WorldSettings.WEATHER_RAIN -> {
                rain = world.rainAt(time);
                thunder = 0.0F;
            }
            case WorldSettings.WEATHER_THUNDER -> {
                rain = world.rainAt(time);
                thunder = world.thunderAt(time);
            }
            default -> {
            }
        }
        // ClientLevelData directly: ClientLevel.setDayTime would also flip the daylight game rule.
        data.setDayTime(day);
        lvl.setRainLevel(rain);
        lvl.setThunderLevel(thunder);
        lastDay = day;
        lastRain = lvl.rainLevel;
        lastThunder = lvl.thunderLevel;
        applied = true;
    }

    /** Recorded day time last seen (the live day time when no override is applied). */
    public static long recordedDay() {
        Minecraft mc = Minecraft.getInstance();
        if (!applied && mc.level != null) {
            return mc.level.getLevelData().getDayTime();
        }
        return recDay;
    }

    /** Recorded day time, rain and thunder levels as last seen, for the self test. */
    public static String recorded() {
        return "day=" + recDay + " rain=" + recRain + " thunder=" + recThunder + " applied=" + applied;
    }
}
