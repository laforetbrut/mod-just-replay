package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ReplayProject;
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
 * Edits one actor: type, name or skin, equipment, pose and visibility.
 *
 * @author vyrriox
 */
public class ActorScreen extends Screen {
    private static final List<Pose> POSES = List.of(Pose.STANDING, Pose.CROUCHING, Pose.SWIMMING, Pose.FALL_FLYING,
            Pose.SLEEPING, Pose.SPIN_ATTACK, Pose.SITTING);

    private final Screen parent;
    private final ReplaySession session;
    private final ReplayProject.ActorData actor;
    private final List<EditBox> boxes = new ArrayList<>();
    private EditBox type, name, mainHand, offHand, head, chest, legs, feet;
    private Pose pose;
    private boolean visible;
    private boolean showName;

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
    }

    @Override
    protected void init() {
        boxes.clear();
        int left = width / 2 - 155;
        int right = width / 2 + 5;
        int y = 40;
        type = box(left, y, actor.type, "justreplay.actor.type");
        name = box(right, y, actor.name, "justreplay.actor.name");
        y += 36;
        mainHand = box(left, y, actor.mainHand, "justreplay.actor.mainhand");
        offHand = box(right, y, actor.offHand, "justreplay.actor.offhand");
        y += 36;
        head = box(left, y, actor.head, "justreplay.actor.head");
        chest = box(right, y, actor.chest, "justreplay.actor.chest");
        y += 36;
        legs = box(left, y, actor.legs, "justreplay.actor.legs");
        feet = box(right, y, actor.feet, "justreplay.actor.feet");
        y += 30;
        addRenderableWidget(CycleButton.builder((Pose p) -> Component.literal(p.name())).withValues(POSES).withInitialValue(pose)
                .create(left, y, 150, 20, Component.translatable("justreplay.actor.pose"), (b, v) -> pose = v));
        addRenderableWidget(CycleButton.onOffBuilder(visible)
                .create(right, y, 74, 20, Component.translatable("justreplay.actor.visible"), (b, v) -> visible = v));
        addRenderableWidget(CycleButton.onOffBuilder(showName)
                .create(right + 76, y, 74, 20, Component.translatable("justreplay.actor.showname"), (b, v) -> showName = v));
        y += 28;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> apply()).bounds(left, y, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(right, y, 150, 20).build());
    }

    private EditBox box(int x, int y, String value, String labelKey) {
        EditBox box = new EditBox(font, x, y + 10, 150, 18, Component.translatable(labelKey));
        box.setMaxLength(256);
        box.setValue(value == null ? "" : value);
        addRenderableWidget(box);
        boxes.add(box);
        return box;
    }

    private void apply() {
        actor.type = type.getValue().isBlank() ? ReplayProject.ActorData.PLAYER : type.getValue().trim();
        actor.name = name.getValue().trim();
        actor.mainHand = mainHand.getValue().trim();
        actor.offHand = offHand.getValue().trim();
        actor.head = head.getValue().trim();
        actor.chest = chest.getValue().trim();
        actor.legs = legs.getValue().trim();
        actor.feet = feet.getValue().trim();
        actor.pose = pose.name();
        actor.visible = visible;
        actor.showName = showName;
        session.actors().refresh(actor);
        session.saveProject();
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        for (EditBox box : boxes) {
            g.drawString(font, box.getMessage(), box.getX(), box.getY() - 10, 0xA0A0A0);
        }
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
