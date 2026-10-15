package com.vyrriox.justreplay.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.client.JRKeys;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.editor.ActionType;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.model.ModelLibrary;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.Dropdown;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.ScrollArea;
import com.vyrriox.justreplay.gui.ui.Segmented;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The replay editor: a top bar, a collapsible sidebar with tabs (cameras, actors, scene, audio, export) and a bottom
 * dock with the transport controls and the multi-lane timeline. The world keeps running and stays visible behind
 * it; the screen does not pause the game.
 *
 * @author vyrriox
 */
public class EditorScreen extends GlassScreen {
    /** Drag handle for the end of the cut track. */
    private static final Object CUT_END = new Object();
    private static final int TOP_H = 18;
    private static final int TAB_CAMERAS = 0;
    private static final int TAB_ACTORS = 1;
    private static final int TAB_SCENE = 2;
    private static final int TAB_EXPORT = 3;
    private static final int TAB_AUDIO = 4;
    private static final int TAB_EFFECTS = 5;
    private static final String[] TAB_KEYS = {"justreplay.editor.tab.cameras", "justreplay.editor.tab.actors",
            "justreplay.editor.tab.scene", "justreplay.editor.tab.export", "justreplay.editor.tab.audio", "justreplay.editor.tab.effects"};
    private static final Icon[] TAB_ICONS = {Icon.CAMERA, Icon.PERSON, Icon.FLAG, Icon.FILM, Icon.SPEAKER, Icon.BOLT};
    /** Editor state kept across rebuilds and reopenings. */
    private static int tab = TAB_CAMERAS;
    private static int previousTab = TAB_CAMERAS;
    private static boolean sidebarOpen = true;
    private static final float[] TAB_SCROLL = new float[6];

    private final ReplaySession session;
    private final CameraPanel cameras;
    private ReplayProject.ActorData selectedActor;
    private ReplayProject.ActorKeyframe selectedActorKey;
    private ReplayProject.ActorAction selectedAction;
    private ActionType actionType = ActionType.WAVE;
    private boolean revealSelection;
    private int selectedRowTop = -1;

    private final EditorTimeline timeline = new EditorTimeline();
    // Audio editing: the lanes under the timeline markers and the sidebar tab.
    private final AudioLanes audioLanes;
    private final AudioPanel audioPanel;
    // Video effects: the filter stack tab.
    private final EffectsPanel effectsPanel;
    private boolean scrubbing;
    private int ghostTick = -1;
    private Object dragging;
    private boolean dragMoved;
    private double pressX;
    private double grabOffset;

    private final Anim.Value sidebar = new Anim.Value(sidebarOpen ? 1.0F : 0.0F, Theme.NORMAL);
    private long tabChangedAt;
    private int sideW;
    private int sideTop;
    private int sideBottom;
    private int dockTop;
    private int nameRight;
    private int timeX;
    private Segmented<Integer> tabs;
    private ScrollArea side;
    private Dropdown<Float> speed;

    private boolean helpOpen;
    private long helpAt;
    private List<String[]> help = List.of();

    private long savedMtime = Long.MIN_VALUE;
    private long savedFlashAt;
    private int saveCheck;
    private String savedTip = "";
    private int shownCursor = -1;
    private String timeText = "";

