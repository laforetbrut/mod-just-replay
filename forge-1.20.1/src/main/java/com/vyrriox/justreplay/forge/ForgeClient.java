package com.vyrriox.justreplay.forge;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.client.Frames;
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
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    private static final ResourceLocation PLACEMENT_GUI = JustReplay.id("placement_gui");

    /** Layers kept by the clean HUD setting. */
    private static final Set<ResourceLocation> REPLAY_LAYERS = Set.of(
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
        IEventBus modBus = context.getModEventBus();
        listen(modBus, RegisterKeyMappingsEvent.class, event -> ClientHooks.registerKeys(event::register));
        listen(modBus, RegisterShadersEvent.class, ForgeClient::onShaders);

        IEventBus game = MinecraftForge.EVENT_BUS;
        listen(game, TickEvent.ClientTickEvent.Pre.class, event -> ClientHooks.onClientTickPre());
        listen(game, TickEvent.ClientTickEvent.Post.class, event -> onTickPost());
        listen(game, ClientPlayerNetworkEvent.LoggingIn.class, event -> ClientHooks.onLoggingIn(event.getConnection()));
        listen(game, ClientPlayerNetworkEvent.LoggingOut.class, event -> ClientHooks.onLoggingOut(event.getConnection()));
        // Before anything renders (mods read their clock while rendering): integration clocks move to this frame.
        game.addListener(EventPriority.HIGHEST, false, TickEvent.RenderTickEvent.Pre.class,
                event -> ReplayCompat.Events.onRenderFrameFirst(Frames.partialTick()));
        listen(game, TickEvent.RenderTickEvent.Pre.class, event -> onFramePre(Frames.partialTick()));
        listen(game, TickEvent.RenderTickEvent.Post.class, event -> {
            ClientHooks.onFramePost(Frames.partialTick());
            FxEngine.onFrameEnd();
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
     * The event's own partial tick is the realtime delta of the frame, not a partial tick. The model view stack already
     * holds the camera at these stages, so the tools draw from an empty pose stack as they do on the other loaders.
     */
    private static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        float partialTick = Frames.partialTick();
        boolean translucent = stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        Camera camera = event.getCamera();
        OverlayRenderer.onRenderLevel(translucent, stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES, camera, partialTick);
        if (translucent) {
            PoseStack pose = new PoseStack();
            PathRenderer.onRenderLevel(camera, pose, partialTick);
            EntityTool.onRenderLevel(camera, pose);
            OverlayPlacementTool.onRenderLevel(camera);
            PlacementTool.onRenderLevel(camera, pose);
        }
    }

    /** Adds our layers above all others; called once the other mods have added theirs. */
    public static void addTopLayers(ForgeLayeredDraw root) {
        ClientHooks.registerGuiLayers(root::add);
        root.add(PLACEMENT_GUI, (graphics, delta) -> OverlayPlacementTool.onRenderGui(graphics));
    }

    /** Makes a layer of any mod obey the clean HUD setting and the chat setting of the editor. */
    public static LayeredDraw.Layer cleanHud(ResourceLocation name, LayeredDraw.Layer layer) {
        boolean replayLayer = REPLAY_LAYERS.contains(name);
        boolean chatLayer = name.equals(ForgeLayeredDraw.CHAT_OVERLAY);
        return (graphics, delta) -> {
            if (!ClientHooks.hidesGuiLayer(replayLayer, chatLayer)) {
                layer.render(graphics, delta);
            }
        };
    }
}
