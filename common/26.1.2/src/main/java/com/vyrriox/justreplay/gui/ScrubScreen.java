package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.client.JRKeys;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The mouse freed over a playing replay, without the editor: a click or a drag on the timeline of the HUD moves the
 * play head, the playback keys keep working, and the same key (or Escape) gives the mouse back to the camera.
 *
 * @author vyrriox
 */
public class ScrubScreen extends GlassScreen {
    /** Extra height around the thin timeline that still catches the mouse. */
    private static final int GRAB = 6;
    private final ReplaySession session;
    private boolean dragging;
    private int pending = -1;

    public ScrubScreen(ReplaySession session) {
        super(Component.translatable("justreplay.scrub.title"));
        this.session = session;
        this.blurBackground = false;
    }

    private Playback playback() {
        return session.playback();
    }

    /** Whether the point is on the HUD's timeline (drawn under this screen). */
    private static boolean onBar(double mx, double my) {
        int w = ReplayHud.barWidth();
        return w > 0 && mx >= ReplayHud.barX() - 2 && mx <= ReplayHud.barX() + w + 2
                && my >= ReplayHud.barY() - GRAB && my <= ReplayHud.barY() + ReplayHud.barHeight() + GRAB;
    }

    private int tickAt(double mx) {
        int duration = playback().duration();
        int tick = Timeline.tickAt(mx, ReplayHud.barX(), ReplayHud.barWidth(), duration);
        return Math.max(0, Math.min(duration, tick));
    }

    /** Moves the play head to the tick under {@code mx} of the timeline; used by the self test too. */
    public void seekAt(double mx) {
        playback().seek(tickAt(mx));
    }

    @Override
    protected void renderOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int w = ReplayHud.barWidth();
        if (w <= 0) {
            return;
        }
        int duration = Math.max(1, playback().duration());
        if (dragging && pending >= 0) {
            int px = Timeline.xOf(pending, ReplayHud.barX(), w, duration);
            g.fill(px, ReplayHud.barY() - 3, px + 1, ReplayHud.barY() + ReplayHud.barHeight() + 3, 0xFFFFFFFF);
        }
        if (dragging || onBar(mouseX, mouseY)) {
            int tick = dragging && pending >= 0 ? pending : tickAt(mouseX);
            Paint.tooltip(g, font, Component.literal(ReplayHud.time(tick, playback().duration())), mouseX, mouseY);
        } else {
            Component tip = Component.translatable("justreplay.scrub.hint", JRKeys.SCRUB.getTranslatedKeyMessage());
            int tw = font.width(tip) + 12;
            int tx = (width - tw) / 2;
            int ty = ReplayHud.barY() - 36;
            Paint.glass(g, tx, ty, tw, 13, 6.5F);
            g.text(font, tip, tx + 6, ty + 3, Theme.TEXT_DIM, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubled) {
        double mx = ev.x();
        double my = ev.y();
        if (ev.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && onBar(mx, my)) {
            dragging = true;
            pending = tickAt(mx);
            return true;
        }
        return super.mouseClicked(ev, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) {
        if (dragging) {
            pending = tickAt(ev.x());
            return true;
        }
        return super.mouseDragged(ev, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ev) {
        if (dragging) {
            double mx = ev.x();
            dragging = false;
            // One seek on release: a jump back rebuilds the world, which a seek per mouse move would repeat.
            playback().seek(tickAt(mx));
            pending = -1;
            return true;
        }
        return super.mouseReleased(ev);
    }

    @Override
    public boolean keyPressed(KeyEvent ev) {
        Playback playback = playback();
        int step = JRConfig.SEEK_STEP_SECONDS.get() * 20;
        if (JRKeys.SCRUB.matches(ev)) {
            onClose();
        } else if (JRKeys.SEEK_BACK.matches(ev)) {
            playback.seek(playback.cursor() - step);
        } else if (JRKeys.SEEK_FORWARD.matches(ev)) {
            playback.seek(playback.cursor() + step);
        } else if (JRKeys.PLAY_PAUSE.matches(ev)) {
            if (playback.cursor() >= playback.duration()) {
                playback.seek(0);
            }
            playback.setPaused(!playback.isPaused());
        } else if (JRKeys.SPEED_UP.matches(ev)) {
            playback.changeSpeed(1);
        } else if (JRKeys.SPEED_DOWN.matches(ev)) {
            playback.changeSpeed(-1);
        } else if (JRKeys.EDITOR.matches(ev)) {
            minecraft.setScreen(new EditorScreen(session));
        } else {
            return super.keyPressed(ev);
        }
        return true;
    }

    @Override
    public void tick() {
        if (!ReplaySession.isRunning() || ReplaySession.current() != session) {
            onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
