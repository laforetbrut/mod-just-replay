package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.format.ModDiff;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassScreen;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.ScrollArea;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Shown before a replay opens when its mods differ from the installed ones: the missing mods first, then the added
 * and the updated ones, with "Open anyway", "Cancel" and a switch that stops the warning for this replay.
 *
 * @author vyrriox
 */
public class ModsChangedScreen extends GlassScreen {
    /** What the player chose. */
    @FunctionalInterface
    public interface Choice {
        void accept(boolean open, boolean dontWarnAgain);
    }

    private final ModDiff mods;
    private final Choice choice;
    private boolean dontWarnAgain;
    private boolean decided;
    private int boxX, boxY, boxW, boxH;

    public ModsChangedScreen(ModDiff mods, Choice choice) {
        super(Component.translatable("justreplay.mods_changed.title"));
        this.mods = mods;
        this.choice = choice;
    }

    /** True while the switch "Don't warn again for this replay" is on. */
    public boolean dontWarnAgain() {
        return dontWarnAgain;
    }

    /** The differences listed, for the self test. */
    public ModDiff mods() {
        return mods;
    }

    /** Same as the "Open anyway" button. */
    public void openAnyway() {
        decide(true);
    }

    @Override
    protected void init() {
        boxW = Math.min(320, width - 24);
        boxH = Math.min(height - 16, 250);
        boxX = (width - boxW) / 2;
        boxY = (height - boxH) / 2;
        int listTop = boxY + 28;
        int listBottom = boxY + boxH - 12 - 16 - Theme.GAP - Theme.ROW - Theme.GAP;
        ScrollArea area = new ScrollArea(this, boxX + 8, listTop, boxW - 16, listBottom - listTop);
        Form f = new Form(area, boxW - 24);
        f.paragraph(Component.translatable("justreplay.mods_changed.message"), Theme.TEXT_DIM);
        if (!mods.missing().isEmpty()) {
            f.header(Component.translatable("justreplay.mods_changed.missing", mods.missing().size()), Icon.CLOSE);
            for (ModDiff.Mod mod : mods.missing()) {
                Component line = Component.literal(ModDiff.label(mod));
                f.text(() -> line, Theme.DANGER);
            }
        }
        if (!mods.added().isEmpty()) {
            f.header(Component.translatable("justreplay.mods_changed.added", mods.added().size()), Icon.PLUS);
            for (ModDiff.Mod mod : mods.added()) {
                Component line = Component.literal(ModDiff.label(mod));
                f.text(() -> line, Theme.TEXT);
            }
        }
        if (!mods.changed().isEmpty()) {
            f.header(Component.translatable("justreplay.mods_changed.updated", mods.changed().size()), Icon.ROTATE);
            for (ModDiff.Changed mod : mods.changed()) {
                Component line = Component.translatable("justreplay.mods_changed.version", mod.id(), mod.recorded(), mod.installed());
                f.text(() -> line, Theme.WARNING);
            }
        }
        f.end();

        GlassSwitch dontWarn = new GlassSwitch(Component.translatable("justreplay.mods_changed.dont_warn"),
                () -> dontWarnAgain, () -> dontWarnAgain = !dontWarnAgain)
                .tip(Component.translatable("justreplay.mods_changed.dont_warn.tip"));
        dontWarn.setX(boxX + 12);
        dontWarn.setY(listBottom + Theme.GAP);
        dontWarn.setWidth(boxW - 24);
        addRenderableWidget(dontWarn);

        int bw = (boxW - 24 - Theme.GAP) / 2;
        int by = boxY + boxH - 12 - 16;
        addRenderableWidget(new GlassButton(boxX + 12, by, bw, 16, CommonComponents.GUI_CANCEL, b -> decide(false)));
        addRenderableWidget(new GlassButton(boxX + 12 + bw + Theme.GAP, by, bw, 16,
                Component.translatable("justreplay.mods_changed.open"), b -> decide(true))
                .icon(Icon.PLAY).style(GlassButton.Style.PRIMARY));
    }

    private void decide(boolean open) {
        if (decided) {
            return;
        }
        decided = true;
        choice.accept(open, open && dontWarnAgain);
    }

    @Override
    protected void renderPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        float a = intro();
        g.pose().pushMatrix();
        g.pose().translate(0, (1.0F - a) * 6.0F);
        Paint.glass(g, boxX, boxY, boxW, boxH, Theme.RADIUS_L);
        g.pose().popMatrix();
    }

    @Override
    protected void renderOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Paint.iconOnText(g, Icon.PUZZLE, boxX + 12, boxY + 11, 10, Theme.WARNING);
        g.text(font, Paint.ellipsize(font, title.getString(), boxW - 40), boxX + 27, boxY + 11, Theme.TEXT, false);
    }

    @Override
    public void onClose() {
        decide(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
