package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ActionType;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.model.ModelLibrary;
import com.vyrriox.justreplay.gui.ui.Dropdown;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.GlassTextField;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.ScrollArea;
import com.vyrriox.justreplay.gui.ui.Segmented;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Pose;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits one actor: type, name or skin, equipment, pose, visibility and Blockbench model on the first tab, its
 * timed actions on the second. Nothing is applied before Done.
 *
 * @author vyrriox
 */
public class ActorScreen extends GlassScreen {
    private static final List<Pose> POSES = List.of(Pose.STANDING, Pose.CROUCHING, Pose.SWIMMING, Pose.FALL_FLYING,
            Pose.SLEEPING, Pose.SPIN_ATTACK, Pose.SITTING);

    private final Screen parent;
    private final ReplaySession session;
    private final ReplayProject.ActorData actor;
    private GlassTextField type, name, mainHand, offHand, head, chest, legs, feet, model, animation, scale;
    private Pose pose;
    private boolean visible;
    private boolean showName;
    /** Field values kept across the two pages. */
    private final String[] values;

    private boolean actionsPage;
    private boolean previousPage;
    private final List<ReplayProject.ActorAction> actions = new ArrayList<>();
    private ReplayProject.ActorAction selected;
    private ActionType actionType = ActionType.WAVE;
    private GlassTextField duration, param;
    private String durationValue = "0";
    private String paramValue;
    private ScrollArea area;
    private float pageScroll;
    private int boxX, boxY, boxW, boxH;

    public ActorScreen(Screen parent, ReplaySession session, ReplayProject.ActorData actor) {
        super(Component.translatable("justreplay.actor.title"));
        this.parent = parent;
        this.session = session;
        this.actor = actor;
        this.visible = actor.visible;
        this.showName = actor.showName;
        Pose p;
        try {
            p = Pose.valueOf(actor.pose);
        } catch (Exception e) {
            p = Pose.STANDING;
        }
        this.pose = p;
        this.values = new String[]{actor.type, actor.name, actor.mainHand, actor.offHand, actor.head, actor.chest, actor.legs,
                actor.feet, actor.model, actor.modelAnimation, trimFloat(actor.modelScale)};
        for (ReplayProject.ActorAction a : actor.actions) {
            actions.add(new ReplayProject.ActorAction(a.tick, a.actionType(), a.duration, a.param));
        }
    }

    private static String trimFloat(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }

    @Override
    protected void init() {
        stashActionFields();
        boxW = Math.min(340, width - 16);
        boxH = Math.min(300, height - 12);
        boxX = (width - boxW) / 2;
        boxY = (height - boxH) / 2;
        int tabsW = Math.min(170, boxW - 110);
        Segmented<Boolean> pages = addRenderableWidget(new Segmented<>(List.of(false, true), actionsPage,
                v -> Component.translatable(v ? "justreplay.actor.tab.actions" : "justreplay.actor.tab.properties"),
                v -> v ? Icon.BOLT : Icon.PERSON, v -> {
            stash();
            stashActionFields();
            previousPage = actionsPage;
            actionsPage = v;
            pageScroll = 0;
            rebuildWidgets();
        }));
        pages.slideFrom(previousPage);
        previousPage = actionsPage;
        pages.setRectangle(tabsW, 14, boxX + boxW - 10 - tabsW, boxY + 7);
        area = new ScrollArea(this, boxX + 8, boxY + 28, boxW - 16, boxH - 28 - 34);
        Form f = new Form(area, boxW - 24);
        if (actionsPage) {
            initActions(f);
        } else {
            initProperties(f);
        }
        f.end();
        area.setScroll(pageScroll, true);
        int bw = (boxW - 24 - Theme.GAP) / 2;
        int by = boxY + boxH - 10 - 16;
        addRenderableWidget(new GlassButton(boxX + 12, by, bw, 16, CommonComponents.GUI_CANCEL, b -> onClose()));
        addRenderableWidget(new GlassButton(boxX + 12 + bw + Theme.GAP, by, bw, 16, CommonComponents.GUI_DONE, b -> apply())
                .icon(Icon.CHECK).style(GlassButton.Style.PRIMARY));
    }

    private GlassTextField field(String value, String labelKey, int maxLength) {
        GlassTextField box = new GlassTextField(font, 0, Component.translatable(labelKey));
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        return box;
    }

    /** Two labelled fields side by side. */
    private GlassTextField[] pair(Form f, int left, String leftKey, int right, String rightKey) {
        f.caption(Component.translatable(leftKey), 0, 2);
        f.caption(Component.translatable(rightKey), 1, 2);
        f.next(9);
        GlassTextField a = f.put(field(values[left], leftKey, 256), 0, 2);
        GlassTextField b = f.put(field(values[right], rightKey, 256), 1, 2);
        f.next(a.getHeight());
        return new GlassTextField[]{a, b};
    }

