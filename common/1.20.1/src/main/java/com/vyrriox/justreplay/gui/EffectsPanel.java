package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.fx.FxEffect;
import com.vyrriox.justreplay.editor.fx.FxParam;
import com.vyrriox.justreplay.editor.fx.FxPresets;
import com.vyrriox.justreplay.editor.fx.FxType;
import com.vyrriox.justreplay.editor.fx.LutLibrary;
import com.vyrriox.justreplay.editor.fx.TransitionType;
import com.vyrriox.justreplay.gui.ui.Anim;
import com.vyrriox.justreplay.gui.ui.Dropdown;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Paint;
import com.vyrriox.justreplay.gui.ui.Stepper;
import com.vyrriox.justreplay.gui.ui.Theme;
import com.vyrriox.justreplay.gui.ui.Tipped;
import com.vyrriox.justreplay.replay.ReplaySession;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Effects" tab of the editor: the filter stack of the project (global, or bound to one camera), presets, the
 * parameters of the selected filter with a keyframe toggle at the play head, and the fades of the export range.
 * Also draws the transition controls of a cut (in the Cameras tab) and the transition and keyframe marks of the
 * timeline.
 *
 * @author vyrriox
 */
final class EffectsPanel {
    /** Camera id whose filters are listed, or empty for the global filters; kept across rebuilds. */
    private static String scope = "";
    private static String selectedId;
    private static FxType addType = FxType.VIGNETTE;
    private static FxPresets preset = FxPresets.CINEMATIC;
    /** Colour of the effect marks on the timeline. */
    static final int FX_COLOR = 0xFFC08CFF;

    private final EditorScreen screen;
    private final ReplaySession session;

    EffectsPanel(EditorScreen screen, ReplaySession session) {
        this.screen = screen;
        this.session = session;
    }

    private static Component tc(String key) {
        return Component.translatable(key);
    }

    private ReplayProject project() {
        return session.project();
    }

    private void save() {
        session.saveProject();
    }

    private FxEffect selected() {
        if (selectedId == null) {
            return null;
        }
        for (FxEffect e : project().effects) {
            if (selectedId.equals(e.id)) {
                return e;
            }
        }
        return null;
    }

    private boolean inScope(FxEffect effect) {
        return scope.isEmpty() ? effect.isGlobal() : scope.equals(effect.camera);
    }

    // ---------------------------------------------------------------- layout

    void build(Form f) {
        if (!scope.isEmpty() && project().camera(scope) == null) {
            scope = "";
        }
        FxEffect selected = selected();
        if (selected != null && !inScope(selected)) {
            selected = null;
            selectedId = null;
        }
        f.header(tc("justreplay.fx.section"), Icon.BOLT);
        f.put(new GlassSwitch(tc("justreplay.fx.enabled"), () -> project().effectsEnabled, () -> {
            project().effectsEnabled = !project().effectsEnabled;
            save();
        }).icon(Icon.BOLT).tip(tc("justreplay.fx.enabled.tip")), 0, 2);
        f.put(new GlassSwitch(tc("justreplay.editor.preview"), session::isCameraPathActive,
                () -> session.setCameraPath(!session.isCameraPathActive())).icon(Icon.EYE).tip(tc("justreplay.fx.preview.tip")), 1, 2);
        f.next();
        List<String> scopes = new ArrayList<>();
        scopes.add("");
        for (ReplayProject.Camera camera : project().cameras) {
            scopes.add(camera.id);
        }
        f.full(new Dropdown<>(screen, tc("justreplay.fx.scope"), scopes, scope, this::scopeLabel, v -> {
            scope = v;
            selectedId = null;
            screen.refresh();
        }).icon(Icon.CAMERA).tip(tc("justreplay.fx.scope.tip")));

        f.put(new Dropdown<>(screen, Component.empty(), List.of(FxPresets.values()), preset,
                p -> tc(p.translationKey()), v -> preset = v).tip(tc("justreplay.fx.preset.tip")), 0, 2);
        f.put(new GlassButton(tc("justreplay.fx.preset.apply"), b -> applyPreset()).icon(Icon.PLUS), 1, 2);
        f.next();
        f.put(new Dropdown<>(screen, Component.empty(), List.of(FxType.values()), addType,
                t -> tc(t.translationKey()), v -> addType = v).tip(tc("justreplay.fx.add.tip")), 0, 2);
        f.put(new GlassButton(tc("justreplay.fx.add"), b -> addEffect(addType)).icon(Icon.PLUS).style(GlassButton.Style.PRIMARY), 1, 2);
        f.next();

        f.header(tc("justreplay.fx.stack"), Icon.SORT);
        boolean any = false;
        for (FxEffect effect : project().effects) {
            if (!inScope(effect)) {
                continue;
            }
            any = true;
            FxType type = effect.fxType();
            Component label = type == null ? Component.literal(effect.type) : tc(type.translationKey());
            f.full(new ListRow(label, b -> {
                selectedId = effect.id;
                screen.refresh();
            }).detail(detail(effect)).icon(Icon.BOLT).action(() -> effect.enabled ? Icon.EYE : Icon.EYE_OFF,
                    tc("justreplay.fx.toggle.tip"), () -> {
                        effect.enabled = !effect.enabled;
                        save();
                    }).selected(() -> effect.id.equals(selectedId)));
        }
        if (!any) {
            f.paragraph(tc(scope.isEmpty() ? "justreplay.fx.none" : "justreplay.fx.none.camera"), Theme.TEXT_MUTED);
        }
        if (selected != null) {
            buildEffect(f, selected);
        }
        buildFades(f);
    }

