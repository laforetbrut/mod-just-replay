package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.editor.ReplayProject;
import com.vyrriox.justreplay.editor.SkinLibrary;
import com.vyrriox.justreplay.editor.SkinSaver;
import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassSwitch;
import com.vyrriox.justreplay.gui.ui.GlassToast;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * "Skin changes" section shared by the actor screen and the entities panel: the timed skin changes of a player, a
 * file picker and slim switch for a new change added at the playback cursor, and a button that saves the skin the
 * player shows right now into the skins folder so it can be painted on.
 *
 * @author vyrriox
 */
final class SkinChangesSection {
    private final String prefix;
    private final Supplier<List<ReplayProject.SkinChange>> changes;
    private final IntSupplier cursor;
    private final Runnable rebuild;
    private final Consumer<List<ReplayProject.SkinChange>> commit;
    /** File of the next change; empty goes back to the base skin. */
    private String file = "";
    private boolean slim;

    /**
     * @param prefix  translation key prefix, {@code justreplay.actor.skinchange} or {@code justreplay.entities.skinchange}
     * @param changes the current changes, never modified here
     * @param commit  receives the new list after an add or a delete, and refreshes the screen
     * @param rebuild refreshes the screen after a selection or a saved file
     */
    SkinChangesSection(String prefix, Supplier<List<ReplayProject.SkinChange>> changes, IntSupplier cursor,
                       Consumer<List<ReplayProject.SkinChange>> commit, Runnable rebuild) {
        this.prefix = prefix;
        this.changes = changes;
        this.cursor = cursor;
        this.commit = commit;
        this.rebuild = rebuild;
    }

    void build(Form f, Supplier<Entity> entity, Supplier<String> label) {
        f.header(Component.translatable(prefix + ".title"), Icon.PERSON);
        List<ReplayProject.SkinChange> current = changes.get();
        if (current.isEmpty()) {
            f.paragraph(Component.translatable(prefix + ".none"), Theme.TEXT_MUTED);
        }
        for (ReplayProject.SkinChange change : current) {
            Component text = change.file.isBlank() ? Component.translatable(prefix + ".base") : Component.literal(change.file);
            f.full(new ListRow(text, b -> {
                file = change.file;
                slim = change.slim;
                rebuild.run();
            }).detail("t" + change.tick + (change.slim ? " slim" : "")).icon(Icon.PERSON)
                    .action(() -> Icon.TRASH, Component.translatable("justreplay.editor.delete"), () -> {
                        List<ReplayProject.SkinChange> next = new ArrayList<>(changes.get());
                        next.remove(change);
                        commit.accept(next);
                    }));
        }
        int w = f.cellWidth(1, 2);
        f.at(new GlassButton(fileLabel(), b -> {
            cycle();
            b.setMessage(fileLabel());
        }).icon(Icon.PERSON).tip(Component.translatable(prefix + ".file.tip")), 0, w);
        f.at(new GlassSwitch(Component.translatable("justreplay.actor.slim"), () -> slim, () -> slim = !slim).icon(Icon.PERSON),
                f.cellX(1, 2), w);
        f.next();
        f.put(new GlassButton(Component.translatable(prefix + ".add"), b -> add()).icon(Icon.PLUS)
                .style(GlassButton.Style.PRIMARY).tip(Component.translatable(prefix + ".add.tip")), 0, 2);
        f.put(new GlassButton(Component.translatable(prefix + ".save"), b -> save(entity.get(), label.get())).icon(Icon.SAVE)
                .tip(Component.translatable(prefix + ".save.tip")), 1, 2);
        f.next();
    }

    private Component fileLabel() {
        return file.isBlank() ? Component.translatable(prefix + ".base") : Component.literal(file);
    }

    private void cycle() {
        List<String> files = new ArrayList<>();
        files.add("");
        files.addAll(SkinLibrary.list());
        int index = files.indexOf(file);
        file = files.get((index + 1) % files.size());
    }

    private void add() {
        int tick = Math.max(0, cursor.getAsInt());
        List<ReplayProject.SkinChange> next = new ArrayList<>(changes.get());
        next.removeIf(c -> c.tick == tick);
        next.add(new ReplayProject.SkinChange(tick, file, slim));
        next.sort(java.util.Comparator.comparingInt(c -> c.tick));
        commit.accept(next);
    }

    private void save(Entity entity, String label) {
        if (entity == null) {
            GlassToast.show(Icon.PERSON, Theme.DANGER, Component.translatable(prefix + ".save.failed"),
                    Component.translatable(prefix + ".save.noentity"));
            return;
        }
        SkinSaver.saveCurrent(entity, label, written -> {
            if (written == null) {
                GlassToast.show(Icon.PERSON, Theme.DANGER, Component.translatable(prefix + ".save.failed"), null);
                return;
            }
            file = written;
            GlassToast.show(Icon.SAVE, Theme.SUCCESS, Component.translatable(prefix + ".save.done"), Component.literal(written));
            rebuild.run();
        });
    }
}
