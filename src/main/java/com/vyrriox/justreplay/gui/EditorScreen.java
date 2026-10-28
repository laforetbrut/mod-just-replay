package com.vyrriox.justreplay.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.client.JRKeys;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.config.JRConfig.EditorMode;
import com.vyrriox.justreplay.editor.ActionType;
import com.vyrriox.justreplay.editor.ActorPlacement;
import com.vyrriox.justreplay.editor.CameraController;
import com.vyrriox.justreplay.editor.CameraPath;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.model.ModelLibrary;
import com.vyrriox.justreplay.export.VideoExporter;
import com.vyrriox.justreplay.format.ReplayStorage;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.Dropdown;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.GlassToast;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListPopup;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.ScrollArea;
import com.vyrriox.justreplay.gui.ui.Segmented;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.Playback;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The replay editor: a top bar, a collapsible sidebar with tabs and a bottom dock with the transport controls and
 * the multi-lane timeline. The world keeps running and stays visible behind it; the screen does not pause the game.
 *
 * <p>Two modes share the same project: SIMPLE shows the essentials with big controls and plain words (one camera
 * path, characters placed in the world, markers, one-click export), ADVANCED shows every tool. Switching keeps
 * everything.
 *
 * @author vyrriox
 */
public class EditorScreen extends GlassScreen {
    /** Drag handle for the end of the cut track. */
    private static final Object CUT_END = new Object();
    private static final int TOP_H = 18;
    /** Height of the transport row and of the main buttons in the simple mode. */
    private static final int BIG = 20;
    /** Height of the transport row and of the main buttons of the simple mode in a short window. */
    private static final int BIG_COMPACT = 16;
    /** Height the sidebar keeps at least, when the dock can give it up (fewer audio lanes). */
    private static final int MIN_SIDE = 110;
    private static final String[] COMMON_MOBS = {"minecraft:villager", "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper",
            "minecraft:iron_golem", "minecraft:wolf", "minecraft:cat", "minecraft:horse", "minecraft:pig", "minecraft:cow",
            "minecraft:sheep", "minecraft:chicken", "minecraft:fox", "minecraft:enderman", "minecraft:piglin", "minecraft:pillager",
            "minecraft:witch", "minecraft:allay"};

    /**
     * A sidebar tab: shown in the modes it names, filled by its builder. {@link #registerTab} adds the tab of a
     * feature; the order of the list is the order on screen.
     */
    public record SidebarTab(String id, String translationKey, Icon icon, boolean simple, boolean advanced,
                             BiConsumer<EditorScreen, Form> builder) {
        boolean shownIn(EditorMode mode) {
            return mode == EditorMode.SIMPLE ? simple : advanced;
        }
    }

    private static final List<SidebarTab> TABS = new ArrayList<>(List.of(
            new SidebarTab("camera", "justreplay.editor.tab.camera", Icon.CAMERA, true, false, EditorScreen::buildSimpleCamera),
            new SidebarTab("cameras", "justreplay.editor.tab.cameras", Icon.CAMERA, false, true, (s, f) -> s.cameras.build(f)),
            new SidebarTab("characters", "justreplay.editor.tab.characters", Icon.PERSON, true, false, EditorScreen::buildSimpleActors),
            new SidebarTab("actors", "justreplay.editor.tab.actors", Icon.PERSON, false, true, EditorScreen::buildActors),
            new SidebarTab("scene", "justreplay.editor.tab.scene", Icon.FLAG, false, true, EditorScreen::buildScene),
            new SidebarTab("audio", "justreplay.editor.tab.audio", Icon.SPEAKER, false, true, (s, f) -> s.audioPanel.build(f)),
            new SidebarTab("effects", "justreplay.editor.tab.effects", Icon.BOLT, false, true, (s, f) -> s.effectsPanel.build(f)),
            new SidebarTab("export", "justreplay.editor.tab.export", Icon.FILM, true, true, EditorScreen::buildExportTab)));

    /** Adds (or replaces) a sidebar tab, before the tab {@code beforeId}, or last when it is null or unknown. */
    public static void registerTab(SidebarTab tab, String beforeId) {
        TABS.removeIf(t -> t.id().equals(tab.id()));
        int index = TABS.size();
        for (int i = 0; i < TABS.size(); i++) {
            if (TABS.get(i).id().equals(beforeId)) {
                index = i;
                break;
            }
        }
        TABS.add(index, tab);
    }

    static {
        // Texts and images in the world, in both modes, before the export tab.
        registerTab(new SidebarTab("overlays", "justreplay.editor.tab.overlays", Icon.SCENE, true, true, (s, f) -> s.overlaysPanel.build(f)),
                "export");
        // Weather and time of day over the recording, in both modes.
        registerTab(new SidebarTab("world", "justreplay.editor.tab.world", Icon.CLOCK, true, true, (s, f) -> s.worldPanel.build(f, !simple())),
                "overlays");
        // Recorded entities: hide, move, sound volume; in both modes, before the export tab.
        registerTab(new SidebarTab("entities", "justreplay.editor.tab.entities", Icon.PERSON, true, true, (s, f) -> s.entitiesPanel.build(f)),
                "export");
    }

    /** Editor state kept across rebuilds and reopenings. */
    private static final Map<EditorMode, String> TAB = new EnumMap<>(EditorMode.class);
    private static String previousTab;
    private static boolean sidebarOpen = true;
    private static final Map<String, Float> TAB_SCROLL = new HashMap<>();

    private final ReplaySession session;
    private final CameraPanel cameras;
    private ReplayProject.ActorData selectedActor;
    private ReplayProject.ActorKeyframe selectedActorKey;
    private ReplayProject.ActorAction selectedAction;
    private ActionType actionType = ActionType.WAVE;
    private boolean revealSelection;
    private int selectedRowTop = -1;
    private Component status;
    private int statusColor = Theme.TEXT_MUTED;

    private final EditorTimeline timeline = new EditorTimeline();
    // Audio editing: the lanes under the timeline markers and the sidebar tab.
    private final AudioLanes audioLanes;
    private final AudioPanel audioPanel;
    // Video effects: the filter stack tab.
    private final EffectsPanel effectsPanel;
    private final WorldPanel worldPanel;
    // Recorded entities: the Entities tab.
    private final EntitiesPanel entitiesPanel;
    // Texts and images in the world: the tab and the timeline lane.
    private final OverlaysPanel overlaysPanel;
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
    private int rowH;
    /** Height of the main buttons of the simple mode sidebar. */
    private int bigH = BIG - 2;
    private boolean compact;
    private Segmented<String> tabs;
    private ScrollArea side;
    private Dropdown<Float> speed;