    private Component scopeLabel(String id) {
        if (id.isEmpty()) {
            return tc("justreplay.fx.scope.global");
        }
        ReplayProject.Camera camera = project().camera(id);
        return Component.literal(camera == null ? "?" : (project().cameras.indexOf(camera) + 1) + " " + camera.name);
    }

    private static String detail(FxEffect effect) {
        FxType type = effect.fxType();
        if (type == null) {
            return "";
        }
        for (FxParam p : type.params) {
            if (effect.animated(p)) {
                return "K";
            }
        }
        return "";
    }

    private void buildEffect(Form f, FxEffect effect) {
        FxType type = effect.fxType();
        f.header(type == null ? Component.literal(effect.type) : tc(type.translationKey()), Icon.EDIT);
        f.put(new GlassButton(Component.empty(), b -> move(effect, -1)).icon(Icon.CHEVRON_UP).tip(tc("justreplay.fx.up.tip")), 0, 4);
        f.put(new GlassButton(Component.empty(), b -> move(effect, 1)).icon(Icon.CHEVRON_DOWN).tip(tc("justreplay.fx.down.tip")), 1, 4);
        f.put(new GlassButton(Component.empty(), b -> duplicate(effect)).icon(Icon.COPY).tip(tc("justreplay.fx.duplicate.tip")), 2, 4);
        f.put(new GlassButton(Component.empty(), b -> delete(effect)).icon(Icon.TRASH).style(GlassButton.Style.DANGER)
                .tip(tc("justreplay.fx.delete.tip")), 3, 4);
        f.next();
        if (type == null) {
            f.paragraph(tc("justreplay.fx.unknown"), Theme.WARNING);
            return;
        }
        if (type == FxType.LUT) {
            List<String> files = LutLibrary.list();
            if (files.isEmpty()) {
                f.paragraph(tc("justreplay.fx.lut.none"), Theme.TEXT_MUTED);
            } else {
                if (effect.file.isEmpty() || !files.contains(effect.file)) {
                    effect.file = files.get(0);
                    save();
                }
                f.full(new Dropdown<>(screen, tc("justreplay.fx.lut.file"), files, effect.file, Component::literal, v -> {
                    effect.file = v;
                    save();
                }).icon(Icon.FILM));
            }
            f.put(new GlassButton(tc("justreplay.audio.folder"), b -> Util.getPlatform().openPath(LutLibrary.folder())).icon(Icon.FOLDER), 0, 2);
            f.put(new GlassButton(tc("justreplay.fx.lut.reload"), b -> {
                LutLibrary.reload();
                screen.refresh();
            }).icon(Icon.ROTATE).tip(tc("justreplay.fx.lut.reload.tip")), 1, 2);
            f.next();
        }
        for (FxParam param : type.params) {
            if (param.isChoice()) {
                List<Integer> choices = new ArrayList<>();
                for (int i = 0; i < param.choices().length; i++) {
                    choices.add(i);
                }
                f.full(new Dropdown<>(screen, tc(param.translationKey()), choices, Math.round(effect.base(param)),
                        i -> Component.literal(param.choices()[i]), v -> {
                    effect.values.put(param.name(), (float) v);
                    save();
                }));
            } else {
                f.full(new ParamSlider(param, effect));
            }
        }
        f.text(() -> keyInfo(effect), Theme.TEXT_MUTED);
    }

