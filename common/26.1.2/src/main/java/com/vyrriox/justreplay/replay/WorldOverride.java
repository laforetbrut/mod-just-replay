package com.vyrriox.justreplay.replay;

import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.editor.WorldSettings;
import com.vyrriox.justreplay.mixin.LevelWeatherAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.gamerules.GameRules;

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
            recDay = dayTime(lvl);
            recRain = ((LevelWeatherAccessor) lvl).justreplay$getRainLevel();
            recThunder = ((LevelWeatherAccessor) lvl).justreplay$getThunderLevel();
        } else {
            long day = dayTime(lvl);
            if (day != lastDay) {
                float rate = dayRate(lvl);
                long moved = day - lastDay;
                long ticks = game - recGame;
                // Only the day clock moved: the recorded time moves along. Anything else is a recorded SetTime.
                recDay = rate > 0.0F && Math.abs(moved - ticks * rate) <= 1.0F ? recDay + moved : day;
            }
            if (((LevelWeatherAccessor) lvl).justreplay$getRainLevel() != lastRain) {
                recRain = ((LevelWeatherAccessor) lvl).justreplay$getRainLevel();
            }
            if (((LevelWeatherAccessor) lvl).justreplay$getThunderLevel() != lastThunder) {
                recThunder = ((LevelWeatherAccessor) lvl).justreplay$getThunderLevel();
            }
        }
        recGame = game;
        if (world == null || !world.active()) {
            if (applied) {
                setDayTime(lvl, recDay);
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
        setDayTime(lvl, day);
        lvl.setRainLevel(rain);
        lvl.setThunderLevel(thunder);
        lastDay = day;
        lastRain = ((LevelWeatherAccessor) lvl).justreplay$getRainLevel();
        lastThunder = ((LevelWeatherAccessor) lvl).justreplay$getThunderLevel();
        applied = true;
    }

    /** Day time of the default world clock of the level (ticks), 0 when the dimension has none. */
    private static long dayTime(ClientLevel lvl) {
        return lvl.getDefaultClockTime();
    }

    /** Rate of the default world clock of the level as last sent by the recording, 0 when the day cycle is stopped. */
    private static float dayRate(ClientLevel lvl) {
        return lvl.dimensionType().defaultClock().map(clock -> ((ClockRates) lvl.clockManager()).justreplay$rate(clock)).orElse(1.0F);
    }

    /**
     * Sets the default world clock of the level to a total tick count, keeping the recorded rate. The sky reads the
     * clock through environment attributes cached per tick, so that cache is dropped for the change to show this frame.
     */
    private static void setDayTime(ClientLevel lvl, long ticks) {
        lvl.dimensionType().defaultClock().ifPresent(clock -> {
            float partial = ((ClockRates) lvl.clockManager()).justreplay$partialTick(clock);
            float rate = ((ClockRates) lvl.clockManager()).justreplay$rate(clock);
            lvl.clockManager().handleUpdates(lvl.getGameTime(),
                    java.util.Map.of(clock, new net.minecraft.world.clock.ClockNetworkState(ticks, partial, rate)));
            lvl.environmentAttributes().invalidateTickCache();
        });
    }

    /** Recorded day time last seen (the live day time when no override is applied). */
    public static long recordedDay() {
        Minecraft mc = Minecraft.getInstance();
        if (!applied && mc.level != null) {
            return dayTime(mc.level);
        }
        return recDay;
    }

    /** Recorded day time, rain and thunder levels as last seen, for the self test. */
    public static String recorded() {
        return "day=" + recDay + " rain=" + recRain + " thunder=" + recThunder + " applied=" + applied;
    }
}
