package com.vyrriox.justreplay.editor.audio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One audio track of the project: a mixer strip (volume, mute, solo) holding clips. Clips of a track may overlap,
 * they are simply mixed.
 *
 * @author vyrriox
 */
public final class AudioTrack {
    public String id = UUID.randomUUID().toString();
    public String name = "Track";
    public float volume = 1.0F;
    public boolean mute;
    public boolean solo;
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
        for (AudioClip clip : clips) {
            t.clips.add(clip.copy());
        }
        return t;
    }
}
