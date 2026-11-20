package com.vyrriox.justreplay.compat;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * One integration with another mod. It runs while that mod is installed, the integration is supported on this loader
 * and the config leaves it on. The first error escaping one of its hooks (a method or class of the other mod that
 * changed, a crash in its code) turns it off until the game restarts, with a single warning in the log: the
 * integration loses its feature, the game, the recording and the replay go on. No game class here, so the mixin
 * plugin and the unit tests can use it.
 *
 * @author vyrriox
 */
public final class Integration {
    /** Why an integration runs or not, as the startup line tells it. */
    public enum State {
        ON("on"),
        OFF_IN_CONFIG("off in the config"),
        UNSUPPORTED("installed but not supported on this loader"),
        ABSENT("not installed"),
        FAILED("failed, off until restart");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The logger of {@code JustReplay.LOGGER}, named here so that this class needs no game class. */
    private static final BiConsumer<String, Throwable> LOG =
            (message, error) -> LoggerFactory.getLogger("com.vyrriox.justreplay.JustReplay").warn(message, error);

    private final String modId;
    private final String name;
    private final State initial;
    private final BiConsumer<String, Throwable> warn;
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean on;
    private volatile String failedHook;

    /** An integration in this state, whose single failure warning goes to {@code warn} (message, error). */
    public Integration(String modId, String name, State initial, BiConsumer<String, Throwable> warn) {
        this.modId = modId;
        this.name = name;
        this.initial = initial;
        this.warn = warn;
        this.on = initial == State.ON;
    }

    /** The integration with this mod, from what the loader and the config say about it. */
    public static Integration of(String modId, String name, boolean installed, boolean supported, boolean enabled) {
        State state = !installed ? State.ABSENT : !supported ? State.UNSUPPORTED : !enabled ? State.OFF_IN_CONFIG : State.ON;
        return new Integration(modId, name, state, LOG);
    }

    /** True while the hooks of this integration run. A volatile read: cheap enough for every packet or frame. */
    public boolean on() {
        return on;
    }

    /** True when it started on, failed or not: what it changed in its mod must still be put back. */
    public boolean started() {
        return initial == State.ON;
    }

    public String modId() {
        return modId;
    }

    public String name() {
        return name;
    }

    public State state() {
        return failed.get() ? State.FAILED : initial;
    }

    /** The hook whose error turned this integration off, or null. */
    public String failedHook() {
        return failedHook;
    }

    /**
     * Turns this integration off after an error escaped {@code hook}. Only the first error is logged, with its stack
     * trace, so a broken integration writes one warning however often its hooks run and on whatever thread.
     */
    public void fail(String hook, Throwable error) {
        on = false;
        if (failed.compareAndSet(false, true)) {
            failedHook = hook;
            warn.accept("The " + name + " integration of Just Replay failed in " + hook + " and is off until the game restarts; "
                    + name + " itself keeps working. If this happens at every start, set compat." + modId + " to false in "
                    + "config/" + CompatSwitches.FILE_NAME + ". Cause: " + error, error);
        }
    }

    /** The body of a hook: reflection and the other mod's code may throw anything, checked or not. */
    @FunctionalInterface
    public interface Hook {
        void run() throws Throwable;
    }

    /** The body of a yes or no hook. */
    @FunctionalInterface
    public interface Check {
        boolean test() throws Throwable;
    }

    /** Runs a hook of this integration if it is on; an error turns the integration off. */
    public void run(String hook, Hook body) {
        if (!on) {
            return;
        }
        try {
            body.run();
        } catch (Throwable t) {
            fail(hook, t);
        }
    }

    /** A yes or no hook: false while the integration is off or when the hook fails (which turns it off). */
    public boolean test(String hook, Check body) {
        if (!on) {
            return false;
        }
        try {
            return body.test();
        } catch (Throwable t) {
            fail(hook, t);
            return false;
        }
    }

    /**
     * Runs a hook that puts back what the integration changed in its mod (end of a replay or an export) when it started
     * on, even after a failure turned it off. A new error is not logged again.
     */
    public void cleanup(String hook, Hook body) {
        if (!started()) {
            return;
        }
        try {
            body.run();
        } catch (Throwable t) {
            fail(hook, t);
        }
    }

    /** The startup line for these integrations, of which the mods in {@code found} are installed. */
    public static String startupLine(String version, String loader, String loaderVersion, String game,
                              Iterable<Integration> integrations, Set<String> found) {
        List<String> on = new ArrayList<>();
        List<String> offInConfig = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int absent = 0;
        for (Integration integration : integrations) {
            if (!found.contains(integration.modId())) {
                absent++;
                continue;
            }
            switch (integration.state()) {
                case ON -> on.add(integration.name());
                case OFF_IN_CONFIG -> offInConfig.add(integration.name());
                case UNSUPPORTED -> unsupported.add(integration.name());
                case FAILED -> failed.add(integration.name() + " (in " + integration.failedHook() + ")");
                default -> absent++;
            }
        }
        StringBuilder line = new StringBuilder("Just Replay ").append(version.isEmpty() ? "?" : version).append(" on ").append(loader);
        if (!loaderVersion.isEmpty()) {
            line.append(' ').append(loaderVersion);
        }
        line.append(", Minecraft ").append(game.isEmpty() ? "?" : game).append(". Integrations on: ").append(list(on))
                .append("; off in the config: ").append(list(offInConfig))
                .append("; installed but not supported on this loader: ").append(list(unsupported));
        if (!failed.isEmpty()) {
            line.append("; failed: ").append(list(failed));
        }
        return line.append("; not installed: ").append(absent).append('.').toString();
    }

    private static String list(List<String> names) {
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    @Override
    public String toString() {
        return name + " (" + modId + "): " + state().label();
    }
}
