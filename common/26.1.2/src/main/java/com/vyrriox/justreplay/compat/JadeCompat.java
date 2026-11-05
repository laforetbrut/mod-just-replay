package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.gui.EditorScreen;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Jade in replays.
 * <ul>
 *     <li>Jade draws its tooltip from a render event, so the clean HUD setting does not hide it. The optional mixin
 *     stops its client tick while the clean HUD is on or the editor is open, which also stops the ray trace and the
 *     block and entity requests.</li>
 *     <li>The recorded "show overlay" packet would switch the viewer's own Jade off and save that to disk: dropped.
 *     So are its recorded data and handshake, which belong to the live connection.</li>
 *     <li>Jade's requests have no server to answer in a replay: blocked.</li>
 * </ul>
 * Packets are matched by class name: nothing here loads a Jade class when Jade is absent.
 *
 * @author vyrriox
 */
public final class JadeCompat {
    private static final String PACKAGE = "snownee.jade.network.";
    private static final Set<String> RECORDED_DROPPED = Set.of(PACKAGE + "ShowOverlayPacket", PACKAGE + "ReceiveDataPacket",
            PACKAGE + "ServerHandshakePacket");
    private static final Set<String> REQUESTS = Set.of(PACKAGE + "RequestBlockPacket", PACKAGE + "RequestEntityPacket");
    private static Field rootElement;
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
            if (rootElement == null) {
                rootElement = tickHandler.getClass().getField("rootElement");
            }
            rootElement.set(tickHandler, null);
        } catch (Throwable t) {
            fieldFailed = true;
            ReplayCompat.warnOnce("jade.root", "Cannot clear the Jade tooltip, its classes changed", t);
        }
    }

    /** The tooltip Jade holds now, or null when none (or Jade cannot be read). */
    public static Object currentTooltip() {
        try {
            Class<?> client = Class.forName("snownee.jade.JadeClient", false, JadeCompat.class.getClassLoader());
            Object handler = client.getMethod("tickHandler").invoke(null);
            return handler.getClass().getField("rootElement").get(handler);
        } catch (Throwable t) {
            return null;
        }
    }

    /** See {@link ReplayCompat#filterPlayback}: Jade's live connection payloads are dropped. */
    static Packet<?> filterPlayback(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket custom
                && RECORDED_DROPPED.contains(custom.payload().getClass().getName())) {
            return null;
        }
        return packet;
    }

    /** Jade's requests to the replay host have no server to go to. */
    static boolean blockServerbound(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom && REQUESTS.contains(custom.payload().getClass().getName());
    }
}
