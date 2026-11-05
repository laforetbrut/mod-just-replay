package com.vyrriox.justreplay.forge;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.client.Frames;
import com.vyrriox.justreplay.client.hook.ClientHooks;
import com.vyrriox.justreplay.client.hook.HudLayer;
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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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

    /** Same step as Minecraft's later layered HUD: a layer draws above whatever came before it in depth. */
    private static final float LAYER_DEPTH = 200.0F;

    /** Overlays kept by the clean HUD setting. */
    private static final Set<ResourceLocation> REPLAY_OVERLAYS = Set.of(
            VanillaGuiOverlay.CHAT_PANEL.id(),
            VanillaGuiOverlay.TITLE_TEXT.id(),
            VanillaGuiOverlay.SUBTITLES.id(),
            VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id(),
            VanillaGuiOverlay.SCOREBOARD.id(),
            VanillaGuiOverlay.PLAYER_LIST.id(),
            VanillaGuiOverlay.RECORD_OVERLAY.id(),
            VanillaGuiOverlay.DEBUG_TEXT.id());

    private static final List<HudLayer> TOP_LAYERS = new ArrayList<>();

    private ForgeClient() {
    }

    static void init(FMLJavaModLoadingContext context) {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new JRConfigScreen(parent)));
        for (Class<?> type : PRELOADED) {
            try {
                Class.forName(type.getName(), true, type.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        IEventBus modBus = context.getModEventBus();
        listen(modBus, RegisterKeyMappingsEvent.class, event -> ClientHooks.registerKeys(event::register));
        listen(modBus, RegisterShadersEvent.class, ForgeClient::onShaders);

        IEventBus game = MinecraftForge.EVENT_BUS;
        listen(game, TickEvent.ClientTickEvent.class, event -> {
            if (event.phase == TickEvent.Phase.START) {
                ClientHooks.onClientTickPre();
            } else {
                onTickPost();
            }
        });
        listen(game, ClientPlayerNetworkEvent.LoggingIn.class, event -> ClientHooks.onLoggingIn(event.getConnection()));
        listen(game, ClientPlayerNetworkEvent.LoggingOut.class, event -> ClientHooks.onLoggingOut(event.getConnection()));
        // Before anything renders (mods read their clock while rendering): integration clocks move to this frame.
        game.addListener(EventPriority.HIGHEST, false, TickEvent.RenderTickEvent.class, event -> {
            if (event.phase == TickEvent.Phase.START) {
                ReplayCompat.Events.onRenderFrameFirst(Frames.partialTick());
            }
        });
        listen(game, TickEvent.RenderTickEvent.class, event -> {
            if (event.phase == TickEvent.Phase.START) {
                onFramePre(Frames.partialTick());
            } else {
                ClientHooks.onFramePost(Frames.partialTick());
                FxEngine.onFrameEnd();
            }
        });
        listen(game, ComputeFovModifierEvent.class, event -> {
            if (ClientHooks.locksFovModifier()) {
                event.setNewFovModifier(1.0F);
            }
        });
        listen(game, ViewportEvent.ComputeCameraAngles.class, event -> {
            Float roll = ClientHooks.cameraRoll();
            if (roll != null) {
                event.setRoll(roll);
            }
        });
        listen(game, ViewportEvent.ComputeFov.class, event -> {
            Double fov = ClientHooks.cameraFov(event.usedConfiguredFov());
            if (fov != null) {
                event.setFOV(fov);
            }
        });
        listen(game, RenderHighlightEvent.Block.class, event -> {
            if (ClientHooks.cancelsBlockHighlight()) {
                event.setCanceled(true);
            }
        });
        listen(game, ScreenEvent.Init.Post.class, event -> ClientHooks.onScreenInit(event.getScreen(), event::addListener));
        listen(game, RenderLevelStageEvent.class, ForgeClient::onRenderLevel);
        listen(game, RenderGuiOverlayEvent.Pre.class, ForgeClient::onGuiOverlay);
        // After every overlay of every mod, so our layers stay above all others whatever the load order.
        game.addListener(EventPriority.LOWEST, false, RenderGuiEvent.Post.class, ForgeClient::onGuiEnd);
    }

    /** Registers a listener with its event class given explicitly, so no generic type lookup is needed. */
    private static <T extends Event> void listen(IEventBus bus, Class<T> type, Consumer<T> listener) {
        bus.addListener(EventPriority.NORMAL, false, type, listener);
    }

    private static void onShaders(RegisterShadersEvent event) {
        try {
            FxEngine.registerShaders((name, onLoaded) -> event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), name, DefaultVertexFormat.POSITION), onLoaded));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /**
     * The event's own partial tick is the realtime delta of the frame, not a partial tick. On this Minecraft version the
     * model view stack does not hold the camera at these stages, the event's pose does, so every tool draws from it.
     */
    private static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        boolean translucent = stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        boolean particles = stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES;
        if (!translucent && !particles) {
            return;
        }
        float partialTick = Frames.partialTick();
        Camera camera = event.getCamera();
        PoseStack view = event.getPoseStack();
        OverlayRenderer.onRenderLevel(translucent, particles, camera, view, partialTick);
        if (translucent) {
            PathRenderer.onRenderLevel(camera, view, partialTick);
            EntityTool.onRenderLevel(camera, view);
            OverlayPlacementTool.onRenderLevel(camera, view);
            PlacementTool.onRenderLevel(camera, view);
        }
    }

    /** Makes an overlay of any mod obey the clean HUD setting and the chat setting of the editor. */
    private static void onGuiOverlay(RenderGuiOverlayEvent.Pre event) {
        ResourceLocation name = event.getOverlay().id();
        if (ClientHooks.hidesGuiLayer(REPLAY_OVERLAYS.contains(name), name.equals(VanillaGuiOverlay.CHAT_PANEL.id()))) {
            event.setCanceled(true);
        }
    }

    /** Our layers are not overlays, so the clean HUD check above never hides them. */
    private static void onGuiEnd(RenderGuiEvent.Post event) {
        if (TOP_LAYERS.isEmpty()) {
            ClientHooks.registerGuiLayers((name, layer) -> TOP_LAYERS.add(layer));
            TOP_LAYERS.add((graphics, partialTick) -> OverlayPlacementTool.onRenderGui(graphics));
        }
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.pose().pushPose();
        for (HudLayer layer : TOP_LAYERS) {
            graphics.pose().translate(0.0F, 0.0F, LAYER_DEPTH);
            layer.render(graphics, event.getPartialTick());
        }
        graphics.pose().popPose();
    }
}
