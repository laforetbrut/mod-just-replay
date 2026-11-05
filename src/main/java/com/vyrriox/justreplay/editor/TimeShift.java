package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.editor.audio.AudioClip;
import com.vyrriox.justreplay.editor.audio.AudioTrack;
import com.vyrriox.justreplay.editor.fx.FxEffect;
import com.vyrriox.justreplay.editor.overlay.Overlay;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Ripple edits of the project timeline: inserting time pushes every edit at or after a tick later, removing time
 * deletes the edits of a range and pulls the later ones earlier. Only the edit moves; the recording stays as it is.
 * Items that straddle the tick keep their start and grow or shrink.
 *
 * @author vyrriox
 */
public final class TimeShift {
    private TimeShift() {
    }

    /** Pushes everything at or after {@code at} later by {@code ticks}. */
    public static void insert(ReplayProject project, int at, int ticks) {
        if (ticks > 0) {
            apply(project, at, ticks, false);
        }
    }

    /** Deletes the edits of {@code [at, at + ticks)} and pulls the later ones earlier. */
    public static void remove(ReplayProject project, int at, int ticks) {
        if (ticks > 0) {
            apply(project, at, ticks, true);
        }
    }

    private static void apply(ReplayProject p, int at, int n, boolean remove) {
        Shift s = new Shift(at, n, remove);
        if (p.cameraKeyframes != null) {
            s.points(p.cameraKeyframes, k -> k.tick, (k, t) -> k.tick = t);
        }
        for (ReplayProject.Camera camera : p.cameras) {
            s.points(camera.keyframes, k -> k.tick, (k, t) -> k.tick = t);
        }
        s.points(p.cuts, c -> c.tick, (c, t) -> c.tick = t);
        if (p.cutsEnd >= 0) {
            p.cutsEnd = s.clamp(p.cutsEnd);
        }
        s.points(p.markers, m -> m.tick, (m, t) -> m.tick = t);
        for (ReplayProject.ActorData actor : p.actors) {
            s.points(actor.keyframes, k -> k.tick, (k, t) -> k.tick = t);
            s.points(actor.skinChanges, c -> c.tick, (c, t) -> c.tick = t);
            actions(s, actor.actions);
        }
        for (AudioTrack track : p.audioTracks) {
            clips(s, track.clips);
        }
        for (FxEffect effect : p.effects) {
            keys(s, effect.keys);
        }
        Iterator<Overlay> overlays = p.overlays.iterator();
        while (overlays.hasNext()) {
            Overlay o = overlays.next();
            keys(s, o.keys);
            if (remove && o.start >= at && o.end < at + n) {
                overlays.remove();
                continue;
            }
            // An overlay across the tick keeps its start and grows or shrinks by its end.
            o.start = s.clamp(o.start);
            o.end = Math.max(o.start, s.clamp(o.end));
        }
        if (p.world != null) {
            s.points(p.world.timeKeys, k -> k.tick, (k, t) -> k.tick = t);
            s.points(p.world.rainKeys, k -> k.tick, (k, t) -> k.tick = t);
            s.points(p.world.thunderKeys, k -> k.tick, (k, t) -> k.tick = t);
        }
        for (ReplayProject.EntityEdit edit : p.entityEdits) {
            s.points(edit.offsets, k -> k.tick, (k, t) -> k.tick = t);
            s.points(edit.skinChanges, c -> c.tick, (c, t) -> c.tick = t);
            if (s.remove && edit.hidden && edit.hideTo >= 0 && edit.hideFrom >= s.at && edit.hideTo < s.at + s.n) {
                // The whole hidden range was removed: nothing is left to hide.
                edit.hidden = false;
                edit.hideFrom = 0;
                edit.hideTo = -1;
                continue;
            }
            edit.hideFrom = s.clamp(edit.hideFrom);
            if (edit.hideTo >= 0) {
                edit.hideTo = Math.max(edit.hideFrom, s.clamp(edit.hideTo));
            }
        }
        p.sort();
    }

    private static void keys(Shift s, Map<String, List<FxEffect.Key>> keys) {
        for (List<FxEffect.Key> list : keys.values()) {
            s.points(list, k -> k.tick, (k, t) -> k.tick = t);
        }
        keys.values().removeIf(List::isEmpty);
    }

    private static void actions(Shift s, List<ReplayProject.ActorAction> actions) {
        Iterator<ReplayProject.ActorAction> it = actions.iterator();
        while (it.hasNext()) {
            ReplayProject.ActorAction a = it.next();
            int start = a.tick;
            int end = start + Math.max(0, a.duration);
            if (s.remove && start >= s.at && start < s.at + s.n) {
                it.remove();
                continue;
            }
            a.tick = s.clamp(start);
            if (a.duration > 0 && start < s.at && end > s.at) {
                // Straddles the tick: grows with an insertion, loses the removed part.
                a.duration = Math.max(1, s.clamp(end) - a.tick);
            }
        }
    }

    private static void clips(Shift s, List<AudioClip> clips) {
        Iterator<AudioClip> it = clips.iterator();
        while (it.hasNext()) {
            AudioClip c = it.next();
            int start = c.start;
            if (start >= s.at) {
                if (s.remove && start < s.at + s.n) {
                    // Starts in the removed range: the part heard there goes, the rest starts at the tick.
                    double cut = s.at + s.n - start;
                    if (cut >= c.length()) {
                        it.remove();
                        continue;
                    }
                    if (c.loop) {
                        c.length -= (int) cut;
                        c.offset += cut * c.speed();
                    } else {
                        c.in += cut * c.speed();
                    }
                    c.start = s.at;
                } else {
                    c.start = s.clamp(start);
                }
            } else if (s.remove && c.end() > s.at) {
                // Straddles the tick: the sound keeps its start and ends at the tick, a recording cannot be split.
                double keep = s.at - start;
                if (c.loop) {
                    c.length = Math.max(1, (int) keep);
                } else {
                    c.out = c.in + keep * c.speed();
                }
            }
            c.clampFades();
        }
    }

    /** Maps one tick through the edit. */
    private static final class Shift {
        final int at;
        final int n;
        final boolean remove;

        Shift(int at, int n, boolean remove) {
            this.at = at;
            this.n = n;
            this.remove = remove;
        }

        /** New tick; a tick inside a removed range lands on {@link #at}. */
        int clamp(int tick) {
            if (tick < at) {
                return tick;
            }
            if (!remove) {
                return tick + n;
            }
            return tick < at + n ? at : tick - n;
        }

        /** Moves point items, dropping the ones inside a removed range. */
        <T> void points(List<T> list, ToIntFunction<T> get, java.util.function.ObjIntConsumer<T> set) {
            if (list == null) {
                return;
            }
            Iterator<T> it = list.iterator();
            while (it.hasNext()) {
                T item = it.next();
                int tick = get.applyAsInt(item);
                if (remove && tick >= at && tick < at + n) {
                    it.remove();
                } else {
                    set.accept(item, clamp(tick));
                }
            }
        }
    }
}
