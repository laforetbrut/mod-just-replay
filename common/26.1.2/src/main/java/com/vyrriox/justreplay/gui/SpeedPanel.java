package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.SpeedTrack;
import com.vyrriox.justreplay.editor.TimeMap;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Stepper;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The speed track in the Effects tab: speed keys at the play head (held or ramping to the next key), freezes and
 * timelapses, and the speeds the game sounds play between; and its lane on the timeline. Exported videos and the
 * preview follow it; cameras, texts and effects stay on the replay's clock.
 *
 * @author vyrriox
 */
final class SpeedPanel {
    /** Speeds the steppers go through. */
    private static final float[] LADDER = {0.1F, 0.2F, 0.25F, 0.33F, 0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 2.0F, 3.0F, 4.0F, 6.0F, 8.0F};
    static final int COLOR = 0xFF7FE0C8;
    private static final int FREEZE_COLOR = 0xFFB9A2FF;

    private final EditorScreen screen;
    private final ReplaySession session;
    /** Start of a timelapse being made, or -1. */
    private int lapseFrom = -1;

    SpeedPanel(EditorScreen screen, ReplaySession session) {
        this.screen = screen;
        this.session = session;
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    private SpeedTrack track() {
        return session.project().speed;
    }

    private int cursor() {
        return session.playback().cursor();
    }

    private void save() {
        track().normalize();
        session.saveProject();
        screen.refresh();
    }

    static String speed(double value) {
        return "x" + (Math.abs(value - Math.round(value)) < 0.005 ? Integer.toString((int) Math.round(value))
                : String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", ""));
    }

    private static float step(float value, int direction) {
        if (direction > 0) {
            for (float v : LADDER) {
                if (v > value + 0.001F) {
                    return v;
                }
            }
            return LADDER[LADDER.length - 1];
        }
        for (int i = LADDER.length - 1; i >= 0; i--) {
            if (LADDER[i] < value - 0.001F) {
                return LADDER[i];
            }
        }
        return LADDER[0];
    }

    void build(Form f) {
        SpeedTrack t = track();
        f.header(tc("justreplay.speed.section"), Icon.CLOCK);
        f.paragraph(tc("justreplay.speed.help"), Theme.TEXT_MUTED);
        SpeedTrack.Key key = t.keyAt(cursor());
        f.full(new Stepper(tc(key != null ? "justreplay.speed.key" : "justreplay.speed.here"), () -> {
            SpeedTrack.Key k = track().keyAt(cursor());
            return k != null ? k.speed : track().speedAt(cursor());
        }, "", 2, () -> setSpeed(-1), () -> setSpeed(1)).icon(Icon.CLOCK).tip(tc("justreplay.speed.key.tip")));
        if (key != null) {
            f.full(new GlassSwitch(tc("justreplay.speed.ramp"), () -> {
                SpeedTrack.Key k = track().keyAt(cursor());
                return k != null && k.ramp;
            }, () -> {
                SpeedTrack.Key k = track().keyAt(cursor());
                if (k != null) {
                    k.ramp = !k.ramp;
                    save();
                }
            }).icon(Icon.ANGLE).tip(tc("justreplay.speed.ramp.tip")));
            f.full(new GlassButton(tc("justreplay.speed.key.remove"), b -> {
                track().keys.removeIf(k -> k.tick == cursor());
                save();
            }).icon(Icon.TRASH).tip(tc("justreplay.speed.key.remove.tip")));
        }

        f.full(new Stepper(tc("justreplay.speed.freeze"), () -> track().freezeAt(cursor()), " s", 1,
                () -> setFreeze(-0.5F), () -> setFreeze(0.5F)).icon(Icon.PAUSE).tip(tc("justreplay.speed.freeze.tip")));

        SpeedTrack.Timelapse lapse = t.timelapseAt(cursor());
        if (lapse != null) {
            f.full(new Stepper(Component.translatable("justreplay.speed.timelapse.length", Timeline.formatTicks(lapse.from),
                    Timeline.formatTicks(lapse.to)), () -> {
                SpeedTrack.Timelapse l = track().timelapseAt(cursor());
                return l == null ? 0 : l.seconds;
            }, " s", 1, () -> setLapseSeconds(-1.0F), () -> setLapseSeconds(1.0F)).icon(Icon.FORWARD)
                    .tip(tc("justreplay.speed.timelapse.length.tip")));
            f.full(new GlassButton(tc("justreplay.speed.timelapse.remove"), b -> {
                track().timelapses.removeIf(l -> cursor() >= l.from && cursor() < l.to);
                save();
            }).icon(Icon.TRASH));
        } else if (lapseFrom < 0) {
            f.full(new GlassButton(tc("justreplay.speed.timelapse.from"), b -> {
                lapseFrom = cursor();
                screen.refresh();
            }).icon(Icon.FORWARD).tip(tc("justreplay.speed.timelapse.from.tip")));
        } else {
            f.put(new GlassButton(Component.translatable("justreplay.speed.timelapse.to", Timeline.formatTicks(lapseFrom)), b -> {
                int from = Math.min(lapseFrom, cursor());
                int to = Math.max(lapseFrom, cursor());
                lapseFrom = -1;
                if (to > from) {
                    // Five seconds of video by default, whatever the length of the stretch.
                    track().timelapses.add(new SpeedTrack.Timelapse(from, to, 5.0F));
                }
                save();
            }).icon(Icon.CHECK).style(GlassButton.Style.PRIMARY), 0, 2);
            f.put(new GlassButton(tc("justreplay.speed.timelapse.cancel"), b -> {
                lapseFrom = -1;
                screen.refresh();
            }).icon(Icon.CLOSE), 1, 2);
            f.next();
        }

        f.full(new Stepper(tc("justreplay.speed.sounds.min"), () -> track().soundMin, "", 2,
                () -> setSoundRange(true, -1), () -> setSoundRange(true, 1)).icon(Icon.SPEAKER).tip(tc("justreplay.speed.sounds.tip")));
        f.full(new Stepper(tc("justreplay.speed.sounds.max"), () -> track().soundMax, "", 2,
                () -> setSoundRange(false, -1), () -> setSoundRange(false, 1)).icon(Icon.SPEAKER).tip(tc("justreplay.speed.sounds.tip")));
        if (!t.isEmpty()) {
            int[] range = session.project().exportRange(session.playback().duration());
            if (range != null) {
                double seconds = TimeMap.of(t, range[0], range[1]).durationSeconds();
                f.text(() -> Component.translatable("justreplay.speed.video_length", Timeline.formatTicks(seconds * 20.0),
                        Timeline.formatTicks(range[1] - range[0])), Theme.TEXT_DIM);
            }
            f.full(new GlassButton(tc("justreplay.speed.clear"), b -> {
                session.project().speed = new SpeedTrack();
                lapseFrom = -1;
                save();
            }).icon(Icon.TRASH).style(GlassButton.Style.DANGER).tip(tc("justreplay.speed.clear.tip")));
        }
    }

    private void setSpeed(int direction) {
        SpeedTrack t = track();
        SpeedTrack.Key k = t.keyAt(cursor());
        float from = k != null ? k.speed : (float) t.speedAt(cursor());
        t.setKey(cursor(), step(from, direction));
        save();
    }

    private void setFreeze(float delta) {
        SpeedTrack t = track();
        float seconds = (float) t.freezeAt(cursor()) + delta;
        t.freezes.removeIf(fr -> fr.tick == cursor());
        if (seconds > 0.01F) {
            t.freezes.add(new SpeedTrack.Freeze(cursor(), seconds));
        }
        save();
    }

    private void setLapseSeconds(float delta) {
        SpeedTrack.Timelapse l = track().timelapseAt(cursor());
        if (l != null) {
            l.seconds = SpeedTrack.clampSeconds(Math.max(1.0F, Math.round(l.seconds + delta)));
            save();
        }
    }

    private void setSoundRange(boolean min, int direction) {
        SpeedTrack t = track();
        if (min) {
            t.soundMin = Math.min(t.soundMax, step(t.soundMin, direction));
        } else {
            t.soundMax = Math.max(t.soundMin, step(t.soundMax, direction));
        }
        save();
    }

    // ---------------------------------------------------------------- timeline lane

    /** Draws the speed lane: timelapses as bars, keys as ticks with their speed, freezes as pause signs. */
    static void renderLane(GuiGraphicsExtractor g, Font font, EditorTimeline timeline, SpeedTrack track, int y, int h) {
        for (SpeedTrack.Timelapse lapse : track.timelapses) {
            int x0 = timeline.xOf(lapse.from);
            int x1 = timeline.xOf(lapse.to);
            if (x1 < timeline.x || x0 > timeline.x + timeline.w) {
                continue;
            }
            Paint.rect(g, x0, y + 2, Math.max(2, x1 - x0), h - 4, 2, 0x407FE0C8);
            String label = speed(lapse.speed());
            if (x1 - x0 > font.width(label) + 4) {
                g.text(font, label, x0 + 2, y + (h - 7) / 2, 0xD9FFFFFF, false);
            }
        }
        int lastEnd = Integer.MIN_VALUE;
        for (int i = 0; i < track.keys.size(); i++) {
            SpeedTrack.Key key = track.keys.get(i);
            int kx = timeline.xOf(key.tick);
            if (key.ramp && i + 1 < track.keys.size()) {
                int nx = timeline.xOf(track.keys.get(i + 1).tick);
                g.fill(kx, y + h / 2, Math.max(kx + 1, nx), y + h / 2 + 1, 0x997FE0C8);
            }
            if (kx < timeline.x - 40 || kx > timeline.x + timeline.w + 2) {
                continue;
            }
            g.fill(kx, y + 1, kx + 1, y + h - 1, COLOR);
            String label = speed(key.speed);
            if (kx + 3 > lastEnd) {
                g.text(font, label, kx + 3, y + (h - 7) / 2, 0xD9C8FFF0, false);
                lastEnd = kx + 3 + font.width(label) + 3;
            }
        }
        for (SpeedTrack.Freeze freeze : track.freezes) {
            int fx = timeline.xOf(freeze.tick);
            if (fx < timeline.x - 4 || fx > timeline.x + timeline.w + 4) {
                continue;
            }
            g.fill(fx - 2, y + 2, fx - 1, y + h - 2, FREEZE_COLOR);
            g.fill(fx + 1, y + 2, fx + 2, y + h - 2, FREEZE_COLOR);
        }
    }

    /** Tick of the key, freeze or timelapse start under the mouse, or -1. */
    static int hit(EditorTimeline timeline, SpeedTrack track, double mouseX) {
        for (SpeedTrack.Key key : track.keys) {
            if (Math.abs(timeline.xOf(key.tick) - mouseX) <= 3) {
                return key.tick;
            }
        }
        for (SpeedTrack.Freeze freeze : track.freezes) {
            if (Math.abs(timeline.xOf(freeze.tick) - mouseX) <= 3) {
                return freeze.tick;
            }
        }
        for (SpeedTrack.Timelapse lapse : track.timelapses) {
            if (mouseX >= timeline.xOf(lapse.from) && mouseX <= timeline.xOf(lapse.to)) {
                return lapse.from;
            }
        }
        return -1;
    }

    /** Tooltip of the lane at the mouse, or null. */
    static Component tooltip(EditorTimeline timeline, SpeedTrack track, double mouseX) {
        for (SpeedTrack.Key key : track.keys) {
            if (Math.abs(timeline.xOf(key.tick) - mouseX) <= 3) {
                return Component.translatable(key.ramp ? "justreplay.speed.tip.ramp" : "justreplay.speed.tip.key", speed(key.speed),
                        Timeline.formatTicks(key.tick));
            }
        }
        for (SpeedTrack.Freeze freeze : track.freezes) {
            if (Math.abs(timeline.xOf(freeze.tick) - mouseX) <= 3) {
                return Component.translatable("justreplay.speed.tip.freeze", String.format(Locale.ROOT, "%.1f", freeze.seconds),
                        Timeline.formatTicks(freeze.tick));
            }
        }
        for (SpeedTrack.Timelapse lapse : track.timelapses) {
            if (mouseX >= timeline.xOf(lapse.from) && mouseX <= timeline.xOf(lapse.to)) {
                return Component.translatable("justreplay.speed.tip.timelapse", speed(lapse.speed()), Timeline.formatTicks(lapse.from),
                        Timeline.formatTicks(lapse.to), String.format(Locale.ROOT, "%.1f", lapse.seconds));
            }
        }
        return null;
    }
}
