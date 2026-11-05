package com.vyrriox.justreplay.fabric;

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
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric entry point; the mod only exists on the client.
 *
 * @author vyrriox
 */
public final class JustReplayFabric implements ClientModInitializer {
    /** Classes whose static state is expected to be set up with the mod, as on the other loaders. */
    private static final Class<?>[] PRELOADED = {
            ReplayCompat.Events.class,
            PathRenderer.class,
            SkinLibrary.class,
            AudioPlayer.class,
            FxEngine.class,
            ModelRenderer.class,
            OverlayRenderer.class,
            EntityTool.class,
            OverlayPlacementTool.class,
            PlacementTool.class,
    };

    @Override
    public void onInitializeClient() {
        for (Class<?> type : PRELOADED) {
            try {
                Class.forName(type.getName(), true, type.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        FabricClientEvents.register();
    }
}
