package com.vyrriox.justreplay.mcp;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.gui.EditorScreen;
import com.vyrriox.justreplay.gui.ExportScreen;
import com.vyrriox.justreplay.gui.JRConfigScreen;
import com.vyrriox.justreplay.gui.ReplayListScreen;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.GlassToast;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.platform.Platform;
import com.vyrriox.justreplay.record.Recorder;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import com.vyrriox.justreplay.replay.VersionCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Game side of the MCP server of {@code core}: follows the settings at every client tick, tells the read tools what
 * the game is doing (on the client thread only) and shows that the server listens: a toast when it starts, stops or
 * cannot start, a badge in the HUD and on the title screen while it listens. Neither badge is ever in a video.
 *
 * @author vyrriox
 */
public final class McpClient {
    private static final String LABEL = "MCP";
    /** The badge's dot lights up this long after a call. */
    private static final long ACTIVITY_MILLIS = 700L;
    private static McpHost host;
    private static boolean failureLogged;
    private static long badgeFrames;

    private McpClient() {
    }

    /** The server's host, made on first use. */
    public static McpHost host() {
        if (host == null) {
            McpRegistry registry = McpReadTools.registry(new GameView());
            McpObserveTools.register(registry, new ReplayObserver());
            host = new McpHost(JRConfig.MCP, registry, new McpProtocol.Info(JustReplay.MOD_ID, "Just Replay",
                    Platform.get().modVersion(), McpReadTools.INSTRUCTIONS + McpObserveTools.INSTRUCTIONS), new Toasts());
        }
        return host;
    }

    /** Client thread, every tick: starts or stops the server as the settings say and answers the waiting calls. */
    public static void tick(Minecraft mc) {
        try {
            com.vyrriox.justreplay.export.FrameGrab.tick();
            host().tick();
        } catch (RuntimeException | LinkageError e) {
            // The server is an extra: whatever goes wrong with it, the game goes on.
            if (!failureLogged) {
                failureLogged = true;
                JustReplay.LOGGER.error("MCP server error", e);
            }
        }
    }

    /** Frames that drew a badge since the game started, for the self test. */
    public static long badgeFrames() {
        return badgeFrames;
    }

    public static boolean isListening() {
        return host != null && host.isListening();
    }

    // ------------------------------------------------------------------ badges

    /** Width of the badge. */
    public static int badgeWidth(Font font) {
        return font.width(LABEL) + 20;
    }

    /** Draws the badge at {@code x, y} while the server listens; returns its width, 0 when nothing was drawn. */
    public static int renderBadge(GuiGraphicsExtractor g, Font font, int x, int y) {
        if (!isListening()) {
            return 0;
        }
        badgeFrames++;
        int w = badgeWidth(font);
        Paint.glass(g, x, y, w, 14, 7);
        boolean busy = System.currentTimeMillis() - host.lastCallMillis() < ACTIVITY_MILLIS;
        float glow = busy ? 1.0F : 0.5F + 0.5F * (float) Math.sin(Anim.now() / 1000.0 * Math.PI * 0.8);
        Paint.circle(g, x + 7, y + 7, 2.6F + glow * 1.2F, Theme.withAlpha(Theme.ACCENT, (int) (0x30 + 0x40 * glow)));
        Paint.circle(g, x + 7, y + 7, 2.4F, busy ? Theme.SUCCESS : Theme.ACCENT);
        g.text(font, LABEL, x + 14, y + 4, Theme.TEXT, false);
        return w;
    }

    /** The badge of the title screen: drawn only while the server listens, it takes no clicks. */
    public static AbstractWidget titleBadge() {
        return new TitleBadge(badgeWidth(Minecraft.getInstance().font));
    }

    /**
     * A named class with its own constructor: javac names the constructor parameters of an anonymous subclass after
     * the game's, which clash on the NeoForge 1.20.1 build machines ("variable o is already defined").
     */
    private static final class TitleBadge extends AbstractWidget {
        TitleBadge(int badgeWidth) {
            super(6, 6, badgeWidth, 14, Component.literal(LABEL));
            active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            renderBadge(g, Minecraft.getInstance().font, getX(), getY());
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            // Nothing to read out: the badge only shows a state.
        }
    }

    // ------------------------------------------------------------------ toasts

