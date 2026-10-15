package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.audio.AudioClip;
import com.vyrriox.justreplay.editor.audio.AudioLibrary;
import com.vyrriox.justreplay.editor.audio.AudioPcm;
import com.vyrriox.justreplay.editor.audio.AudioTrack;
import com.vyrriox.justreplay.gui.ui.Dropdown;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Stepper;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The "Audio" tab of the editor: audio files of the audio folder, game sound volume and ducking, the track list
 * (volume, mute, solo) and the properties of the clip selected on the timeline (volume, fades, trim, speed, loop).
 *
 * @author vyrriox
 */
final class AudioPanel {
    /** File chosen in the file list, kept across rebuilds. */
    private static String picked;

    private final EditorScreen screen;
    private final ReplaySession session;
    private final AudioLanes lanes;
    private Component status;

    AudioPanel(EditorScreen screen, ReplaySession session, AudioLanes lanes) {
        this.screen = screen;
        this.session = session;
        this.lanes = lanes;
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    private ReplayProject project() {
        return session.project();
    }

    private void save() {
        session.saveProject();
    }

    /** Small step, or a fine one with Shift held. */
    private static double step(double normal, double fine) {
        return Screen.hasShiftDown() ? fine : normal;
    }

    // ---------------------------------------------------------------- layout

    void build(Form f) {
        lanes.validate();
        buildFiles(f);
        buildGame(f);
        buildTracks(f);
        if (lanes.selectedClip != null) {
            buildClip(f, lanes.selectedClip);
        }
    }

    private void buildFiles(Form f) {
        f.header(tc("justreplay.audio.files"), Icon.FOLDER);
        List<String> files = AudioLibrary.list();
        if (picked == null || !files.contains(picked)) {
            picked = files.isEmpty() ? null : files.get(0);
        }
        if (files.isEmpty()) {
            f.paragraph(tc("justreplay.audio.files.none"), Theme.TEXT_MUTED);
        } else {
            f.full(new Dropdown<>(screen, tc("justreplay.audio.file"), files, picked, Component::literal, v -> picked = v)
                    .icon(Icon.SPEAKER).tip(tc("justreplay.audio.file.tip")));
        }
        f.put(new GlassButton(tc("justreplay.audio.add_clip"), b -> addClip()).icon(Icon.PLUS).style(GlassButton.Style.PRIMARY)
                .enabled(picked != null).tip(tc("justreplay.audio.add_clip.tip")), 0, 2);
        f.put(new GlassButton(tc("justreplay.audio.folder"), b -> Util.getPlatform().openPath(AudioLibrary.folder())).icon(Icon.FOLDER)
                .tip(tc("justreplay.audio.folder.tip")), 1, 2);
        f.next();
        f.text(() -> status, Theme.WARNING);
        List<String> unsupported = AudioLibrary.unsupported();
        if (!unsupported.isEmpty()) {
            f.paragraph(Component.translatable("justreplay.audio.convert", unsupported.size()), Theme.WARNING);
        }
        f.paragraph(tc("justreplay.audio.formats"), Theme.TEXT_MUTED);
    }

    private void buildGame(Form f) {
        f.header(tc("justreplay.audio.game"), Icon.SPEAKER);
        f.full(new Stepper(tc("justreplay.audio.game.volume"), () -> Math.round(project().gameVolume * 100), "%", 0,
                () -> gameVolume(-step(0.1, 0.01)), () -> gameVolume(step(0.1, 0.01)))
                .icon(Icon.SPEAKER).enabled(() -> !project().muteGame).tip(tc("justreplay.audio.game.volume.tip")));
        f.full(new GlassSwitch(tc("justreplay.audio.game.mute"), () -> project().muteGame, () -> {
            project().muteGame = !project().muteGame;
            save();
        }).icon(Icon.SPEAKER_OFF).tip(tc("justreplay.audio.game.mute.tip")));
        f.full(new GlassSwitch(tc("justreplay.audio.ducking"), () -> project().ducking, () -> {
            project().ducking = !project().ducking;
            save();
        }).icon(Icon.ARROW_DOWN).tip(tc("justreplay.audio.ducking.tip")));
        f.full(new Stepper(tc("justreplay.audio.ducking.amount"), () -> Math.round(project().duckAmount * 100), "%", 0,
                () -> duckAmount(-0.1), () -> duckAmount(0.1)).icon(Icon.ARROW_DOWN).enabled(() -> project().ducking));
    }

    private void gameVolume(double delta) {
        project().gameVolume = (float) clamp(Math.round((project().gameVolume + delta) * 100) / 100.0, 0.0, 1.0);
        save();
    }

    private void duckAmount(double delta) {
        project().duckAmount = (float) clamp(Math.round((project().duckAmount + delta) * 10) / 10.0, 0.1, 1.0);
        save();
    }

    private void buildTracks(Form f) {
        f.header(tc("justreplay.audio.tracks"), Icon.SORT);
        List<AudioTrack> tracks = project().audioTracks;
        if (tracks.isEmpty()) {
            f.paragraph(tc("justreplay.audio.tracks.none"), Theme.TEXT_MUTED);
        }
        for (int i = 0; i < tracks.size(); i++) {
            AudioTrack track = tracks.get(i);
            String detail = (track.solo ? "S  " : "") + Math.round(track.volume * 100) + "%  " + track.clips.size();
            f.full(new ListRow(Component.literal(track.name), b -> {
                lanes.selectedTrack = track;
                lanes.reveal(track);
                screen.refresh();
            }).dot(AudioLanes.color(i)).detail(detail).action(() -> track.mute ? Icon.SPEAKER_OFF : Icon.SPEAKER,
                    tc("justreplay.audio.track.mute.tip"), () -> {
                        track.mute = !track.mute;
                        save();
                    }).selected(() -> track == lanes.selectedTrack).tip(tc("justreplay.audio.track.row.tip")));
        }
        f.put(new GlassButton(tc("justreplay.audio.track.add"), b -> addTrack()).icon(Icon.PLUS), 0, 3);
        AudioTrack selected = lanes.selectedTrack;
        f.put(new GlassButton(tc("justreplay.editor.cam.rename"), b -> renameTrack()).icon(Icon.EDIT).enabled(selected != null), 1, 3);
        f.put(new GlassButton(tc("justreplay.editor.delete"), b -> screen.confirm(tc("justreplay.audio.track.delete.confirm"), this::deleteTrack))
                .icon(Icon.TRASH).style(GlassButton.Style.DANGER).enabled(selected != null), 2, 3);
        f.next();
        if (selected == null) {
            return;
        }
        f.full(new Stepper(tc("justreplay.audio.volume"), () -> Math.round(selected.volume * 100), "%", 0,
                () -> trackVolume(selected, -step(0.05, 0.01)), () -> trackVolume(selected, step(0.05, 0.01))).icon(Icon.SPEAKER));
        f.put(new GlassSwitch(tc("justreplay.audio.track.mute"), () -> selected.mute, () -> {
            selected.mute = !selected.mute;
            save();
        }).icon(Icon.SPEAKER_OFF), 0, 2);
        f.put(new GlassSwitch(tc("justreplay.audio.track.solo"), () -> selected.solo, () -> {
            selected.solo = !selected.solo;
            save();
        }).icon(Icon.TARGET), 1, 2);
        f.next();
    }

    private void trackVolume(AudioTrack track, double delta) {
        track.volume = (float) clamp(Math.round((track.volume + delta) * 100) / 100.0, 0.0, 2.0);
        save();
    }

    private void buildClip(Form f, AudioClip clip) {
        f.header(Component.literal(AudioLanes.label(clip)), Icon.EDIT);
        f.text(() -> Component.translatable("justreplay.audio.clip.info", Timeline.formatTicks(clip.start),
                Timeline.formatTicks(clip.length())), Theme.TEXT_DIM);
        f.text(this::clipState, Theme.WARNING);
        f.full(new Stepper(tc("justreplay.audio.volume"), () -> Math.round(clip.volume * 100), "%", 0,
                () -> clipVolume(clip, -step(0.05, 0.01)), () -> clipVolume(clip, step(0.05, 0.01))).icon(Icon.SPEAKER));
        f.full(new Stepper(tc("justreplay.audio.fade_in"), () -> clip.fadeIn / 20.0, "s", 2,
                () -> fade(clip, true, -step(5, 1)), () -> fade(clip, true, step(5, 1))).icon(Icon.ARROW_UP)
                .tip(tc("justreplay.audio.fine.tip")));
        f.full(new Stepper(tc("justreplay.audio.fade_out"), () -> clip.fadeOut / 20.0, "s", 2,
                () -> fade(clip, false, -step(5, 1)), () -> fade(clip, false, step(5, 1))).icon(Icon.ARROW_DOWN)
                .tip(tc("justreplay.audio.fine.tip")));
        f.full(new Stepper(tc("justreplay.audio.trim_in"), () -> clip.in / 20.0, "s", 2,
                () -> trimIn(clip, -step(10, 1)), () -> trimIn(clip, step(10, 1))).icon(Icon.SKIP_START)
                .tip(tc("justreplay.audio.trim.tip")));
        f.full(new Stepper(tc("justreplay.audio.trim_out"), () -> clip.out / 20.0, "s", 2,
                () -> trimOut(clip, -step(10, 1)), () -> trimOut(clip, step(10, 1))).icon(Icon.SKIP_END)
                .tip(tc("justreplay.audio.trim.tip")));
        f.full(new Stepper(tc("justreplay.audio.speed"), clip::speed, "x", 2,
                () -> speed(clip, -0.05), () -> speed(clip, 0.05)).icon(Icon.CLOCK).tip(tc("justreplay.audio.speed.tip")));
        f.full(new GlassSwitch(tc("justreplay.audio.loop"), () -> clip.loop, () -> {
            if (!clip.loop) {
                clip.length = Math.max(1, (int) Math.round(clip.length()));
                clip.offset = 0;
            }
            clip.loop = !clip.loop;
            clip.clampFades();
            save();
            screen.refresh();
        }).icon(Icon.ROTATE).tip(tc("justreplay.audio.loop.tip")));
        if (clip.loop) {
            f.full(new Stepper(tc("justreplay.audio.loop.length"), () -> clip.length / 20.0, "s", 2,
                    () -> loopLength(clip, -step(20, 1)), () -> loopLength(clip, step(20, 1))).icon(Icon.END)
                    .tip(tc("justreplay.audio.fine.tip")));
        }
        f.put(new GlassButton(tc("justreplay.audio.split"), b -> lanes.splitAtPlayHead()).icon(Icon.CUT)
                .tip(tc("justreplay.audio.split.tip")), 0, 3);
        f.put(new GlassButton(tc("justreplay.editor.cam.duplicate"), b -> lanes.duplicateSelected()).icon(Icon.COPY)
                .tip(tc("justreplay.audio.duplicate.tip")), 1, 3);
        f.put(new GlassButton(tc("justreplay.editor.delete"), b -> lanes.deleteSelected()).icon(Icon.TRASH)
                .style(GlassButton.Style.DANGER), 2, 3);
        f.next();
        f.full(new GlassButton(tc("justreplay.audio.goto"), b -> session.playback().seek(clip.start)).icon(Icon.PIN));
    }

    private Component clipState() {
        AudioClip clip = lanes.selectedClip;
        if (clip == null) {
            return null;
        }
        return switch (AudioLibrary.state(clip.file)) {
            case LOADING -> tc("justreplay.audio.loading");
            case MISSING -> Component.translatable("justreplay.audio.error", clip.file, tc("justreplay.audio.missing").getString());
            case FAILED -> Component.translatable("justreplay.audio.error", clip.file, String.valueOf(AudioLibrary.error(clip.file)));
            default -> null;
        };
    }

    private void clipVolume(AudioClip clip, double delta) {
        clip.volume = (float) clamp(Math.round((clip.volume + delta) * 100) / 100.0, 0.0, 2.0);
        save();
    }

    private void fade(AudioClip clip, boolean in, double delta) {
        if (in) {
            clip.fadeIn = Math.max(0, clip.fadeIn + (int) delta);
            clip.fadeOut = Math.min(clip.fadeOut, Math.max(0, (int) Math.floor(clip.length()) - clip.fadeIn));
        } else {
            clip.fadeOut = Math.max(0, clip.fadeOut + (int) delta);
            clip.fadeIn = Math.min(clip.fadeIn, Math.max(0, (int) Math.floor(clip.length()) - clip.fadeOut));
        }
        clip.clampFades();
        save();
    }

    private void trimIn(AudioClip clip, double delta) {
        clip.in = clamp(clip.in + delta, 0.0, Math.max(0.0, clip.out - 1.0));
        if (clip.loop && clip.span() > 0) {
            clip.offset = Math.min(clip.offset, clip.span() - 0.001);
        }
        clip.clampFades();
        save();
    }

    private void trimOut(AudioClip clip, double delta) {
        AudioPcm pcm = AudioLibrary.get(clip.file);
        double max = pcm != null ? pcm.lengthTicks() : Math.max(clip.out, clip.out + delta);
        clip.out = clamp(clip.out + delta, clip.in + 1.0, max);
        if (clip.loop && clip.span() > 0) {
            clip.offset = Math.min(clip.offset, clip.span() - 0.001);
        }
        clip.clampFades();
        save();
    }

    private void speed(AudioClip clip, double delta) {
        clip.speed = (float) clamp(Math.round((clip.speed() + delta) * 100) / 100.0, AudioClip.MIN_SPEED, AudioClip.MAX_SPEED);
        clip.clampFades();
        save();
    }

    private void loopLength(AudioClip clip, double delta) {
        clip.length = (int) Math.max(1, clip.length + delta);
        clip.clampFades();
        save();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---------------------------------------------------------------- tracks and clips

    private AudioTrack addTrack() {
        List<AudioTrack> tracks = project().audioTracks;
        AudioTrack track = new AudioTrack(nextTrackName());
        tracks.add(track);
        lanes.selectedTrack = track;
        lanes.reveal(track);
        save();
        screen.refresh();
        return track;
    }

    private String nextTrackName() {
        for (int n = project().audioTracks.size() + 1; ; n++) {
            String name = "Track " + n;
            boolean used = false;
            for (AudioTrack track : project().audioTracks) {
                used |= name.equals(track.name);
            }
            if (!used) {
                return name;
            }
        }
    }

    private void renameTrack() {
        AudioTrack track = lanes.selectedTrack;
        if (track == null) {
            return;
        }
        Minecraft.getInstance().setScreen(new TextInputScreen(screen, tc("justreplay.audio.track.rename"), tc("justreplay.audio.track.name"),
                track.name, name -> {
            if (!name.isBlank()) {
                track.name = name;
                save();
            }
        }));
    }

    private void deleteTrack() {
        AudioTrack track = lanes.selectedTrack;
        if (track == null) {
            return;
        }
        project().audioTracks.remove(track);
        lanes.selectedTrack = null;
        lanes.validate();
        save();
        screen.refresh();
    }

    /** Decodes the chosen file if needed and places it at the play head on the selected (or first) track. */
    private void addClip() {
        String file = picked;
        if (file == null) {
            return;
        }
        int tick = session.playback().cursor();
        status = tc("justreplay.audio.loading");
        Minecraft mc = Minecraft.getInstance();
        AudioLibrary.load(file).whenCompleteAsync((pcm, error) -> {
            if (ReplaySession.current() != session) {
                return;
            }
            if (error != null || pcm == null) {
                status = Component.translatable("justreplay.audio.error", file, String.valueOf(AudioLibrary.error(file)));
                return;
            }
            status = null;
            AudioTrack track = lanes.selectedTrack;
            if (track == null || !project().audioTracks.contains(track)) {
                track = project().audioTracks.isEmpty() ? addTrack() : project().audioTracks.get(0);
            }
            AudioClip clip = new AudioClip(file, tick, pcm.lengthTicks());
            track.clips.add(clip);
            track.sort();
            lanes.selectedTrack = track;
            lanes.selectedClip = clip;
            lanes.reveal(track);
            save();
            screen.refresh();
        }, mc);
    }
}
