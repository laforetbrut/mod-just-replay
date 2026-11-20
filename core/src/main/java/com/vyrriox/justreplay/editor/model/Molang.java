package com.vyrriox.justreplay.editor.model;

import java.util.Locale;

/**
 * A small subset of Molang for Blockbench keyframe values: numbers, + - * /, parentheses, the comparison free
 * math functions (sin and cos take degrees, like Blockbench) and the time queries. Anything else fails to parse
 * and the caller falls back to 0.
 *
 * @author vyrriox
 */
public final class Molang {
    /** A compiled expression of the animation time in seconds. */
    public interface Expr {
        double eval(double time);
    }

    private final String src;
    private int pos;

    private Molang(String src) {
        this.src = src;
    }

    /** Parses an expression; throws {@link IllegalArgumentException} when it is not understood. */
    public static Expr parse(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return t -> 0.0;
        }
        try {
            double value = Double.parseDouble(trimmed);
            return t -> value;
        } catch (NumberFormatException ignored) {
        }
        Molang parser = new Molang(trimmed.toLowerCase(Locale.ROOT));
        Expr expr = parser.sum();
        parser.skipSpaces();
        if (parser.pos < parser.src.length() && parser.src.charAt(parser.pos) == ';') {
            parser.pos++;
            parser.skipSpaces();
        }
        if (parser.pos != parser.src.length()) {
            throw new IllegalArgumentException("Unexpected '" + parser.src.substring(parser.pos) + "'");
        }
        return expr;
    }

    private void skipSpaces() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private boolean eat(char c) {
        skipSpaces();
        if (pos < src.length() && src.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private Expr sum() {
        Expr left = product();
        while (true) {
            if (eat('+')) {
                Expr a = left, b = product();
                left = t -> a.eval(t) + b.eval(t);
            } else if (eat('-')) {
                Expr a = left, b = product();
                left = t -> a.eval(t) - b.eval(t);
            } else {
                return left;
            }
        }
    }

    private Expr product() {
        Expr left = unary();
        while (true) {
            if (eat('*')) {
                Expr a = left, b = unary();
                left = t -> a.eval(t) * b.eval(t);
            } else if (eat('/')) {
                Expr a = left, b = unary();
                left = t -> {
                    double d = b.eval(t);
                    return d == 0 ? 0 : a.eval(t) / d;
                };
            } else {
                return left;
            }
        }
    }

    private Expr unary() {
        if (eat('-')) {
            Expr a = unary();
            return t -> -a.eval(t);
        }
        if (eat('+')) {
            return unary();
        }
        return atom();
    }

    private Expr atom() {
        skipSpaces();
        if (eat('(')) {
            Expr inner = sum();
            if (!eat(')')) {
                throw new IllegalArgumentException("Missing )");
            }
            return inner;
        }
        if (pos >= src.length()) {
            throw new IllegalArgumentException("Unexpected end");
        }
        char c = src.charAt(pos);
        if (Character.isDigit(c) || c == '.') {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            double value = Double.parseDouble(src.substring(start, pos));
            return t -> value;
        }
        if (Character.isLetter(c) || c == '_') {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_' || src.charAt(pos) == '.')) {
                pos++;
            }
            return name(src.substring(start, pos));
        }
        throw new IllegalArgumentException("Unexpected '" + c + "'");
    }

    private Expr name(String name) {
        switch (name) {
            case "q.anim_time", "query.anim_time", "q.life_time", "query.life_time" -> {
                return t -> t;
            }
            case "math.pi" -> {
                return t -> Math.PI;
            }
            default -> {
            }
        }
        if (!name.startsWith("math.") || !eat('(')) {
            throw new IllegalArgumentException("Unknown name " + name);
        }
        java.util.List<Expr> args = new java.util.ArrayList<>();
        if (!eat(')')) {
            do {
                args.add(sum());
            } while (eat(','));
            if (!eat(')')) {
                throw new IllegalArgumentException("Missing ) after " + name);
            }
        }
        Expr a = args.isEmpty() ? t -> 0.0 : args.get(0);
        Expr b = args.size() > 1 ? args.get(1) : t -> 0.0;
        Expr c = args.size() > 2 ? args.get(2) : t -> 0.0;
        return switch (name) {
            case "math.sin" -> t -> Math.sin(Math.toRadians(a.eval(t)));
            case "math.cos" -> t -> Math.cos(Math.toRadians(a.eval(t)));
            case "math.abs" -> t -> Math.abs(a.eval(t));
            case "math.sqrt" -> t -> Math.sqrt(Math.max(0, a.eval(t)));
            case "math.floor" -> t -> Math.floor(a.eval(t));
            case "math.ceil" -> t -> Math.ceil(a.eval(t));
            case "math.round" -> t -> Math.round(a.eval(t));
            case "math.trunc" -> t -> (double) (long) a.eval(t);
            case "math.min" -> t -> Math.min(a.eval(t), b.eval(t));
            case "math.max" -> t -> Math.max(a.eval(t), b.eval(t));
            case "math.pow" -> t -> Math.pow(a.eval(t), b.eval(t));
            case "math.mod" -> t -> {
                double d = b.eval(t);
                return d == 0 ? 0 : a.eval(t) % d;
            };
            case "math.clamp" -> t -> Math.max(b.eval(t), Math.min(c.eval(t), a.eval(t)));
            case "math.lerp" -> t -> {
                double from = a.eval(t);
                return from + (b.eval(t) - from) * c.eval(t);
            };
            default -> throw new IllegalArgumentException("Unknown function " + name);
        };
    }
}