    private void initProperties(Form f) {
        f.header(Component.translatable("justreplay.actor.section.identity"), Icon.PERSON);
        GlassTextField[] row = pair(f, 0, "justreplay.actor.type", 1, "justreplay.actor.name");
        type = row[0];
        name = row[1];
        f.header(Component.translatable("justreplay.actor.section.equipment"), Icon.PUZZLE);
        row = pair(f, 2, "justreplay.actor.mainhand", 3, "justreplay.actor.offhand");
        mainHand = row[0];
        offHand = row[1];
        row = pair(f, 4, "justreplay.actor.head", 5, "justreplay.actor.chest");
        head = row[0];
        chest = row[1];
        row = pair(f, 6, "justreplay.actor.legs", 7, "justreplay.actor.feet");
        legs = row[0];
        feet = row[1];

        f.header(Component.translatable("justreplay.actor.section.model"), Icon.CUBE);
        f.caption(Component.translatable("justreplay.actor.model"), 0, 2);
        f.caption(Component.translatable("justreplay.actor.animation"), 1, 2);
        f.next(9);
        int cellW = f.cellWidth(1, 2);
        int rightX = f.cellX(1, 2);
        model = f.at(field(values[8], "justreplay.actor.model", 256), 0, cellW - 18);
        f.at(new GlassButton(Component.empty(), b -> {
            stash();
            minecraft.setScreen(new ModelPickScreen(this, file -> {
                values[0] = ReplayProject.ActorData.MODEL;
                values[8] = file;
            }));
        }).icon(Icon.FOLDER).tip(Component.translatable("justreplay.model.pick.title")), cellW - 16, 16).setHeight(16);
        animation = f.at(field(values[9], "justreplay.actor.animation", 256), rightX, cellW - 44);
        animation.tip(animationsTooltip());
        scale = f.at(field(values[10], "justreplay.actor.scale", 8), rightX + cellW - 41, 41);
        scale.tip(Component.translatable("justreplay.actor.scale"));
        f.next(16);

        f.header(Component.translatable("justreplay.actor.section.look"), Icon.EYE);
        f.full(new Dropdown<>(this, Component.translatable("justreplay.actor.pose"), POSES, pose,
                p -> Component.translatable("justreplay.actor.pose." + p.name().toLowerCase(java.util.Locale.ROOT)), v -> pose = v)
                .icon(Icon.PERSON));
        f.put(new GlassSwitch(Component.translatable("justreplay.actor.visible"), () -> visible, () -> visible = !visible).icon(Icon.EYE), 0, 2);
        f.put(new GlassSwitch(Component.translatable("justreplay.actor.showname"), () -> showName, () -> showName = !showName)
                .icon(Icon.EDIT), 1, 2);
        f.next();
    }

    private void initActions(Form f) {
        f.caption(Component.translatable("justreplay.actor.action.type"), 0, 2, 6);
        f.caption(Component.translatable("justreplay.actor.action.duration.short"), 2, 1, 6);
        f.caption(Component.translatable("justreplay.actor.action.param"), 3, 3, 6);
        f.next(9);
        f.put(new Dropdown<>(this, Component.empty(), List.of(ActionType.values()), actionType,
                t -> Component.translatable(t.translationKey()), v -> {
            actionType = v;
            param.setValue(defaultParam(v));
            updateHint();
        }), 0, 2, 6).setHeight(16);
        duration = f.put(field(selected != null ? Integer.toString(selected.duration) : durationValue, "justreplay.actor.action.duration", 7), 2, 1, 6);
        duration.tip(Component.translatable("justreplay.actor.action.duration"));
        param = f.put(field(selected != null ? selected.param : paramValue != null ? paramValue : defaultParam(actionType),
                "justreplay.actor.action.param", 128), 3, 3, 6);
        updateHint();
        param.tip(animationsTooltip());
        f.next(16);
        f.put(new GlassButton(Component.translatable("justreplay.actor.action.add"), b -> addAction()).icon(Icon.PLUS)
                .style(GlassButton.Style.PRIMARY), 0, 2);
        f.put(new GlassButton(Component.translatable("justreplay.actor.action.update"), b -> updateSelected()).icon(Icon.CHECK)
                .enabled(selected != null), 1, 2);
        f.next();
        f.header(Component.translatable("justreplay.editor.actor.actions"), Icon.BOLT);
        if (actions.isEmpty()) {
            f.paragraph(Component.translatable("justreplay.actor.action.none"), Theme.TEXT_MUTED);
        }
        for (ReplayProject.ActorAction action : actions) {
            f.full(new ListRow(Component.literal(actionLabel(action)), b -> {
                stashActionFields();
                pageScroll = area.scrollTarget();
                selected = action;
                actionType = action.actionType();
                paramValue = null;
                rebuildWidgets();
            }).detail(action.actionType().shortLabel).icon(Icon.BOLT).selected(() -> action == selected)
                    .action(() -> Icon.TRASH, Component.translatable("justreplay.editor.delete"), () -> {
                        stashActionFields();
                        pageScroll = area.scrollTarget();
                        actions.remove(action);
                        if (selected == action) {
                            selected = null;
                        }
                        rebuildWidgets();
                    }));
        }
    }

