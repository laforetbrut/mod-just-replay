package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ActionType;
import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.model.ModelLibrary;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Pose;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits one actor: type, name or skin, equipment, pose, visibility and Blockbench model on the first page, its
 * timed actions on the second. Nothing is applied before Done.
 *
 * @author vyrriox
 */
public class ActorScreen extends Screen {
    private static final List<Pose> POSES = List.of(Pose.STANDING, Pose.CROUCHING, Pose.SWIMMING, Pose.FALL_FLYING,
            Pose.SLEEPING, Pose.SPIN_ATTACK, Pose.SITTING);
    private static final int ROW = 20;

    private final Screen parent;
    private final ReplaySession session;
    private final ReplayProject.ActorData actor;
    private final List<EditBox> boxes = new ArrayList<>();
    private EditBox type, name, mainHand, offHand, head, chest, legs, feet, model, animation, scale;
    private Pose pose;
    private boolean visible;
    private boolean showName;
    /** Field values kept across the two pages. */
    private final String[] values;

    private boolean actionsPage;
    private final List<ReplayProject.ActorAction> actions = new ArrayList<>();
    private ReplayProject.ActorAction selected;
    private ActionType actionType = ActionType.WAVE;
    private EditBox duration, param;
    private int actionScroll;
    private int listY;

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
        boxes.clear();
        int left = width / 2 - 155;
        int right = width / 2 + 5;
        addRenderableWidget(Button.builder(Component.translatable(actionsPage ? "justreplay.actor.page.properties" : "justreplay.actor.page.actions"),
                b -> {
                    stash();
                    actionsPage = !actionsPage;
                    rebuildWidgets();
                }).bounds(width - 108, 6, 100, 18).build());
        if (actionsPage) {
            initActions(left);
        } else {
            initProperties(left, right);
        }
        int y = height - 26;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> apply()).bounds(left, y, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(right, y, 150, 20).build());
    }

    private void initProperties(int left, int right) {
        int y = 28;
        int step = 32;
        type = box(left, y, values[0], "justreplay.actor.type");
        name = box(right, y, values[1], "justreplay.actor.name");
        y += step;
        mainHand = box(left, y, values[2], "justreplay.actor.mainhand");
        offHand = box(right, y, values[3], "justreplay.actor.offhand");
        y += step;
        head = box(left, y, values[4], "justreplay.actor.head");
        chest = box(right, y, values[5], "justreplay.actor.chest");
        y += step;
        legs = box(left, y, values[6], "justreplay.actor.legs");
        feet = box(right, y, values[7], "justreplay.actor.feet");
        y += step;
        model = box(left, y, values[8], "justreplay.actor.model");
        animation = new EditBox(font, right, y + 10, 104, 18, Component.translatable("justreplay.actor.animation"));
        animation.setMaxLength(256);
        animation.setValue(values[9] == null ? "" : values[9]);
        animation.setTooltip(animationsTooltip());
        addRenderableWidget(animation);
        boxes.add(animation);
        scale = new EditBox(font, right + 108, y + 10, 42, 18, Component.translatable("justreplay.actor.scale"));
        scale.setMaxLength(8);
        scale.setValue(values[10]);
        addRenderableWidget(scale);
        boxes.add(scale);
        addRenderableWidget(Button.builder(Component.literal("..."), b -> {
            stash();
            minecraft.setScreen(new ModelPickScreen(this, file -> {
                values[0] = ReplayProject.ActorData.MODEL;
                values[8] = file;
            }));
        }).bounds(left + 152, y + 10, 18, 18).build());
        y += step + 2;
        addRenderableWidget(CycleButton.builder((Pose p) -> Component.literal(p.name())).withValues(POSES).withInitialValue(pose)
                .create(left, y, 150, 20, Component.translatable("justreplay.actor.pose"), (b, v) -> pose = v));
        addRenderableWidget(CycleButton.onOffBuilder(visible)
                .create(right, y, 74, 20, Component.translatable("justreplay.actor.visible"), (b, v) -> visible = v));
        addRenderableWidget(CycleButton.onOffBuilder(showName)
                .create(right + 76, y, 74, 20, Component.translatable("justreplay.actor.showname"), (b, v) -> showName = v));
    }

    private void initActions(int left) {
        int y = 30;
        addRenderableWidget(CycleButton.builder((ActionType t) -> Component.translatable(t.translationKey()))
                .withValues(ActionType.values()).withInitialValue(actionType).displayOnlyValue()
                .create(left, y + 10, 110, 18, Component.translatable("justreplay.actor.action.type"), (b, v) -> {
                    actionType = v;
                    param.setValue(defaultParam(v));
                    updateHint();
                }));
        duration = new EditBox(font, left + 114, y + 10, 50, 18, Component.translatable("justreplay.actor.action.duration"));
        duration.setMaxLength(7);
        duration.setValue(selected != null ? Integer.toString(selected.duration) : "0");
        addRenderableWidget(duration);
        boxes.add(duration);
        param = new EditBox(font, left + 168, y + 10, 142, 18, Component.translatable("justreplay.actor.action.param"));
        param.setMaxLength(128);
        param.setValue(selected != null ? selected.param : defaultParam(actionType));
        addRenderableWidget(param);
        boxes.add(param);
        updateHint();
        param.setTooltip(animationsTooltip());
        y += 32;
        addRenderableWidget(Button.builder(Component.translatable("justreplay.actor.action.add"), b -> addAction())
                .bounds(left, y, 153, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("justreplay.actor.action.update"), b -> updateSelected())
                .bounds(left + 157, y, 153, 18).build());
        y += 24;
        listY = y;
        int rows = Math.max(1, (height - 34 - y) / ROW);
        actionScroll = Math.max(0, Math.min(actionScroll, actions.size() - rows));
        for (int i = actionScroll; i < Math.min(actions.size(), actionScroll + rows); i++) {
            ReplayProject.ActorAction action = actions.get(i);
            addRenderableWidget(Button.builder(Component.literal(actionLabel(action)), b -> {
                selected = action;
                actionType = action.actionType();
                rebuildWidgets();
            }).bounds(left, y, 288, 18).build());
            addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                actions.remove(action);
                if (selected == action) {
                    selected = null;
                }
                rebuildWidgets();
            }).bounds(left + 292, y, 18, 18).build());
            y += ROW;
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
        param.setHint(hint.getString().equals(key) ? Component.empty() : hint.copy().withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    private String actionLabel(ReplayProject.ActorAction action) {
        StringBuilder label = new StringBuilder();
        if (action == selected) {
            label.append("> ");
        }
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
        rebuildWidgets();
    }

    private void updateSelected() {
        if (selected == null) {
            return;
        }
        selected.type = actionType.name();
        selected.duration = parseDuration();
        selected.param = param.getValue().trim();
        rebuildWidgets();
    }

    private EditBox box(int x, int y, String value, String labelKey) {
        EditBox box = new EditBox(font, x, y + 10, 150, 18, Component.translatable(labelKey));
        box.setMaxLength(256);
        box.setValue(value == null ? "" : value);
        addRenderableWidget(box);
        boxes.add(box);
        return box;
    }

    /** Keeps the property fields while the other page is shown. */
    private void stash() {
        if (actionsPage || type == null) {
            return;
        }
        EditBox[] fields = {type, name, mainHand, offHand, head, chest, legs, feet, model, animation, scale};
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
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (actionsPage && my >= listY) {
            actionScroll = Math.max(0, actionScroll - (int) Math.signum(sy));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        for (EditBox box : boxes) {
            g.drawString(font, box.getMessage(), box.getX(), box.getY() - 10, 0xA0A0A0);
        }
        if (actionsPage) {
            int left = width / 2 - 155;
            g.drawString(font, Component.translatable("justreplay.actor.action.type"), left, 30, 0xA0A0A0);
            if (actions.isEmpty()) {
                g.drawString(font, Component.translatable("justreplay.actor.action.none"), left, listY + 4, 0x808080);
            }
        }
    }

    /** Animation names of the chosen model as a tooltip, or null when there is none. */
    private net.minecraft.client.gui.components.Tooltip animationsTooltip() {
        List<String> names = ModelLibrary.animations(values[8]);
        return names.isEmpty() ? null : net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("justreplay.actor.animations", String.join(", ", names)));
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
