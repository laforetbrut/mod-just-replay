package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;
import com.vyrriox.justreplay.editor.ActionType;
import com.vyrriox.justreplay.editor.ArmorBits;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.WorldSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The edit tools of the cast: actors (players wearing a skin, Blockbench models, any entity type), their keyframes
 * and timed actions, and the edits of recorded entities (hidden, moved, scaled, muted, glowing, body parts and armor
 * hidden, skins changed). They follow the editor's rules: a keyframe or an action replaces the one of its kind at
 * its tick, an actor whose look changed is rebuilt, an entity edit that changes nothing is dropped.
 *
 * @author vyrriox
 */
final class McpActorTools {
    /** The poses the actor screen offers. */
    static final List<String> POSES = List.of("STANDING", "CROUCHING", "SWIMMING", "FALL_FLYING", "SLEEPING", "SPIN_ATTACK", "SITTING");
    static final Map<String, Integer> ARMOR = armor();
    private static final List<String> SLOTS = List.of("mainHand", "offHand", "head", "chest", "legs", "feet");
    private static final Set<String> PLAYER_ONLY = Set.of("skin", "slim", "hideSkinLayers", "hiddenParts");
    private static final Set<String> MODEL_ONLY = Set.of("model", "modelAnimation", "modelScale");
    private static final double MAX_C = McpEditTools.MAX_C;
    private static final int MAX_T = McpEditTools.MAX_T;

    private McpActorTools() {
    }

