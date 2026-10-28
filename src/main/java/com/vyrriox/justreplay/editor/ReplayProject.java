package com.vyrriox.justreplay.editor;

import com.vyrriox.justreplay.editor.audio.AudioTrack;
import com.vyrriox.justreplay.editor.fx.FxEffect;
import com.vyrriox.justreplay.editor.fx.TransitionType;
import com.vyrriox.justreplay.editor.overlay.Overlay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Everything the editor adds on top of a recording: cameras, the cut track, markers, actors, audio tracks and
 * overlays. Saved as
 * {@code project.json} beside the stream, the recording itself is never modified.
 *
 * <p>Projects written before multi-camera editing kept one camera path in {@link #cameraKeyframes},
 * {@link #cameraCurve}, {@link #cameraTiming} and {@link #cameraEase}; {@link #normalize()} turns it into the first
 * camera and clears those fields, so they are no longer written.
 *
 * @author vyrriox
 */
public final class ReplayProject {
    /** Legacy single camera path, only read from older projects. */
    public List<CameraKeyframe> cameraKeyframes;
    /** Legacy curve of the single camera path. */
    public String cameraCurve;
    /** Legacy timing of the single camera path. */
    public String cameraTiming;
    /** Legacy ease of the single camera path. */
    public Boolean cameraEase;
    /** Draw the cameras in the world while editing. */
    public boolean showPath = true;
    public List<Camera> cameras = new ArrayList<>();
    /** Which camera is live from which tick, ordered by tick. Empty: the selected camera is used alone. */
    public List<Cut> cuts = new ArrayList<>();
    /** Last tick of the cut track, or -1 for the end of the replay. */
    public int cutsEnd = -1;
    /** Id of the camera being edited. */
    public String selectedCamera;
    /** Play the recorded voice chat and include it in video exports. */
    public boolean voices = true;
    public List<Marker> markers = new ArrayList<>();
    public List<ActorData> actors = new ArrayList<>();
    /** Music and sound design tracks, mixed over the game sounds. */
    public List<AudioTrack> audioTracks = new ArrayList<>();
    /** Volume of the recorded game sounds, 0 to 1. */
    public float gameVolume = 1.0F;
    /**
     * Volume of each kind of game sound, 0 to 4 (400 percent), by key of {@link com.vyrriox.justreplay.editor.audio.SoundKinds}:
     * footsteps, players, blocks, hostile, neutral, ambient, weather, records. A missing key takes the kind's default
     * (2 for footsteps, 1 for the others).
     */
    public java.util.Map<String, Float> soundGains = new java.util.LinkedHashMap<>();
    /** Silence the recorded game sounds (the voices keep their own switch). */
    public boolean muteGame;
    /** Lower the game sounds and voices while an audio clip plays. */
    public boolean ducking;
    /** How much the ducking lowers them, 0 to 1. */
    public float duckAmount = 0.5F;
    /** Filter stack, in the order it is applied; filters bound to a camera only act while it is live. */
    public List<FxEffect> effects = new ArrayList<>();
    /** Render the filters, transitions and fades (a bypass switch for the whole effect chain). */
    public boolean effectsEnabled = true;
    /** Fade from black (or white) at the start of the export range, in ticks. */
    public int fadeIn;
    /** Fade to black (or white) at the end of the export range, in ticks. */
    public int fadeOut;
    /** The start and end fades go through white instead of black. */
    public boolean fadeWhite;
    /** Texts and images placed in the world, drawn in the order of the list when at the same distance. */
    public List<Overlay> overlays = new ArrayList<>();
    /** Time and weather set on top of the recording (all off by default). */
    public WorldSettings world = new WorldSettings();
    /** Edits of entities that were recorded (hidden, moved, sound volume), by entity UUID. */
    public List<EntityEdit> entityEdits = new ArrayList<>();

    /**
     * Edit of one recorded entity. Hidden from {@code hideFrom} to {@code hideTo} (-1: the end of the replay);
     * moved by offset keyframes (x, y, z hold the position offset, yaw the turn; pitch is unused); its sounds
     * scaled by {@code soundGain} (0 to 4) or muted.
     */
    public static final class EntityEdit {
        public String uuid = "";
        /** Readable label: entity type and name when it was picked. */
        public String label = "";
        public boolean hidden;
        public int hideFrom;
        public int hideTo = -1;
        public List<ActorKeyframe> offsets = new ArrayList<>();
        public float soundGain = 1.0F;
        public boolean muted;
        /** For a recorded player: hides the outer layer of its skin (hat, jacket, sleeves, trousers). */
        public boolean hideSkinLayers;
        /** For a recorded player: body parts hidden, a mask of {@link com.vyrriox.justreplay.editor.BodyParts}. */
        public int hiddenParts;
        /** For a recorded player: skins worn from given ticks, sorted by tick. */
        public List<SkinChange> skinChanges = new ArrayList<>();

        public EntityEdit() {
        }

        public EntityEdit(String uuid, String label) {
            this.uuid = uuid;
            this.label = label;
        }

        public boolean hiddenAt(double time) {
            return hidden && time >= hideFrom && (hideTo < 0 || time <= hideTo);
        }

        /** True when the edit changes nothing and can be dropped. */
        public boolean isEmpty() {
            return !hidden && offsets.isEmpty() && !muted && !hideSkinLayers && hiddenParts == 0 && skinChanges.isEmpty() && Math.abs(soundGain - 1.0F) < 0.001F;
        }

        public void normalize() {
            if (uuid == null) uuid = "";
            if (label == null) label = "";
            if (offsets == null) offsets = new ArrayList<>();
            offsets.removeIf(k -> k == null);
            offsets.sort(java.util.Comparator.comparingInt(k -> k.tick));
            skinChanges = SkinChange.normalize(skinChanges);
            hideFrom = Math.max(0, hideFrom);
            if (hideTo < -1) hideTo = -1;
            if (!(soundGain >= 0.0F && soundGain <= 4.0F)) soundGain = 1.0F;
        }
    }

    /**
     * A skin worn from a tick on, until the next change. An empty {@link #file} goes back to the base skin.
     */
    public static final class SkinChange {
        public int tick;
        public String file = "";
        public boolean slim;

        public SkinChange() {
        }

        public SkinChange(int tick, String file, boolean slim) {
            this.tick = tick;
            this.file = file == null ? "" : file;
            this.slim = slim;
        }

        /** The change in effect at this time (last one with tick at or before it), or null for the base skin. */
        public static SkinChange at(List<SkinChange> changes, double time) {
            SkinChange found = null;
            for (int i = 0, n = changes.size(); i < n; i++) {
                SkinChange c = changes.get(i);
                if (c.tick > time) {
                    break;
                }
                found = c;
            }
            return found;
        }

        static List<SkinChange> normalize(List<SkinChange> changes) {
            List<SkinChange> out = changes == null ? new ArrayList<>() : changes;
            out.removeIf(c -> c == null);
            for (SkinChange c : out) {
                if (c.file == null) c.file = "";
                c.tick = Math.max(0, c.tick);
            }
            out.sort(java.util.Comparator.comparingInt(c -> c.tick));
            return out;
        }
    }

    /** Edit of a recorded entity, or null. */
    public EntityEdit entityEdit(String uuid) {
        for (EntityEdit edit : entityEdits) {
            if (edit.uuid.equals(uuid)) {
                return edit;
            }
        }
        return null;
    }

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

    /**
     * One camera of the project. A STATIC camera holds a single placement, a PATH camera follows its keyframes,
     * a FOLLOW camera tracks an entity from an offset. Roll and field of view of the placement also apply to
     * FOLLOW cameras.
     */
    public static final class Camera {
        public static final String STATIC = "STATIC";
        public static final String PATH = "PATH";
        public static final String FOLLOW = "FOLLOW";

        public String id = UUID.randomUUID().toString();
        public String name = "Camera";
        public String type = PATH;

        // PATH
        public List<CameraKeyframe> keyframes = new ArrayList<>();
        /** Curve shape: CATMULL_ROM (through every keyframe), BEZIER (rounded, smoothest) or LINEAR. */
        public String curve = "CATMULL_ROM";
        /** CONSTANT_SPEED (only the first and last keyframe times matter) or KEYFRAMES (each keyframe at its tick). */
        public String timing = "CONSTANT_SPEED";
        /** Gentle acceleration at the start and deceleration at the end of a constant speed path. */
        public boolean ease = true;

        // STATIC placement (feet position of the free camera); roll and fov are also used by FOLLOW
        public double x, y, z;
        public float yaw, pitch, roll;
        public float fov = 70.0F;

        // FOLLOW
        /** UUID of the followed entity. */
        public String target = "";
        public String targetName = "";
        /** BEHIND, SIDE, FRONT or ORBIT. */
        public String followMode = "BEHIND";
        /** Horizontal distance from the target, in blocks. */
        public double distance = 4.0;
        /** Height of the camera above the target's eyes, in blocks. */
        public double height = 1.0;
        /** Orbit speed in degrees per second, ORBIT mode only. */
        public float orbitSpeed = 20.0F;
        /** Smoothing time in seconds, 0 for a rigid follow. */
        public float smoothing = 0.3F;

        public Camera() {
        }

        public Camera(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public boolean isStatic() {
            return STATIC.equals(type);
        }

        public boolean isPath() {
            return PATH.equals(type);
        }

        public boolean isFollow() {
            return FOLLOW.equals(type);
        }

        public void sort() {
            keyframes.sort(Comparator.comparingInt(k -> k.tick));
        }

        /** Deep copy with a new id. */
        public Camera copy(String newName) {
            Camera c = new Camera(newName, type);
            for (CameraKeyframe k : keyframes) {
                c.keyframes.add(new CameraKeyframe(k.tick, k.x, k.y, k.z, k.yaw, k.pitch, k.roll, k.fov));
            }
            c.curve = curve;
            c.timing = timing;
            c.ease = ease;
            c.x = x;
            c.y = y;
            c.z = z;
            c.yaw = yaw;
            c.pitch = pitch;
            c.roll = roll;
            c.fov = fov;
            c.target = target;
            c.targetName = targetName;
            c.followMode = followMode;
            c.distance = distance;
            c.height = height;
            c.orbitSpeed = orbitSpeed;
            c.smoothing = smoothing;
            return c;
        }
    }

    /**
     * From {@link #tick}, {@link #camera} is live; the camera move is blended over {@link #blend} ticks (0: hard cut).
     * The picture changes with {@link #transition} over {@link #transitionTicks} ticks from the cut.
     */
    public static final class Cut {
        public int tick;
        public String camera = "";
        public int blend;
        /** Name of a {@link TransitionType}. */
        public String transition = TransitionType.CUT.name();
        public int transitionTicks = 10;
        /** Motion of a wipe or slide: LEFT, RIGHT, UP or DOWN. */
        public String transitionDir = "LEFT";

        public Cut() {
        }

        public Cut(int tick, String camera, int blend) {
            this.tick = tick;
            this.camera = camera;
            this.blend = blend;
        }

        public TransitionType transitionType() {
            return TransitionType.parse(transition);
        }

        /** True when the picture transition plays at a time. */
        public boolean transitionAt(double time) {
            return transitionType() != TransitionType.CUT && time >= tick && time < tick + transitionTicks;
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

    /**
     * A timed action of an actor: a one shot animation (swing, hurt, jump...) or a state held for
     * {@link #duration} ticks, or until the next {@code IDLE} action when the duration is 0.
     */
    public static final class ActorAction {
        public int tick;
        /** Name of an {@link ActionType}. */
        public String type = ActionType.SWING_MAIN.name();
        public int duration;
        /** Type specific value: look target, animation name, wander radius, entity event id... */
        public String param = "";

        public ActorAction() {
        }

        public ActorAction(int tick, ActionType type, int duration, String param) {
            this.tick = tick;
            this.type = type.name();
            this.duration = duration;
            this.param = param;
        }

        public ActionType actionType() {
            return ActionType.parse(type);
        }
    }

    public static final class ActorData {
        public static final String PLAYER = "player";
        /** Blockbench model actor; {@link #model} names the file in the models folder. */
        public static final String MODEL = "model";

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
        /** Keep the feet on the ground between keyframes (not for flying mobs or FLY actions). */
        public boolean groundSnap = true;
        public List<ActorKeyframe> keyframes = new ArrayList<>();
        public List<ActorAction> actions = new ArrayList<>();
        /** Blockbench file name for model actors. */
        public String model = "";
        /** Animation of the model played in a loop when no action plays another one. */
        public String modelAnimation = "";
        /** Model scale for model actors. */
        public float modelScale = 1.0F;
        /**
         * Skin file of a player actor, a PNG of the skins folder (64x64 or legacy 64x32). Empty: the skin of the
         * Mojang account named by {@link #name}.
         */
        public String skin = "";
        /** Slim (Alex) arms for a player actor wearing a skin file. */
        public boolean slim;
        /** For a player actor: hides the outer layer of its skin (hat, jacket, sleeves, trousers), shown by default. */
        public boolean hideSkinLayers;
        /** For a player actor: body parts hidden, a mask of {@link com.vyrriox.justreplay.editor.BodyParts}. */
        public int hiddenParts;
        /** For a player actor: skins worn from given ticks, sorted by tick. */
        public List<SkinChange> skinChanges = new ArrayList<>();

        public boolean isPlayer() {
            return PLAYER.equals(type);
        }

        public boolean isModel() {
            return MODEL.equals(type);
        }

        /** True for a player actor wearing a skin file rather than the skin of an account. */
        public boolean hasSkinFile() {
            return isPlayer() && skin != null && !skin.isBlank();
        }

        public void sort() {
            keyframes.sort(Comparator.comparingInt(k -> k.tick));
            actions.sort(Comparator.comparingInt(a -> a.tick));
        }
    }

    public void normalize() {
        if (cameras == null) cameras = new ArrayList<>();
        if (cuts == null) cuts = new ArrayList<>();
        if (markers == null) markers = new ArrayList<>();
        if (actors == null) actors = new ArrayList<>();
        if (audioTracks == null) audioTracks = new ArrayList<>();
        if (effects == null) effects = new ArrayList<>();
        if (overlays == null) overlays = new ArrayList<>();
        if (world == null) world = new WorldSettings();
        world.normalize();
        if (entityEdits == null) entityEdits = new ArrayList<>();
        entityEdits.removeIf(e -> e == null || e.uuid == null || e.uuid.isBlank());
        for (EntityEdit edit : entityEdits) {
            edit.normalize();
        }
        overlays.removeIf(o -> o == null);
        for (Overlay overlay : overlays) {
            overlay.normalize();
        }
        effects.removeIf(e -> e == null);
        for (FxEffect effect : effects) {
            effect.normalize();
        }
        fadeIn = Math.max(0, fadeIn);
        fadeOut = Math.max(0, fadeOut);
        audioTracks.removeIf(t -> t == null);
        for (AudioTrack track : audioTracks) {
            track.normalize();
        }
        if (!(gameVolume >= 0.0F && gameVolume <= 1.0F)) gameVolume = 1.0F;
        if (soundGains == null) soundGains = new java.util.LinkedHashMap<>();
        soundGains.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || !(e.getValue() >= 0.0F && e.getValue() <= 4.0F));
        if (!(duckAmount >= 0.0F && duckAmount <= 1.0F)) duckAmount = 0.5F;
        cameras.removeIf(c -> c == null);
        if (cameras.isEmpty()) {
            // Older project (or a new one): the single camera path becomes the first camera.
            Camera first = new Camera("Camera 1", Camera.PATH);
            if (cameraKeyframes != null) first.keyframes.addAll(cameraKeyframes);
            if (cameraCurve != null) first.curve = cameraCurve;
            if (cameraTiming != null) first.timing = cameraTiming;
            if (cameraEase != null) first.ease = cameraEase;
            cameras.add(first);
        }
        cameraKeyframes = null;
        cameraCurve = null;
        cameraTiming = null;
        cameraEase = null;
        for (Camera camera : cameras) {
            if (camera.id == null || camera.id.isBlank()) camera.id = UUID.randomUUID().toString();
            if (camera.name == null) camera.name = "Camera";
            if (!Camera.STATIC.equals(camera.type) && !Camera.FOLLOW.equals(camera.type)) camera.type = Camera.PATH;
            if (camera.keyframes == null) camera.keyframes = new ArrayList<>();
            if (camera.target == null) camera.target = "";
            if (camera.targetName == null) camera.targetName = "";
            if (camera.followMode == null) camera.followMode = "BEHIND";
        }
        cuts.removeIf(c -> c == null || camera(c.camera) == null);
        for (Cut cut : cuts) {
            cut.transition = cut.transitionType().name();
            cut.transitionTicks = Math.max(1, Math.min(200, cut.transitionTicks));
            if (cut.transitionDir == null) cut.transitionDir = "LEFT";
        }
        effects.removeIf(e -> !e.isGlobal() && camera(e.camera) == null);
        if (camera(selectedCamera) == null) selectedCamera = cameras.get(0).id;
        for (ActorData actor : actors) {
            if (actor.keyframes == null) actor.keyframes = new ArrayList<>();
            actor.skinChanges = SkinChange.normalize(actor.skinChanges);
            if (actor.pose == null) actor.pose = "STANDING";
            if (actor.actions == null) actor.actions = new ArrayList<>();
            actor.actions.removeIf(a -> a == null);
            for (ActorAction action : actor.actions) {
                if (action.type == null) action.type = ActionType.IDLE.name();
                if (action.param == null) action.param = "";
            }
            if (actor.model == null) actor.model = "";
            if (actor.modelAnimation == null) actor.modelAnimation = "";
            if (actor.skin == null) actor.skin = "";
            if (!(actor.modelScale > 0.01F && actor.modelScale < 100F)) actor.modelScale = 1.0F;
            actor.sort();
        }
        sort();
        mergeCuts();
    }

    public void sort() {
        for (Camera camera : cameras) {
            camera.sort();
        }
        cuts.sort(Comparator.comparingInt(c -> c.tick));
        markers.sort(Comparator.comparingInt(m -> m.tick));
        for (AudioTrack track : audioTracks) {
            track.sort();
        }
    }

    // ---------------------------------------------------------------- cameras

    public Camera camera(String id) {
        if (id == null) {
            return null;
        }
        for (Camera camera : cameras) {
            if (id.equals(camera.id)) {
                return camera;
            }
        }
        return null;
    }

    /** The camera being edited; never null once normalized. */
    public Camera selected() {
        Camera camera = camera(selectedCamera);
        if (camera == null && !cameras.isEmpty()) {
            camera = cameras.get(0);
            selectedCamera = camera.id;
        }
        return camera;
    }

    public void select(Camera camera) {
        if (camera != null) {
            selectedCamera = camera.id;
        }
    }

    /** Next free default name, "Camera N". */
    public String nextCameraName() {
        for (int n = cameras.size() + 1; ; n++) {
            String name = "Camera " + n;
            boolean used = false;
            for (Camera camera : cameras) {
                used |= name.equals(camera.name);
            }
            if (!used) {
                return name;
            }
        }
    }

    /** Removes a camera and every cut to it; the project always keeps at least one camera. */
    public void removeCamera(Camera camera) {
        if (cameras.size() <= 1 || !cameras.remove(camera)) {
            return;
        }
        cuts.removeIf(c -> c.camera.equals(camera.id));
        effects.removeIf(e -> camera.id.equals(e.camera));
        mergeCuts();
        if (camera.id.equals(selectedCamera)) {
            selectedCamera = cameras.get(0).id;
        }
    }

    // ---------------------------------------------------------------- cuts

    /** Index of the cut live at a time: the last cut at or before it, the first cut before the track starts, or -1. */
    public int cutIndexAt(double time) {
        if (cuts.isEmpty()) {
            return -1;
        }
        int index = 0;
        for (int i = 1; i < cuts.size(); i++) {
            if (cuts.get(i).tick <= time) {
                index = i;
            } else {
                break;
            }
        }
        return index;
    }

    /**
     * Makes {@code camera} live from {@code tick}: retargets a cut at that tick, or inserts one. Does nothing when
     * the camera is already live there. Returns the cut now starting at that tick, or null.
     */
    public Cut cutTo(int tick, Camera camera, int blend) {
        int index = cutIndexAt(tick);
        if (index >= 0) {
            Cut live = cuts.get(index);
            if (live.tick == tick) {
                live.camera = camera.id;
                mergeCuts();
                return cuts.contains(live) ? live : null;
            }
            if (live.tick < tick && live.camera.equals(camera.id)) {
                return null;
            }
        }
        Cut cut = new Cut(tick, camera.id, blend);
        cuts.add(cut);
        mergeCuts();
        return cuts.contains(cut) ? cut : null;
    }

    /** Sorts the cuts and drops those switching to the camera already live, so no boundary is useless. */
    public void mergeCuts() {
        cuts.sort(Comparator.comparingInt(c -> c.tick));
        for (int i = cuts.size() - 1; i > 0; i--) {
            if (cuts.get(i).camera.equals(cuts.get(i - 1).camera)) {
                cuts.remove(i);
            }
        }
    }

    /** Last tick covered by the cut track, given the replay duration, or -1 without cuts. */
    public int cutTrackEnd(int duration) {
        if (cuts.isEmpty()) {
            return -1;
        }
        int last = cuts.get(cuts.size() - 1).tick;
        int end = cutsEnd >= 0 ? cutsEnd : duration;
        return Math.max(end, last + 1);
    }

    /**
     * Ticks to export: the cut track when there is one, else the keyframes of the selected camera. Null when there
     * is nothing to export.
     */
    public int[] exportRange(int duration) {
        if (!cuts.isEmpty()) {
            return new int[]{cuts.get(0).tick, cutTrackEnd(duration)};
        }
        Camera camera = selected();
        if (camera == null || !camera.isPath() || camera.keyframes.size() < 2) {
            return null;
        }
        return new int[]{camera.keyframes.get(0).tick, camera.keyframes.get(camera.keyframes.size() - 1).tick};
    }
}
