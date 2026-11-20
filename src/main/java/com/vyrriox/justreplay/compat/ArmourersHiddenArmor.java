package com.vyrriox.justreplay.compat;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.editor.ArmorSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Armor a replay hides takes its Armourer's Workshop skins with it. Armourer's Workshop draws its skins itself, from
 * the items it collects for each entity once a tick: the armor it wears (a skin can sit on any armor piece) and its
 * wardrobe. It keeps the skins it loaded from them until the collected items change. Both collectors pass through
 * here at their end (see the {@code ArmourersEquipmentSlotsMixin} and {@code ArmourersWardrobeSlotsMixin}), and the
 * items of a hidden slot come back empty: Armourer's Workshop sees a change and reloads the skins without them, and
 * reloads them again once the slot shows. {@link ArmourersArmorCover} says which wardrobe skins follow which slot.
 * <p>
 * Only players tick while a replay is paused, so the slots of other entities are collected again from
 * {@link #tick} when their hidden slots change: a switch in the editor shows at once. Everything is reached by
 * reflection. If this Armourer's Workshop build lacks one of the members used here, the skins of hidden slots simply
 * stay visible (one line in the log says so); the rest of the integration keeps working.
 *
 * @author vyrriox
 */
public final class ArmourersHiddenArmor {
    private static final String PKG = "moe.plushie.armourers_workshop.";
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /** Hidden slots each entity's skins were last collected again for, by entity id; client thread only. */
    private static final Map<Integer, Integer> REFRESHED = new HashMap<>();

    private static Reflect reflect;
    private static boolean unavailable;
    /** For the self test: armor pieces left out of the equipment Armourer's Workshop collected. */
    private static int equipmentHidden;
    /** For the self test: wardrobe collections seen during replays, and wardrobe skins left out of them. */
    private static int wardrobeCalls;
    private static int wardrobeHidden;

    private record Reflect(Field armourSlots, Field entityProvider, Method renderDataOf, Method tickSlots,
                           Method wardrobeOf, Method wardrobeEntity, Method slotTypeOf) {
    }

    private ArmourersHiddenArmor() {
    }

    private static Reflect reflect() {
        if (reflect != null || unavailable) {
            return reflect;
        }
        try {
            ClassLoader cl = ArmourersHiddenArmor.class.getClassLoader();
            Class<?> handler = Class.forName(PKG + "core.client.other.EntitySlotsHandler", false, cl);
            Class<?> entityProvider = Class.forName(PKG + "core.client.other.EntitySlotsHandler$EntityProvider", false, cl);
            Class<?> renderData = Class.forName(PKG + "core.client.other.EntityRenderData", false, cl);
            Class<?> wardrobe = Class.forName(PKG + "core.capability.SkinWardrobe", false, cl);
            Class<?> slotType = Class.forName(PKG + "core.menu.SkinSlotType", false, cl);
            reflect = new Reflect(accessible(entityProvider.getDeclaredField("armourSlots")),
                    accessible(handler.getDeclaredField("entityProvider")),
                    renderData.getMethod("of", Entity.class, boolean.class),
                    accessible(handler.getDeclaredMethod("tickSlots", Object.class, wardrobe)),
                    wardrobe.getMethod("of", Entity.class), wardrobe.getMethod("entity"),
                    slotType.getMethod("byItem", ItemStack.class));
        } catch (Throwable t) {
            unavailable = true;
            JustReplay.LOGGER.warn("Armourer's Workshop skins of hidden armor slots stay visible in replays: this build of the "
                    + "mod lacks {}", t.toString());
        }
        return reflect;
    }

    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T member) {
        member.setAccessible(true);
        return member;
    }

    /**
     * End of Armourer's Workshop's equipment collection for {@code entity}: the pieces of its hidden armor slots
     * leave both the list it keeps ({@code armourSlots}) and the list it compares with the last tick's.
     */
    public static void filterEquipment(Object provider, Object entity, List<?> collected) throws Exception {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        int hidden = ArmorSlots.hidden(living);
        if (hidden == 0) {
            return;
        }
        Reflect r = reflect();
        if (r == null) {
            return;
        }
        List<?> kept = (List<?>) r.armourSlots.get(provider);
        for (EquipmentSlot slot : ARMOR) {
            if ((hidden & ArmorSlots.bit(slot)) == 0) {
                continue;
            }
            ItemStack worn = living.getItemBySlot(slot);
            if (worn.isEmpty()) {
                continue;
            }
            if (empty(kept, worn)) {
                equipmentHidden++;
            }
            empty(collected, worn);
        }
    }

    /** End of Armourer's Workshop's wardrobe collection: the skins covering a hidden slot of its entity leave it. */
    public static void filterWardrobe(Object wardrobe, List<?> collected) throws Exception {
        wardrobeCalls++;
        Reflect r = reflect();
        if (r == null || !(r.wardrobeEntity.invoke(wardrobe) instanceof Entity entity)) {
            return;
        }
        int hidden = ArmorSlots.hidden(entity);
        if (hidden != 0) {
            wardrobeHidden += filterSkins(r, hidden, collected);
        }
    }

    /** Empties every skin item of {@code stacks} that covers one of the {@code hidden} slots; returns how many. */
    private static int filterSkins(Reflect r, int hidden, List<?> stacks) throws Exception {
        int count = 0;
        for (int i = 0; i < stacks.size(); i++) {
            if (stacks.get(i) instanceof ItemStack stack && !stack.isEmpty()
                    && r.slotTypeOf.invoke(null, stack) instanceof Enum<?> type
                    && ArmourersArmorCover.hides(hidden, type.name())) {
                set(stacks, i, ItemStack.EMPTY);
                count++;
            }
        }
        return count;
    }

    /** Replaces this very stack (not an equal one) by an empty stack in {@code list}; false if it was not there. */
    private static boolean empty(List<?> list, ItemStack stack) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == stack) {
                set(list, i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void set(List<?> list, int index, Object value) {
        ((List<Object>) list).set(index, value);
    }

    /**
     * Once a client tick of a replay: the skins of an entity whose hidden slots changed since its last collection here
     * are collected again, which a paused replay would otherwise leave until play goes on.
     */
    public static void tick(Minecraft mc) throws Exception {
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        Reflect r = null;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity)) {
                continue;
            }
            int hidden = ArmorSlots.hidden(entity);
            Integer last = REFRESHED.get(entity.getId());
            if ((last == null ? 0 : last) == hidden) {
                continue;
            }
            if (r == null && (r = reflect()) == null) {
                return;
            }
            refresh(r, entity);
            if (hidden == 0) {
                REFRESHED.remove(entity.getId());
            } else {
                REFRESHED.put(entity.getId(), hidden);
            }
        }
    }

    /** Runs Armourer's Workshop's slot collection for an entity it draws, as its own tick would. */
    private static void refresh(Reflect r, Entity entity) throws Exception {
        Object data = r.renderDataOf.invoke(null, entity, false);
        if (data != null) {
            r.tickSlots.invoke(data, entity, r.wardrobeOf.invoke(null, entity));
        }
    }

    /** The replay closed. */
    static void clear() {
        REFRESHED.clear();
    }

    /**
     * For the self test: the armor Armourer's Workshop keeps for {@code entity}, collected again first when
     * {@code collectNow}, or null when it does not draw this entity (or this build cannot be read).
     */
    public static List<ItemStack> collectedArmor(Entity entity, boolean collectNow) throws Exception {
        Reflect r = reflect();
        if (r == null) {
            return null;
        }
        Object data = r.renderDataOf.invoke(null, entity, collectNow);
        if (data == null) {
            return null;
        }
        if (collectNow) {
            r.tickSlots.invoke(data, entity, r.wardrobeOf.invoke(null, entity));
        }
        List<ItemStack> copy = new ArrayList<>();
        for (Object o : (List<?>) r.armourSlots.get(r.entityProvider.get(data))) {
            copy.add((ItemStack) o);
        }
        return copy;
    }

    /**
     * For the self test: wardrobe skins of each kind (head, chest, wings, legs, feet, outfit, sword) made with
     * Armourer's Workshop's own classes, filtered for a hidden head then for a hidden chest. Returns, per kind and
     * in that order, 'h' for hidden and 'k' for kept, the two runs separated by a slash (expected "hkkkkhk/khhkkhk"),
     * or null when this build cannot be read.
     */
    public static String wardrobeCoverCheck() throws Exception {
        Reflect r = reflect();
        if (r == null) {
            return null;
        }
        ClassLoader cl = ArmourersHiddenArmor.class.getClassLoader();
        Class<?> types = Class.forName(PKG + "core.skin.SkinTypes", false, cl);
        Class<?> skinType = Class.forName(PKG + "core.skin.SkinType", false, cl);
        Class<?> descriptor = Class.forName(PKG + "core.skin.SkinDescriptor", false, cl);
        Method asItemStack = descriptor.getMethod("asItemStack");
        StringBuilder out = new StringBuilder();
        for (int hidden : new int[]{ArmorSlots.HEAD, ArmorSlots.CHEST}) {
            List<ItemStack> stacks = new ArrayList<>();
            for (String kind : new String[]{"ARMOR_HEAD", "ARMOR_CHEST", "ARMOR_WINGS", "ARMOR_LEGS", "ARMOR_FEET", "OUTFIT", "ITEM_SWORD"}) {
                Object type = types.getField(kind).get(null);
                Object skin = descriptor.getConstructor(String.class, skinType).newInstance("justreplay:selftest_" + kind, type);
                stacks.add((ItemStack) asItemStack.invoke(skin));
            }
            filterSkins(r, hidden, stacks);
            if (!out.isEmpty()) {
                out.append('/');
            }
            for (ItemStack stack : stacks) {
                out.append(stack.isEmpty() ? 'h' : 'k');
            }
        }
        return out.toString();
    }

    public static int equipmentHidden() {
        return equipmentHidden;
    }

    public static int wardrobeCalls() {
        return wardrobeCalls;
    }

    public static int wardrobeHidden() {
        return wardrobeHidden;
    }
}