    private static Map<String, Integer> armor() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("head", ArmorBits.HEAD);
        m.put("chest", ArmorBits.CHEST);
        m.put("legs", ArmorBits.LEGS);
        m.put("feet", ArmorBits.FEET);
        return m;
    }

    static List<String> actionTypes() {
        List<String> out = new ArrayList<>();
        for (ActionType t : ActionType.values()) {
            out.add(t.name());
        }
        return out;
    }

    /** The fields of an actor; the ones naming files or game ids are checked against what the game has. */
    private static McpFields<ReplayProject.ActorData> fields(McpControl control) {
        McpFields<ReplayProject.ActorData> f = new McpFields<ReplayProject.ActorData>()
                .text("name", "Player actors: the account whose skin they wear when no skin file is set. Other actors: the name "
                        + "shown above them.", 64, (a, v) -> a.name = v.trim())
                .bool("visible", "false hides the actor from the replay and the videos.", (a, v) -> a.visible = v)
                .bool("showName", "Shows the name above the actor.", (a, v) -> a.showName = v)
                .choice("pose", "Pose: " + String.join(", ", POSES) + ".", POSES, (a, v) -> a.pose = v)
                .bool("groundSnap", "Keeps the feet on the ground between keyframes (not for flying mobs).", (a, v) -> a.groundSnap = v);
        for (String slot : SLOTS) {
            f.custom(slot, s -> s.string(slot, "Item in the " + slot + " slot, such as minecraft:diamond_sword; an empty "
                    + "string clears it."), (args, name) -> item(control, args, name), (ReplayProject.ActorData a, String v) -> {
                switch (slot) {
                    case "mainHand" -> a.mainHand = v;
                    case "offHand" -> a.offHand = v;
                    case "head" -> a.head = v;
                    case "chest" -> a.chest = v;
                    case "legs" -> a.legs = v;
                    default -> a.feet = v;
                }
            });
        }
        return f
                .custom("skin", s -> s.string("skin", "Player actors: a PNG of the skins folder (list_files kind=skins), or an "
                        + "empty string for the skin of the account named by 'name'."), (args, name) -> file(control, args, name, "skins"),
                        (ReplayProject.ActorData a, String v) -> a.skin = v)
                .bool("slim", "Player actors wearing a skin file: slim (Alex) arms.", (a, v) -> a.slim = v)
                .bool("hideSkinLayers", "Player actors: hides the outer layer of the skin (hat, jacket, sleeves, trousers).",
                        (a, v) -> a.hideSkinLayers = v)
                .custom("hiddenParts", s -> s.strings("hiddenParts", "Player actors: body parts hidden, among "
                        + String.join(", ", control.bodyParts().keySet()) + "; an empty list shows them all.", 6),
                        (args, name) -> mask(args, name, control.bodyParts()), (ReplayProject.ActorData a, Integer v) -> a.hiddenParts = v)
                .custom("hiddenArmor", s -> s.strings("hiddenArmor", "Armor pieces not drawn, among head, chest, legs, feet; "
                        + "the equipment stays set. An empty list shows them all.", 4),
                        (args, name) -> mask(args, name, ARMOR), (ReplayProject.ActorData a, Integer v) -> a.hiddenArmor = v)
                .custom("model", s -> s.string("model", "Model actors: a Blockbench file of the models folder (list_files kind=models)."),
                        (args, name) -> file(control, args, name, "models"), (ReplayProject.ActorData a, String v) -> a.model = v)
                .text("modelAnimation", "Model actors: animation played in a loop when no action plays another one; empty for none.",
                        128, (a, v) -> a.modelAnimation = v.trim())
                .number("modelScale", "Model actors: scale of the model.", 0.05, 50.0, (a, v) -> a.modelScale = v.floatValue())
                .number("scale", "Render scale around the feet, 0.05 to 10 (1 leaves the actor as it is).", 0.05, 10.0,
                        (a, v) -> a.scale = v.floatValue());
    }

    private static String item(McpControl control, McpArgs args, String name) throws McpArgs.Invalid {
        String value = args.text(name, 256).trim();
        if (!value.isEmpty() && !control.known("item", value)) {
            throw new McpArgs.Invalid("'" + value + "' in '" + name + "' is not an item of this game (an id such as minecraft:diamond_sword)");
        }
        return value;
    }

    private static String file(McpControl control, McpArgs args, String name, String kind) throws McpArgs.Invalid {
        String value = args.text(name, 256).trim();
        if (!value.isEmpty() && !control.files(kind).contains(value)) {
            throw new McpArgs.Invalid("No file '" + value + "' in the " + kind + " folder (list_files kind=" + kind + " gives them)");
        }
        return value;
    }

    /** A list of names turned into a mask of their bits. */
    static int mask(McpArgs args, String name, Map<String, Integer> bits) throws McpArgs.Invalid {
        int mask = 0;
        for (String part : args.choices(name, new ArrayList<>(bits.keySet()))) {
            mask |= bits.get(part);
        }
        return mask;
    }

    /** The names whose bit is set in {@code mask}. */
    static List<String> names(int mask, Map<String, Integer> bits) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : bits.entrySet()) {
            if ((mask & e.getValue()) != 0) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    static ReplayProject.ActorData actorById(ReplayProject p, String id) {
        for (ReplayProject.ActorData a : p.actors) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }

    /** An actor by id, or by its exact name (any case) when only one actor has it. */
    static ReplayProject.ActorData actor(ReplayProject p, String key) throws McpArgs.Invalid {
        String k = key.startsWith(com.vyrriox.justreplay.editor.Marker.ACTOR_PREFIX)
                ? key.substring(com.vyrriox.justreplay.editor.Marker.ACTOR_PREFIX.length()) : key;
        ReplayProject.ActorData byId = actorById(p, k);
        if (byId != null) {
            return byId;
        }
        ReplayProject.ActorData found = null;
        for (ReplayProject.ActorData a : p.actors) {
            if (a.name != null && a.name.equalsIgnoreCase(key)) {
                if (found != null) {
                    throw new McpArgs.Invalid("Several actors are named '" + key + "': use its id");
                }
                found = a;
            }
        }
        if (found == null) {
            throw new McpArgs.Invalid("No actor has the id or name '" + key + "' (get_project with sections=actors gives them)");
        }
        return found;
    }

    static String shownName(ReplayProject.ActorData a) {
        return a.name == null || a.name.isEmpty() ? a.type : a.name;
    }

    static JsonObject actorJson(ReplayProject.ActorData a, McpControl control) {
        JsonObject o = McpJson.summarize(McpJson.tree(a)).getAsJsonObject();
        o.addProperty("shownName", shownName(a));
        o.add("hiddenPartNames", McpJson.tree(names(a.hiddenParts, control.bodyParts())));
        o.add("hiddenArmorNames", McpJson.tree(names(a.hiddenArmor, ARMOR)));
        return o;
    }

    // ------------------------------------------------------------------ registration

    static void register(McpRegistry registry, McpObserver observer, McpControl control) {
        McpFields<ReplayProject.ActorData> fields = fields(control);
        registry.add(McpTool.builder("edit_actor")
                .title("Edit an actor")
                .description("Adds, changes or removes an actor of the edit: a player wearing a skin (an account's or a PNG of "
                        + "the skins folder), a Blockbench model, or any entity type such as minecraft:zombie. A new actor "
                        + "stands at x, y, z (its feet) from 'tick', or where the view looks when no position is given, facing "
                        + "the camera. Skin changes over time: add_skin_change and remove_skin_change. Keyframes and actions "
                        + "have their own tools.")
                .input(fields.addTo(McpSchema.object()
                        .choice("action", "add, update, remove, add_skin_change or remove_skin_change.", null, "add", "update",
                                "remove", "add_skin_change", "remove_skin_change")
                        .string("actor", "Id or exact name of the actor (all actions but add).")
                        .string("type", "player (default), model, or an entity type id such as minecraft:zombie."))
                        .integer("tick", "add: tick of the first keyframe (the play head when left out). Skin changes: their tick.", 0, MAX_T)
                        .number("x", "add: X of the feet.", -MAX_C, MAX_C)
                        .number("y", "add: Y of the feet.", -MAX_C, MAX_C)
                        .number("z", "add: Z of the feet.", -MAX_C, MAX_C)
                        .number("yaw", "add: the way the actor faces, in degrees (0 south); toward the camera when left out.", -3600.0, 3600.0)
                        .required("action"))
                .destructive()
                .handler((args, context) -> actorTool(observer, control, fields, args))
                .build());
        registry.add(McpTool.builder("edit_actor_keyframe")
                .title("Edit an actor keyframe")
                .description("Adds, changes or removes a keyframe of an actor: where its feet are and the way it faces at a "
                        + "tick; the actor walks between keyframes. A new keyframe at a tick replaces the one there. Without a "
                        + "position, the actor stands under the camera, as the editor's keyframe button does.")
                .input(McpSchema.object()
                        .choice("action", "add, update or remove.", null, "add", "update", "remove")
                        .string("actor", "Id or exact name of the actor.")
                        .integer("tick", "Tick of the keyframe; add: the play head when left out.", 0, MAX_T)
                        .integer("newTick", "update: moves the keyframe to this tick.", 0, MAX_T)
                        .number("x", "X of the feet.", -MAX_C, MAX_C)
                        .number("y", "Y of the feet.", -MAX_C, MAX_C)
                        .number("z", "Z of the feet.", -MAX_C, MAX_C)
                        .number("yaw", "The way the actor faces, in degrees (0 south, 90 west).", -3600.0, 3600.0)
                        .number("pitch", "Head pitch in degrees, positive looks down.", -90.0, 90.0)
                        .required("action", "actor"))
                .destructive()
                .handler((args, context) -> keyframeTool(observer, control, args))
                .build());
        registry.add(McpTool.builder("edit_actor_action")
                .title("Edit an actor action")
                .description("Adds, changes or removes a timed action of an actor: one shot animations (SWING_MAIN, HURT, "
                        + "JUMP...) and states held for 'duration' ticks or until the next IDLE (WALK, SIT, SLEEP, WAVE, "
                        + "LOOK_AT, WALK_TO, FOLLOW, EMOTE, DANCE...). An action is found by its tick and type. Parameters: "
                        + "LOOK_AT camera, recorder or an entity; WANDER a radius; WALK_TO and RUN_TO 'x y z' (or 'target'); "
                        + "FOLLOW 'recorder|2.5'; ANIMATION a model animation, '|once' to play it once; ENTITY_EVENT an event "
                        + "byte; EMOTE an Emotecraft emote; DANCE 'style|bpm|origin tick'; CUSTOM_EMOTE 'file:animation'.")
                .input(McpSchema.object()
                        .choice("action", "add, update or remove.", null, "add", "update", "remove")
                        .string("actor", "Id or exact name of the actor.")
                        .integer("tick", "Tick of the action; add: the play head when left out.", 0, MAX_T)
                        .choice("type", "Type of the action: " + String.join(", ", actionTypes()) + ".", null,
                                actionTypes().toArray(new String[0]))
                        .integer("newTick", "update: moves the action to this tick.", 0, MAX_T)
                        .integer("duration", "Ticks the state lasts; 0 holds it until the next IDLE action (or the natural length "
                                + "of a one shot).", 0, 72_000)
                        .string("param", "Parameter of the action (see the description); its default when left out on add.")
                        .point("target", "WALK_TO and RUN_TO: the destination [x, y, z] (sets param).")
                        .integer("clipFrom", "EMOTE, CUSTOM_EMOTE: first emote tick played.", 0, MAX_T)
                        .integer("clipTo", "EMOTE, CUSTOM_EMOTE: last emote tick played, -1 for the end.", -1, MAX_T)
                        .required("action", "actor"))
                .destructive()
                .handler((args, context) -> actionTool(observer, control, args))
                .build());
        McpFields<ReplayProject.EntityEdit> entityFields = entityFields(control);
        registry.add(McpTool.builder("edit_entity")
                .title("Edit a recorded entity")
                .description("Changes how a recorded entity shows in the replay and the videos, as the Entities tab does: "
                        + "hidden (from hideFrom to hideTo), its sounds scaled or muted, scaled, glowing, a recorded player's "
                        + "skin layers, body parts, armor and skin changes; set_offset moves it by an offset from a tick. "
                        + "reset drops every change of the entity. hide_type and show_type hide every entity of a type "
                        + "(entity=... is then left out). Actors are edited with edit_actor.")
                .input(entityFields.addTo(McpSchema.object()
                        .choice("action", "update, reset, set_offset, remove_offset, add_skin_change, remove_skin_change, "
                                        + "hide_type or show_type.", null, "update", "reset", "set_offset", "remove_offset",
                                "add_skin_change", "remove_skin_change", "hide_type", "show_type")
                        .string("entity", "The entity: an id from list_entities (or the UUID of an entity edited before).")
                        .string("type", "hide_type, show_type: an entity type id such as minecraft:bat."))
                        .integer("tick", "set_offset, remove_offset, skin changes: tick (the play head when left out).", 0, MAX_T)
                        .number("x", "set_offset: X offset in blocks.", -64.0, 64.0)
                        .number("y", "set_offset: Y offset in blocks.", -64.0, 64.0)
                        .number("z", "set_offset: Z offset in blocks.", -64.0, 64.0)
                        .number("yaw", "set_offset: turn in degrees.", -180.0, 180.0)
                        .string("skin", "add_skin_change: a PNG of the skins folder, or an empty string for the recorded skin.")
                        .bool("slim", "add_skin_change: slim (Alex) arms.")
                        .required("action"))
                .destructive()
                .handler((args, context) -> entityTool(observer, control, entityFields, args))
                .build());
    }

    // ------------------------------------------------------------------ actors

    private static final Set<String> POSITION = Set.of("x", "y", "z", "yaw");

    static McpToolResult actorTool(McpObserver observer, McpControl control, McpFields<ReplayProject.ActorData> fields, McpArgs args)
            throws McpArgs.Invalid {
        String action = McpEditTools.action(args, List.of("add", "update", "remove", "add_skin_change", "remove_skin_change"));
        return McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    McpEditTools.only(args, action, McpEditTools.names(fields.names(), "type", "tick", POSITION));
                    ReplayProject.ActorData a = new ReplayProject.ActorData();
                    a.type = type(control, args, ReplayProject.ActorData.PLAYER);
                    checkKind(args, a.type);
                    McpFields.Changes<ReplayProject.ActorData> given = fields.read(args);
                    if (a.isPlayer()) {
                        a.name = control.playerName();
                    }
                    given.applyTo(a);
                    if (a.isModel()) {
                        if (a.model.isEmpty()) {
                            throw new McpArgs.Invalid("A model actor needs 'model', a file of the models folder (list_files kind=models)");
                        }
                        modelDefaults(control, a, given.has("name"), given.has("modelAnimation"));
                    }
                    checkAnimation(control, a);
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    double[] spot = spot(control, args);
                    McpCamera view = observer.currentCamera();
                    double yaw = args.has("yaw") ? args.number("yaw", 0.0, -3600.0, 3600.0)
                            : McpPlayTools.aim(spot[0], spot[1], spot[2], view.x(), view.y(), view.z())[0];
                    a.setKeyframe(tick, spot[0], spot[1], spot[2], (float) yaw);
                    p.actors.add(a);
                    out.add("actor", actorJson(a, control));
                }
                case "update" -> {
                    McpEditTools.only(args, action, McpEditTools.names("actor", fields.names(), "type"));
                    ReplayProject.ActorData a = actor(p, args.required("actor"));
                    String type = args.has("type") ? type(control, args, a.type) : a.type;
                    checkKind(args, type);
                    McpFields.Changes<ReplayProject.ActorData> given = fields.read(args);
                    if (given.isEmpty() && !args.has("type")) {
                        throw McpEditTools.nothing();
                    }
                    ReplayProject.ActorData trial = McpEditTools.copy(a);
                    trial.type = type;
                    given.applyTo(trial);
                    if (trial.isModel() && trial.model.isEmpty()) {
                        throw new McpArgs.Invalid("A model actor needs 'model', a file of the models folder (list_files kind=models)");
                    }
                    checkAnimation(control, trial);
                    a.type = type;
                    given.applyTo(a);
                    change.rebuilt.add(a);
                    out.add("actor", actorJson(a, control));
                }
                case "remove" -> {
                    McpEditTools.only(args, action, Set.of("actor"));
                    ReplayProject.ActorData a = actor(p, args.required("actor"));
                    p.actors.remove(a);
                    change.removed.add(a);
                    out.addProperty("removed", a.id);
                    out.addProperty("name", shownName(a));
                }
                case "add_skin_change" -> {
                    McpEditTools.only(args, action, Set.of("actor", "tick", "skin", "slim"));
                    ReplayProject.ActorData a = actor(p, args.required("actor"));
                    if (!a.isPlayer()) {
                        throw new McpArgs.Invalid("Only player actors change skins ('" + shownName(a) + "' is " + a.type + ")");
                    }
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    String skin = args.has("skin") ? file(control, args, "skin", "skins") : "";
                    boolean replaced = a.skinChanges.removeIf(c -> c.tick == tick);
                    a.skinChanges.add(new ReplayProject.SkinChange(tick, skin, args.bool("slim", false)));
                    a.skinChanges.sort(java.util.Comparator.comparingInt(c -> c.tick));
                    change.rebuilt.add(a);
                    out.addProperty("replaced", replaced);
                    out.add("skinChanges", McpJson.tree(a.skinChanges));
                }
                default -> {
                    McpEditTools.only(args, action, Set.of("actor", "tick"));
                    ReplayProject.ActorData a = actor(p, args.required("actor"));
                    int tick = McpControlTools.requiredTick(args, "tick", r);
                    if (!a.skinChanges.removeIf(c -> c.tick == tick)) {
                        throw new McpArgs.Invalid("Actor '" + shownName(a) + "' has no skin change at tick " + tick);
                    }
                    change.rebuilt.add(a);
                    out.add("skinChanges", McpJson.tree(a.skinChanges));
                }
            }
            out.addProperty("actors", p.actors.size());
            return out;
        });
    }

    /** The {@code type} argument: player, model or an entity type of the game. */
    private static String type(McpControl control, McpArgs args, String def) throws McpArgs.Invalid {
        String type = args.string("type", def).trim();
        if (type.equalsIgnoreCase(ReplayProject.ActorData.PLAYER) || type.equalsIgnoreCase(ReplayProject.ActorData.MODEL)) {
            return type.toLowerCase(Locale.ROOT);
        }
        if (type.equals("minecraft:player")) {
            throw new McpArgs.Invalid("A player actor has the type 'player'");
        }
        if (!control.known("entity_type", type)) {
            throw new McpArgs.Invalid("'" + type + "' is not an entity type of this game: give player, model or an id such as minecraft:zombie");
        }
        return type;
    }

    /** Refuses the fields that do nothing on an actor of this type. */
    private static void checkKind(McpArgs args, String type) throws McpArgs.Invalid {
        for (String name : args.names()) {
            if (PLAYER_ONLY.contains(name) && !ReplayProject.ActorData.PLAYER.equals(type)) {
                throw new McpArgs.Invalid("'" + name + "' is for player actors (this one is " + type + ")");
            }
            if (MODEL_ONLY.contains(name) && !ReplayProject.ActorData.MODEL.equals(type)) {
                throw new McpArgs.Invalid("'" + name + "' is for model actors (this one is " + type + ")");
            }
        }
    }

    /** The editor's defaults for a new model actor: named after its file, playing its idle animation. */
    private static void modelDefaults(McpControl control, ReplayProject.ActorData a, boolean named, boolean animated) {
        if (!named) {
            a.name = a.model.endsWith(".bbmodel") ? a.model.substring(0, a.model.length() - ".bbmodel".length()) : a.model;
        }
        if (!animated) {
            for (String animation : control.modelAnimations(a.model)) {
                if (animation.toLowerCase(Locale.ROOT).contains("idle")) {
                    a.modelAnimation = animation;
                    break;
                }
            }
        }
    }

    private static void checkAnimation(McpControl control, ReplayProject.ActorData a) throws McpArgs.Invalid {
        if (a.isModel() && !a.modelAnimation.isEmpty() && !control.modelAnimations(a.model).contains(a.modelAnimation)) {
            throw new McpArgs.Invalid("Model '" + a.model + "' has no animation '" + a.modelAnimation + "' (its animations: "
                    + String.join(", ", control.modelAnimations(a.model)) + ")");
        }
    }

    /** Where a new actor stands: the position given, else the spot the view looks at. */
    private static double[] spot(McpControl control, McpArgs args) throws McpArgs.Invalid {
        boolean xyz = args.has("x") || args.has("y") || args.has("z");
        if (xyz) {
            if (!(args.has("x") && args.has("y") && args.has("z"))) {
                throw new McpArgs.Invalid("Give x, y and z together (the feet of the actor)");
            }
            return new double[]{args.number("x", 0.0, -MAX_C, MAX_C), args.number("y", 0.0, -MAX_C, MAX_C), args.number("z", 0.0, -MAX_C, MAX_C)};
        }
        double[] spot = control.lookSpot();
        if (spot == null) {
            throw new McpArgs.Invalid("The view looks at no block to stand the actor on: give x, y and z (its feet)");
        }
        return spot;
    }

    // ------------------------------------------------------------------ actor keyframes

    static McpToolResult keyframeTool(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        String action = McpEditTools.action(args, List.of("add", "update", "remove"));
        return McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject.ActorData a = actor(r.project(), args.required("actor"));
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    McpEditTools.only(args, action, Set.of("actor", "tick", "x", "y", "z", "yaw", "pitch"));
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    boolean xyz = args.has("x") || args.has("y") || args.has("z");
                    if (xyz && !(args.has("x") && args.has("y") && args.has("z"))) {
                        throw new McpArgs.Invalid("Give x, y and z together (the feet of the actor)");
                    }
                    double[] at = xyz ? new double[]{args.number("x", 0.0, -MAX_C, MAX_C), args.number("y", 0.0, -MAX_C, MAX_C),
                            args.number("z", 0.0, -MAX_C, MAX_C), facing(a, tick)} : control.underCamera();
                    boolean replaced = a.keyframeAt(tick) != null;
                    ReplayProject.ActorKeyframe k = a.setKeyframe(tick, at[0], at[1], at[2],
                            (float) args.number("yaw", at[3], -3600.0, 3600.0));
                    k.pitch = (float) args.number("pitch", 0.0, -90.0, 90.0);
                    out.add("keyframe", McpJson.tree(k));
                    out.addProperty("replaced", replaced);
                }
                case "update" -> {
                    McpEditTools.only(args, action, Set.of("actor", "tick", "newTick", "x", "y", "z", "yaw", "pitch"));
                    int tick = McpControlTools.requiredTick(args, "tick", r);
                    ReplayProject.ActorKeyframe k = a.keyframeAt(tick);
                    if (k == null) {
                        List<Integer> ticks = new ArrayList<>();
                        a.keyframes.forEach(key -> ticks.add(key.tick));
                        throw new McpArgs.Invalid("Actor '" + shownName(a) + "' has no keyframe at tick " + tick + " (its keyframes: "
                                + McpEditTools.ticks(ticks) + ")");
                    }
                    if (args.names().size() <= 3) {
                        throw McpEditTools.nothing();
                    }
                    int to = McpControlTools.tick(args, "newTick", r, tick);
                    if (to != tick && a.keyframeAt(to) != null) {
                        throw new McpArgs.Invalid("Actor '" + shownName(a) + "' already has a keyframe at tick " + to);
                    }
                    double x = args.number("x", k.x, -MAX_C, MAX_C);
                    double y = args.number("y", k.y, -MAX_C, MAX_C);
                    double z = args.number("z", k.z, -MAX_C, MAX_C);
                    float yaw = (float) args.number("yaw", k.yaw, -3600.0, 3600.0);
                    float pitch = (float) args.number("pitch", k.pitch, -90.0, 90.0);
                    a.keyframes.remove(k);
                    ReplayProject.ActorKeyframe moved = a.setKeyframe(to, x, y, z, yaw);
                    moved.pitch = pitch;
                    out.add("keyframe", McpJson.tree(moved));
                }
                default -> {
                    McpEditTools.only(args, action, Set.of("actor", "tick"));
                    int tick = McpControlTools.requiredTick(args, "tick", r);
                    if (!a.keyframes.removeIf(k -> k.tick == tick)) {
                        throw new McpArgs.Invalid("Actor '" + shownName(a) + "' has no keyframe at tick " + tick);
                    }
                    out.addProperty("removedTick", tick);
                }
            }
            JsonObject actor = new JsonObject();
            actor.addProperty("id", a.id);
            actor.addProperty("name", shownName(a));
            actor.addProperty("keyframes", a.keyframes.size());
            out.add("actor", actor);
            return out;
        });
    }

    /** The way an actor faces at a tick: its last keyframe up to it, else its first, else south. */
    private static double facing(ReplayProject.ActorData a, int tick) {
        double yaw = a.keyframes.isEmpty() ? 0.0 : a.keyframes.get(0).yaw;
        for (ReplayProject.ActorKeyframe k : a.keyframes) {
            if (k.tick <= tick) {
                yaw = k.yaw;
            }
        }
        return yaw;
    }

    // ------------------------------------------------------------------ actor actions

    static McpToolResult actionTool(McpObserver observer, McpControl control, McpArgs args) throws McpArgs.Invalid {
        String action = McpEditTools.action(args, List.of("add", "update", "remove"));
        return McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject.ActorData a = actor(r.project(), args.required("actor"));
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            switch (action) {
                case "add" -> {
                    McpEditTools.only(args, action, Set.of("actor", "tick", "type", "duration", "param", "target", "clipFrom", "clipTo"));
                    if (!args.has("type")) {
                        throw new McpArgs.Invalid("Argument 'type' is required: " + String.join(", ", actionTypes()));
                    }
                    ActionType type = ActionType.valueOf(args.choice("type", null, actionTypes()));
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    String param = param(control, args, a, type, null);
                    ReplayProject.ActorAction act = new ReplayProject.ActorAction(tick, type, args.integer("duration", 0, 0, 72_000), param);
                    clip(args, act);
                    boolean replaced = a.actions.stream().anyMatch(x -> x.tick == tick && x.actionType() == type);
                    ReplayProject.ActorAction set = a.setAction(tick, type, act.duration, act.param);
                    set.clipFrom = act.clipFrom;
                    set.clipTo = act.clipTo;
                    set.normalizeClip();
                    out.add("actorAction", actionJson(set));
                    out.addProperty("replaced", replaced);
                }
                case "update" -> {
                    McpEditTools.only(args, action, Set.of("actor", "tick", "type", "newTick", "duration", "param", "target", "clipFrom", "clipTo"));
                    ReplayProject.ActorAction act = find(args, r, a);
                    if (args.names().size() <= 3 + (args.has("type") ? 1 : 0)) {
                        throw McpEditTools.nothing();
                    }
                    ReplayProject.ActorAction trial = McpEditTools.copy(act);
                    trial.tick = McpControlTools.tick(args, "newTick", r, act.tick);
                    trial.duration = args.integer("duration", act.duration, 0, 72_000);
                    trial.param = param(control, args, a, act.actionType(), act.param);
                    clip(args, trial);
                    act.tick = trial.tick;
                    act.duration = trial.duration;
                    act.param = trial.param;
                    act.clipFrom = trial.clipFrom;
                    act.clipTo = trial.clipTo;
                    act.normalizeClip();
                    a.sort();
                    out.add("actorAction", actionJson(act));
                }
                default -> {
                    McpEditTools.only(args, action, Set.of("actor", "tick", "type"));
                    ReplayProject.ActorAction act = find(args, r, a);
                    a.actions.remove(act);
                    out.add("removedAction", actionJson(act));
                }
            }
            JsonObject actor = new JsonObject();
            actor.addProperty("id", a.id);
            actor.addProperty("name", shownName(a));
            actor.addProperty("actions", a.actions.size());
            out.add("actor", actor);
            return out;
        });
    }

    /** The action an update or a removal names: by tick, and type when several share it. */
    private static ReplayProject.ActorAction find(McpArgs args, McpObserver.Replay r, ReplayProject.ActorData a) throws McpArgs.Invalid {
        int tick = McpControlTools.requiredTick(args, "tick", r);
        String type = args.choice("type", null, actionTypes());
        List<ReplayProject.ActorAction> at = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (ReplayProject.ActorAction act : a.actions) {
            if (act.tick == tick) {
                types.add(act.type);
                if (type == null || act.type.equals(type)) {
                    at.add(act);
                }
            }
        }
        if (at.isEmpty()) {
            throw new McpArgs.Invalid("Actor '" + shownName(a) + "' has no " + (type == null ? "action" : type + " action")
                    + " at tick " + tick + (types.isEmpty() ? "" : " (there: " + String.join(", ", types) + ")"));
        }
        if (at.size() > 1) {
            throw new McpArgs.Invalid("Actor '" + shownName(a) + "' has several actions at tick " + tick + " (" + String.join(", ", types)
                    + "): give 'type'");
        }
        return at.get(0);
    }

    /** The parameter of an action: 'target' for a walk, 'param', or the editor's default for a new action. */
    private static String param(McpControl control, McpArgs args, ReplayProject.ActorData a, ActionType type, String current)
            throws McpArgs.Invalid {
        double[] target = args.point("target");
        if (target != null) {
            if (type != ActionType.WALK_TO && type != ActionType.RUN_TO) {
                throw new McpArgs.Invalid("'target' is for WALK_TO and RUN_TO actions");
            }
            if (args.has("param")) {
                throw new McpArgs.Invalid("Give target or param, not both");
            }
            return String.format(Locale.ROOT, "%.2f %.2f %.2f", target[0], target[1], target[2]);
        }
        String param = args.text("param", 256);
        if (param != null) {
            param = param.trim();
            if (type == ActionType.ANIMATION && a.isModel() && !param.isEmpty()) {
                String animation = param.contains("|") ? param.substring(0, param.indexOf('|')) : param;
                if (!control.modelAnimations(a.model).contains(animation)) {
                    throw new McpArgs.Invalid("Model '" + a.model + "' has no animation '" + animation + "' (its animations: "
                            + String.join(", ", control.modelAnimations(a.model)) + ")");
                }
            }
            return param;
        }
        return current != null ? current : control.defaultActionParam(a, type.name());
    }

    private static void clip(McpArgs args, ReplayProject.ActorAction act) throws McpArgs.Invalid {
        boolean emote = act.actionType() == ActionType.EMOTE || act.actionType() == ActionType.CUSTOM_EMOTE;
        if ((args.has("clipFrom") || args.has("clipTo")) && !emote) {
            throw new McpArgs.Invalid("clipFrom and clipTo are for EMOTE and CUSTOM_EMOTE actions");
        }
        act.clipFrom = args.integer("clipFrom", act.clipFrom, 0, MAX_T);
        act.clipTo = args.integer("clipTo", act.clipTo, -1, MAX_T);
        if (act.clipTo >= 0 && act.clipTo <= act.clipFrom) {
            throw new McpArgs.Invalid("'clipTo' must be after 'clipFrom', or -1 for the end of the emote");
        }
    }

    private static JsonObject actionJson(ReplayProject.ActorAction act) {
        JsonObject o = McpJson.tree(act).getAsJsonObject();
        o.addProperty("time", McpJson.time(act.tick));
        return o;
    }

    // ------------------------------------------------------------------ recorded entities

    private static final Set<String> PLAYER_EDITS = Set.of("hideSkinLayers", "hiddenParts");

    private static McpFields<ReplayProject.EntityEdit> entityFields(McpControl control) {
        return new McpFields<ReplayProject.EntityEdit>()
                .bool("hidden", "Hides the entity from hideFrom to hideTo.", (e, v) -> e.hidden = v)
                .integer("hideFrom", "First tick it is hidden.", 0, MAX_T, (e, v) -> e.hideFrom = v)
                .integer("hideTo", "Last tick it is hidden, -1 for the end of the replay.", -1, MAX_T, (e, v) -> e.hideTo = v)
                .number("soundGain", "Volume of its sounds, 0 to 4 (1 leaves them as they are).", 0.0, 4.0, (e, v) -> e.soundGain = v.floatValue())
                .bool("muted", "Silences its sounds.", (e, v) -> e.muted = v)
                .number("scale", "Render scale around the feet, 0.05 to 10.", 0.05, 10.0, (e, v) -> e.scale = v.floatValue())
                .bool("glow", "Outlined through walls in its team colour.", (e, v) -> e.glow = v)
                .bool("hideSkinLayers", "Recorded players: hides the outer layer of the skin.", (e, v) -> e.hideSkinLayers = v)
                .custom("hiddenParts", s -> s.strings("hiddenParts", "Recorded players: body parts hidden, among "
                        + String.join(", ", control.bodyParts().keySet()) + "; an empty list shows them all.", 6),
                        (args, name) -> mask(args, name, control.bodyParts()), (ReplayProject.EntityEdit e, Integer v) -> e.hiddenParts = v)
                .custom("hiddenArmor", s -> s.strings("hiddenArmor", "Armor pieces not drawn, among head, chest, legs, feet; an "
                        + "empty list shows them all.", 4), (args, name) -> mask(args, name, ARMOR),
                        (ReplayProject.EntityEdit e, Integer v) -> e.hiddenArmor = v);
    }

    /** The edit an entity argument names, with whether it is a player; created (not yet added) when it has none. */
    private record Target(ReplayProject.EntityEdit edit, boolean player, boolean existed) {
    }

    private static Target target(McpObserver observer, ReplayProject p, McpArgs args) throws McpArgs.Invalid {
        String key = args.required("entity");
        McpEntity e = observer.entity(key);
        if (e != null) {
            if (McpEntity.ACTOR.equals(e.source)) {
                throw new McpArgs.Invalid("'" + key + "' is an actor of the edit: edit_actor changes it");
            }
            ReplayProject.EntityEdit edit = p.entityEdit(e.uuid);
            boolean existed = edit != null;
            if (edit == null) {
                edit = new ReplayProject.EntityEdit(e.uuid, ReplayProject.EntityEdit.label(e.type, e.name,
                        e.player || !e.customName.isEmpty()));
            }
            return new Target(edit, e.player, existed);
        }
        ReplayProject.EntityEdit edit = p.entityEdit(key.trim());
        if (edit == null) {
            throw new McpArgs.Invalid("No entity '" + key + "' in the world at the play head, and no edit of an entity with that "
                    + "UUID (list_entities gives the ids; seek to a tick where the entity is)");
        }
        return new Target(edit, edit.label.startsWith("player "), true);
    }

    static McpToolResult entityTool(McpObserver observer, McpControl control, McpFields<ReplayProject.EntityEdit> fields, McpArgs args)
            throws McpArgs.Invalid {
        String action = McpEditTools.action(args, List.of("update", "reset", "set_offset", "remove_offset", "add_skin_change",
                "remove_skin_change", "hide_type", "show_type"));
        return McpEditTools.edit(observer, control, (r, change) -> {
            ReplayProject p = r.project();
            JsonObject out = new JsonObject();
            out.addProperty("action", action);
            if (action.equals("hide_type") || action.equals("show_type")) {
                McpEditTools.only(args, action, Set.of("type"));
                String type = args.required("type");
                if (!control.known("entity_type", type)) {
                    throw new McpArgs.Invalid("'" + type + "' is not an entity type of this game (an id such as minecraft:bat)");
                }
                List<String> hidden = new ArrayList<>(p.hiddenEntityTypes);
                hidden.remove(type);
                if (action.equals("hide_type")) {
                    hidden.add(type);
                }
                p.hiddenEntityTypes = WorldSettings.cleanIds(hidden);
                out.add("hiddenEntityTypes", McpJson.tree(p.hiddenEntityTypes));
                return out;
            }
            Target t = target(observer, p, args);
            ReplayProject.EntityEdit edit = t.edit();
            switch (action) {
                case "update" -> {
                    McpEditTools.only(args, action, McpEditTools.names("entity", fields.names()));
                    if (!t.player()) {
                        for (String name : PLAYER_EDITS) {
                            if (args.has(name)) {
                                throw new McpArgs.Invalid("'" + name + "' is for recorded players");
                            }
                        }
                    }
                    McpFields.Changes<ReplayProject.EntityEdit> given = fields.read(args);
                    if (given.isEmpty()) {
                        throw McpEditTools.nothing();
                    }
                    ReplayProject.EntityEdit trial = McpEditTools.copy(edit);
                    given.applyTo(trial);
                    if (trial.hideTo >= 0 && trial.hideTo < trial.hideFrom) {
                        throw new McpArgs.Invalid("'hideTo' must not be before 'hideFrom' (or -1 for the end of the replay)");
                    }
                    given.applyTo(edit);
                }
                case "reset" -> {
                    McpEditTools.only(args, action, Set.of("entity"));
                    if (!t.existed()) {
                        throw new McpArgs.Invalid("Entity '" + edit.label + "' has no edit to reset");
                    }
                    p.entityEdits.remove(edit);
                    out.addProperty("reset", edit.uuid);
                    return out;
                }
                case "set_offset" -> {
                    McpEditTools.only(args, action, Set.of("entity", "tick", "x", "y", "z", "yaw"));
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    edit.offsets.removeIf(k -> k.tick == tick);
                    edit.offsets.add(new ReplayProject.ActorKeyframe(tick, args.number("x", 0.0, -64.0, 64.0), args.number("y", 0.0, -64.0, 64.0),
                            args.number("z", 0.0, -64.0, 64.0), (float) args.number("yaw", 0.0, -180.0, 180.0), 0.0F));
                    edit.offsets.sort(java.util.Comparator.comparingInt(k -> k.tick));
                }
                case "remove_offset" -> {
                    McpEditTools.only(args, action, Set.of("entity", "tick"));
                    int tick = McpControlTools.requiredTick(args, "tick", r);
                    if (!edit.offsets.removeIf(k -> k.tick == tick)) {
                        throw new McpArgs.Invalid("Entity '" + edit.label + "' has no offset at tick " + tick);
                    }
                }
                case "add_skin_change" -> {
                    McpEditTools.only(args, action, Set.of("entity", "tick", "skin", "slim"));
                    if (!t.player()) {
                        throw new McpArgs.Invalid("Only recorded players change skins");
                    }
                    int tick = McpControlTools.tick(args, "tick", r, r.tick());
                    String skin = args.has("skin") ? file(control, args, "skin", "skins") : "";
                    edit.skinChanges.removeIf(c -> c.tick == tick);
                    edit.skinChanges.add(new ReplayProject.SkinChange(tick, skin, args.bool("slim", false)));
                    edit.skinChanges.sort(java.util.Comparator.comparingInt(c -> c.tick));
                }
                default -> {
                    McpEditTools.only(args, action, Set.of("entity", "tick"));
                    int tick = McpControlTools.requiredTick(args, "tick", r);
                    if (!edit.skinChanges.removeIf(c -> c.tick == tick)) {
                        throw new McpArgs.Invalid("Entity '" + edit.label + "' has no skin change at tick " + tick);
                    }
                }
            }
            if (!t.existed()) {
                p.entityEdits.add(edit);
            }
            edit.normalize();
            // The Entities tab drops an edit that no longer changes anything.
            p.entityEdits.removeIf(ReplayProject.EntityEdit::isEmpty);
            JsonObject o = McpJson.summarize(McpJson.tree(edit)).getAsJsonObject();
            o.add("hiddenPartNames", McpJson.tree(names(edit.hiddenParts, control.bodyParts())));
            o.add("hiddenArmorNames", McpJson.tree(names(edit.hiddenArmor, ARMOR)));
            out.add("entityEdit", o);
            out.addProperty("kept", p.entityEdits.contains(edit));
            return out;
        });
    }
}