    private Component keyInfo(FxEffect effect) {
        FxType type = effect.fxType();
        if (type == null) {
            return null;
        }
        int keys = 0;
        for (FxParam p : type.params) {
            List<FxEffect.Key> list = effect.keys.get(p.name());
            keys += list == null ? 0 : list.size();
        }
        return keys == 0 ? tc("justreplay.fx.keys.none") : Component.translatable("justreplay.fx.keys", keys);
    }

    private void buildFades(Form f) {
        f.header(tc("justreplay.fx.fades"), Icon.FILM);
        f.full(new Stepper(tc("justreplay.fx.fade_in"), () -> project().fadeIn / 20.0, "s", 2,
                () -> fade(true, -step()), () -> fade(true, step())).icon(Icon.ARROW_UP).tip(tc("justreplay.fx.fade.tip")));
        f.full(new Stepper(tc("justreplay.fx.fade_out"), () -> project().fadeOut / 20.0, "s", 2,
                () -> fade(false, -step()), () -> fade(false, step())).icon(Icon.ARROW_DOWN).tip(tc("justreplay.fx.fade.tip")));
        f.full(new GlassSwitch(tc("justreplay.fx.fade_white"), () -> project().fadeWhite, () -> {
            project().fadeWhite = !project().fadeWhite;
            save();
        }).icon(Icon.EYE));
    }

    private static int step() {
        return Screen.hasShiftDown() ? 1 : 10;
    }

    private void fade(boolean in, int delta) {
        if (in) {
            project().fadeIn = Math.max(0, Math.min(400, project().fadeIn + delta));
        } else {
            project().fadeOut = Math.max(0, Math.min(400, project().fadeOut + delta));
        }
        save();
    }

    // ---------------------------------------------------------------- stack

    private void addEffect(FxType type) {
        FxEffect effect = new FxEffect(type, scope);
        if (type == FxType.LUT) {
            List<String> files = LutLibrary.list();
            effect.file = files.isEmpty() ? "" : files.get(0);
        }
        project().effects.add(effect);
        selectedId = effect.id;
        save();
        screen.refresh();
    }

    private void applyPreset() {
        List<FxEffect> added = preset.build(scope);
        project().effects.addAll(added);
        if (!added.isEmpty()) {
            selectedId = added.get(0).id;
        }
        save();
        screen.refresh();
    }

    /** Moves a filter before or after its neighbour of the same scope. */
    private void move(FxEffect effect, int direction) {
        List<FxEffect> list = project().effects;
        int i = list.indexOf(effect);
        for (int j = i + direction; j >= 0 && j < list.size(); j += direction) {
            if (inScope(list.get(j))) {
                list.set(i, list.get(j));
                list.set(j, effect);
                save();
                screen.refresh();
                return;
            }
        }
    }

    private void duplicate(FxEffect effect) {
        FxEffect copy = effect.copy();
        List<FxEffect> list = project().effects;
        list.add(list.indexOf(effect) + 1, copy);
        selectedId = copy.id;
        save();
        screen.refresh();
    }

    private void delete(FxEffect effect) {
        project().effects.remove(effect);
        selectedId = null;
        save();
        screen.refresh();
    }

    /** Applies a preset from code (self test). */
    static List<FxEffect> applyPreset(ReplayProject project, FxPresets preset, String camera) {
        List<FxEffect> added = preset.build(camera);
        project.effects.addAll(added);
        return added;
    }

    // ---------------------------------------------------------------- transitions (Cameras tab)