    /** Keeps what was typed in the action fields across rebuilds. */
    private void stashActionFields() {
        if (duration != null && param != null && actionsPage) {
            durationValue = duration.getValue();
            paramValue = param.getValue();
        }
    }

    private String defaultParam(ActionType type) {
        if (type == ActionType.ANIMATION) {
            String file = values[8];
            List<String> names = ModelLibrary.animations(file);
            return names.isEmpty() ? "" : names.get(0) + "|once";
        }
        return type.defaultParam;
    }

    private void updateHint() {
        if (param == null) {
            return;
        }
        String key = "justreplay.action.hint." + actionType.name().toLowerCase(java.util.Locale.ROOT);
        Component hint = Component.translatable(key);
        param.setHint(hint.getString().equals(key) ? Component.empty() : hint.copy().withColor(Theme.TEXT_MUTED));
    }

    private String actionLabel(ReplayProject.ActorAction action) {
        StringBuilder label = new StringBuilder();
        label.append(Timeline.formatTicks(action.tick)).append("  ").append(Component.translatable(action.actionType().translationKey()).getString());
        if (action.duration > 0 && action.actionType().kind == ActionType.Kind.STATE) {
            label.append("  ").append(action.duration).append("t");
        }
        if (action.param != null && !action.param.isBlank()) {
            label.append("  ").append(action.param);
        }
        return label.toString();
    }

    private int parseDuration() {
        try {
            return Math.max(0, Integer.parseInt(duration.getValue().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void addAction() {
        ReplayProject.ActorAction action = new ReplayProject.ActorAction(session.playback().cursor(), actionType, parseDuration(),
                param.getValue().trim());
        actions.add(action);
        actions.sort(java.util.Comparator.comparingInt(a -> a.tick));
        selected = action;
        stashActionFields();
        pageScroll = area.scrollTarget();
        rebuildWidgets();
    }

    private void updateSelected() {
        if (selected == null) {
            return;
        }
        selected.type = actionType.name();
        selected.duration = parseDuration();
        selected.param = param.getValue().trim();
        pageScroll = area.scrollTarget();
        rebuildWidgets();
    }

    /** Keeps the property fields while the other page is shown. */
    private void stash() {
        if (actionsPage || type == null) {
            return;
        }
        GlassTextField[] fields = {type, name, mainHand, offHand, head, chest, legs, feet, model, animation, scale};
        for (int i = 0; i < fields.length; i++) {
            values[i] = fields[i].getValue();
        }
    }

    private void apply() {
        stash();
        actor.type = values[0].isBlank() ? ReplayProject.ActorData.PLAYER : values[0].trim();
        actor.name = values[1].trim();
        actor.mainHand = values[2].trim();
        actor.offHand = values[3].trim();
        actor.head = values[4].trim();
        actor.chest = values[5].trim();
        actor.legs = values[6].trim();
        actor.feet = values[7].trim();
        actor.model = values[8].trim();
        actor.modelAnimation = values[9].trim();
        try {
            float s = Float.parseFloat(values[10].trim());
            actor.modelScale = s > 0.01F && s < 100F ? s : 1.0F;
        } catch (NumberFormatException e) {
            actor.modelScale = 1.0F;
        }
        actor.pose = pose.name();
        actor.visible = visible;
        actor.showName = showName;
        actor.actions = new ArrayList<>(actions);
        actor.sort();
        session.actors().refresh(actor);
        session.saveProject();
        onClose();
    }

    @Override
    public void resize(net.minecraft.client.Minecraft mc, int width, int height) {
        stash();
        super.resize(mc, width, height);
    }

    @Override
    protected void renderPanels(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float a = intro();
        g.pose().pushPose();
        g.pose().translate(0, (1.0F - a) * 6.0F, 0);
        Paint.glass(g, boxX, boxY, boxW, boxH, Theme.RADIUS_L);
        g.pose().popPose();
    }

    @Override
    protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Paint.icon(g, actor.isModel() ? Icon.CUBE : Icon.PERSON, boxX + 12, boxY + 9, 10, Theme.ACCENT);
        String who = actor.name.isBlank() ? actor.type : actor.name;
        g.drawString(font, Paint.ellipsize(font, title.getString() + "  " + who, boxW - 200), boxX + 27, boxY + 11, Theme.TEXT, false);
    }

    /** Animation names of the chosen model as a tooltip, or null when there is none. */
    private Component animationsTooltip() {
        List<String> names = ModelLibrary.animations(values[8]);
        return names.isEmpty() ? null : Component.translatable("justreplay.actor.animations", String.join(", ", names));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
