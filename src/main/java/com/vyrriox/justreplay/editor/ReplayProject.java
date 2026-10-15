package com.vyrriox.justreplay.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Everything the editor adds on top of a recording: camera path, markers and actors. Saved as
 * {@code project.json} beside the stream, the recording itself is never modified.
 *
 * @author vyrriox
 */
public final class ReplayProject {
    public List<CameraKeyframe> cameraKeyframes = new ArrayList<>();
    /** Curve shape: CATMULL_ROM (through every keyframe), BEZIER (rounded, smoothest) or LINEAR. */
    public String cameraCurve = "CATMULL_ROM";
    /** CONSTANT_SPEED (only the first and last keyframe times matter) or KEYFRAMES (each keyframe at its tick). */
    public String cameraTiming = "CONSTANT_SPEED";
    /** Gentle acceleration at the start and deceleration at the end of a constant speed path. */
    public boolean cameraEase = true;
    /** Draw the camera path in the world while editing. */
    public boolean showPath = true;
    /** Play the recorded voice chat and include it in video exports. */
    public boolean voices = true;
    public List<Marker> markers = new ArrayList<>();
    public List<ActorData> actors = new ArrayList<>();

    public static final class CameraKeyframe {
        public int tick;
        public double x, y, z;
        public float yaw, pitch, roll;
        public float fov = 70.0F;

        public CameraKeyframe() {
        }

        public CameraKeyframe(int tick, double x, double y, double z, float yaw, float pitch, float roll, float fov) {
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.fov = fov;
        }
    }

    public static final class Marker {
        public int tick;
        public String name = "";

        public Marker() {
        }

        public Marker(int tick, String name) {
            this.tick = tick;
            this.name = name;
        }
    }

    public static final class ActorKeyframe {
        public int tick;
        public double x, y, z;
        public float yaw, pitch;

        public ActorKeyframe() {
        }

        public ActorKeyframe(int tick, double x, double y, double z, float yaw, float pitch) {
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    public static final class ActorData {
        public static final String PLAYER = "player";

        public String id = UUID.randomUUID().toString();
        /** {@link #PLAYER} or an entity type id such as {@code minecraft:zombie}. */
        public String type = PLAYER;
        /** Skin owner for player actors, custom name otherwise. */
        public String name = "";
        public boolean visible = true;
        public boolean showName = true;
        public String mainHand = "";
        public String offHand = "";
        public String head = "";
        public String chest = "";
        public String legs = "";
        public String feet = "";
        public String pose = "STANDING";
        public List<ActorKeyframe> keyframes = new ArrayList<>();

        public boolean isPlayer() {
            return PLAYER.equals(type);
        }

        public void sort() {
            keyframes.sort(Comparator.comparingInt(k -> k.tick));
        }
    }

    public void normalize() {
        if (cameraKeyframes == null) cameraKeyframes = new ArrayList<>();
        if (markers == null) markers = new ArrayList<>();
        if (actors == null) actors = new ArrayList<>();
        for (ActorData actor : actors) {
            if (actor.keyframes == null) actor.keyframes = new ArrayList<>();
            if (actor.pose == null) actor.pose = "STANDING";
            actor.sort();
        }
        sort();
    }

    public void sort() {
        cameraKeyframes.sort(Comparator.comparingInt(k -> k.tick));
        markers.sort(Comparator.comparingInt(m -> m.tick));
    }
}
