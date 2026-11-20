package com.vyrriox.justreplay.format;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Names the player gives to replays: the name shown in the replay list ({@link ReplayMeta#name}) and the folder name
 * made from it, which must be valid on Windows, macOS and Linux and never take the folder of another replay. Plain
 * Java, no game code.
 *
 * @author vyrriox
 */
public final class ReplayNames {
    /** Longest name in characters: keeps replay paths far below the 260 characters Windows allows. */
    public static final int MAX_LENGTH = 64;
    /** Characters Windows refuses in file names; '/' is also the one Linux and macOS refuse. */
    private static final String FORBIDDEN = "<>:\"/\\|?*";
    /** Minecraft's formatting code sign: it would restyle the name wherever the game draws it. */
    private static final int FORMATTING_SIGN = 0xA7;
    /** Highest number tried for a free folder before falling back to the clock. */
    private static final int MAX_SUFFIX = 9999;
    /** Names Windows keeps for devices, with or without an extension (con.txt is refused too). */
    private static final Set<String> DEVICES = Set.of("con", "prn", "aux", "nul",
            "com0", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "com¹", "com²", "com³",
            "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9", "lpt¹", "lpt²", "lpt³");

    private ReplayNames() {
    }

    /**
     * The name as shown: control, invisible and formatting characters dropped, each run of white space made one
     * space, trimmed, and cut to {@link #MAX_LENGTH} without splitting a character. Empty when nothing is left.
     */
    public static String clean(String typed) {
        if (typed == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(typed.length(), MAX_LENGTH));
        boolean space = false;
        for (int i = 0; i < typed.length(); ) {
            int cp = typed.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = true;
                continue;
            }
            if (!visible(cp)) {
                continue;
            }
            int needed = (space && out.length() > 0 ? 1 : 0) + Character.charCount(cp);
            if (out.length() + needed > MAX_LENGTH) {
                break;
            }
            if (space && out.length() > 0) {
                out.append(' ');
            }
            space = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static boolean visible(int cp) {
        if (Character.isISOControl(cp) || cp == FORMATTING_SIGN) {
            return false;
        }
        int type = Character.getType(cp);
        // Zero width and direction marks could disguise a name; a lone surrogate is not a character.
        return type != Character.FORMAT && type != Character.SURROGATE;
    }

    /**
     * Folder name for a name: characters Windows or Linux refuse become '_'; no dot or space at either end (Windows
     * drops them, a leading dot hides the folder on Linux and "." or ".." would leave the replay folder); Windows
     * device names (CON, NUL, COM1...) get a '_' in front and names the replay list skips (ending in .tmp) one at
     * the end. Empty when nothing usable is left.
     */
    public static String folderName(String name) {
        String cleaned = clean(name);
        StringBuilder out = new StringBuilder(cleaned.length() + 1);
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            out.append(FORBIDDEN.indexOf(c) >= 0 ? '_' : c);
        }
        String folder = strip(out.toString());
        if (folder.isEmpty()) {
            return "";
        }
        int dot = folder.indexOf('.');
        String stem = (dot < 0 ? folder : folder.substring(0, dot)).trim().toLowerCase(Locale.ROOT);
        if (DEVICES.contains(stem)) {
            folder = "_" + folder;
        }
        if (folder.toLowerCase(Locale.ROOT).endsWith(".tmp")) {
            folder = folder + "_";
        }
        return folder;
    }

    /**
     * Folder name with every character outside printable ASCII made '_', for file systems whose encoding cannot
     * store the name (a Linux game started with a non UTF-8 locale). Empty when nothing usable is left.
     */
    public static String asciiFolderName(String name) {
        String folder = folderName(name);
        StringBuilder out = new StringBuilder(folder.length());
        for (int i = 0; i < folder.length(); ) {
            int cp = folder.codePointAt(i);
            i += Character.charCount(cp);
            out.append(cp >= 0x20 && cp < 0x7F ? (char) cp : '_');
        }
        return folderName(out.toString());
    }

    private static String strip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '.' || s.charAt(start) == ' ')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(start, end);
    }

    /** {@code name} followed by the number that tells it from replays of the same name: "Boss fight (2)". */
    public static String withSuffix(String name, int number) {
        return name + " (" + number + ")";
    }

    /**
     * {@code base} when no replay folder has that name yet, else the first free of "base (2)", "base (3)"... A
     * replay is never written over.
     *
     * @param taken whether a folder of that name already exists
     */
    public static String unique(String base, Predicate<String> taken) {
        if (!taken.test(base)) {
            return base;
        }
        for (int n = 2; n <= MAX_SUFFIX; n++) {
            String candidate = withSuffix(base, n);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        // Ten thousand replays of one name: the clock tells the next one apart.
        long stamp = System.currentTimeMillis();
        String candidate;
        do {
            candidate = base + " (" + Long.toString(stamp++, 36) + ")";
        } while (taken.test(candidate));
        return candidate;
    }
}
