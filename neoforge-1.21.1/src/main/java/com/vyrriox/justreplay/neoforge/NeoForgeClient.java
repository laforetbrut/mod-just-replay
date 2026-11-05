package com.vyrriox.justreplay.neoforge;

import com.vyrriox.justreplay.client.JustReplayClient;
import com.vyrriox.justreplay.compat.ReplayCompat;
import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.SkinLibrary;
import com.vyrriox.justreplay.editor.audio.AudioPlayer;
import com.vyrriox.justreplay.editor.fx.FxEngine;
import com.vyrriox.justreplay.editor.model.ModelRenderer;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import com.vyrriox.justreplay.gui.EntityTool;
import com.vyrriox.justreplay.gui.OverlayPlacementTool;
import com.vyrriox.justreplay.gui.PlacementTool;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;

/**
 * Client side set-up on NeoForge; this class is only loaded on the physical client.
 *
 * @author vyrriox
 */
final class NeoForgeClient {
    private static final Class<?>[] SUBSCRIBERS = {
            JustReplayClient.GameEvents.class,
            JustReplayClient.ModEvents.class,
            ReplayCompat.Events.class,
            PathRenderer.class,
            SkinLibrary.class,
            AudioPlayer.class,
            FxEngine.GameEvents.class,
            FxEngine.ModEvents.class,
            ModelRenderer.class,
            OverlayRenderer.class,
            EntityTool.class,
            OverlayPlacementTool.class,
            PlacementTool.class,
    };

    private NeoForgeClient() {
    }

    static void init(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new com.vyrriox.justreplay.gui.JRConfigScreen(parent));
        for (Class<?> subscriber : SUBSCRIBERS) {
            subscribe(subscriber, modBus);
        }
    }

    /** Sends each listener to the bus its event belongs to and initializes the class, as an automatic subscriber does. */
    private static void subscribe(Class<?> type, IEventBus modBus) {
        try {
            Class.forName(type.getName(), true, type.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        boolean mod = false;
        boolean game = false;
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(SubscribeEvent.class)) {
                boolean modEvent = IModBusEvent.class.isAssignableFrom(method.getParameterTypes()[0]);
                mod |= modEvent;
                game |= !modEvent;
            }
        }
        if (!mod) {
            NeoForge.EVENT_BUS.register(type);
        } else if (!game) {
            modBus.register(type);
        } else {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(SubscribeEvent.class)) {
                    boolean modEvent = IModBusEvent.class.isAssignableFrom(method.getParameterTypes()[0]);
                    (modEvent ? modBus : NeoForge.EVENT_BUS).register(method);
                }
            }
        }
    }
}
