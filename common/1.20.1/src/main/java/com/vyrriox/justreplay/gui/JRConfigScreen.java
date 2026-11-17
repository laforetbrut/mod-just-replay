package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.config.ConfigFile;
import com.vyrriox.justreplay.config.JRConfig;
import com.vyrriox.justreplay.export.ExportResolution;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.GlassSlider;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.GlassTextField;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.ScrollArea;
import com.vyrriox.justreplay.gui.ui.Segmented;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings of the mod, generated from the sections of {@link JRConfig#SPEC}. Edits stay in this screen until Done.
 *
 * @author vyrriox
 */
public class JRConfigScreen extends GlassScreen {
    private static final String LANG = "justreplay.configuration.";
    /** Number ranges up to this wide get a slider; wider ones get a text field. */
    private static final int SLIDER_RANGE = 240;

    private final Screen parent;
    private final Map<ConfigFile.Value<?>, Object> draft = new LinkedHashMap<>();
    private int boxX, boxY, boxW, boxH;

    public JRConfigScreen(Screen parent) {
        super(Component.translatable(LANG + "title"));
        this.parent = parent;
        for (ConfigFile.Section section : JRConfig.SPEC.sections()) {
            for (ConfigFile.Value<?> value : section.values()) {
                draft.put(value, value.get());
            }
        }
    }

    @Override
    protected void init() {
        boxW = Math.min(360, width - 24);
        boxH = height - 16;
        boxX = (width - boxW) / 2;
        boxY = 8;
        ScrollArea area = new ScrollArea(this, boxX + 8, boxY + 28, boxW - 16, boxH - 28 - 36);
        Form f = new Form(area, boxW - 24);
        for (ConfigFile.Section section : JRConfig.SPEC.sections()) {
            f.header(Component.translatable(LANG + section.name()), null);
            for (ConfigFile.Value<?> value : section.values()) {
                addRow(f, value);
            }
            f.space(2);
        }
        f.end();

        int bw = (boxW - 24 - Theme.GAP) / 2;
        int by = boxY + boxH - 10 - 16;
        addRenderableWidget(new GlassButton(boxX + 12, by, bw, 16, CommonComponents.GUI_CANCEL, b -> onClose()));
        addRenderableWidget(new GlassButton(boxX + 12 + bw + Theme.GAP, by, bw, 16, CommonComponents.GUI_DONE, b -> {
            apply();
            onClose();
        }).style(GlassButton.Style.PRIMARY));
    }

    private void addRow(Form f, ConfigFile.Value<?> value) {
        Component label = Component.translatable(LANG + value.key());
        Component tip = Component.literal(value.comment());
        if (value instanceof ConfigFile.BoolValue) {
            f.full(new GlassSwitch(label, () -> (Boolean) draft.get(value), () -> draft.put(value, !(Boolean) draft.get(value))).tip(tip));
        } else if (value instanceof ConfigFile.IntValue number && (long) number.max() - number.min() <= SLIDER_RANGE) {
            f.full(new GlassSlider(0, number.min(), number.max(), 1, (Integer) draft.get(value),
                    v -> Component.empty().append(label).append(": " + (int) v), v -> draft.put(value, (int) v)));
        } else if (value instanceof ConfigFile.IntValue) {
            f.text(() -> label, Theme.TEXT_DIM);
            GlassTextField field = new GlassTextField(font, 0, label);
            field.setMaxLength(10);
            field.setValue(String.valueOf(draft.get(value)));
            field.setFilter(text -> text.chars().allMatch(Character::isDigit));
            field.setResponder(text -> storeNumber(value, text));
            f.full(field.tip(tip));
        } else if (value instanceof ConfigFile.EnumValue<?> choice) {
            f.text(() -> label, Theme.TEXT_DIM);
            f.full(segmented(choice).tip(tip));
        } else {
            f.text(() -> label, Theme.TEXT_DIM);
            GlassTextField field = new GlassTextField(font, 0, label);
            field.setMaxLength(512);
            field.setValue(String.valueOf(draft.get(value)));
            field.setResponder(text -> draft.put(value, text.trim().isEmpty() ? value.defaultValue() : text.trim()));
            f.full(field.tip(tip));
        }
    }

    /** A number being typed may be empty or too long for an int: the last valid one stays. */
    private void storeNumber(ConfigFile.Value<?> value, String text) {
        try {
            draft.put(value, Integer.parseInt(text));
        } catch (NumberFormatException ignored) {
            // Keeps the previous draft.
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> Segmented<E> segmented(ConfigFile.EnumValue<E> choice) {
        return new Segmented<>(List.of(choice.choices()), (E) draft.get(choice), JRConfigScreen::choiceName, null, v -> draft.put(choice, v));
    }

    private static Component choiceName(Enum<?> choice) {
        if (choice instanceof ExportResolution resolution) {
            return resolution.isWindow() ? resolution.getTranslatedName() : Component.literal(resolution.height() + "p");
        }
        return Component.literal(choice.name());
    }

    private void apply() {
        JRConfig.SPEC.batch(() -> draft.forEach(JRConfigScreen::store));
    }

    @SuppressWarnings("unchecked")
    private static <T> void store(ConfigFile.Value<T> value, Object newValue) {
        value.set((T) newValue);
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
        Paint.iconOnText(g, Icon.GEAR, boxX + 12, boxY + 11, 10, Theme.ACCENT);
        g.drawString(font, Paint.ellipsize(font, title.getString(), boxW - 40), boxX + 27, boxY + 11, Theme.TEXT, false);
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