    private static final class Toasts implements McpHost.Events {
        @Override
        public void started(int port) {
            GlassToast.show(Icon.BOLT, Theme.ACCENT, Component.translatable("justreplay.mcp.toast.started"),
                    Component.translatable("justreplay.mcp.toast.started.detail", "http://127.0.0.1:" + port + McpHttpServer.PATH));
        }

        @Override
        public void stopped() {
            GlassToast.show(Icon.BOLT, Theme.TEXT_DIM, Component.translatable("justreplay.mcp.toast.stopped"), null);
        }

        @Override
        public void failed(int port, String reason) {
            GlassToast.show(Icon.CLOSE, Theme.WARNING, Component.translatable("justreplay.mcp.toast.failed"), Component.literal(reason));
        }
    }

    // ------------------------------------------------------------------ what the tools read

    /** The game seen by the read tools. Its first two methods run on the client thread only. */
    private static final class GameView implements McpGame {
        @Override
        public McpState state() {
            Minecraft mc = Minecraft.getInstance();
            McpState s = new McpState();
            s.minecraft = VersionCheck.runningVersion();
            s.loader = Platform.get().loaderName();
            s.modVersion = Platform.get().modVersion();
            s.screen = screenName(mc.screen);

            s.world.loaded = mc.level != null;
            s.world.replayWorld = ReplaySession.isRunning();
            s.world.singleplayer = mc.level != null && mc.hasSingleplayerServer();
            s.world.server = mc.getCurrentServer() == null ? "" : mc.getCurrentServer().name;
            s.world.dimension = com.vyrriox.justreplay.editor.MarkerRenderer.dimension();

            Recorder recorder = Recorder.active();
            if (recorder != null) {
                s.recording.active = true;
                Path dir = recorder.dir();
                s.recording.replayId = dir == null ? "" : dir.getFileName().toString();
                s.recording.ticks = recorder.tick();
            }
            s.instantReplay.enabled = JRConfig.REPLAY_BUFFER.get();
            s.instantReplay.capturing = Recorder.isBuffering();
            s.instantReplay.minutes = JRConfig.BUFFER_MINUTES.get();

            ReplaySession session = ReplaySession.current();
            s.replay.opening = ReplaySession.isOpening();
            if (session != null && ReplaySession.isRunning()) {
                Playback playback = session.playback();
                s.replay.open = true;
                s.replay.replayId = session.dir().getFileName().toString();
                s.replay.name = session.meta().name;
                s.replay.tick = playback.cursor();
                s.replay.durationTicks = playback.duration();
                s.replay.paused = playback.isPaused();
                s.replay.speed = playback.speed();
                s.replay.cameraPath = session.isCameraPathActive();
                s.replay.spectating = session.spectate().isActive() ? session.spectate().targetName().getString() : "";
            }

            s.editor.open = mc.screen instanceof EditorScreen;
            s.editor.mode = JRConfig.EDITOR_MODE.get().name();

            VideoExporter export = VideoExporter.current();
            if (VideoExporter.isActive() && export != null && !export.isGrab()) {
                s.export.active = true;
                s.export.file = export.shownFileName();
                s.export.startTick = export.startTick();
                s.export.endTick = export.endTick();
                s.export.progress = export.progress();
                s.export.frames = export.frames();
                s.export.totalFrames = export.totalFrames();
                s.export.preparing = export.isPreparing();
                s.export.finishing = export.isDraining();
            }
            return s;
        }

        @Override
        public OpenReplay openReplay() {
            ReplaySession session = ReplaySession.current();
            if (session == null || !ReplaySession.isRunning()) {
                return null;
            }
            Playback playback = session.playback();
            return new OpenReplay(session.dir().getFileName().toString(), session.meta(), session.project(), playback.cursor(),
                    playback.duration());
        }

        @Override
        public Path replaysRoot() {
            return ReplayStorage.root();
        }

        private static String screenName(Screen screen) {
            if (screen == null) {
                return "none";
            }
            if (screen instanceof TitleScreen) {
                return "title";
            }
            if (screen instanceof PauseScreen) {
                return "pause";
            }
            if (screen instanceof EditorScreen) {
                return "editor";
            }
            if (screen instanceof ReplayListScreen) {
                return "replays";
            }
            if (screen instanceof ExportScreen) {
                return "export";
            }
            if (screen instanceof JRConfigScreen) {
                return "settings";
            }
            return "other";
        }
    }
}
