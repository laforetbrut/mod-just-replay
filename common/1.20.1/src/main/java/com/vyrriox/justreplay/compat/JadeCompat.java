package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.gui.EditorScreen;
import com.vyrriox.justreplay.record.Payloads;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;

/**
 * Jade in replays.
 * <ul>
 *     <li>Jade draws its tooltip from a render event, so the clean HUD setting does not hide it. The optional mixin
 *     stops its client tick while the clean HUD is on or the editor is open, which also stops the ray trace and the
 *     block and entity requests.</li>
 *     <li>The recorded messages from the server (the "show overlay" switch, which would switch the viewer's own Jade
 *     off and save that to disk, the recorded data and the ping answers) belong to the live connection: dropped.</li>
 *     <li>Jade's requests have no server to answer in a replay: blocked.</li>
 * </ul>
 * On 1.20.1 Jade speaks on one Forge channel, and every message on it is one of the above, so the channel decides.
 * Nothing here loads a Jade class when Jade is absent.
 *
 * @author vyrriox
 */
public final class JadeCompat {
    /** The Forge channel of Jade's network messages (1.20.1). */
    private static final ResourceLocation CHANNEL = new ResourceLocation("jade", "networking");
    private static Field tooltipRenderer;
    private static boolean fieldFailed;

    private JadeCompat() {
    }

    /** True while the replay hides the tooltip: the clean HUD is on, or the editor is open. */
    public static boolean tooltipHidden() {
        return ReplaySession.isRunning() && (JRConfig.CLEAN_HUD.get() || Minecraft.getInstance().screen instanceof EditorScreen);
    }

    /** Called by the tick mixin: forgets the tooltip Jade built, so nothing is drawn. */
    public static void clearTooltip(Object tickHandler) {
        if (fieldFailed) {
            return;
        }
        try {
            if (tooltipRenderer == null) {
                tooltipRenderer = tickHandler.getClass().getField("tooltipRenderer");
            }
            tooltipRenderer.set(tickHandler, null);
        } catch (Throwable t) {
            fieldFailed = true;
            ReplayCompat.warnOnce("jade.root", "Cannot clear the Jade tooltip, its classes changed", t);
        }
    }

    /** The tooltip Jade holds now, or null when none (or Jade cannot be read). */
    public static Object currentTooltip() {
        try {
            Class<?> type = Class.forName("snownee.jade.overlay.WailaTickHandler", false, JadeCompat.class.getClassLoader());
            Object handler = type.getMethod("instance").invoke(null);
            return type.getField("tooltipRenderer").get(handler);
        } catch (Throwable t) {
            return null;
        }
    }

    /** See {@link ReplayCompat#filterPlayback}: Jade's messages from the live server are dropped. */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (Payloads.isClientbound(packet) && CHANNEL.equals(Payloads.idOf(packet))) {
            return null;
        }
        return packet;
    }

    /** Jade's requests to the replay host have no server to go to. */
    static boolean blockServerbound(Packet<?> packet) {
        return Payloads.isServerbound(packet) && CHANNEL.equals(Payloads.idOf(packet));
    }
}
