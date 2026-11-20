package com.vyrriox.justreplay.gui;

import com.vyrriox.justreplay.gui.ui.Form;
import com.vyrriox.justreplay.gui.ui.GlassButton;
import com.vyrriox.justreplay.gui.ui.GlassTextField;
import com.vyrriox.justreplay.gui.ui.Icon;
import com.vyrriox.justreplay.gui.ui.ListPopup;
import com.vyrriox.justreplay.gui.ui.ListRow;
import com.vyrriox.justreplay.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A list of hidden types in an editor tab (particle types, entity types): a search field, a button that lists the
 * matching types to hide one, and a row per hidden type that shows it again when clicked.
 *
 * @author vyrriox
 */
final class TypeList {
    /** Search text of each list, kept while the panel rebuilds. */
    private static final Map<String, String> QUERIES = new HashMap<>();

    private TypeList() {
    }

    /**
     * @param key     names the list: its search text and the {@code justreplay.types.<key>.*} texts
     * @param all     every type id that can be hidden, in display order
     * @param nameOf  readable name of a type id
     * @param hidden  the hidden ids
     * @param toggle  hides or shows an id, then saves
     */
    static void build(EditorScreen screen, Form f, String key, List<String> all, Function<String, String> nameOf,
                      List<String> hidden, Consumer<String> toggle) {
        Minecraft mc = Minecraft.getInstance();
        GlassTextField search = new GlassTextField(mc.font, 0, Component.translatable("justreplay.types.search"));
        search.leading(Icon.SEARCH, 12);
        search.setHint(Component.translatable("justreplay.types.search").withColor(Theme.TEXT_MUTED));
        search.setValue(QUERIES.getOrDefault(key, ""));
        search.setResponder(text -> QUERIES.put(key, text));
        f.put(search, 0, 2, 3);
        f.put(new GlassButton(Component.translatable("justreplay.types." + key + ".add"), b -> {
            List<String> ids = matching(all, hidden, nameOf, QUERIES.getOrDefault(key, ""));
            if (ids.isEmpty()) {
                return;
            }
            List<String> labels = new ArrayList<>();
            for (String id : ids) {
                String name = nameOf.apply(id);
                labels.add(name.equals(id) ? id : name + "  (" + id + ")");
            }
            screen.openPopup(new ListPopup(b.getX(), b.getY(), b.getWidth(), b.getHeight(), screen.width, screen.height, mc.font,
                    labels, -1, i -> {
                screen.closePopup();
                toggle.accept(ids.get(i));
            }));
        }).icon(Icon.EYE_OFF).tip(Component.translatable("justreplay.types." + key + ".add.tip")), 2, 3);
        f.next();
        if (hidden.isEmpty()) {
            f.paragraph(Component.translatable("justreplay.types." + key + ".none"), Theme.TEXT_MUTED);
            return;
        }
        for (String id : List.copyOf(hidden)) {
            f.full(new ListRow(Component.literal(nameOf.apply(id)), b -> toggle.accept(id)).detail(id).icon(Icon.EYE_OFF));
        }
        f.paragraph(Component.translatable("justreplay.types.show_again"), Theme.TEXT_MUTED);
    }

    /** Ids not hidden yet whose id or name holds the search text, ignoring case. */
    static List<String> matching(List<String> all, List<String> hidden, Function<String, String> nameOf, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        for (String id : all) {
            if (hidden.contains(id)) {
                continue;
            }
            if (q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q) || nameOf.apply(id).toLowerCase(Locale.ROOT).contains(q)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