    private boolean helpOpen;
    private long helpAt;
    private List<String[]> help = List.of();
    private final Anim.Value helpScroll = new Anim.Value(0.0F, Theme.NORMAL + 4);
    private int helpMax;
    private final Map<String[], List<FormattedCharSequence>> helpLines = new IdentityHashMap<>();
    private int helpLinesW = -1;

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
        this.worldPanel = new WorldPanel(this, session);
        this.entitiesPanel = new EntitiesPanel(this, session);
        this.overlaysPanel = new OverlaysPanel(this, session);
        this.timeline.overlays = overlaysPanel;
        this.blurBackground = false;
    }

    public ReplaySession session() {
        return session;
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    public static EditorMode mode() {
        return JRConfig.EDITOR_MODE.get();
    }

    private static boolean simple() {
        return mode() == EditorMode.SIMPLE;
    }

    /** Vertical gap between the transport row and the timeline. */
    private int dockGap() {
        return compact ? 4 : 6;
    }

    private int dockHeight() {
        return 4 + rowH + dockGap() + timeline.height() + (compact ? 3 : 4);
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        boolean simple = simple();
        // A short window (the default 854 x 480 window at GUI scale 2 is 240 high) gets smaller controls so the
        // sidebar keeps room above the dock.
        compact = height < 300;
        rowH = simple ? (compact ? BIG_COMPACT : BIG) : Theme.ROW + 1;
        bigH = compact ? BIG_COMPACT : BIG - 2;
        timeline.lanes(simple ? EditorTimeline.SIMPLE_LANES : EditorTimeline.ALL_LANES);
        timeline.compact(compact);
        // The audio lanes give way first: fewer tracks at once (the others scroll) rather than a cramped sidebar.
        int tracks = AudioLanes.MAX_VISIBLE;
        audioLanes.maxVisible(tracks);
        while (tracks > 1 && height - dockHeight() - 4 - 4 - (4 + TOP_H + 4) < MIN_SIDE) {
            audioLanes.maxVisible(--tracks);
        }
        sideW = Mth.clamp(width * 40 / 100, Math.min(160, width - 16), simple ? 240 : 220);
        dockTop = height - 4 - dockHeight();
        sideTop = 4 + TOP_H + 4;
        sideBottom = dockTop - 4;
        initTopBar();
        initTransport();
        timeline.layout(8 + EditorTimeline.LABELS + 2, dockTop + 4 + rowH + dockGap(), width - 16 - EditorTimeline.LABELS - 2);
        initSidebar();
        if (savedMtime == Long.MIN_VALUE) {
            savedMtime = projectTime();
        }
    }

    private void initTopBar() {
        int y = 4 + (TOP_H - 14) / 2;
        addRenderableWidget(new GlassButton(8, y, 16, 14, Component.empty(), b -> toggleSidebar())
                .icon(Icon.SIDEBAR).style(GlassButton.Style.GHOST).selected(() -> sidebarOpen).tip(tc("justreplay.editor.sidebar.tip")));
        int modeW = Math.max(font.width(tc("justreplay.editor.mode.simple")), font.width(tc("justreplay.editor.mode.advanced"))) * 2 + 22;
        int exportW = font.width(tc("justreplay.editor.export")) + 24;
        // Narrow window: the export button keeps only its icon so the replay name still has some room.
        int fixed = 8 + 16 + 4 + 3 + 16 + 2 + 16 + 2 + 16 + 6 + modeW + 6;
        if (width - fixed - exportW < 42 + 70) {
            exportW = 20;
        }
        int rx = width - 8 - 16;
        addRenderableWidget(new GlassButton(rx, y, 16, 14, Component.empty(), b -> onClose())
                .icon(Icon.CLOSE).style(GlassButton.Style.GHOST).tip(tc("justreplay.editor.close.tip")));
        rx -= 4 + exportW;
        addRenderableWidget(new GlassButton(rx, y, exportW, 14, tc("justreplay.editor.export"), b -> {
            if (simple()) {
                oneClickExport();
            } else {
                openExport();
            }
        }).icon(Icon.EXPORT).style(GlassButton.Style.PRIMARY).tip(tc(simple() ? "justreplay.editor.simple.export.tip" : "justreplay.editor.export.tip")));
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
        // Simple / Advanced switch, remembered in the config.
        rx -= 6 + modeW;
        Segmented<EditorMode> modes = addRenderableWidget(new Segmented<>(List.of(EditorMode.SIMPLE, EditorMode.ADVANCED), mode(),
                m -> tc("justreplay.editor.mode." + m.name().toLowerCase(Locale.ROOT)), null, this::setMode)
                .tip(tc("justreplay.editor.mode.tip")));
        modes.setRectangle(modeW, 14, rx, y);
        nameRight = rx - 6;
    }

    private void setMode(EditorMode mode) {
        if (mode == mode()) {
            return;
        }
        JRConfig.EDITOR_MODE.set(mode);
        JRConfig.SPEC.save();
        previousTab = null;
        tabChangedAt = Anim.now();
        status = null;
        rebuildWidgets();
    }

    /**
     * The transport row. Its parts are measured first and give way, in this order, when the window is too narrow:
     * the label of the marker button, the zoom in and out buttons (Ctrl + wheel still zooms, "fit" stays), the label
     * of the camera point button, then the jump to start and end buttons. Unlabelled buttons keep a tooltip, and
     * nothing ever overlaps the zoom buttons.
     */
    private void initTransport() {
        boolean simple = simple();
        Playback playback = session.playback();
        int y = dockTop + 4;
        int h = rowH;
        int btn = simple ? (compact ? 18 : 20) : 16;
        int play = simple ? (compact ? 26 : 32) : 24;
        int speedW = simple && !compact ? 40 : 36;
        int gap = compact ? 5 : 8;
        String total = Timeline.formatTicks(playback.duration());
        int timeW = font.width(total + " / " + total);
        Component point = tc("justreplay.editor.simple.point");
        Component marker = tc("justreplay.editor.simple.marker");
        int pointW = font.width(point) + 26;
        int markerW = font.width(marker) + 26;

        boolean skips = true;
        boolean allZoom = true;
        int labels = simple ? 2 : 0;
        while (true) {
            int left = 8 + (skips ? btn + 2 : 0) + (btn + 2) + (play + 2) + (btn + 2) + (skips && !simple ? btn + 2 : 0);
            int actions = switch (labels) {
                case 2 -> pointW + 3 + markerW;
                case 1 -> pointW + 3 + btn;
                default -> simple ? 2 * btn + 2 : 3 * (btn + 2) - 2;
            };
            int end = left + 4 + timeW + 6 + speedW + gap + actions;
            int zoomLeft = width - 8 - (allZoom ? 3 * 18 - 2 : 16);
            if (end + 4 <= zoomLeft) {
                break;
            }
            if (labels == 2) {
                labels = 1;
            } else if (allZoom) {
                allZoom = false;
            } else if (labels == 1) {
                labels = 0;
            } else if (skips) {
                skips = false;
            } else {
                break;
            }
        }

        int x = 8;
        Component step = Component.literal(Integer.toString(stepSeconds()));
        if (skips) {
            x = transport(x, y, btn, h, Icon.SKIP_START, tc("justreplay.editor.start.tip"), () -> playback.seek(0));
        }
        x = transport(x, y, btn, h, Icon.REWIND, Component.translatable("justreplay.editor.back.tip", step), () -> seekRelative(-stepTicks()));
        addRenderableWidget(new GlassButton(x, y, play, h, Component.empty(), b -> togglePlay())
                .icon(() -> playback.isPaused() ? Icon.PLAY : Icon.PAUSE).style(GlassButton.Style.PRIMARY).tip(tc("justreplay.editor.play.tip")));
        x += play + 2;
        x = transport(x, y, btn, h, Icon.FORWARD, Component.translatable("justreplay.editor.forward.tip", step), () -> seekRelative(stepTicks()));
        if (skips && !simple) {
            x = transport(x, y, btn, h, Icon.SKIP_END, tc("justreplay.editor.end.tip"), () -> playback.seek(playback.duration()));
        }
        timeX = x + 4;
        x = timeX + timeW + 6;
        x = speedDropdown(x, y, speedW, h);
        x += gap;
        if (simple) {
            // The main actions of the simple mode are spelled out when there is room, else icons with tooltips.
            if (labels >= 1) {
                addRenderableWidget(new GlassButton(x, y, pointW, h, point, b -> addSimplePoint()).icon(Icon.KEY)
                        .tip(tc("justreplay.editor.simple.point.tip")));
                x += pointW + 3;
            } else {
                x = transport(x, y, btn, h, Icon.KEY, point.copy().append(" (K)"), this::addSimplePoint);
            }
            if (labels == 2) {
                addRenderableWidget(new GlassButton(x, y, markerW, h, marker, b -> addMarker()).icon(Icon.FLAG)
                        .tip(tc("justreplay.editor.marker.tip")));
            } else {
                transport(x, y, btn, h, Icon.FLAG, tc("justreplay.editor.marker.tip"), this::addMarker);
            }
        } else {
            x = transport(x, y, btn, h, Icon.KEY, tc("justreplay.editor.key.tip"), cameras::addKeyframe);
            x = transport(x, y, btn, h, Icon.FLAG, tc("justreplay.editor.marker.tip"), this::addMarker);
            transport(x, y, btn, h, Icon.CUT, tc("justreplay.editor.cut.add.tip"), cameras::cutHere);
        }
        zoomButtons(y, h, allZoom);
    }

    private int speedDropdown(int x, int y, int w, int h) {
        Playback playback = session.playback();
        List<Float> speeds = new ArrayList<>();
        for (float s : Playback.SPEEDS) {
            speeds.add(s);
        }
        speed = addRenderableWidget(new Dropdown<>(this, Component.empty(), speeds, playback.speed(),
                v -> Component.literal("x" + ReplayHud.trim(v)), this::setSpeed));
        speed.setRectangle(w, h, x, y);
        speed.tip(tc("justreplay.editor.speed.tip"));
        return x + w;
    }

    /** Zoom buttons at the right of the transport row; {@code all} false keeps only "fit". */
    private void zoomButtons(int y, int h, boolean all) {
        int rx = width - 8 - 16;
        if (!all) {
            transport(rx, y, 16, h, Icon.FIT, tc("justreplay.editor.zoom_fit.tip"), timeline::fit);
            return;
        }
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

    /** Tabs of the current mode, in order. */
    private static List<SidebarTab> visibleTabs() {
        List<SidebarTab> list = new ArrayList<>();
        EditorMode mode = mode();
        for (SidebarTab tab : TABS) {
            if (tab.shownIn(mode)) {
                list.add(tab);
            }
        }
        return list;
    }

    /** The open tab of the current mode. */
    private static SidebarTab currentTab() {
        List<SidebarTab> list = visibleTabs();
        String id = TAB.get(mode());
        for (SidebarTab tab : list) {
            if (tab.id().equals(id)) {
                return tab;
            }
        }
        return list.get(0);
    }

    private static boolean isTab(String id) {
        return currentTab().id().equals(id);
    }

    /** Id of the tab listing the actors in the current mode. */
    private static String actorTab() {
        return simple() ? "characters" : "actors";
    }

    private void initSidebar() {
        int contentW = sideW - 10;
        List<SidebarTab> list = visibleTabs();
        SidebarTab current = currentTab();
        List<String> ids = new ArrayList<>();
        Map<String, SidebarTab> byId = new HashMap<>();
        for (SidebarTab tab : list) {
            ids.add(tab.id());
            byId.put(tab.id(), tab);
        }
        tabs = addRenderableWidget(new Segmented<>(ids, current.id(), id -> tc(byId.get(id).translationKey()), id -> byId.get(id).icon(),
                this::selectTab));
        tabs.slideFrom(previousTab);
        previousTab = current.id();
        tabs.setWidth(contentW);
        tabs.setHeight(simple() ? (compact ? 18 : 24) : Theme.ROW);
        int areaTop = sideTop + 9 + tabs.getHeight();
        side = new ScrollArea(this, 0, areaTop, contentW + 2, sideBottom - areaTop - 4);
        Form form = new Form(side, contentW - 5);
        current.builder().accept(this, form);
        form.end();
        side.setScroll(TAB_SCROLL.getOrDefault(mode() + ":" + current.id(), 0.0F), true);
        if (revealSelection && current.id().equals(actorTab()) && selectedRowTop >= 0) {
            side.reveal(selectedRowTop, selectedRowTop + Theme.ROW);
        }
        revealSelection = false;
        placeSidebar();
    }

    /** Tab ids shown in the current mode (used by the self test to capture every panel). */
    public static List<String> tabIds() {
        return visibleTabs().stream().map(SidebarTab::id).toList();
    }

    public void showTabForTest(String id) {
        selectTab(id);
    }

    /** Selects an overlay and shows it in the Overlays tab. */
    public void openOverlay(String id) {
        OverlaysPanel.select(id);
        openTab("overlays");
    }

    /** Opens a sidebar tab of the current mode (and the sidebar itself). */
    public void openTab(String id) {
        sidebarOpen = true;
        if (!isTab(id)) {
            selectTab(id);
        } else {
            rebuildWidgets();
        }
    }

    public void setHelpForTest(boolean open) {
        if (open != helpOpen) {
            toggleHelp();
        }
    }

    private void selectTab(String id) {
        previousTab = currentTab().id();
        TAB.put(mode(), id);
        tabChangedAt = Anim.now();
        status = null;
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
        side.y = sideTop + 9 + tabs.getHeight();
        TAB_SCROLL.put(mode() + ":" + currentTab().id(), side.scrollTarget());
    }

    private int sidebarX() {
        return Math.round(4 - (1.0F - sidebar.get()) * (sideW + 8));
    }

    /** A main button of the simple mode: taller, label and icon. */
    private GlassButton big(Component label, Icon icon, Component tip, GlassButton.OnPress action) {
        GlassButton b = new GlassButton(label, action).icon(icon).tip(tip);
        b.setHeight(bigH);
        return b;
    }

    /** Two big buttons side by side. */
    private void bigPair(Form f, GlassButton left, GlassButton right) {
        f.put(left, 0, 2);
        f.put(right, 1, 2);
        f.next(bigH);
    }

    /** Shows a short feedback line in the open tab. */
    private void setStatus(Component text, int color) {
        status = text;
        statusColor = color;
        if (minecraft != null && minecraft.screen == this) {
            rebuildWidgets();
        }
    }

    // ---------------------------------------------------------------- simple mode: camera

    /** The path camera of the simple mode: the selected camera when it is a path, else the first path, or null. */
    private ReplayProject.Camera pathCamera() {
        ReplayProject project = session.project();
        ReplayProject.Camera selected = project.selected();
        if (selected != null && selected.isPath()) {
            return selected;
        }
        for (ReplayProject.Camera camera : project.cameras) {
            if (camera.isPath()) {
                return camera;
            }
        }
        return null;
    }

    /** Makes a path camera the selected one, creating it when the project has none. */
    private ReplayProject.Camera usePathCamera() {
        ReplayProject project = session.project();
        ReplayProject.Camera camera = pathCamera();
        if (camera == null) {
            camera = new ReplayProject.Camera(project.nextCameraName(), ReplayProject.Camera.PATH);
            project.cameras.add(camera);
        }
        if (camera != project.selected()) {
            project.select(camera);
            cameras.selectedKey = null;
        }
        return camera;
    }

    private void addSimplePoint() {
        usePathCamera();
        cameras.addKeyframe();
        ReplayProject.Camera camera = pathCamera();
        int count = camera == null ? 0 : camera.keyframes.size();
        setStatus(count < 2 ? tc("justreplay.editor.simple.point.first") : Component.translatable("justreplay.editor.simple.point.added", count),
                Theme.SUCCESS);
    }

    private void buildSimpleCamera(Form f) {
        ReplayProject.Camera camera = pathCamera();
        f.header(tc("justreplay.editor.simple.camera"), Icon.CAMERA);
        // In a short window the main button comes before the explanation, so it shows without scrolling.
        if (!compact) {
            f.paragraph(tc("justreplay.editor.simple.camera.help"), Theme.TEXT_MUTED);
        }
        f.full(big(tc("justreplay.editor.simple.point"), Icon.KEY, tc("justreplay.editor.simple.point.tip"), b -> addSimplePoint())
                .style(GlassButton.Style.PRIMARY));
        f.text(() -> status, statusColor);
        if (compact) {
            f.paragraph(tc("justreplay.editor.simple.camera.help"), Theme.TEXT_MUTED);
        }
        f.full(new GlassSwitch(tc("justreplay.editor.simple.play_path"), session::isCameraPathActive,
                () -> session.setCameraPath(!session.isCameraPathActive())).icon(Icon.EYE).tip(tc("justreplay.editor.simple.play_path.tip")));
        f.full(new GlassSwitch(tc("justreplay.editor.simple.show_path"), () -> session.project().showPath, () -> {
            session.project().showPath = !session.project().showPath;
            session.saveProject();
        }).icon(Icon.PATH).tip(tc("justreplay.editor.simple.show_path.tip")));

        f.header(tc("justreplay.editor.simple.motion"), Icon.ROTATE);
        f.caption(tc("justreplay.editor.simple.smoothness"), 0, 1);
        f.next(9);
        String curve = camera == null ? CameraPath.Curve.CATMULL_ROM.name() : camera.curve;
        f.full(new Segmented<>(List.of(CameraPath.Curve.CATMULL_ROM.name(), CameraPath.Curve.BEZIER.name()), curve,
                v -> tc(v.equals(CameraPath.Curve.BEZIER.name()) ? "justreplay.editor.simple.smooth.bezier" : "justreplay.editor.simple.smooth.curve"),
                null, v -> {
            usePathCamera().curve = v;
            session.saveProject();
        }).tip(tc("justreplay.editor.simple.smoothness.tip")));
        f.full(new GlassSwitch(tc("justreplay.editor.simple.constant"), () -> {
            ReplayProject.Camera c = pathCamera();
            return c == null || CameraPath.Timing.CONSTANT_SPEED.name().equals(c.timing);
        }, () -> {
            ReplayProject.Camera c = usePathCamera();
            c.timing = CameraPath.Timing.CONSTANT_SPEED.name().equals(c.timing) ? CameraPath.Timing.KEYFRAMES.name()
                    : CameraPath.Timing.CONSTANT_SPEED.name();
            session.saveProject();
        }).icon(Icon.CLOCK).tip(tc("justreplay.editor.simple.constant.tip")));

        f.header(tc("justreplay.editor.simple.points"), Icon.KEY);
        if (camera == null || camera.keyframes.isEmpty()) {
            f.paragraph(tc("justreplay.editor.simple.points.none"), Theme.TEXT_MUTED);
        } else {
            for (int i = 0; i < camera.keyframes.size(); i++) {
                ReplayProject.CameraKeyframe key = camera.keyframes.get(i);
                f.full(new ListRow(Component.translatable("justreplay.editor.simple.point.row", i + 1), b -> {
                    cameras.selectedKey = key;
                    cameras.gotoSelected();
                }).detail(Timeline.formatTicks(key.tick)).icon(Icon.KEY).selected(() -> cameras.selectedKey == key)
                        .action(() -> Icon.TRASH, tc("justreplay.editor.simple.point.delete"), () -> {
                            camera.keyframes.remove(key);
                            if (cameras.selectedKey == key) {
                                cameras.selectedKey = null;
                            }
                            session.saveProject();
                            rebuildWidgets();
                        }).tip(tc("justreplay.editor.simple.point.row.tip")));
            }
            f.full(new GlassButton(tc("justreplay.editor.simple.points.clear"), b -> confirm(tc("justreplay.editor.clearpath.confirm"), () -> {
                camera.keyframes.clear();
                cameras.selectedKey = null;
                session.saveProject();
            })).icon(Icon.TRASH).style(GlassButton.Style.DANGER));
        }
        if (session.project().cuts.size() > 1 || session.project().cameras.size() > 1) {
            f.space(2);
            f.paragraph(tc("justreplay.editor.simple.more_cameras"), Theme.WARNING);
        }
        buildMarkers(f);
    }

    // ---------------------------------------------------------------- simple mode: characters

    private void buildSimpleActors(Form f) {
        f.header(tc("justreplay.editor.simple.characters"), Icon.PERSON);
        f.caption(tc("justreplay.editor.simple.add"), 0, 1);
        f.next(9);
        GlassButton player = big(tc("justreplay.editor.simple.add.player"), Icon.PERSON, tc("justreplay.editor.simple.add.player.tip"),
                b -> addPlayerActor());
        GlassButton mob = big(tc("justreplay.editor.simple.add.mob"), Icon.PUZZLE, tc("justreplay.editor.simple.add.mob.tip"), this::pickMob);
        bigPair(f, player, mob);
        actorRows(f);
        if (selectedActor == null) {
            if (!session.project().actors.isEmpty()) {
                f.paragraph(tc("justreplay.editor.simple.select"), Theme.TEXT_MUTED);
            }
            return;
        }
        ReplayProject.ActorData actor = selectedActor;
        f.header(Component.literal(displayName(actor)), Icon.EDIT);
        f.text(() -> status, statusColor);
        f.caption(tc("justreplay.editor.simple.where"), 0, 1);
        f.next(9);
        f.full(big(tc("justreplay.editor.simple.place"), Icon.PIN, tc("justreplay.editor.simple.place.tip"),
                b -> PlacementTool.start(this, session, actor, ActorPlacement.Kind.PLACE, false)).style(GlassButton.Style.PRIMARY));
        bigPair(f, big(tc("justreplay.editor.simple.crosshair"), Icon.TARGET, tc("justreplay.editor.simple.crosshair.tip"), b -> moveToCrosshair(actor)),
                big(tc("justreplay.editor.simple.face"), Icon.CAMERA, tc("justreplay.editor.simple.face.tip"), b -> faceCamera(actor)));
        f.caption(tc("justreplay.editor.simple.does"), 0, 1);
        f.next(9);
        bigPair(f, big(tc("justreplay.editor.simple.walk"), Icon.PATH, tc("justreplay.editor.simple.walk.tip"),
                        b -> PlacementTool.start(this, session, actor, ActorPlacement.Kind.WALK_TO, false)),
                followButton(actor));
        bigPair(f, big(tc("justreplay.editor.simple.sit"), Icon.ARROW_DOWN, tc("justreplay.editor.simple.sit.tip"),
                        b -> PlacementTool.start(this, session, actor, ActorPlacement.Kind.SIT, false)),
                big(tc("justreplay.editor.simple.bed"), Icon.CLOCK, tc("justreplay.editor.simple.bed.tip"),
                        b -> PlacementTool.start(this, session, actor, ActorPlacement.Kind.BED, false)));
        bigPair(f, big(tc("justreplay.editor.simple.wave"), Icon.PERSON, tc("justreplay.editor.simple.wave.tip"),
                        b -> quickAction(actor, ActionType.WAVE, 60, "")),
                big(tc("justreplay.editor.simple.stop"), Icon.STOP, tc("justreplay.editor.simple.stop.tip"),
                        b -> quickAction(actor, ActionType.IDLE, 0, "")));
        f.space(4);
        f.full(new GlassButton(tc("justreplay.editor.simple.remove"), b -> confirm(tc("justreplay.editor.actor.delete.confirm"), this::deleteActor))
                .icon(Icon.TRASH).style(GlassButton.Style.DANGER));
        if (!actor.actions.isEmpty()) {
            f.header(tc("justreplay.editor.simple.timeline"), Icon.BOLT);
            for (ReplayProject.ActorAction action : actor.actions) {
                f.full(new ListRow(Component.literal(actionLabel(action)), b -> {
                    selectedAction = action;
                    selectedActorKey = null;
                    session.playback().seek(action.tick);
                }).icon(Icon.BOLT).selected(() -> action == selectedAction)
                        .action(() -> Icon.TRASH, tc("justreplay.editor.delete"), () -> {
                            actor.actions.remove(action);
                            if (selectedAction == action) {
                                selectedAction = null;
                            }
                            session.saveProject();
                            rebuildWidgets();
                        }));
            }
        }
    }

    /** Follow: picks who to follow in a list (the recording player or another named actor). */
    private GlassButton followButton(ReplayProject.ActorData actor) {
        return big(tc("justreplay.editor.simple.follow"), Icon.TARGET, tc("justreplay.editor.simple.follow.tip"), b -> {
            List<String> labels = new ArrayList<>();
            List<String> targets = new ArrayList<>();
            labels.add(tr("justreplay.editor.simple.follow.recorder"));
            targets.add("recorder");
            for (ReplayProject.ActorData other : session.project().actors) {
                if (other != actor && !other.name.isBlank()) {
                    labels.add(other.name);
                    targets.add(other.name);
                }
            }
            openPopup(new ListPopup(b.getX(), b.getY(), b.getWidth(), b.getHeight(), width, height, font, labels, -1, i -> {
                closePopup();
                quickAction(actor, ActionType.FOLLOW, 0, targets.get(i) + "|2.5");
            }));
        });
    }

    private void pickMob(net.minecraft.client.gui.components.Button b) {
        List<String> labels = new ArrayList<>();
        for (String id : COMMON_MOBS) {
            labels.add(EntityType.byString(id).map(t -> t.getDescription().getString()).orElse(id));
        }
        labels.add(tr("justreplay.editor.simple.add.mob.other"));
        openPopup(new ListPopup(b.getX(), b.getY(), b.getWidth(), b.getHeight(), width, height, font, labels, -1, i -> {
            closePopup();
            if (i < COMMON_MOBS.length) {
                ReplayProject.ActorData actor = new ReplayProject.ActorData();
                actor.type = COMMON_MOBS[i];
                addActor(actor);
            } else {
                addEntityActor();
            }
        }));
    }

    /** Adds an action at the play head, replacing the same action there. */
    private void quickAction(ReplayProject.ActorData actor, ActionType type, int duration, String param) {
        int tick = session.playback().cursor();
        ReplayProject.ActorAction action = ActorPlacement.action(actor, tick, type, duration, param);
        selectedAction = action;
        selectedActorKey = null;
        session.saveProject();
        setStatus(Component.translatable("justreplay.editor.simple.added", tr(type.translationKey()), Timeline.formatTicks(tick)), Theme.SUCCESS);
    }

    /** Keyframe at the play head where the camera looks, keeping the actor's facing. */
    void moveToCrosshair(ReplayProject.ActorData actor) {
        int tick = session.playback().cursor();
        double[] state = ActorPlacement.stateAt(actor, tick, session.actors().entityOf(actor));
        BlockHitResult hit = ActorPlacement.crosshair(false);
        ActorPlacement.Spot spot = ActorPlacement.ground(hit, state != null ? (float) state[3] : 0.0F, false);
        if (!spot.valid()) {
            setStatus(tc("justreplay.editor.simple.crosshair.none"), Theme.WARNING);
            return;
        }
        selectedActorKey = ActorPlacement.keyframe(actor, tick, spot.x(), spot.y(), spot.z(), spot.yaw());
        session.saveProject();
        setStatus(Component.translatable("justreplay.editor.simple.moved", Timeline.formatTicks(tick)), Theme.SUCCESS);
    }

    /** Keyframe at the play head turning the actor towards the camera. */
    void faceCamera(ReplayProject.ActorData actor) {
        int tick = session.playback().cursor();
        Entity entity = session.actors().entityOf(actor);
        double[] state = ActorPlacement.stateAt(actor, tick, entity);
        if (state == null) {
            return;
        }
        Vec3 at = entity != null ? entity.position() : new Vec3(state[0], state[1], state[2]);
        float yaw = ActorPlacement.yawTowards(at, minecraft.gameRenderer.getMainCamera().getPosition());
        selectedActorKey = ActorPlacement.keyframe(actor, tick, state[0], state[1], state[2], yaw);
        session.saveProject();
        setStatus(Component.translatable("justreplay.editor.simple.turned", Timeline.formatTicks(tick)), Theme.SUCCESS);
    }

    /** Called by the placement tool before the editor comes back. */
    void afterPlacement(ReplayProject.ActorData actor) {
        if (actor != null) {
            selectedActor = actor;
            selectedActorKey = null;
            selectedAction = null;
            revealSelection = true;
            TAB.put(mode(), actorTab());
            setStatus(Component.translatable("justreplay.editor.simple.placed", Timeline.formatTicks(session.playback().cursor())), Theme.SUCCESS);
        } else {
            status = null;
        }
    }

    // ---------------------------------------------------------------- tabs

    private void actorRows(Form f) {
        List<ReplayProject.ActorData> actors = session.project().actors;
        if (actors.isEmpty()) {
            f.paragraph(tc(simple() ? "justreplay.editor.simple.none" : "justreplay.editor.actor.none"), Theme.TEXT_MUTED);
        }
        int revealTop = -1;
        for (ReplayProject.ActorData actor : actors) {
            if (actor == selectedActor) {
                revealTop = f.y();
            }
            ListRow row = new ListRow(Component.literal(displayName(actor)), b -> selectActor(actor));
            row.detail(actorKind(actor)).action(() -> actor.visible ? Icon.EYE : Icon.EYE_OFF, tc("justreplay.editor.actor.visible.tip"), () -> {
                actor.visible = !actor.visible;
                session.actors().refresh(actor);
                session.saveProject();
            }).icon(actor.isPlayer() ? Icon.PERSON : actor.isModel() ? Icon.CUBE : Icon.PUZZLE).selected(() -> actor == selectedActor);
            f.full(row);
        }
        selectedRowTop = revealTop;
    }

    private void selectActor(ReplayProject.ActorData actor) {
        selectedActor = actor;
        selectedActorKey = null;
        selectedAction = null;
        status = null;
        rebuildWidgets();
    }

    private static String displayName(ReplayProject.ActorData actor) {
        if (!actor.name.isBlank()) {
            return actor.name;
        }
        return EntityType.byString(actor.type).map(t -> t.getDescription().getString()).orElse(actor.type);
    }

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
        actorRows(f);
        if (selectedActor == null) {
            if (!session.project().actors.isEmpty()) {
                f.paragraph(tc("justreplay.editor.actor.select"), Theme.TEXT_MUTED);
            }
            return;
        }
        ReplayProject.ActorData actor = selectedActor;
        f.header(Component.literal(displayName(actor)), Icon.EDIT);
        f.put(new GlassButton(tc("justreplay.editor.actor.keyframe"), b -> keyframeActor()).icon(Icon.KEY)
                .tip(tc("justreplay.editor.actor.keyframe.tip")), 0, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.edit"), b -> minecraft.setScreen(new ActorScreen(this, session, actor)))
                .icon(Icon.EDIT).tip(tc("justreplay.editor.actor.edit.tip")), 1, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.delete"), b -> confirm(tc("justreplay.editor.actor.delete.confirm"), this::deleteActor))
                .icon(Icon.TRASH).style(GlassButton.Style.DANGER), 2, 3);
        f.next();
        f.text(() -> selectedActorKey == null ? null
                : Component.translatable("justreplay.editor.actor.key_info", Timeline.formatTicks(selectedActorKey.tick)), Timeline.ACTOR_COLOR);
        f.header(tc("justreplay.editor.actor.placement"), Icon.PIN);
        f.put(new GlassButton(tc("justreplay.editor.actor.place"), b -> PlacementTool.start(this, session, actor, ActorPlacement.Kind.PLACE, false))
                .icon(Icon.PIN).tip(tc("justreplay.editor.simple.place.tip")), 0, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.crosshair"), b -> moveToCrosshair(actor)).icon(Icon.TARGET)
                .tip(tc("justreplay.editor.simple.crosshair.tip")), 1, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.face"), b -> faceCamera(actor)).icon(Icon.CAMERA)
                .tip(tc("justreplay.editor.simple.face.tip")), 2, 3);
        f.next();
        f.put(new GlassButton(tc("justreplay.editor.actor.sit"), b -> PlacementTool.start(this, session, actor, ActorPlacement.Kind.SIT, false))
                .icon(Icon.ARROW_DOWN).tip(tc("justreplay.editor.simple.sit.tip")), 0, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.bed"), b -> PlacementTool.start(this, session, actor, ActorPlacement.Kind.BED, false))
                .icon(Icon.CLOCK).tip(tc("justreplay.editor.simple.bed.tip")), 1, 3);
        f.put(new GlassButton(tc("justreplay.editor.actor.walk"), b -> PlacementTool.start(this, session, actor, ActorPlacement.Kind.WALK_TO, false))
                .icon(Icon.PATH).tip(tc("justreplay.editor.simple.walk.tip")), 2, 3);
        f.next();
        f.text(() -> status, statusColor);
        f.header(tc("justreplay.editor.actor.actions"), Icon.BOLT);
        f.full(new Dropdown<>(this, tc("justreplay.actor.action.type"), List.of(ActionType.values()), actionType,
                t -> Component.translatable(t.translationKey()), v -> actionType = v).icon(Icon.BOLT)
                .tip(tc("justreplay.editor.actor.action.tip")));
        f.put(new GlassButton(tc("justreplay.editor.actor.action.add"), b -> addAction()).icon(Icon.PLUS), 0, 2);
        f.put(new GlassButton(tc("justreplay.editor.actor.action.delete"), b -> deleteAction()).icon(Icon.TRASH)
                .style(GlassButton.Style.DANGER), 1, 2);
        f.next();
        if (actor.actions.isEmpty()) {
            f.paragraph(tc("justreplay.actor.action.none"), Theme.TEXT_MUTED);
        }
        for (ReplayProject.ActorAction action : actor.actions) {
            f.full(new ListRow(Component.literal(actionLabel(action)), b -> {
                selectedAction = action;
                selectedActorKey = null;
                cameras.selectedKey = null;
                session.playback().seek(action.tick);
                rebuildWidgets();
            }).detail(action.actionType().shortLabel).icon(Icon.BOLT).selected(() -> action == selectedAction));
        }
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

    private void buildMarkers(Form f) {
        f.header(tc("justreplay.editor.markers"), Icon.FLAG);
        f.paragraph(tc("justreplay.editor.simple.markers.help"), Theme.TEXT_MUTED);
        f.full(big(tc("justreplay.editor.simple.marker"), Icon.FLAG, tc("justreplay.editor.marker.tip"), b -> addMarker())
                .style(GlassButton.Style.PRIMARY));
        markerRows(f);
    }

    private void markerRows(Form f) {
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
    }

    private void buildScene(Form f) {
        f.header(tc("justreplay.editor.markers"), Icon.FLAG);
        f.full(new GlassButton(tc("justreplay.editor.marker.add"), b -> addMarker()).icon(Icon.PLUS).tip(tc("justreplay.editor.marker.tip")));
        markerRows(f);
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

    private void buildExportTab(Form f) {
        if (simple()) {
            buildSimpleExport(f);
        } else {
            buildExport(f);
        }
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

    private void buildSimpleExport(Form f) {
        f.header(tc("justreplay.editor.export.section"), Icon.FILM);
        // In a short window the main button comes before the explanation, so it shows without scrolling.
        if (!compact) {
            f.paragraph(tc("justreplay.editor.simple.export.help"), Theme.TEXT_MUTED);
        }
        f.text(this::exportRangeLine, Theme.TEXT);
        f.text(() -> Component.translatable("justreplay.editor.simple.export.settings", JRConfig.EXPORT_RESOLUTION.get().getTranslatedName(),
                JRConfig.EXPORT_FPS.get()), Theme.TEXT_DIM);
        f.space(3);
        f.full(big(tc("justreplay.editor.simple.export"), Icon.FILM, tc("justreplay.editor.simple.export.tip"), b -> oneClickExport())
                .style(GlassButton.Style.PRIMARY));
        f.text(() -> status, statusColor);
        if (compact) {
            f.paragraph(tc("justreplay.editor.simple.export.help"), Theme.TEXT_MUTED);
        }
        f.full(new GlassButton(tc("justreplay.export.folder"), b -> Util.getPlatform().openPath(ReplayStorage.exportRoot())).icon(Icon.FOLDER));
        f.full(new GlassButton(tc("justreplay.editor.simple.export.more"), b -> openExport()).icon(Icon.GEAR)
                .tip(tc("justreplay.editor.export.tip")));
    }

    /** Exports with the saved settings, named after the replay; tells what is missing instead when it cannot. */
    private void oneClickExport() {
        int[] range = session.project().exportRange(session.playback().duration());
        if (range == null) {
            TAB.put(mode(), "export");
            setStatus(tc("justreplay.editor.simple.export.missing"), Theme.WARNING);
            return;
        }
        String name = session.meta().name.replaceAll("[^A-Za-z0-9._-]", "_");
        Component error = VideoExporter.start(session, range[0], range[1], name);
        if (error != null) {
            TAB.put(mode(), "export");
            setStatus(error, Theme.DANGER);
        }
    }

    private Component exportRangeLine() {
        int[] range = session.project().exportRange(session.playback().duration());
        if (range == null) {
            return tc(simple() ? "justreplay.editor.simple.export.nothing" : "justreplay.editor.export.nothing");
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
            if (isTab("camera")) {
                rebuildWidgets();
            }
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
            if (isTab("scene") || isTab("camera")) {
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

    /** Adds an actor and lets the user place it in the world; cancelling the placement removes it again. */
    private void addActor(ReplayProject.ActorData actor) {
        session.project().actors.add(actor);
        selectedActor = actor;
        selectedActorKey = null;
        selectedAction = null;
        revealSelection = true;
        session.saveProject();
        PlacementTool.start(this, session, actor, ActorPlacement.Kind.PLACE, true);
    }

    private void keyframeActor() {
        LocalPlayer p = minecraft.player;
        if (selectedActor == null || p == null) {
            return;
        }
        int tick = session.playback().cursor();
        selectedActorKey = ActorPlacement.keyframe(selectedActor, tick, p.getX(), groundBelow(p), p.getZ(), p.getYRot());
        cameras.selectedKey = null;
        session.saveProject();
    }

    /** Feet height of an actor placed under the camera: the ground within 4 blocks, else the camera height. */
    private double groundBelow(LocalPlayer p) {
        Vec3 from = p.position();
        BlockHitResult hit = p.level().clip(new net.minecraft.world.level.ClipContext(from, from.subtract(0, 4, 0),
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
        status = null;
        session.saveProject();
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- picking in the world

    /** True over the top bar, the open sidebar or the dock. */
    private boolean overPanels(double mx, double my) {
        if (my < 4 + TOP_H + 2 || my >= dockTop - 2) {
            return true;
        }
        return sidebar.get() > 0.02F && mx < sidebarX() + sideW + 2 && my >= sideTop - 2 && my <= sideBottom + 2;
    }

    /** Ray through the world under a point of the screen: {from, to}. */
    private Vec3[] mouseRay(double mx, double my) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Double override = CameraController.fov();
        double fov = override != null ? override : minecraft.options.fov().get();
        double tan = Math.tan(Math.toRadians(fov) / 2.0);
        double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
        double nx = mx / width * 2.0 - 1.0;
        double ny = 1.0 - my / height * 2.0;
        Vec3 dir = new Vec3(camera.getLookVector()).add(new Vec3(camera.getUpVector()).scale(ny * tan))
                .add(new Vec3(camera.getLeftVector()).scale(-nx * tan * aspect));
        Vec3 from = camera.getPosition();
        return new Vec3[]{from, from.add(dir.normalize().scale(ActorPlacement.REACH))};
    }

    /** Selects the actor under the mouse in the world; true when one was hit. */
    private boolean pickActor(double mx, double my) {
        Vec3[] ray = mouseRay(mx, my);
        ReplayProject.ActorData actor = session.actors().pick(ray[0], ray[1]);
        if (actor == null) {
            return false;
        }
        selectedActor = actor;
        selectedActorKey = null;
        selectedAction = null;
        revealSelection = true;
        status = null;
        if (!sidebarOpen) {
            sidebarOpen = true;
        }
        previousTab = currentTab().id();
        TAB.put(mode(), actorTab());
        rebuildWidgets();
        return true;
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
            if (timeline.laneAt(my) == EditorTimeline.Lane.OVERLAYS && overlaysPanel.mouseClicked(timeline, mx, my)) {
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
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        // A click on the world itself selects the actor under the mouse.
        return button == 0 && !overPanels(mx, my) && pickActor(mx, my);
    }

    /** Drops the audio clip selection when another lane is clicked; never consumes the click. */
    private boolean clearAudioSelection() {
        if (audioLanes.selectedClip != null) {
            audioLanes.selectedClip = null;
            if (isTab("audio")) {
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
        if (timeline.barDragged(mx) || audioLanes.mouseDragged(timeline, mx, my) || overlaysPanel.mouseDragged(timeline, mx)) {
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
        if (timeline.barReleased() || audioLanes.mouseReleased() || overlaysPanel.mouseReleased()) {
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
        if (helpOpen) {
            helpScroll.to(Mth.clamp(helpScroll.target() - (float) sy * 24.0F, 0.0F, helpMax));
            return true;
        }
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
        if (hasControlDown() && (keyCode == GLFW.GLFW_KEY_Z || keyCode == GLFW.GLFW_KEY_Y)) {
            boolean redo = keyCode == GLFW.GLFW_KEY_Y || hasShiftDown();
            boolean done = redo ? session.redo() : session.undo();
            if (done) {
                // Selections point at the previous objects: reopen the editor on the restored project.
                minecraft.setScreen(new EditorScreen(session));
            }
            return true;
        }
        if (!simple() && audioLanes.keyPressed(keyCode)) {
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
                if (simple()) {
                    addSimplePoint();
                } else {
                    cameras.addKeyframe();
                }
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
        if (cut >= 0 && !simple()) {
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
        timeline.begin(playback.duration(), playback.cursor(), !playback.isPaused() && dragging == null && !scrubbing && !audioLanes.dragging()
                && !overlaysPanel.dragging());
    }

    @Override
    protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTopBar(g, mouseX, mouseY);
        Playback playback = session.playback();
        if (playback.cursor() != shownCursor) {
            shownCursor = playback.cursor();
            timeText = Timeline.formatTicks(playback.cursor()) + " / " + Timeline.formatTicks(playback.duration());
        }
        g.drawString(font, timeText, timeX, Paint.textY(dockTop + 4, rowH), Theme.TEXT, false);
        timeline.render(g, font, session, cameras.selectedKey, cameras.selectedCut, selectedActor, selectedActorKey, selectedAction,
                ghostTick, mouseX, mouseY);
        if (timeline.contains(mouseX, mouseY) && dragging == null && !scrubbing && !helpOpen && !audioLanes.dragging() && !overlaysPanel.dragging()) {
            renderTimelineTooltip(g, mouseX, mouseY);
        }
        if (helpOpen) {
            renderHelp(g);
        }
    }

    private void renderTopBar(GuiGraphics g, int mouseX, int mouseY) {
        int y = Paint.textY(4, TOP_H);
        Paint.iconOnText(g, Icon.FILM, 29, y, 9, Theme.ACCENT);
        int x = 42;
        float flash = 1.0F - Anim.progress(savedFlashAt, 1600);
        Component state = savedMtime < 0 ? tc("justreplay.editor.unsaved") : tc("justreplay.editor.saved");
        int stateW = font.width(state) + 12;
        String name = Paint.ellipsize(font, session.meta().name, Math.max(20, nameRight - x - stateW - 10));
        g.drawString(font, name, x, y, Theme.TEXT, false);
        int sx = x + font.width(name) + 10;
        if (sx + stateW <= nameRight) {
            int color = savedMtime < 0 ? Theme.TEXT_MUTED : Theme.mix(0xFF7FA894, Theme.SUCCESS, flash);
            Paint.iconOnText(g, savedMtime < 0 ? Icon.DOT : Icon.CHECK, sx, y, 8, color);
            g.drawString(font, state, sx + 11, y, Theme.text(Theme.mix(Theme.TEXT_MUTED, Theme.TEXT_DIM, flash), 1.0F), false);
            if (mouseX >= sx && mouseX < sx + stateW && mouseY >= 4 && mouseY < 4 + TOP_H && savedMtime >= 0) {
                savedTip = new SimpleDateFormat("HH:mm:ss").format(new Date(savedMtime));
                deferTooltip(Component.translatable("justreplay.editor.saved.tip", savedTip), mouseX, mouseY);
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
                        deferTooltip(Component.literal(name + "  " + Timeline.formatTicks(cut.tick)
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
                        deferTooltip(Component.translatable("justreplay.editor.camera.key_tip", Timeline.formatTicks(k.tick),
                                Math.round(k.roll), Math.round(k.fov)), mouseX, mouseY);
                        return;
                    }
                }
            }
            case ACTOR -> {
                if (selectedActor == null) {
                    deferTooltip(tc("justreplay.editor.actor.lane.tip"), mouseX, mouseY);
                    return;
                }
                for (ReplayProject.ActorAction action : selectedActor.actions) {
                    if (Math.abs(timeline.xOf(action.tick) - mouseX) <= 2) {
                        deferTooltip(Component.literal(actionLabel(action)), mouseX, mouseY);
                        return;
                    }
                }
                for (ReplayProject.ActorKeyframe k : selectedActor.keyframes) {
                    if (Math.abs(timeline.xOf(k.tick) - mouseX) <= 4) {
                        deferTooltip(Component.translatable("justreplay.editor.actor.key_info", Timeline.formatTicks(k.tick)), mouseX, mouseY);
                        return;
                    }
                }
            }
            case OVERLAYS -> deferTooltip(overlaysPanel.tooltip(timeline, mouseX, mouseY), mouseX, mouseY);
            case AUDIO -> {
                Component tip = audioLanes.tooltip(timeline, mouseX, mouseY);
                if (tip != null) {
                    deferTooltip(tip, mouseX, mouseY);
                }
            }
            case MARKERS -> {
                for (ReplayProject.Marker marker : project.markers) {
                    if (Math.abs(timeline.xOf(marker.tick) - mouseX) <= 3) {
                        deferTooltip(Component.literal(marker.name + "  " + Timeline.formatTicks(marker.tick)), mouseX, mouseY);
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
            helpLines.clear();
            helpScroll.snap(0.0F);
        }
    }

    /**
     * Short name of a key, from its key code so it does not depend on the keyboard layout where that only confuses
     * (the digit row of an AZERTY keyboard is named after its symbols): arrows, Ctrl, Shift, Alt, Esc, digits.
     */
    private static String keyName(int glfw) {
        switch (glfw) {
            case GLFW.GLFW_KEY_LEFT -> {
                return "←";
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                return "→";
            }
            case GLFW.GLFW_KEY_UP -> {
                return "↑";
            }
            case GLFW.GLFW_KEY_DOWN -> {
                return "↓";
            }
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> {
                return "Ctrl";
            }
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> {
                return "Shift";
            }
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> {
                return "Alt";
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                return "Esc";
            }
            default -> {
            }
        }
        if (glfw >= GLFW.GLFW_KEY_0 && glfw <= GLFW.GLFW_KEY_9) {
            return Integer.toString(glfw - GLFW.GLFW_KEY_0);
        }
        if (glfw >= GLFW.GLFW_KEY_KP_0 && glfw <= GLFW.GLFW_KEY_KP_9) {
            return "Num" + (glfw - GLFW.GLFW_KEY_KP_0);
        }
        return InputConstants.Type.KEYSYM.getOrCreate(glfw).getDisplayName().getString();
    }

    /** Short name of a binding, with its modifier ("Ctrl+K"). */
    private static String key(KeyMapping mapping) {
        if (mapping.isUnbound()) {
            return "-";
        }
        InputConstants.Key key = mapping.getKey();
        String name = key.getType() == InputConstants.Type.KEYSYM ? keyName(key.getValue()) : key.getDisplayName().getString();
        return switch (mapping.getKeyModifier()) {
            case CONTROL -> "Ctrl+" + name;
            case SHIFT -> "Shift+" + name;
            case ALT -> "Alt+" + name;
            default -> name;
        };
    }

    /** A row of bindings shown as a range ("1–9") when they are consecutive keys, else its first and last key. */
    private static String keyRange(KeyMapping[] mappings) {
        KeyMapping first = mappings[0];
        KeyMapping last = mappings[mappings.length - 1];
        boolean consecutive = !first.isUnbound() && first.getKey().getType() == InputConstants.Type.KEYSYM;
        for (int i = 1; i < mappings.length && consecutive; i++) {
            InputConstants.Key key = mappings[i].getKey();
            consecutive = !mappings[i].isUnbound() && key.getType() == InputConstants.Type.KEYSYM
                    && key.getValue() == first.getKey().getValue() + i && mappings[i].getKeyModifier() == first.getKeyModifier();
        }
        if (consecutive) {
            return key(first) + "–" + keyName(last.getKey().getValue());
        }
        return key(first) + " … " + key(last);
    }

    /** Shortcut list built from the real bindings: {keys, action} rows, {null, title} for a section. */
    private List<String[]> buildHelp() {
        String wheel = tr("justreplay.help.mouse.wheel");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{null, tr("justreplay.help.editor")});
        rows.add(new String[]{keyName(GLFW.GLFW_KEY_SPACE), tr("justreplay.help.play")});
        rows.add(new String[]{keyName(GLFW.GLFW_KEY_LEFT) + " / " + keyName(GLFW.GLFW_KEY_RIGHT),
                Component.translatable("justreplay.help.seek", stepSeconds()).getString()});
        rows.add(new String[]{keyName(GLFW.GLFW_KEY_DELETE), tr("justreplay.help.delete")});
        rows.add(new String[]{keyName(GLFW.GLFW_KEY_K), tr("justreplay.help.keyframe")});
        if (!simple()) {
            rows.add(new String[]{keyName(GLFW.GLFW_KEY_S), tr("justreplay.help.audio_split")});
            rows.add(new String[]{"Ctrl+" + keyName(GLFW.GLFW_KEY_D), tr("justreplay.help.audio_duplicate")});
            rows.add(new String[]{keyRange(JRKeys.CUT_TO), tr("justreplay.help.cut")});
        }
        rows.add(new String[]{key(JRKeys.CAMERA_PATH), tr("justreplay.help.preview")});
        rows.add(new String[]{key(JRKeys.EDITOR) + " / " + keyName(GLFW.GLFW_KEY_ESCAPE), tr("justreplay.help.close")});
        rows.add(new String[]{keyName(GLFW.GLFW_KEY_F1) + " / ?", tr("justreplay.help.help")});
        rows.add(new String[]{tr("justreplay.help.mouse.click"), tr("justreplay.help.pick")});
        rows.add(new String[]{null, tr("justreplay.help.timeline")});
        rows.add(new String[]{tr("justreplay.help.mouse.click"), tr("justreplay.help.click")});
        rows.add(new String[]{tr("justreplay.help.mouse.drag"), tr("justreplay.help.drag")});
        rows.add(new String[]{"Ctrl+" + wheel, tr("justreplay.help.zoom")});
        rows.add(new String[]{wheel, tr("justreplay.help.scroll")});
        rows.add(new String[]{null, tr("justreplay.help.placing")});
        rows.add(new String[]{tr("justreplay.help.mouse.click"), tr("justreplay.help.place.confirm")});
        rows.add(new String[]{wheel, tr("justreplay.help.place.turn")});
        rows.add(new String[]{"Shift+" + wheel, tr("justreplay.help.place.fine")});
        rows.add(new String[]{"Ctrl", tr("justreplay.help.place.center")});
        rows.add(new String[]{keyName(GLFW.GLFW_KEY_ESCAPE), tr("justreplay.help.place.cancel")});
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

    /**
     * The shortcut sheet: two columns when the window is wide enough, one otherwise. Descriptions wrap instead of
     * being cut, and the sheet scrolls with the wheel when it is taller than the window.
     */
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
        int wide = Math.min(width - 16, 500);
        boolean twoColumns = split > 0 && (wide - 24) / 2 >= 170;
        int boxW = twoColumns ? wide : Math.min(width - 16, 340);
        int colW = twoColumns ? (boxW - 24) / 2 : boxW - 16;
        if (colW != helpLinesW) {
            helpLines.clear();
            helpLinesW = colW;
        }
        List<String[]> first = twoColumns ? help.subList(0, split) : help;
        List<String[]> second = twoColumns ? help.subList(split, help.size()) : List.of();
        int content = Math.max(columnHeight(first, colW), columnHeight(second, colW));
        int boxH = Math.min(height - 16, 28 + content + 6);
        helpMax = Math.max(0, content - (boxH - 28 - 4));
        float scroll = helpScroll.to(Mth.clamp(helpScroll.target(), 0.0F, helpMax));
        int bx = (width - boxW) / 2;
        int by = (height - boxH) / 2 + Math.round((1.0F - a) * 8);
        Paint.glass(g, bx, by, boxW, boxH, Theme.RADIUS_L);
        Paint.iconOnText(g, Icon.KEYBOARD, bx + 10, by + 10, 10, Theme.ACCENT);
        String title = tr("justreplay.help.title");
        Component hint = tc("justreplay.help.dismiss");
        int hintX = bx + boxW - 10 - font.width(hint);
        boolean showHint = bx + 25 + font.width(title) + 12 <= hintX;
        g.drawString(font, Paint.ellipsize(font, title, boxW - 35), bx + 25, by + 10, Theme.TEXT, false);
        if (showHint) {
            g.drawString(font, hint, hintX, by + 10, Theme.TEXT_MUTED, false);
        }
        int top = by + 28 - Math.round(scroll);
        g.enableScissor(bx, by + 24, bx + boxW, by + boxH - 4);
        renderHelpColumn(g, first, bx + 8, top, colW, by + 24, by + boxH - 4);
        if (twoColumns) {
            renderHelpColumn(g, second, bx + 16 + colW, top, colW, by + 24, by + boxH - 4);
        }
        g.disableScissor();
        if (helpMax > 0) {
            int trackY = by + 28;
            int trackH = boxH - 28 - 6;
            int thumb = Math.max(12, trackH * trackH / Math.max(1, content));
            float ty = trackY + (trackH - thumb) * (scroll / helpMax);
            Paint.rect(g, bx + boxW - 5, trackY, 2, trackH, 1, 0x1AFFFFFF);
            Paint.rect(g, bx + boxW - 5, ty, 2, thumb, 1, 0x80FFFFFF);
        }
        g.pose().popPose();
    }

    /** Width of the key column of a help column. */
    private int helpKeyWidth(List<String[]> rows, int w) {
        int keyW = 0;
        for (String[] row : rows) {
            if (row[0] != null) {
                keyW = Math.max(keyW, font.width(row[0]) + 8);
            }
        }
        return Math.min(keyW, w * 2 / 5);
    }

    /** Wrapped description of a help row, cached for the current column width. */
    private List<FormattedCharSequence> helpLines(String[] row, int width) {
        return helpLines.computeIfAbsent(row, r -> font.split(Component.literal(r[1]), Math.max(20, width)));
    }

    /** Height of a column of the help: section titles take 12 pixels, shortcuts 11 per line of description. */
    private int columnHeight(List<String[]> rows, int w) {
        int keyW = helpKeyWidth(rows, w);
        int h = 0;
        for (String[] row : rows) {
            h += row[0] == null ? 12 : Math.max(11, helpLines(row, w - keyW - 6).size() * 10 + 1);
        }
        return h;
    }

    private void renderHelpColumn(GuiGraphics g, List<String[]> rows, int x, int y, int w, int clipTop, int clipBottom) {
        int keyW = helpKeyWidth(rows, w);
        for (String[] row : rows) {
            if (row[0] == null) {
                if (y + 12 > clipTop && y < clipBottom) {
                    g.drawString(font, row[1].toUpperCase(Locale.ROOT), x, y + 2, Theme.ACCENT, false);
                }
                y += 12;
                continue;
            }
            List<FormattedCharSequence> lines = helpLines(row, w - keyW - 6);
            int rowH = Math.max(11, lines.size() * 10 + 1);
            if (y + rowH > clipTop && y < clipBottom) {
                String keys = Paint.ellipsize(font, row[0], keyW - 8);
                int kw = font.width(keys) + 8;
                Paint.rect(g, x + keyW - kw, y, kw, 10, Theme.RADIUS_S, 0x33FFFFFF, 0x1AFFFFFF);
                Paint.outline(g, x + keyW - kw, y, kw, 10, Theme.RADIUS_S, 0x4DFFFFFF, 0x1AFFFFFF);
                g.drawString(font, keys, x + keyW - kw + 4, y + 1, Theme.TEXT, false);
                int ly = y + 1;
                for (FormattedCharSequence line : lines) {
                    g.drawString(font, line, x + keyW + 6, ly, Theme.TEXT_DIM, false);
                    ly += 10;
                }
            }
            y += rowH;
        }
    }

    @Override
    protected boolean tooltipsEnabled() {
        return !helpOpen;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        session.saveProject();
    }

    /** Test hook: the actor selected in the editor, or null. */
    public ReplayProject.ActorData selectedActor() {
        return selectedActor;
    }
}