    public EditorScreen(ReplaySession session) {
        super(Component.translatable("justreplay.editor.title"));
        this.session = session;
        this.cameras = new CameraPanel(this, session);
        this.audioLanes = new AudioLanes(this, session);
        this.audioPanel = new AudioPanel(this, session, audioLanes);
        this.timeline.audio = audioLanes;
        this.effectsPanel = new EffectsPanel(this, session);
        this.blurBackground = false;
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private int dockHeight() {
        return 4 + Theme.ROW + 6 + EditorTimeline.HEIGHT + audioLanes.height() + 4;
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        sideW = Mth.clamp(width * 40 / 100, 160, 220);
        dockTop = height - 4 - dockHeight();
        sideTop = 4 + TOP_H + 4;
        sideBottom = dockTop - 4;
        initTopBar();
        initTransport();
        timeline.layout(8 + EditorTimeline.LABELS + 2, dockTop + 4 + Theme.ROW + 7, width - 16 - EditorTimeline.LABELS - 2);
        initSidebar();
        if (savedMtime == Long.MIN_VALUE) {
            savedMtime = projectTime();
        }
    }

    private void initTopBar() {
        int y = 4 + (TOP_H - 14) / 2;
        addRenderableWidget(new GlassButton(8, y, 16, 14, Component.empty(), b -> toggleSidebar())
                .icon(Icon.SIDEBAR).style(GlassButton.Style.GHOST).selected(() -> sidebarOpen).tip(tc("justreplay.editor.sidebar.tip")));
        int rx = width - 8 - 16;
        addRenderableWidget(new GlassButton(rx, y, 16, 14, Component.empty(), b -> onClose())
                .icon(Icon.CLOSE).style(GlassButton.Style.GHOST).tip(tc("justreplay.editor.close.tip")));
        int exportW = font.width(tc("justreplay.editor.export")) + 24;
        rx -= 4 + exportW;
        addRenderableWidget(new GlassButton(rx, y, exportW, 14, tc("justreplay.editor.export"), b -> openExport())
                .icon(Icon.EXPORT).style(GlassButton.Style.PRIMARY).tip(tc("justreplay.editor.export.tip")));
        rx -= 3 + 16;
        addRenderableWidget(new GlassButton(rx, y, 16, 14, Component.empty(), b -> {
            session.saveProject();
            savedFlashAt = Anim.now();
        }).icon(Icon.SAVE).style(GlassButton.Style.GHOST).tip(tc("justreplay.editor.save.tip")));
        rx -= 2 + 16;
        addRenderableWidget(new GlassButton(rx, y, 16, 14, Component.empty(), b -> openSettings())
                .icon(Icon.GEAR).style(GlassButton.Style.GHOST).tip(tc("justreplay.editor.settings.tip")));
        rx -= 2 + 16;
        addRenderableWidget(new GlassButton(rx, y, 16, 14, Component.empty(), b -> toggleHelp())
                .icon(Icon.HELP).style(GlassButton.Style.GHOST).selected(() -> helpOpen).tip(tc("justreplay.editor.help.tip")));
        nameRight = rx - 6;
    }

    private void initTransport() {
        Playback playback = session.playback();
        int y = dockTop + 4;
        int x = 8;
        int h = Theme.ROW + 1;
        Component step = Component.literal(Integer.toString(stepSeconds()));
        x = transport(x, y, 16, h, Icon.SKIP_START, tc("justreplay.editor.start.tip"), () -> playback.seek(0));
        x = transport(x, y, 16, h, Icon.REWIND, Component.translatable("justreplay.editor.back.tip", step), () -> seekRelative(-stepTicks()));
        addRenderableWidget(new GlassButton(x, y, 24, h, Component.empty(), b -> togglePlay())
                .icon(() -> playback.isPaused() ? Icon.PLAY : Icon.PAUSE).style(GlassButton.Style.PRIMARY).tip(tc("justreplay.editor.play.tip")));
        x += 26;
        x = transport(x, y, 16, h, Icon.FORWARD, Component.translatable("justreplay.editor.forward.tip", step), () -> seekRelative(stepTicks()));
        x = transport(x, y, 16, h, Icon.SKIP_END, tc("justreplay.editor.end.tip"), () -> playback.seek(playback.duration()));
        timeX = x + 6;
        x = timeX + font.width("00:00.0 / 00:00.0") + 6;
        List<Float> speeds = new ArrayList<>();
        for (float s : Playback.SPEEDS) {
            speeds.add(s);
        }
        speed = addRenderableWidget(new Dropdown<>(this, Component.empty(), speeds, playback.speed(),
                v -> Component.literal("x" + ReplayHud.trim(v)), this::setSpeed));
        speed.setRectangle(36, h, x, y);
        speed.tip(tc("justreplay.editor.speed.tip"));
        x += 36 + 8;
        x = transport(x, y, 16, h, Icon.KEY, tc("justreplay.editor.key.tip"), cameras::addKeyframe);
        x = transport(x, y, 16, h, Icon.FLAG, tc("justreplay.editor.marker.tip"), this::addMarker);
        transport(x, y, 16, h, Icon.CUT, tc("justreplay.editor.cut.add.tip"), cameras::cutHere);
        int rx = width - 8 - 16;
        transport(rx, y, 16, h, Icon.PLUS, tc("justreplay.editor.zoom_in.tip"), () -> timeline.zoom(0.5, timeline.x + timeline.w / 2.0));
        rx -= 18;
        transport(rx, y, 16, h, Icon.FIT, tc("justreplay.editor.zoom_fit.tip"), timeline::fit);
        rx -= 18;
        transport(rx, y, 16, h, Icon.MINUS, tc("justreplay.editor.zoom_out.tip"), () -> timeline.zoom(2.0, timeline.x + timeline.w / 2.0));
    }

    private int transport(int x, int y, int w, int h, Icon icon, Component tip, Runnable action) {
        addRenderableWidget(new GlassButton(x, y, w, h, Component.empty(), b -> action.run()).icon(icon).tip(tip));
        return x + w + 2;
    }

    private void initSidebar() {
        int contentW = sideW - 10;
        tabs = addRenderableWidget(new Segmented<>(List.of(TAB_CAMERAS, TAB_ACTORS, TAB_SCENE, TAB_AUDIO, TAB_EFFECTS, TAB_EXPORT), tab,
                i -> tc(TAB_KEYS[i]), i -> TAB_ICONS[i], this::selectTab));
        tabs.slideFrom(previousTab);
        previousTab = tab;
        tabs.setWidth(contentW);
        side = new ScrollArea(this, 0, sideTop + 23, contentW + 2, sideBottom - sideTop - 27);
        Form form = new Form(side, contentW - 5);
        switch (tab) {
            case TAB_ACTORS -> buildActors(form);
            case TAB_SCENE -> buildScene(form);
            case TAB_EXPORT -> buildExport(form);
            case TAB_AUDIO -> audioPanel.build(form);
            case TAB_EFFECTS -> effectsPanel.build(form);
            default -> cameras.build(form);
        }
        form.end();
        side.setScroll(TAB_SCROLL[tab], true);
        if (revealSelection && tab == TAB_ACTORS && selectedRowTop >= 0) {
            side.reveal(selectedRowTop, selectedRowTop + Theme.ROW);
        }
        revealSelection = false;
        placeSidebar();
    }

    private void selectTab(int index) {
        previousTab = tab;
        tab = index;
        tabChangedAt = Anim.now();
        rebuildWidgets();
    }

    private void toggleSidebar() {
        sidebarOpen = !sidebarOpen;
    }

    /** Moves the sidebar for its slide animation. */
    private void placeSidebar() {
        float open = sidebar.to(sidebarOpen ? 1.0F : 0.0F);
        int sx = Math.round(4 - (1.0F - open) * (sideW + 8));
        float shift = (1.0F - Anim.easeOutCubic(Anim.progress(tabChangedAt, 240))) * 10.0F;
        tabs.setX(sx + 5);
        tabs.setY(sideTop + 5);
        tabs.visible = open > 0.02F;
        side.x = sx + 5 + Math.round(shift);
        side.y = sideTop + 23;
        TAB_SCROLL[tab] = side.scrollTarget();
    }

    private int sidebarX() {
        return Math.round(4 - (1.0F - sidebar.get()) * (sideW + 8));
    }

    // ---------------------------------------------------------------- tabs

    private void buildActors(Form f) {
        f.header(tc("justreplay.editor.actors"), Icon.PERSON);
        f.caption(tc("justreplay.editor.actor.new"), 0, 1);
        f.next(9);
        f.put(new GlassButton(tc("justreplay.editor.actor.add.player"), b -> addPlayerActor()).icon(Icon.PERSON)
                .tip(tc("justreplay.editor.actor.player.tip")), 0, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.add.entity"), b -> addEntityActor()).icon(Icon.PUZZLE)
                .tip(tc("justreplay.editor.actor.entity.tip")), 1, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.add.model"), b -> addModelActor()).icon(Icon.CUBE)
                .tip(tc("justreplay.editor.actor.model.tip")), 2, 3);
        f.next();
        List<ReplayProject.ActorData> actors = session.project().actors;
        if (actors.isEmpty()) {
            f.paragraph(tc("justreplay.editor.actor.none"), Theme.TEXT_MUTED);
        }
        int revealTop = -1;
        for (ReplayProject.ActorData actor : actors) {
            if (actor == selectedActor) {
                revealTop = f.y();
            }
            ListRow row = new ListRow(Component.literal(actor.name.isBlank() ? actor.type : actor.name), b -> {
                selectedActor = actor;
                selectedActorKey = null;
                selectedAction = null;
                rebuildWidgets();
            });
            row.detail(actorKind(actor)).action(() -> actor.visible ? Icon.EYE : Icon.EYE_OFF, tc("justreplay.editor.actor.visible.tip"), () -> {
                actor.visible = !actor.visible;
                session.actors().refresh(actor);
                session.saveProject();
            }).icon(actor.isPlayer() ? Icon.PERSON : actor.isModel() ? Icon.CUBE : Icon.PUZZLE).selected(() -> actor == selectedActor);
            f.full(row);
        }
        selectedRowTop = revealTop;
        if (selectedActor == null) {
            if (!actors.isEmpty()) {
                f.paragraph(tc("justreplay.editor.actor.select"), Theme.TEXT_MUTED);
            }
            return;
        }
        f.header(Component.literal(selectedActor.name.isBlank() ? selectedActor.type : selectedActor.name), Icon.EDIT);
        f.put(new GlassButton(tc("justreplay.editor.actor.keyframe"), b -> keyframeActor()).icon(Icon.KEY)
                .tip(tc("justreplay.editor.actor.keyframe.tip")), 0, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.edit"), b -> minecraft.setScreen(new ActorScreen(this, session, selectedActor)))
                .icon(Icon.EDIT).tip(tc("justreplay.editor.actor.edit.tip")), 1, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.delete"), b -> confirm(tc("justreplay.editor.actor.delete.confirm"), this::deleteActor))
                .icon(Icon.TRASH).style(GlassButton.Style.DANGER), 2, 3);
        f.next();
        f.text(() -> selectedActorKey == null ? null
                : Component.translatable("justreplay.editor.actor.key_info", Timeline.formatTicks(selectedActorKey.tick)), Timeline.ACTOR_COLOR);
        f.header(tc("justreplay.editor.actor.actions"), Icon.BOLT);
        f.full(new Dropdown<>(this, tc("justreplay.actor.action.type"), List.of(ActionType.values()), actionType,
                t -> Component.translatable(t.translationKey()), v -> actionType = v).icon(Icon.BOLT)
                .tip(tc("justreplay.editor.actor.action.tip")));
        f.put(new GlassButton(tc("justreplay.editor.actor.action.add"), b -> addAction()).icon(Icon.PLUS), 0, 2);
        f.put(new GlassButton(tc("justreplay.editor.actor.action.delete"), b -> deleteAction()).icon(Icon.TRASH)
                .style(GlassButton.Style.DANGER), 1, 2);
        f.next();
        if (selectedActor.actions.isEmpty()) {
            f.paragraph(tc("justreplay.actor.action.none"), Theme.TEXT_MUTED);
        }
        for (ReplayProject.ActorAction action : selectedActor.actions) {
            f.full(new ListRow(Component.literal(actionLabel(action)), b -> {
                selectedAction = action;
                selectedActorKey = null;
                cameras.selectedKey = null;
                session.playback().seek(action.tick);
                rebuildWidgets();
            }).detail(action.actionType().shortLabel).icon(Icon.BOLT).selected(() -> action == selectedAction));
        }
        selectedRowTop = revealTop;
    }

    private static String actorKind(ReplayProject.ActorData actor) {
        if (actor.isPlayer()) {
            return tr("justreplay.editor.actor.kind.player");
        }
        if (actor.isModel()) {
            return tr("justreplay.editor.actor.kind.model");
        }
        return actor.type.startsWith("minecraft:") ? actor.type.substring(10) : actor.type;
    }

    private void buildScene(Form f) {
        f.header(tc("justreplay.editor.markers"), Icon.FLAG);
        f.full(new GlassButton(tc("justreplay.editor.marker.add"), b -> addMarker()).icon(Icon.PLUS).tip(tc("justreplay.editor.marker.tip")));
        List<ReplayProject.Marker> markers = session.project().markers;
        if (markers.isEmpty()) {
            f.paragraph(Component.translatable("justreplay.editor.marker.none", JRKeys.MARKER.getTranslatedKeyMessage()), Theme.TEXT_MUTED);
        }
        for (ReplayProject.Marker marker : markers) {
            f.full(new ListRow(Component.literal(marker.name), b -> session.playback().seek(marker.tick))
                    .detail(Timeline.formatTicks(marker.tick))
                    .action(() -> Icon.TRASH, tc("justreplay.editor.marker.delete.tip"), () -> {
                        session.project().markers.remove(marker);
                        session.saveProject();
                        rebuildWidgets();
                    }).icon(Icon.FLAG).tip(tc("justreplay.editor.marker.row.tip")));
        }
        f.header(tc("justreplay.editor.scene.view"), Icon.EYE);
        f.full(new GlassSwitch(tc("justreplay.editor.voices"), () -> session.project().voices, () -> {
            ReplayProject p = session.project();
            p.voices = !p.voices;
            session.saveProject();
        }).icon(Icon.SPEAKER).tip(tc("justreplay.editor.voices.tip")));
        f.full(new GlassSwitch(tc("justreplay.editor.showpath"), () -> session.project().showPath, () -> {
            session.project().showPath = !session.project().showPath;
            session.saveProject();
        }).icon(Icon.PATH).tip(tc("justreplay.editor.showpath.tip")));
        f.full(new GlassSwitch(tc("justreplay.editor.preview"), session::isCameraPathActive,
                () -> session.setCameraPath(!session.isCameraPathActive())).icon(Icon.CAMERA).tip(tc("justreplay.editor.preview.tip")));
        f.full(new GlassButton(tc("justreplay.editor.camera.recorder"), b -> session.jumpToRecorder(minecraft)).icon(Icon.PERSON)
                .tip(tc("justreplay.editor.camera.recorder.tip")));
    }

    private void buildExport(Form f) {
        f.header(tc("justreplay.editor.export.section"), Icon.FILM);
        f.text(this::exportRangeLine, Theme.TEXT);
        f.text(() -> Component.translatable("justreplay.editor.export.settings", JRConfig.EXPORT_RESOLUTION.get().getTranslatedName(),
                JRConfig.EXPORT_FPS.get(), JRConfig.EXPORT_CRF.get()), Theme.TEXT_DIM);
        f.space(3);
        f.full(new GlassButton(tc("justreplay.editor.export.open"), b -> openExport()).icon(Icon.EXPORT).style(GlassButton.Style.PRIMARY));
        f.full(new GlassButton(tc("justreplay.export.folder"), b -> Util.getPlatform().openPath(ReplayStorage.exportRoot())).icon(Icon.FOLDER));
        f.space(2);
        f.paragraph(tc("justreplay.editor.export.help"), Theme.TEXT_MUTED);
    }

    private Component exportRangeLine() {
        int[] range = session.project().exportRange(session.playback().duration());
        if (range == null) {
            return tc("justreplay.editor.export.nothing");
        }
        return Component.translatable(session.project().cuts.isEmpty() ? "justreplay.export.range" : "justreplay.export.range.cuts",
                Timeline.formatTicks(range[0]), Timeline.formatTicks(range[1]));
    }

    private void openExport() {
        minecraft.setScreen(new ExportScreen(this, session));
    }

    private void openSettings() {
        net.neoforged.fml.ModList.get().getModContainerById(JustReplay.MOD_ID).ifPresent(container ->
                minecraft.setScreen(new net.neoforged.neoforge.client.gui.ConfigurationScreen(container, this)));
    }

    // ---------------------------------------------------------------- shared with CameraPanel

    /** Rebuilds the widgets after a selection change. */
    void refresh() {
        rebuildWidgets();
    }

    void clearActorKey() {
        selectedActorKey = null;
    }

    void confirm(Component question, Runnable action) {
        minecraft.setScreen(new ConfirmDialog(question, tc("justreplay.editor.cannot_undo"), tc("justreplay.editor.confirm.delete"), ok -> {
            if (ok) {
                action.run();
            }
            minecraft.setScreen(this);
        }));
    }

    private String actionLabel(ReplayProject.ActorAction action) {
        String label = Timeline.formatTicks(action.tick) + " " + Component.translatable(action.actionType().translationKey()).getString();
        if (action.param != null && !action.param.isBlank()) {
            label += " " + action.param;
        }
        return label;
    }

    // ---------------------------------------------------------------- transport

    private static int stepSeconds() {
        return JRConfig.SEEK_STEP_SECONDS.get();
    }

    private static int stepTicks() {
        return stepSeconds() * 20;
    }

    private void seekRelative(int delta) {
        session.playback().seek(session.playback().cursor() + delta);
    }

    private void togglePlay() {
        Playback playback = session.playback();
        if (playback.cursor() >= playback.duration()) {
            playback.seek(0);
        }
        playback.setPaused(!playback.isPaused());
    }

    private static int speedIndex(float speed) {
        for (int i = 0; i < Playback.SPEEDS.length; i++) {
            if (Playback.SPEEDS[i] == speed) {
                return i;
            }
        }
        return 3;
    }

    private void setSpeed(float value) {
        Playback playback = session.playback();
        playback.changeSpeed(speedIndex(value) - speedIndex(playback.speed()));
    }

    private void addMarker() {
        int tick = session.playback().cursor();
        minecraft.setScreen(new TextInputScreen(this, Component.translatable("justreplay.editor.marker"),
                Component.translatable("justreplay.editor.marker.name"), "Marker " + (session.project().markers.size() + 1), name -> {
            session.project().markers.add(new ReplayProject.Marker(tick, name));
            session.saveProject();
        }));
    }

    /** Deletes the selected cut, camera keyframe or actor keyframe, else the markers at the play head. */
    void deleteSelected() {
        if (audioLanes.deleteSelected()) {
            return;
        }
        if (cameras.selectedCut != null) {
            cameras.deleteCut();
            return;
        }
        if (cameras.selectedKey != null) {
            session.project().selected().keyframes.remove(cameras.selectedKey);
            cameras.selectedKey = null;
        } else if (selectedActor != null && selectedActorKey != null) {
            selectedActor.keyframes.remove(selectedActorKey);
            selectedActorKey = null;
        } else if (selectedActor != null && selectedAction != null) {
            selectedActor.actions.remove(selectedAction);
            selectedAction = null;
            rebuildWidgets();
        } else {
            int cursor = session.playback().cursor();
            session.project().markers.removeIf(m -> Math.abs(m.tick - cursor) <= 2);
            if (tab == TAB_SCENE) {
                rebuildWidgets();
            }
        }
        session.saveProject();
    }

    // ---------------------------------------------------------------- actors

    private void addPlayerActor() {
        minecraft.setScreen(new TextInputScreen(this, Component.translatable("justreplay.editor.actor.player"),
                Component.translatable("justreplay.editor.actor.skin"), minecraft.getUser().getName(), name -> {
            ReplayProject.ActorData actor = new ReplayProject.ActorData();
            actor.type = ReplayProject.ActorData.PLAYER;
            actor.name = name;
            addActor(actor);
        }));
    }

    private void addEntityActor() {
        minecraft.setScreen(new TextInputScreen(this, Component.translatable("justreplay.editor.actor.entity"),
                Component.translatable("justreplay.editor.actor.entityid"), "minecraft:zombie", id -> {
            ReplayProject.ActorData actor = new ReplayProject.ActorData();
            actor.type = id.isBlank() ? "minecraft:zombie" : id;
            addActor(actor);
        }));
    }

    private void addModelActor() {
        minecraft.setScreen(new ModelPickScreen(this, file -> {
            ReplayProject.ActorData actor = new ReplayProject.ActorData();
            actor.type = ReplayProject.ActorData.MODEL;
            actor.model = file;
            actor.name = file.endsWith(ModelLibrary.EXTENSION)
                    ? file.substring(0, file.length() - ModelLibrary.EXTENSION.length()) : file;
            for (String animation : ModelLibrary.animations(file)) {
                // A model usually has an idle loop: make it the default animation.
                if (animation.toLowerCase(Locale.ROOT).contains("idle")) {
                    actor.modelAnimation = animation;
                    break;
                }
            }
            addActor(actor);
        }));
    }

    private void addAction() {
        if (selectedActor == null) {
            return;
        }
        String param = actionType.defaultParam;
        if (actionType == ActionType.ANIMATION && selectedActor.isModel()) {
            List<String> animations = ModelLibrary.animations(selectedActor.model);
            param = animations.isEmpty() ? "" : animations.get(0) + "|once";
        }
        ReplayProject.ActorAction action = new ReplayProject.ActorAction(session.playback().cursor(), actionType, 0, param);
        selectedActor.actions.add(action);
        selectedActor.sort();
        selectedAction = action;
        selectedActorKey = null;
        cameras.selectedKey = null;
        session.saveProject();
        rebuildWidgets();
    }

    private void deleteAction() {
        if (selectedActor != null && selectedAction != null) {
            selectedActor.actions.remove(selectedAction);
            selectedAction = null;
            session.saveProject();
            rebuildWidgets();
        }
    }

    private void addActor(ReplayProject.ActorData actor) {
        LocalPlayer p = minecraft.player;
        if (p != null) {
            double groundY = groundBelow(p);
            actor.keyframes.add(new ReplayProject.ActorKeyframe(session.playback().cursor(), p.getX(), groundY, p.getZ(), p.getYRot(), 0));
        }
        session.project().actors.add(actor);
        selectedActor = actor;
        selectedActorKey = null;
        selectedAction = null;
        revealSelection = true;
        session.saveProject();
    }

    private void keyframeActor() {
        LocalPlayer p = minecraft.player;
        if (selectedActor == null || p == null) {
            return;
        }
        int tick = session.playback().cursor();
        selectedActor.keyframes.removeIf(k -> k.tick == tick);
        ReplayProject.ActorKeyframe key = new ReplayProject.ActorKeyframe(tick, p.getX(), groundBelow(p), p.getZ(), p.getYRot(), p.getXRot());
        selectedActor.keyframes.add(key);
        selectedActor.sort();
        selectedActorKey = key;
        cameras.selectedKey = null;
        session.saveProject();
    }

    /** Feet height of an actor placed under the camera: the ground within 4 blocks, else the camera height. */
    private double groundBelow(LocalPlayer p) {
        net.minecraft.world.phys.Vec3 from = p.position();
        net.minecraft.world.phys.BlockHitResult hit = p.level().clip(new net.minecraft.world.level.ClipContext(from, from.subtract(0, 4, 0),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getLocation().y : from.y;
    }

    private void deleteActor() {
        if (selectedActor == null) {
            return;
        }
        session.actors().remove(selectedActor);
        session.project().actors.remove(selectedActor);
        selectedActor = null;
        selectedActorKey = null;
        selectedAction = null;
        session.saveProject();
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (helpOpen) {
            helpOpen = false;
            return true;
        }
        if (hasPopup()) {
            return super.mouseClicked(mx, my, button);
        }
        if (button == 0 && timeline.barClicked(mx, my)) {
            return true;
        }
        if (button == 0 && timeline.contains(mx, my)) {
            int duration = session.playback().duration();
            ReplayProject project = session.project();
            dragging = null;
            if (timeline.laneAt(my) == EditorTimeline.Lane.AUDIO ? audioLanes.mouseClicked(timeline, mx, my) : clearAudioSelection()) {
                return true;
            }
            switch (timeline.laneAt(my)) {
                case CUTS -> {
                    if (clickCuts(project, mx, duration)) {
                        return true;
                    }
                }
                case CAMERA -> {
                    for (ReplayProject.CameraKeyframe k : project.selected().keyframes) {
                        if (Math.abs(timeline.xOf(k.tick) - mx) <= 4) {
                            cameras.selectedKey = k;
                            cameras.selectedCut = null;
                            selectedActorKey = null;
                            startDrag(k, k.tick, mx);
                            return true;
                        }
                    }
                }
                case ACTOR -> {
                    if (selectedActor != null) {
                        for (ReplayProject.ActorKeyframe k : selectedActor.keyframes) {
                            if (Math.abs(timeline.xOf(k.tick) - mx) <= 4) {
                                selectedActorKey = k;
                                cameras.selectedKey = null;
                                cameras.selectedCut = null;
                                selectedAction = null;
                                startDrag(k, k.tick, mx);
                                return true;
                            }
                        }
                        for (ReplayProject.ActorAction a : selectedActor.actions) {
                            if (Math.abs(timeline.xOf(a.tick) - mx) <= 3) {
                                selectedAction = a;
                                selectedActorKey = null;
                                cameras.selectedKey = null;
                                cameras.selectedCut = null;
                                startDrag(a, a.tick, mx);
                                return true;
                            }
                        }
                    }
                }
                case MARKERS -> {
                    for (ReplayProject.Marker marker : project.markers) {
                        if (Math.abs(timeline.xOf(marker.tick) - mx) <= 3) {
                            session.playback().seek(marker.tick);
                            return true;
                        }
                    }
                }
                default -> {
                }
            }
            cameras.selectedKey = null;
            cameras.selectedCut = null;
            selectedActorKey = null;
            scrubbing = true;
            ghostTick = timeline.tickAt(mx);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    /** Drops the audio clip selection when another lane is clicked; never consumes the click. */
    private boolean clearAudioSelection() {
        if (audioLanes.selectedClip != null) {
            audioLanes.selectedClip = null;
            if (tab == TAB_AUDIO) {
                rebuildWidgets();
            }
        }
        return false;
    }

    /** Cut lane: grab a boundary or the track end to drag it, or click a segment to select its cut and camera. */
    private boolean clickCuts(ReplayProject project, double mx, int duration) {
        if (project.cuts.isEmpty()) {
            return false;
        }
        if (project.cutsEnd >= 0) {
            int end = project.cutTrackEnd(duration);
            if (Math.abs(timeline.xOf(end) - mx) <= 3) {
                startDrag(CUT_END, end, mx);
                return true;
            }
        }
        for (ReplayProject.Cut cut : project.cuts) {
            if (Math.abs(timeline.xOf(cut.tick) - mx) <= 3) {
                selectCut(project, cut);
                startDrag(cut, cut.tick, mx);
                return true;
            }
        }
        for (int i = 0; i < project.cuts.size(); i++) {
            int[] seg = timeline.segment(project, i);
            if (mx >= seg[0] && mx <= seg[1]) {
                selectCut(project, project.cuts.get(i));
                return true;
            }
        }
        return false;
    }

    private void selectCut(ReplayProject project, ReplayProject.Cut cut) {
        selectedActorKey = null;
        cameras.select(project.camera(cut.camera));
        cameras.selectedCut = cut;
        cameras.selectedKey = null;
    }

    private void startDrag(Object item, int tick, double mx) {
        dragging = item;
        dragMoved = false;
        pressX = mx;
        grabOffset = mx - timeline.xOf(tick);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (timeline.barDragged(mx) || audioLanes.mouseDragged(timeline, mx, my)) {
            return true;
        }
        int duration = session.playback().duration();
        if (dragging != null) {
            // A plain click selects; the item only moves once the mouse travelled a few pixels.
            if (!dragMoved && Math.abs(mx - pressX) < 3) {
                return true;
            }
            dragMoved = true;
            int tick = timeline.tickAt(mx - grabOffset);
            if (dragging instanceof ReplayProject.CameraKeyframe k) {
                k.tick = tick;
            } else if (dragging instanceof ReplayProject.ActorKeyframe k) {
                k.tick = tick;
            } else if (dragging instanceof ReplayProject.Cut cut) {
                // A cut boundary stays between its neighbours.
                List<ReplayProject.Cut> cuts = session.project().cuts;
                int i = cuts.indexOf(cut);
                int min = i > 0 ? cuts.get(i - 1).tick + 1 : 0;
                int max = i + 1 < cuts.size() ? cuts.get(i + 1).tick - 1 : duration;
                cut.tick = Math.max(min, Math.min(max, tick));
            } else if (dragging == CUT_END) {
                List<ReplayProject.Cut> cuts = session.project().cuts;
                session.project().cutsEnd = Math.max(cuts.get(cuts.size() - 1).tick + 1, Math.min(duration, tick));
            } else if (dragging instanceof ReplayProject.ActorAction a) {
                a.tick = tick;
            }
            return true;
        }
        if (scrubbing) {
            ghostTick = timeline.tickAt(mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (timeline.barReleased() || audioLanes.mouseReleased()) {
            return true;
        }
        if (dragging != null) {
            dragging = null;
            session.project().sort();
            session.project().mergeCuts();
            if (selectedActor != null) {
                selectedActor.sort();
            }
            session.saveProject();
            if (dragMoved) {
                rebuildWidgets();
            }
            return true;
        }
        if (scrubbing) {
            scrubbing = false;
            session.playback().seek(ghostTick);
            ghostTick = -1;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!hasPopup() && !helpOpen && timeline.contains(mx, my)) {
            if (timeline.laneAt(my) == EditorTimeline.Lane.AUDIO && audioLanes.mouseScrolled(sy)) {
                return true;
            }
            if (hasControlDown()) {
                timeline.zoom(sy > 0 ? 0.8 : 1.25, mx);
            } else {
                timeline.scroll(-(sy != 0 ? sy : -sx) * 0.12);
            }
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (helpOpen && (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_F1)) {
            helpOpen = false;
            return true;
        }
        if (hasPopup()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (audioLanes.keyPressed(keyCode)) {
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE -> {
                togglePlay();
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                seekRelative(-stepTicks());
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                seekRelative(stepTicks());
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                deleteSelected();
                return true;
            }
            case GLFW.GLFW_KEY_K -> {
                cameras.addKeyframe();
                return true;
            }
            case GLFW.GLFW_KEY_F1 -> {
                toggleHelp();
                return true;
            }
            default -> {
            }
        }
        if (JRKeys.EDITOR.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (JRKeys.CAMERA_PATH.matches(keyCode, scanCode)) {
            session.setCameraPath(!session.isCameraPathActive());
            return true;
        }
        int cut = JRKeys.cutKeyIndex(keyCode, scanCode);
        if (cut >= 0) {
            CameraPanel.quickSwitch(session, cut);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (c == '?' && !hasPopup()) {
            toggleHelp();
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    // ---------------------------------------------------------------- render

    @Override
    public void tick() {
        cameras.tick();
        if (speed != null && speed.value() != session.playback().speed()) {
            speed.setValue(session.playback().speed());
        }
        if (++saveCheck % 10 == 0) {
            long time = projectTime();
            if (time != savedMtime) {
                savedMtime = time;
                savedFlashAt = Anim.now();
            }
        }
    }

    private long projectTime() {
        try {
            return Files.getLastModifiedTime(session.dir().resolve(ReplayStorage.PROJECT)).toMillis();
        } catch (Exception e) {
            return -1L;
        }
    }

    @Override
    protected void renderPanels(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        placeSidebar();
        Paint.glass(g, 4, 4, width - 8, TOP_H, Theme.RADIUS_L);
        float open = sidebar.get();
        if (open > 0.01F) {
            Paint.glass(g, sidebarX(), sideTop, sideW, sideBottom - sideTop, Theme.RADIUS_L);
        }
        Paint.glass(g, 4, dockTop, width - 8, height - 4 - dockTop, Theme.RADIUS_L);
        Playback playback = session.playback();
        timeline.begin(playback.duration(), playback.cursor(), !playback.isPaused() && dragging == null && !scrubbing && !audioLanes.dragging());
    }

    @Override
    protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTopBar(g, mouseX, mouseY);
        Playback playback = session.playback();
        if (playback.cursor() != shownCursor) {
            shownCursor = playback.cursor();
            timeText = Timeline.formatTicks(playback.cursor()) + " / " + Timeline.formatTicks(playback.duration());
        }
        g.drawString(font, timeText, timeX, dockTop + 4 + (Theme.ROW + 1 - 7) / 2, Theme.TEXT, false);
        timeline.render(g, font, session, cameras.selectedKey, cameras.selectedCut, selectedActor, selectedActorKey, selectedAction,
                ghostTick, mouseX, mouseY);
        if (timeline.contains(mouseX, mouseY) && dragging == null && !scrubbing && !helpOpen && !audioLanes.dragging()) {
            renderTimelineTooltip(g, mouseX, mouseY);
        }
        if (helpOpen) {
            renderHelp(g);
        }
    }

    private void renderTopBar(GuiGraphics g, int mouseX, int mouseY) {
        int y = 4 + (TOP_H - 7) / 2;
        Paint.icon(g, Icon.FILM, 29, 4 + (TOP_H - 9) / 2.0F, 9, Theme.ACCENT);
        int x = 42;
        float flash = 1.0F - Anim.progress(savedFlashAt, 1600);
        Component state = savedMtime < 0 ? tc("justreplay.editor.unsaved") : tc("justreplay.editor.saved");
        int stateW = font.width(state) + 12;
        String name = Paint.ellipsize(font, session.meta().name, Math.max(20, nameRight - x - stateW - 10));
        g.drawString(font, name, x, y, Theme.TEXT, false);
        int sx = x + font.width(name) + 10;
        if (sx + stateW <= nameRight) {
            int color = savedMtime < 0 ? Theme.TEXT_MUTED : Theme.mix(0xFF7FA894, Theme.SUCCESS, flash);
            Paint.icon(g, savedMtime < 0 ? Icon.DOT : Icon.CHECK, sx, 4 + (TOP_H - 8) / 2.0F, 8, color);
            g.drawString(font, state, sx + 11, y, Theme.text(Theme.mix(Theme.TEXT_MUTED, Theme.TEXT_DIM, flash), 1.0F), false);
            if (mouseX >= sx && mouseX < sx + stateW && mouseY >= 4 && mouseY < 4 + TOP_H && savedMtime >= 0) {
                savedTip = new SimpleDateFormat("HH:mm:ss").format(new Date(savedMtime));
                Paint.tooltip(g, font, Component.translatable("justreplay.editor.saved.tip", savedTip), mouseX, mouseY);
            }
        }
    }

    private void renderTimelineTooltip(GuiGraphics g, int mouseX, int mouseY) {
        ReplayProject project = session.project();
        switch (timeline.laneAt(mouseY)) {
            case CUTS -> {
                for (int i = 0; i < project.cuts.size(); i++) {
                    int[] seg = timeline.segment(project, i);
                    if (mouseX >= seg[0] && mouseX <= seg[1]) {
                        ReplayProject.Cut cut = project.cuts.get(i);
                        ReplayProject.Camera camera = project.camera(cut.camera);
                        String name = camera == null ? "?" : (project.cameras.indexOf(camera) + 1) + " " + camera.name;
                        Paint.tooltip(g, font, Component.literal(name + "  " + Timeline.formatTicks(cut.tick)
                                + (cut.blend > 0 ? "  blend " + cut.blend + "t" : "")
                                + (i > 0 && cut.transitionType() != com.vyrriox.justreplay.editor.fx.TransitionType.CUT
                                ? "  " + tr(cut.transitionType().translationKey()) + " " + cut.transitionTicks + "t" : "")), mouseX, mouseY);
                        return;
                    }
                }
            }
            case CAMERA -> {
                for (ReplayProject.CameraKeyframe k : project.selected().keyframes) {
                    if (Math.abs(timeline.xOf(k.tick) - mouseX) <= 4) {
                        Paint.tooltip(g, font, Component.translatable("justreplay.editor.camera.key_tip", Timeline.formatTicks(k.tick),
                                Math.round(k.roll), Math.round(k.fov)), mouseX, mouseY);
                        return;
                    }
                }
            }
            case ACTOR -> {
                if (selectedActor == null) {
                    Paint.tooltip(g, font, tc("justreplay.editor.actor.lane.tip"), mouseX, mouseY);
                    return;
                }
                for (ReplayProject.ActorAction action : selectedActor.actions) {
                    if (Math.abs(timeline.xOf(action.tick) - mouseX) <= 2) {
                        Paint.tooltip(g, font, Component.literal(actionLabel(action)), mouseX, mouseY);
                        return;
                    }
                }
                for (ReplayProject.ActorKeyframe k : selectedActor.keyframes) {
                    if (Math.abs(timeline.xOf(k.tick) - mouseX) <= 4) {
                        Paint.tooltip(g, font, Component.translatable("justreplay.editor.actor.key_info", Timeline.formatTicks(k.tick)), mouseX, mouseY);
                        return;
                    }
                }
            }
            case AUDIO -> {
                Component tip = audioLanes.tooltip(timeline, mouseX, mouseY);
                if (tip != null) {
                    Paint.tooltip(g, font, tip, mouseX, mouseY);
                }
            }
            case MARKERS -> {
                for (ReplayProject.Marker marker : project.markers) {
                    if (Math.abs(timeline.xOf(marker.tick) - mouseX) <= 3) {
                        Paint.tooltip(g, font, Component.literal(marker.name + "  " + Timeline.formatTicks(marker.tick)), mouseX, mouseY);
                        return;
                    }
                }
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- help

    private void toggleHelp() {
        helpOpen = !helpOpen;
        if (helpOpen) {
            helpAt = Anim.now();
            help = buildHelp();
        }
    }

    private static String key(int glfw) {
        return InputConstants.Type.KEYSYM.getOrCreate(glfw).getDisplayName().getString();
    }

    private static String key(KeyMapping mapping) {
        return mapping.isUnbound() ? "-" : mapping.getTranslatedKeyMessage().getString();
    }

    /** Shortcut list built from the real bindings: {keys, action} rows, {null, title} for a section. */
    private List<String[]> buildHelp() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{null, tr("justreplay.help.editor")});
        rows.add(new String[]{key(GLFW.GLFW_KEY_SPACE), tr("justreplay.help.play")});
        rows.add(new String[]{key(GLFW.GLFW_KEY_LEFT) + " " + key(GLFW.GLFW_KEY_RIGHT),
                Component.translatable("justreplay.help.seek", stepSeconds()).getString()});
        rows.add(new String[]{key(GLFW.GLFW_KEY_DELETE), tr("justreplay.help.delete")});
        rows.add(new String[]{key(GLFW.GLFW_KEY_K), tr("justreplay.help.keyframe")});
        rows.add(new String[]{key(GLFW.GLFW_KEY_S), tr("justreplay.help.audio_split")});
        rows.add(new String[]{key(GLFW.GLFW_KEY_LEFT_CONTROL) + " + " + key(GLFW.GLFW_KEY_D), tr("justreplay.help.audio_duplicate")});
        rows.add(new String[]{key(JRKeys.CUT_TO[0]) + "-" + key(JRKeys.CUT_TO[JRKeys.CUT_TO.length - 1]), tr("justreplay.help.cut")});
        rows.add(new String[]{key(JRKeys.CAMERA_PATH), tr("justreplay.help.preview")});
        rows.add(new String[]{key(JRKeys.EDITOR) + " " + key(GLFW.GLFW_KEY_ESCAPE), tr("justreplay.help.close")});
        rows.add(new String[]{key(GLFW.GLFW_KEY_F1) + " ?", tr("justreplay.help.help")});
        rows.add(new String[]{null, tr("justreplay.help.timeline")});
        rows.add(new String[]{tr("justreplay.help.mouse.click"), tr("justreplay.help.click")});
        rows.add(new String[]{tr("justreplay.help.mouse.drag"), tr("justreplay.help.drag")});
        rows.add(new String[]{key(GLFW.GLFW_KEY_LEFT_CONTROL) + " + " + tr("justreplay.help.mouse.wheel"), tr("justreplay.help.zoom")});
        rows.add(new String[]{tr("justreplay.help.mouse.wheel"), tr("justreplay.help.scroll")});
        rows.add(new String[]{null, tr("justreplay.help.watching")});
        for (KeyMapping mapping : new KeyMapping[]{JRKeys.PLAY_PAUSE, JRKeys.SEEK_BACK, JRKeys.SEEK_FORWARD, JRKeys.SPEED_DOWN,
                JRKeys.SPEED_UP, JRKeys.CAMERA_KEYFRAME, JRKeys.MARKER, JRKeys.TOGGLE_HUD, JRKeys.RECORDER_VIEW, JRKeys.STOP_SPECTATING,
                JRKeys.RECORD}) {
            if (!mapping.isUnbound()) {
                rows.add(new String[]{key(mapping), tr(mapping.getName())});
            }
        }
        return rows;
    }

    private void renderHelp(GuiGraphics g) {
        float a = Anim.easeOutCubic(Anim.progress(helpAt, 200));
        g.pose().pushPose();
        g.pose().translate(0, 0, 350);
        g.fill(0, 0, width, height, Theme.fade(0x8C05080C, a));
        int split = 0;
        for (int i = 1; i < help.size(); i++) {
            if (help.get(i)[0] == null && i > help.size() / 3) {
                split = i;
                break;
            }
        }
        boolean twoColumns = width >= 360 && split > 0;
        int colW = twoColumns ? Math.min(230, (width - 40) / 2) : Math.min(300, width - 32);
        int rows = twoColumns ? Math.max(split, help.size() - split) : help.size();
        int boxW = twoColumns ? colW * 2 + 24 : colW + 16;
        int boxH = Math.min(height - 16, 30 + rows * 11 + 8);
        int bx = (width - boxW) / 2;
        int by = (height - boxH) / 2 + Math.round((1.0F - a) * 8);
        Paint.glass(g, bx, by, boxW, boxH, Theme.RADIUS_L);
        Paint.icon(g, Icon.KEYBOARD, bx + 10, by + 8, 10, Theme.ACCENT);
        g.drawString(font, tr("justreplay.help.title"), bx + 25, by + 10, Theme.TEXT, false);
        Component hint = tc("justreplay.help.dismiss");
        g.drawString(font, hint, bx + boxW - 10 - font.width(hint), by + 10, Theme.TEXT_MUTED, false);
        g.enableScissor(bx, by + 24, bx + boxW, by + boxH - 4);
        if (twoColumns) {
            renderHelpColumn(g, help.subList(0, split), bx + 8, by + 28, colW);
            renderHelpColumn(g, help.subList(split, help.size()), bx + 16 + colW, by + 28, colW);
        } else {
            renderHelpColumn(g, help, bx + 8, by + 28, colW);
        }
        g.disableScissor();
        g.pose().popPose();
    }

    private void renderHelpColumn(GuiGraphics g, List<String[]> rows, int x, int y, int w) {
        int keyW = 0;
        for (String[] row : rows) {
            if (row[0] != null) {
                keyW = Math.max(keyW, font.width(row[0]) + 8);
            }
        }
        keyW = Math.min(keyW, w / 2);
        for (String[] row : rows) {
            if (row[0] == null) {
                g.drawString(font, row[1].toUpperCase(Locale.ROOT), x, y + 2, Theme.ACCENT, false);
                y += 12;
                continue;
            }
            String keys = Paint.ellipsize(font, row[0], keyW - 8);
            int kw = font.width(keys) + 8;
            Paint.rect(g, x + keyW - kw, y, kw, 10, Theme.RADIUS_S, 0x33FFFFFF, 0x1AFFFFFF);
            Paint.outline(g, x + keyW - kw, y, kw, 10, Theme.RADIUS_S, 0x4DFFFFFF, 0x1AFFFFFF);
            g.drawString(font, keys, x + keyW - kw + 4, y + 1, Theme.TEXT, false);
            g.drawString(font, Paint.ellipsize(font, row[1], w - keyW - 6), x + keyW + 6, y + 1, Theme.TEXT_DIM, false);
            y += 11;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        session.saveProject();
    }
}
