package com.vyrriox.justreplay.editor;

import java.util.Locale;

/**
 * Kinds of actor actions. One shot actions play their natural length from their tick; state actions hold
 * from their tick for their duration, or until the next {@link #IDLE} when the duration is 0.
 *
 * @author vyrriox
 */
public enum ActionType {
    /** Ends every state action started before it. */
    IDLE("ID", Kind.MARKER, 0, ""),
    WALK("WK", Kind.STATE, 0, ""),
    RUN("RN", Kind.STATE, 0, ""),
    SWING_MAIN("SW", Kind.ONE_SHOT, 6, ""),
    SWING_OFF("SO", Kind.ONE_SHOT, 6, ""),
    ATTACK("AT", Kind.ONE_SHOT, 10, ""),
    HURT("HT", Kind.ONE_SHOT, 10, ""),
    JUMP("JP", Kind.ONE_SHOT, 0, ""),
    DEATH("DE", Kind.STATE, 0, ""),
    CROUCH("CR", Kind.STATE, 0, ""),
    SIT("SI", Kind.STATE, 0, ""),
    SLEEP("SL", Kind.STATE, 0, ""),
    SWIM("SM", Kind.STATE, 0, ""),
    ELYTRA("EL", Kind.STATE, 0, ""),
    SPIN_ATTACK("SP", Kind.STATE, 0, ""),
    /** Uses the item in the hand named by the parameter ({@code main} or {@code off}). */
    USE_ITEM("US", Kind.STATE, 0, "main"),
    BLOCK("BL", Kind.STATE, 0, ""),
    BOW("BW", Kind.STATE, 0, ""),
    EAT("EA", Kind.STATE, 0, ""),
    AGGRESSIVE("AG", Kind.STATE, 0, ""),
    WAVE("WV", Kind.STATE, 0, ""),
    POINT("PT", Kind.STATE, 0, ""),
    CHEER("CH", Kind.STATE, 0, ""),
    HANDS_UP("HU", Kind.STATE, 0, ""),
    /** Turns the head towards another actor (its name) or {@code camera}. */
    LOOK_AT("LK", Kind.STATE, 0, "camera"),
    LOOK_AROUND("LA", Kind.STATE, 0, ""),
    /** Strolls around the keyframed position; the parameter is the radius in blocks. */
    WANDER("WA", Kind.STATE, 0, "2"),
    /**
     * Walks in a straight line to a point, following the ground. The parameter is the destination "x y z"; left
     * empty it is set to the camera position when the action is added. A duration of 0 uses the walking speed.
     */
    WALK_TO("WT", Kind.STATE, 0, ""),
    /** Same as {@link #WALK_TO} at running speed. */
    RUN_TO("RT", Kind.STATE, 0, ""),
    /** Follows a target: "recorder", an actor or player name, or an entity UUID, then "|" and the distance. */
    FOLLOW("FO", Kind.STATE, 0, "recorder|2.5"),
    /** Lifts the actor off the ground: keyframe heights are used as they are. */
    FLY("FL", Kind.STATE, 0, ""),
    /** Plays an animation of a model actor; the parameter is the animation name. */
    ANIMATION("AN", Kind.STATE, 0, ""),
    /** Sends a vanilla entity event byte (the parameter) to the entity when the play head passes it. */
    ENTITY_EVENT("EV", Kind.ONE_SHOT, 0, "4"),
    /** Plays an Emotecraft emote on a player actor; the parameter is the emote name (or UUID). Needs Emotecraft. */
    EMOTE("EM", Kind.STATE, 0, "waving"),
    /**
     * Dances on a beat: "style|bpm|origin", origin being the replay tick (decimals allowed) of a beat. Styles: bounce,
     * pump, hands, wave, shuffle, headbang, jump, dj. Humanoid actors only.
     */
    DANCE("DA", Kind.STATE, 0, "bounce|128|0");

    public enum Kind { ONE_SHOT, STATE, MARKER }

    /** Two letter label drawn on the timeline. */
    public final String shortLabel;
    public final Kind kind;
    /** Natural length of a one shot action, in ticks. */
    public final int length;
    public final String defaultParam;

    ActionType(String shortLabel, Kind kind, int length, String defaultParam) {
        this.shortLabel = shortLabel;
        this.kind = kind;
        this.length = length;
        this.defaultParam = defaultParam;
    }

    public String translationKey() {
        return "justreplay.action." + name().toLowerCase(Locale.ROOT);
    }

    public static ActionType parse(String name) {
        if (name != null) {
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return IDLE;
    }
}