    /** Transition controls of the selected cut, added under the cut track section of the Cameras tab. */
    static void buildTransition(Form f, EditorScreen screen, ReplaySession session, ReplayProject.Cut cut) {
        f.header(tc("justreplay.fx.transition"), Icon.FILM);
        ReplayProject project = session.project();
        if (cut == null || !project.cuts.contains(cut)) {
            f.paragraph(tc("justreplay.fx.transition.select"), Theme.TEXT_MUTED);
            return;
        }
        if (project.cuts.indexOf(cut) == 0) {
            f.paragraph(tc("justreplay.fx.transition.first"), Theme.TEXT_MUTED);
            return;
        }
        TransitionType type = cut.transitionType();
        f.full(new Dropdown<>(screen, tc("justreplay.fx.transition.type"), List.of(TransitionType.values()), type,
                t -> tc(t.translationKey()), v -> {
            cut.transition = v.name();
            session.saveProject();
            screen.refresh();
        }).icon(Icon.FILM).tip(tc("justreplay.fx.transition.tip")));
        if (type == TransitionType.CUT) {
            return;
        }
        f.full(new Stepper(tc("justreplay.fx.transition.duration"), () -> cut.transitionTicks / 20.0, "s", 2,
                () -> duration(session, cut, -1), () -> duration(session, cut, 1)).icon(Icon.CLOCK).tip(tc("justreplay.fx.duration.tip")));
        if (type.directional) {
            f.full(new Dropdown<>(screen, tc("justreplay.fx.transition.direction"), List.of(TransitionType.DIRECTIONS), cut.transitionDir,
                    d -> tc("justreplay.fx.direction." + d.toLowerCase(Locale.ROOT)), v -> {
                cut.transitionDir = v;
                session.saveProject();
            }).icon(Icon.ARROW_UP));
        }
    }

    private static void duration(ReplaySession session, ReplayProject.Cut cut, int direction) {
        if (Screen.hasShiftDown()) {
            cut.transitionTicks = Math.max(1, Math.min(200, cut.transitionTicks + direction));
        } else {
            int[] steps = TransitionType.DURATIONS;
            int value = cut.transitionTicks;
            if (direction > 0) {
                value = steps[steps.length - 1];
                for (int s : steps) {
                    if (s > cut.transitionTicks) {
                        value = s;
                        break;
                    }
                }
            } else {
                value = steps[0];
                for (int i = steps.length - 1; i >= 0; i--) {
                    if (steps[i] < cut.transitionTicks) {
                        value = steps[i];
                        break;
                    }
                }
            }
            cut.transitionTicks = value;
        }
        session.saveProject();
    }

    // ---------------------------------------------------------------- timeline marks

    /** Transition marks over the cut lane: a soft ramp from each cut over its transition. */
    static void renderTransitions(GuiGraphics g, EditorTimeline t, ReplayProject project, int y, int h) {
        List<ReplayProject.Cut> cuts = project.cuts;
        for (int i = 1; i < cuts.size(); i++) {
            ReplayProject.Cut cut = cuts.get(i);
            if (cut.transitionType() == TransitionType.CUT) {
                continue;
            }
            int a = t.xOf(cut.tick);
            int b = Math.max(a + 3, t.xOf(cut.tick + cut.transitionTicks));
            if (b < t.x - 2 || a > t.x + t.w + 2) {
                continue;
            }
            // A wedge growing over the transition, white at the cut.
            int steps = Math.max(1, b - a);
            for (int px = 0; px < steps; px++) {
                int top = y + 1 + Math.round((h - 3) * (1.0F - (float) px / steps));
                g.fill(a + px, top, a + px + 1, y + h - 1, 0x59FFFFFF);
            }
            g.fill(a, y + 1, a + 1, y + h - 1, 0xF2FFFFFF);
        }
    }

    /** Keyframes of the filters, as small marks along the bottom of a lane. */
    static void renderEffectKeys(GuiGraphics g, EditorTimeline t, ReplayProject project, int y, int h) {
        for (FxEffect effect : project.effects) {
            if (effect.keys.isEmpty()) {
                continue;
            }
            for (List<FxEffect.Key> list : effect.keys.values()) {
                for (FxEffect.Key key : list) {
                    int kx = t.xOf(key.tick);
                    if (kx < t.x - 3 || kx > t.x + t.w + 3) {
                        continue;
                    }
                    Paint.rect(g, kx - 1.5F, y + h - 4, 3, 3, 1, FX_COLOR);
                }
            }
        }
    }

