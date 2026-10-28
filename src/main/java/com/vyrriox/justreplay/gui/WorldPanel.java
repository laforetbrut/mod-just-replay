package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.WorldSettings;
import com.vyrriox.justreplay.editor.fx.FxEffect.Key;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassSlider;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Segmented;
import com.vyrriox.justreplay.gui.ui.Stepper;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import com.vyrriox.justreplay.replay.WorldOverride;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * The "World" tab of the editor: weather and time of day on top of the recording. The simple mode shows the weather
 * buttons, a time slider and presets; the advanced mode adds keyframes at the play head for the time and the rain and
 * thunder strengths, freezing the time and the moon phase.
 *
 * @author vyrriox
 */
final class WorldPanel {
    private static final int[] PRESETS = {0, 6000, 12000, 18000};
    private static final String[] PRESET_KEYS = {"dawn", "noon", "dusk", "midnight"};

    private final EditorScreen screen;
    private final ReplaySession session;

    WorldPanel(EditorScreen screen, ReplaySession session) {
        this.screen = screen;
        this.session = session;
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    private WorldSettings world() {
        return session.project().world;
    }

    private int cursor() {
        return session.playback().cursor();
    }

    private void save() {
        session.saveProject();
    }

    void build(Form f, boolean advanced) {
        WorldSettings w = world();
        f.header(tc("justreplay.world.weather"), Icon.BOLT);
        f.full(new Segmented<>(WorldSettings.WEATHERS, w.weather, v -> tc("justreplay.world.weather." + v.toLowerCase()), null, v -> {
            world().weather = v;
            save();
            screen.refresh();
        }).tip(tc("justreplay.world.weather.tip")));
        if (advanced && (w.weather.equals(WorldSettings.WEATHER_RAIN) || w.weather.equals(WorldSettings.WEATHER_THUNDER))) {
            strength(f, "rain", w.rainKeys, () -> w.rain, v -> w.rain = v);
            if (w.weather.equals(WorldSettings.WEATHER_THUNDER)) {
                strength(f, "thunder", w.thunderKeys, () -> w.thunder, v -> w.thunder = v);
            }
        }

        f.header(tc("justreplay.world.time"), Icon.CLOCK);
        f.full(slider(0, WorldSettings.DAY - 100, 100, w.timeOverridden() ? currentTime() : WorldOverride.recordedDay() % WorldSettings.DAY,
                v -> Component.translatable("justreplay.world.time.value", clock(v)), v -> editTime((float) v)));
        for (int i = 0; i < PRESETS.length; i++) {
            int t = PRESETS[i];
            f.put(new GlassButton(tc("justreplay.world.preset." + PRESET_KEYS[i]), b -> {
                editTime(t);
                screen.refresh();
            }).tip(tc("justreplay.world.preset.tip")), i, 4);
        }
        f.next();
        f.put(new GlassButton(tc("justreplay.world.time.recorded"), b -> {
            world().timeMode = WorldSettings.TIME_RECORDED;
            save();
            screen.refresh();
        }).icon(Icon.RECORD).selected(() -> !world().timeOverridden()).tip(tc("justreplay.world.time.recorded.tip")), 0, 2);
        if (advanced) {
            f.put(new GlassButton(tc("justreplay.world.freeze"), b -> {
                world().timeMode = WorldSettings.TIME_FIXED;
                world().time = WorldSettings.wrap(WorldOverride.recordedDay() % WorldSettings.DAY);
                world().timeKeys.clear();
                save();
                screen.refresh();
            }).icon(Icon.PAUSE).tip(tc("justreplay.world.freeze.tip")), 1, 2);
        }
        f.next();
        if (advanced) {
            keyRow(f, "time", w.timeKeys, this::currentTime, () -> world().timeMode = WorldSettings.TIME_FIXED);
            f.paragraph(tc("justreplay.world.time.keys.help"), Theme.TEXT_MUTED);
            f.header(tc("justreplay.world.moon"), Icon.EYE);
            f.full(new Stepper(tc("justreplay.world.moon.phase"), () -> world().moonPhase + 1, "", 0,
                    () -> moon(-1), () -> moon(1)).icon(Icon.EYE).tip(tc("justreplay.world.moon.tip")));
        }
        f.paragraph(tc(world().active() ? "justreplay.world.active" : "justreplay.world.inactive"), Theme.TEXT_MUTED);
        if (world().active()) {
            f.full(new GlassButton(tc("justreplay.world.reset"), b -> {
                session.project().world = new WorldSettings();
                save();
                screen.refresh();
            }).icon(Icon.TRASH).tip(tc("justreplay.world.reset.tip")));
        }
    }

    private float currentTime() {
        return WorldSettings.wrap((float) world().timeAt(cursor()));
    }

    /** Sets the time as the user does: the keyframe at the play head when the time is animated, else the fixed time. */
    private void editTime(float value) {
        WorldSettings w = world();
        w.timeMode = WorldSettings.TIME_FIXED;
        float v = WorldSettings.wrap(value);
        if (w.timeKeys.isEmpty()) {
            w.time = v;
        } else {
            WorldSettings.setKey(w.timeKeys, cursor(), v);
        }
        save();
    }

    private void strength(Form f, String name, List<Key> keys, java.util.function.Supplier<Float> fixed, java.util.function.Consumer<Float> setFixed) {
        double initial = keys.isEmpty() ? fixed.get() : ("rain".equals(name) ? world().rainAt(cursor()) : world().thunderAt(cursor()));
        f.full(slider(0, 1, 0.05, initial, v -> Component.translatable("justreplay.world." + name + ".value", Math.round(v * 100)), v -> {
            if (keys.isEmpty()) {
                setFixed.accept((float) v);
            } else {
                WorldSettings.setKey(keys, cursor(), (float) v);
            }
            save();
        }));
        keyRow(f, name, keys, () -> keys.isEmpty() ? fixed.get()
                : ("rain".equals(name) ? world().rainAt(cursor()) : world().thunderAt(cursor())), null);
    }

    /** Add or remove the keyframe at the play head, and clear all keyframes. */
    private void keyRow(Form f, String name, List<Key> keys, java.util.function.Supplier<Float> current, Runnable onAdd) {
        f.put(new GlassButton(tc("justreplay.world.key"), b -> {
            Key key = WorldSettings.keyAt(keys, cursor());
            if (key != null) {
                keys.remove(key);
            } else {
                if (onAdd != null) {
                    onAdd.run();
                }
                WorldSettings.setKey(keys, cursor(), current.get());
            }
            save();
            screen.refresh();
        }).icon(Icon.KEY).selected(() -> WorldSettings.keyAt(keys, cursor()) != null)
                .tip(Component.translatable("justreplay.world.key.tip", tc("justreplay.world." + name))), 0, 2);
        f.put(new GlassButton(Component.translatable("justreplay.world.keys.clear", keys.size()), b -> {
            keys.clear();
            save();
            screen.refresh();
        }).icon(Icon.TRASH).enabled(!keys.isEmpty()).tip(tc("justreplay.world.keys.clear.tip")), 1, 2);
        f.next();
    }

    private void moon(int delta) {
        world().moonPhase = Math.max(-1, Math.min(7, world().moonPhase + delta));
        save();
    }

    private GlassSlider slider(double min, double max, double step, double initial, java.util.function.DoubleFunction<Component> format,
                               DoubleConsumer onChange) {
        return new GlassSlider(0, min, max, step, Math.max(min, Math.min(max, initial)), format, onChange);
    }

    /** Time of day as a 24 hour clock: tick 0 is 06:00. */
    static String clock(double ticks) {
        int minutes = (int) Math.round(((ticks + 6000) % WorldSettings.DAY) * 1440.0 / WorldSettings.DAY) % 1440;
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
