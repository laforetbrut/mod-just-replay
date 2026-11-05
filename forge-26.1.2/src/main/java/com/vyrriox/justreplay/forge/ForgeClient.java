package com.vyrriox.justreplay.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.client.hook.ClientHooks;
import com.vyrriox.justreplay.compat.ReplayCompat;
import com.vyrriox.justreplay.editor.PathRenderer;
import com.vyrriox.justreplay.editor.SkinLibrary;
import com.vyrriox.justreplay.editor.audio.AudioPlayer;
import com.vyrriox.justreplay.editor.fx.FxEngine;
import com.vyrriox.justreplay.editor.model.ModelRenderer;
import com.vyrriox.justreplay.editor.overlay.OverlayRenderer;
import com.vyrriox.justreplay.gui.EntityTool;
import com.vyrriox.justreplay.gui.JRConfigScreen;
import com.vyrriox.justreplay.gui.OverlayPlacementTool;
import com.vyrriox.justreplay.gui.PlacementTool;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayer;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Client side set-up on Forge, forwarding its events to the loader-free classes in the order of the other loaders. This
 * class is only loaded on the physical client.
 *
 * @author vyrriox
 */
public final class ForgeClient {
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

    private static final Identifier PLACEMENT_GUI = JustReplay.id("placement_gui");

    /** Layers kept by the clean HUD setting. */
    private static final Set<Identifier> REPLAY_LAYERS = Set.of(
            ForgeLayeredDraw.CHAT_OVERLAY,
            ForgeLayeredDraw.TITLE_OVERLAY,
            ForgeLayeredDraw.SUBTITLE_OVERLAY,
            ForgeLayeredDraw.BOSS_OVERLAY,
            ForgeLayeredDraw.SCOREBOARD,
            ForgeLayeredDraw.TAB_LIST,
            ForgeLayeredDraw.HOTBAR_MESSAGE,
            ForgeLayeredDraw.DEBUG_OVERLAY,
            JustReplay.id("hud"),
            JustReplay.id("placement"),
            JustReplay.id("entity_tool"),
            PLACEMENT_GUI);

    /** The camera roll the mixin put on the rotation of this frame, as the argument of {@code Camera.setRotation}. */
    private static float cameraRoll;

    private ForgeClient() {
    }

    static void init(FMLJavaModLoadingContext context) {
        context.getContainer().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(JRConfigScreen::new));
        for (Class<?> type : PRELOADED) {
            try {
                Class.forName(type.getName(), true, type.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        RegisterKeyMappingsEvent.BUS.addListener(event -> ClientHooks.registerKeys(event::register));

        TickEvent.ClientTickEvent.Pre.BUS.addListener(event -> ClientHooks.onClientTickPre());
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> onTickPost());
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(event -> ClientHooks.onLoggingIn(event.getConnection()));
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> ClientHooks.onLoggingOut(event.getConnection()));
        // Before anything renders (mods read their clock while rendering): integration clocks move to this frame.
        TickEvent.RenderTickEvent.Pre.BUS.addListener(Priority.HIGHEST,
                event -> ReplayCompat.Events.onRenderFrameFirst(event.timer().getGameTimeDeltaPartialTick(false)));
        TickEvent.RenderTickEvent.Pre.BUS.addListener(event -> onFramePre(event.timer().getGameTimeDeltaPartialTick(false)));
        TickEvent.RenderTickEvent.Post.BUS.addListener(event -> {
            ClientHooks.onFramePost(event.timer().getGameTimeDeltaPartialTick(false));
            FxEngine.onFrameEnd();
        });
        ComputeFovModifierEvent.BUS.addListener(event -> {
            if (ClientHooks.locksFovModifier()) {
                event.setNewFovModifier(1.0F);
            }
        });
        // Forge fires this event after the camera state was extracted, so it cannot roll the view: the mixin does that.
        // The rotation Forge sets from it stays in use for the rest of the frame, so it has to keep the same roll.
        ViewportEvent.ComputeCameraAngles.BUS.addListener(event -> {
            if (cameraRoll != 0.0F) {
                event.setRoll(cameraRoll);
            }
        });
        ViewportEvent.ComputeFov.BUS.addListener(event -> {
            Double fov = ClientHooks.cameraFov(event.usedConfiguredFov());
            if (fov != null) {
                event.setFOV(fov.floatValue());
            }
        });
        Predicate<RenderHighlightEvent.Block> highlight = event -> ClientHooks.cancelsBlockHighlight();
        RenderHighlightEvent.Block.BUS.addListener(highlight);
        ScreenEvent.Init.Post.BUS.addListener(event -> ClientHooks.onScreenInit(event.getScreen(), event::addListener));
    }

    private static void onTickPost() {
        ClientHooks.onClientTickPost();
        SkinLibrary.onTick();
        AudioPlayer.onTick();
        ModelRenderer.onTick();
        OverlayRenderer.onTick();
    }

    private static void onFramePre(float partialTick) {
        ClientHooks.onFramePre(partialTick);
        ReplayCompat.Events.onRenderFrame(partialTick);
        OverlayRenderer.onRenderFrame();
        EntityTool.onRenderFrame();
        OverlayPlacementTool.onRenderFrame();
        PlacementTool.onRenderFrame();
    }

    public static void setCameraRoll(float roll) {
        cameraRoll = roll;
    }

    /**
     * The level stages run inside the main frame graph pass with the view matrix on the model view stack, and vanilla
     * draws its gizmos through the same immediate buffers at this point.
     */
    public static void onAfterTranslucent() {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack pose = new PoseStack();
        OverlayRenderer.onRenderLevel(true, false, camera, partialTick);
        PathRenderer.onRenderLevel(camera, pose, partialTick);
        EntityTool.onRenderLevel(camera, pose);
        OverlayPlacementTool.onRenderLevel(camera);
        PlacementTool.onRenderLevel(camera, pose);
    }

    /** Fallback of the overlays when a chunk renderer skipped the stage above. */
    public static void onAfterTranslucentParticles() {
        Minecraft mc = Minecraft.getInstance();
        OverlayRenderer.onRenderLevel(false, true, mc.gameRenderer.getMainCamera(), mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
    }

    /** Adds our layers above all others; called once the other mods have added theirs. */
    public static void addTopLayers(ForgeLayeredDraw root) {
        ClientHooks.registerGuiLayers((id, layer) -> root.add(id, layer::render));
        root.add(PLACEMENT_GUI, (graphics, delta) -> OverlayPlacementTool.onRenderGui(graphics));
    }

    /** Makes a layer of any mod obey the clean HUD setting and the chat setting of the editor. */
    public static ForgeLayer cleanHud(Identifier name, ForgeLayer layer) {
        boolean replayLayer = REPLAY_LAYERS.contains(name);
        boolean chatLayer = name.equals(ForgeLayeredDraw.CHAT_OVERLAY);
        return (graphics, delta) -> {
            if (!ClientHooks.hidesGuiLayer(replayLayer, chatLayer)) {
                layer.extract(graphics, delta);
            }
        };
    }
}