    // ---------------------------------------------------------------- parameter slider

    /**
     * One parameter: {@code label [track] value <>}. The track shows the value at the play head; dragging it
     * changes the fixed value, or the keyframe at the play head once the parameter is animated. The diamond adds or
     * removes a keyframe at the play head; Shift + click on it clears every keyframe of the parameter.
     */
    private final class ParamSlider extends AbstractWidget implements Tipped {
        private static final int KEY_W = 12;
        private final FxParam param;
        private final FxEffect effect;
        private final Anim.Value hover = new Anim.Value(0.0F, Theme.FAST);
        private boolean dragging;
        private float shownValue = Float.NaN;
        private String shownText = "";
        private final String label;

        ParamSlider(FxParam param, FxEffect effect) {
            super(0, 0, 0, Theme.ROW, Component.translatable(param.translationKey()));
            this.param = param;
            this.effect = effect;
            this.label = getMessage().getString();
        }

        private int cursor() {
            return session.playback().cursor();
        }

        private int labelW() {
            return Math.min(58, width * 36 / 100);
        }

        private int trackX() {
            return getX() + labelW();
        }

        private int trackW() {
            return Math.max(10, width - labelW() - 30 - KEY_W);
        }

        private int keyX() {
            return getX() + width - KEY_W;
        }

        @Override
        public Component tooltip() {
            return tc("justreplay.fx.param.tip");
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int x = getX(), y = getY();
            float h = hover.to(isHovered() || dragging ? 1.0F : 0.0F);
            float value = effect.value(param, cursor());
            g.drawString(font, Paint.ellipsize(font, label, labelW() - 4), x + 3, y + (height - 7) / 2, Theme.TEXT_DIM, false);
            int tx = trackX(), tw = trackW();
            float t = (value - param.min()) / (param.max() - param.min());
            float ty = y + height / 2.0F - 2;
            Paint.rect(g, tx, ty, tw, 4, 2, Theme.TRACK);
            boolean animated = effect.animated(param);
            int fill = animated ? FX_COLOR : Theme.ACCENT;
            Paint.rect(g, tx, ty, Math.max(4, tw * t), 4, 2, fill);
            float kr = 3.5F + h;
            Paint.circle(g, tx + tw * t, ty + 2, kr, 0xFFFFFFFF);
            if (value != shownValue) {
                shownValue = value;
                float range = param.max() - param.min();
                shownText = range >= 20 ? Integer.toString(Math.round(value)) : String.format(Locale.ROOT, range >= 4 ? "%.1f" : "%.2f", value);
            }
            g.drawString(font, shownText, tx + tw + 3, y + (height - 7) / 2, Theme.TEXT, false);
            FxEffect.Key key = effect.keyAt(param, cursor());
            int color = key != null ? FX_COLOR : animated ? Theme.mix(FX_COLOR, Theme.TEXT_MUTED, 0.5F) : Theme.TEXT_MUTED;
            Paint.iconCentered(g, Icon.KEY, keyX(), y, KEY_W, height, key != null ? 9 : 7, color);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (mouseX >= keyX()) {
                toggleKey();
                return;
            }
            dragging = true;
            setFrom(mouseX);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dx, double dy) {
            if (dragging) {
                setFrom(mouseX);
            }
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            if (dragging) {
                dragging = false;
                save();
            }
        }

        private void setFrom(double mouseX) {
            double t = Math.max(0.0, Math.min(1.0, (mouseX - trackX()) / Math.max(1, trackW())));
            float v = (float) (param.min() + t * (param.max() - param.min()));
            v = Math.round(v / param.step()) * param.step();
            effect.edit(param, cursor(), param.clamp(v));
        }

        private void toggleKey() {
            if (Screen.hasShiftDown()) {
                effect.keys.remove(param.name());
            } else {
                FxEffect.Key key = effect.keyAt(param, cursor());
                if (key != null) {
                    effect.removeKey(param, key);
                } else {
                    effect.setKey(param, cursor(), effect.value(param, cursor()));
                }
            }
            save();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
