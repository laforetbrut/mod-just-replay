package com.vyrriox.justreplay.editor.audio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One audio track of the project: a mixer strip (volume, mute, solo) holding clips. Clips of a track may overlap:
 * they crossfade over the overlap, or are simply mixed when {@link #crossfade} is off.
 *
 * @author vyrriox
 */
public final class AudioTrack {
    public String id = UUID.randomUUID().toString();
    public String name = "Track";
    public float volume = 1.0F;
    public boolean mute;
    public boolean solo;
    /** Overlapping clips crossfade (equal power) over their overlap instead of simply adding up. */
    public boolean crossfade = true;
    public List<AudioClip> clips = new ArrayList<>();

    public AudioTrack() {
    }

    public AudioTrack(String name) {
        this.name = name;
    }

    public void sort() {
        clips.sort(Comparator.comparingInt(c -> c.start));
    }

    /** True when this track is heard, given the solo state of every track. */
    public boolean audible(List<AudioTrack> tracks) {
        if (mute) {
            return false;
        }
        for (AudioTrack track : tracks) {
            if (track.solo) {
                return solo;
            }
        }
        return true;
    }

    /**
     * Length in ticks of the crossfade at the start of clip {@code index}: how long the clips before it still play
     * once it starts. 0 without overlap or with crossfades off. Clips must be sorted.
     */
    public double crossfadeIn(int index) {
        if (!crossfade) {
            return 0.0;
        }
        AudioClip clip = clips.get(index);
        double best = 0.0;
        for (int j = 0; j < index; j++) {
            AudioClip other = clips.get(j);
            if (other.start <= clip.start && other.end() > clip.start) {
                best = Math.max(best, Math.min(other.end(), clip.end()) - clip.start);
            }
        }
        return best;
    }

    /** Length in ticks of the crossfade at the end of clip {@code index}: how early the next clip starts. */
    public double crossfadeOut(int index) {
        if (!crossfade) {
            return 0.0;
        }
        AudioClip clip = clips.get(index);
        double best = 0.0;
        for (int j = index + 1; j < clips.size(); j++) {
            AudioClip other = clips.get(j);
            if (other.start >= clip.start && other.start < clip.end()) {
                best = Math.max(best, clip.end() - other.start);
            }
        }
        return best;
    }

    public void normalize() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (name == null) name = "Track";
        if (!(volume >= 0.0F && volume <= 4.0F)) volume = 1.0F;
        if (clips == null) clips = new ArrayList<>();
        clips.removeIf(c -> c == null);
        for (AudioClip clip : clips) {
            clip.normalize();
        }
        sort();
    }

    /** Deep copy, used to freeze the mix of an export. */
    public AudioTrack copy() {
        AudioTrack t = new AudioTrack(name);
        t.id = id;
        t.volume = volume;
        t.mute = mute;
        t.solo = solo;
        t.crossfade = crossfade;
        for (AudioClip clip : clips) {
            t.clips.add(clip.copy());
        }
        return t;
    }
}
