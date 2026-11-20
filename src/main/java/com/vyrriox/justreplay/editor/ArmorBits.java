package com.vyrriox.justreplay.editor;

/**
 * The bits of the armor mask a replay project keeps for an actor or a recorded player, one per armor slot. Plain Java,
 * so that code which only reads the mask needs no game class; {@code ArmorSlots} hands the same bits to the game side.
 *
 * @author vyrriox
 */
public final class ArmorBits {
    public static final int HEAD = 1;
    public static final int CHEST = 2;
    public static final int LEGS = 4;
    public static final int FEET = 8;
    public static final int ALL = 15;

    private ArmorBits() {
    }
}
