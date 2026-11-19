package com.vyrriox.justreplay.client;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.ReplayNames;
import com.vyrriox.justreplay.format.ReplayRename;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.gui.TextInputScreen;
import com.vyrriox.justreplay.gui.ui.GlassToast;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The window that names a replay when the player stops a recording or saves the last minutes. The replay is already
 * written under the date and time; the window opens at the first moment it gets in the way of nothing (in a world
 * with no other screen open, or on the menu a disconnect leads to), filled with that name, selected so typing
 * replaces it. Enter or Rename renames the replay off the client thread; Esc or Skip keeps the name.
 *
 * @author vyrriox
 */
public final class ReplayNaming {
    /** A saved replay waiting for its window, with the name it was saved under. */
    private record Pending(Path dir, String name) {
    }

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    /** Renames run one after the other, so two of them never pick the same free folder; the thread ends when idle. */
    private static final ThreadPoolExecutor RENAMER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "JustReplay rename");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicInteger RENAMING = new AtomicInteger();
    private static TextInputScreen window;

    static {
        RENAMER.allowCoreThreadTimeOut(true);
    }

    private ReplayNaming() {
    }

    /** Offers to name a replay the player just saved. Nothing when the setting is off or a scene job runs. */
    public static void offer(Path dir) {
        if (JRConfig.NAME_ON_SAVE.get() && !SceneRecorder.enabled()) {
            queue(dir);
        }
    }

    /** Queues the window of a saved replay, whatever the setting. */
    static void queue(Path dir) {
        if (dir != null) {
            PENDING.add(new Pending(dir, ReplayStorage.readMeta(dir).name));
        }
    }

    /** Client tick: opens the window of the oldest replay waiting for a name once the screen is free. */
    public static void tick(Minecraft mc) {
        if (PENDING.isEmpty() || !screenFree(mc)) {
            return;
        }
        Pending pending = PENDING.poll();
        if (!Files.isDirectory(pending.dir())) {
            // Deleted in the meantime.
            return;
        }
        window = new TextInputScreen(mc.screen, Component.translatable("justreplay.name.title"),
                Component.translatable("justreplay.name.label"), pending.name(), typed -> rename(mc, pending, typed))
                .buttons(Component.translatable("justreplay.name.skip"), Component.translatable("justreplay.name.rename"))
                .maxLength(ReplayNames.MAX_LENGTH).selectAll().pausesGame(mc.level != null);
        mc.setScreen(window);
    }

    /**
     * Whether the window may open now: never during a replay or an export, nor over another screen in a world;
     * outside a world only on the menus a disconnect leads to, not on a loading screen.
     */
    private static boolean screenFree(Minecraft mc) {
        if (mc.getOverlay() != null || ReplaySession.current() != null || VideoExporter.isActive()) {
            return false;
        }
        if (mc.level != null) {
            return mc.player != null && mc.screen == null;
        }
        Screen screen = mc.screen;
        return screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen || screen instanceof SelectWorldScreen
                || screen instanceof DisconnectedScreen || screen instanceof com.mojang.realmsclient.RealmsMainScreen;
    }

    private static void rename(Minecraft mc, Pending pending, String typed) {
        String name = ReplayNames.clean(typed);
        if (name.isEmpty() || name.equals(pending.name())) {
            // Same as Skip: the replay keeps the name it was saved under.
            return;
        }
        RENAMING.incrementAndGet();
        RENAMER.execute(() -> {
            ReplayRename.Result result = null;
            try {
                result = ReplayRename.apply(pending.dir(), name);
                JustReplay.LOGGER.info("Replay {} renamed to '{}' in {} ({})", pending.dir().getFileName(), result.name(),
                        result.dir().getFileName(), result.status());
            } catch (Throwable t) {
                JustReplay.LOGGER.error("Cannot rename the replay {}", pending.dir(), t);
            } finally {
                RENAMING.decrementAndGet();
            }
            ReplayRename.Result done = result;
            mc.execute(() -> toast(done));
        });
    }

    private static void toast(ReplayRename.Result result) {
        if (result == null) {
            GlassToast.show(Icon.CLOSE, Theme.WARNING, Component.translatable("justreplay.name.failed"), null);
        } else if (result.status() == ReplayRename.Status.RENAMED) {
            GlassToast.show(Icon.EDIT, Theme.SUCCESS, Component.translatable("justreplay.name.done"), Component.literal(result.name()));
        } else if (result.status() == ReplayRename.Status.LIST_ONLY) {
            GlassToast.show(Icon.EDIT, Theme.WARNING, Component.translatable("justreplay.name.list_only"),
                    Component.translatable("justreplay.name.list_only.detail", result.dir().getFileName().toString()));
        }
    }

    /** Self test: the naming window last opened. */
    static TextInputScreen window() {
        return window;
    }

    /** Self test: whether a rename is still running. */
    static boolean renaming() {
        return RENAMING.get() > 0;
    }
}
