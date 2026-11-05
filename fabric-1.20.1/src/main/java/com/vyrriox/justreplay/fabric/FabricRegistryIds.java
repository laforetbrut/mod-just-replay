package com.vyrriox.justreplay.fabric;

import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.fabric.mixin.MappedRegistryAccessor;
import com.vyrriox.justreplay.format.RegistryIdMap;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.fabricmc.fabric.api.event.registry.RegistryAttributeHolder;
import net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.fabricmc.fabric.impl.registry.sync.RemapException;
import net.fabricmc.fabric.impl.registry.sync.RemappableRegistry;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry ids on Fabric. The file is a format byte, a var int count, then for each registry its name and its ids.
 * <p>
 * Ids are applied through {@link RemappableRegistry#remap} on the registries Fabric itself syncs, which keeps the block
 * state ids and the other id tables right. {@code RegistrySyncManager.apply} is not used: it skips a registry no local
 * mod added to, and a recording can name more entries than that registry has here. Fabric puts the local ids back when
 * the world is left ({@code RegistrySyncManager.unmap} at the end of {@code Minecraft.disconnect}).
 * <p>
 * An entry a recording names but this game lacks would leave a null in the registry's id table, and then iterating the
 * registry, the next remap and the unmap all throw. {@code StateIdTracker} iterates during the remap event, so the holes
 * are filled in an earlier phase of that event, with a holder the registry already has.
 *
 * @author vyrriox
 */
final class FabricRegistryIds implements RegistryIdsBackend {
    private static final int FORMAT = 1;
    private static final ResourceLocation FILL_PHASE = new ResourceLocation("justreplay", "fill_holes");

    private final Set<Registry<?>> hooked = Collections.newSetFromMap(new IdentityHashMap<>());

    /** A registry whose ids change. */
    private record Remap(Registry<?> registry, Object2IntMap<ResourceLocation> ids, int highest) {
    }

    @Override
    public byte[] encode() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            Map<ResourceLocation, RegistryIdMap.Table> tables = current().tables();
            buf.writeByte(FORMAT);
            buf.writeVarInt(tables.size());
            tables.forEach((name, table) -> {
                buf.writeResourceLocation(name);
                buf.writeMap(table.ids(), FriendlyByteBuf::writeVarInt, FriendlyByteBuf::writeResourceLocation);
            });
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } catch (Exception e) {
            JustReplay.LOGGER.error("Cannot encode the registry ids", e);
            return null;
        } finally {
            buf.release();
        }
    }

    @Override
    public RegistryIdMap decode(byte[] bytes) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            int format = buf.readByte();
            if (format != FORMAT) {
                throw new IllegalArgumentException("Registry ids not written on Fabric, format " + format);
            }
            int count = buf.readVarInt();
            Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>();
            for (int i = 0; i < count; i++) {
                ResourceLocation name = buf.readResourceLocation();
                Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>(
                        buf.readMap(FriendlyByteBuf::readVarInt, FriendlyByteBuf::readResourceLocation));
                tables.put(name, new RegistryIdMap.Table(ids, Map.of()));
            }
            return new RegistryIdMap(tables);
        } finally {
            buf.release();
        }
    }

    @Override
    public RegistryIdMap current() {
        Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            tables.put(registry.key().location(), new RegistryIdMap.Table(ids(registry), Map.of()));
        }
        return new RegistryIdMap(tables);
    }

    private static <T> Int2ObjectSortedMap<ResourceLocation> ids(Registry<T> registry) {
        Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>();
        for (T value : registry) {
            ids.put(registry.getId(value), registry.getKey(value));
        }
        return ids;
    }

    @Override
    public boolean apply(RegistryIdMap ids) {
        List<Remap> remaps = new ArrayList<>();
        int missing = 0;
        ResourceLocation example = null;
        for (Map.Entry<ResourceLocation, RegistryIdMap.Table> entry : ids.tables().entrySet()) {
            Registry<?> registry = BuiltInRegistries.REGISTRY.get(entry.getKey());
            if (!(registry instanceof RemappableRegistry) || !(registry instanceof MappedRegistry<?>) || !synced(registry)) {
                continue;
            }
            Int2ObjectSortedMap<ResourceLocation> table = entry.getValue().ids();
            if (table.isEmpty() || table.equals(ids(registry))) {
                continue;
            }
            if (table.firstIntKey() < 0) {
                JustReplay.LOGGER.error("Registry ids of {} start below zero", entry.getKey());
                return false;
            }
            Object2IntMap<ResourceLocation> known = new Object2IntOpenHashMap<>();
            for (Int2ObjectMap.Entry<ResourceLocation> id : table.int2ObjectEntrySet()) {
                if (registry.containsKey(id.getValue())) {
                    known.put(id.getValue(), id.getIntKey());
                } else {
                    missing++;
                    example = id.getValue();
                }
            }
            remaps.add(new Remap(registry, known, table.lastIntKey()));
        }
        if (missing > 0) {
            JustReplay.LOGGER.warn("{} registry entries of this replay are unknown here (mod missing?), e.g. {}", missing, example);
        }
        try {
            for (Remap remap : remaps) {
                hook(remap.registry());
                ((RemappableRegistry) remap.registry()).remap(remap.registry().key().location().toString(), remap.ids(),
                        RemappableRegistry.RemapMode.AUTHORITATIVE);
                // Ids above every local entry leave no hole during the remap, but a recorded packet can still name them.
                fillHoles(remap.registry(), remap.highest() + 1);
            }
            return true;
        } catch (RemapException | RuntimeException e) {
            JustReplay.LOGGER.error("Cannot apply the registry ids, keeping the ids of this game", e);
            rollback();
            return false;
        }
    }

    private static boolean synced(Registry<?> registry) {
        return RegistryAttributeHolder.get(registry.key()).hasAttribute(RegistryAttribute.SYNCED);
    }

    /** Runs the fill before the listeners of the remap event, some of which iterate the registry. */
    private <T> void hook(Registry<T> registry) {
        if (hooked.add(registry)) {
            Event<RegistryIdRemapCallback<T>> event = RegistryIdRemapCallback.event(registry);
            event.addPhaseOrdering(FILL_PHASE, Event.DEFAULT_PHASE);
            event.register(FILL_PHASE, state -> fillHoles(registry, 0));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectList<Holder.Reference<T>> byId(Registry<T> registry) {
        return ((MappedRegistryAccessor<T>) registry).justreplay$getById();
    }

    /**
     * Makes every id below {@code size}, and every one already in the table, read as a placeholder entry. The entry
     * keeps its own id: only the table slot points at it, so a recorded id without an entry decodes to the placeholder
     * and the next remap drops the slot.
     */
    private static <T> void fillHoles(Registry<T> registry, int size) {
        ObjectList<Holder.Reference<T>> slots = byId(registry);
        Holder.Reference<T> placeholder = placeholder(registry, slots);
        if (placeholder == null) {
            return;
        }
        if (slots.size() < size) {
            slots.size(size);
        }
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) == null) {
                slots.set(i, placeholder);
            }
        }
    }

    @Nullable
    private static <T> Holder.Reference<T> placeholder(Registry<T> registry, ObjectList<Holder.Reference<T>> slots) {
        if (registry instanceof DefaultedRegistry<T> defaulted) {
            ResourceKey<T> key = ResourceKey.create(registry.key(), defaulted.getDefaultKey());
            Holder.Reference<T> value = registry.getHolder(key).orElse(null);
            if (value != null) {
                return value;
            }
        }
        for (Holder.Reference<T> slot : slots) {
            if (slot != null) {
                return slot;
            }
        }
        return null;
    }

    private static void rollback() {
        try {
            for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
                if (registry instanceof MappedRegistry<?>) {
                    fillHoles(registry, 0);
                }
            }
            RegistrySyncManager.unmap();
        } catch (RemapException | RuntimeException e) {
            JustReplay.LOGGER.error("Cannot put the local registry ids back: restart the game before opening a world", e);
        }
    }

    @Override
    public void selfCheck(CheckLog log) {
        RegistryIdsBackend.super.selfCheck(log);
        checkFile(log);
        try {
            checkMissingEntries(log);
        } catch (RemapException e) {
            log.log("registry ids unmap: {} -> FAIL", e.toString());
        }
    }

    /** The file format: what {@link #encode()} writes reads back as the same ids. */
    private void checkFile(CheckLog log) {
        byte[] bytes = encode();
        boolean same = bytes != null && decode(bytes).tables().equals(current().tables());
        log.log("registry ids file: {} bytes read back the same -> {}", bytes == null ? -1 : bytes.length, same ? "OK" : "FAIL");
    }

    /**
     * A recording naming entries this game lacks, in the two places that behave differently: inside the local ids and
     * above them. Then what leaving a world does (the unmap) and what joining the next one does (another apply). The
     * last block keeps the last place in the state ids, so a replay running during this check keeps its chunks right.
     */
    private void checkMissingEntries(CheckLog log) throws RemapException {
        RegistrySyncManager.unmap();
        RegistryIdMap local = current();
        List<BlockState> states = states();

        RegistryIdMap recorded = withMissingEntries(local);
        boolean applied = apply(recorded);
        log.log("registry ids with missing entries applied: {} -> {}", applied, applied ? "OK" : "FAIL");
        logMissingEntries(log, "first apply", local, states.size());

        RegistrySyncManager.unmap();
        logRestored(log, "unmap after missing entries", local, states);

        applied = apply(recorded);
        log.log("registry ids reconnect after unmap: applied {} -> {}", applied, applied ? "OK" : "FAIL");
        logMissingEntries(log, "reconnect", local, states.size());

        RegistrySyncManager.unmap();
        logRestored(log, "second unmap", local, states);
    }

    private static RegistryIdMap withMissingEntries(RegistryIdMap local) {
        Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>(local.tables());
        for (Registry<?> registry : List.of(BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM)) {
            ResourceLocation name = registry.key().location();
            Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>(tables.get(name).ids());
            int last = ids.lastIntKey();
            ResourceLocation lastKey = ids.remove(last);
            ids.put(last, new ResourceLocation("justreplay", "selftest_missing_inside"));
            ids.put(last + 1, lastKey);
            ids.put(last + 2, new ResourceLocation("justreplay", "selftest_missing_above"));
            tables.put(name, new RegistryIdMap.Table(ids, Map.of()));
        }
        return new RegistryIdMap(tables);
    }

    private static void logMissingEntries(CheckLog log, String step, RegistryIdMap local, int stateCount) {
        for (Registry<?> registry : List.of(BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM)) {
            logMissingEntries(log, step, registry, local.tables().get(registry.key().location()).ids());
        }
        int wrong = 0;
        int count = 0;
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            count++;
            if (Block.BLOCK_STATE_REGISTRY.byId(Block.BLOCK_STATE_REGISTRY.getId(state)) != state) {
                wrong++;
            }
        }
        log.log("registry ids {}: block states {} of {}, wrong {} -> {}", step, count, stateCount, wrong,
                count == stateCount && wrong == 0 ? "OK" : "FAIL");
    }

    private static <T> void logMissingEntries(CheckLog log, String step, Registry<T> registry, Int2ObjectSortedMap<ResourceLocation> local) {
        int holes = 0;
        for (Holder.Reference<T> slot : byId(registry)) {
            if (slot == null) {
                holes++;
            }
        }
        int iterated = 0;
        for (T ignored : registry) {
            iterated++;
        }
        int last = local.lastIntKey();
        T moved = registry.get(local.get(last));
        boolean ok = holes == 0 && iterated == last + 3 && registry.byId(last) != null && registry.byId(last + 2) != null
                && registry.getId(moved) == last + 1;
        log.log("registry ids {} {}: holes {}, iterated {}, last entry at {} (was {}) -> {}", step, registry.key().location(),
                holes, iterated, registry.getId(moved), last, ok ? "OK" : "FAIL");
    }

    private void logRestored(CheckLog log, String step, RegistryIdMap local, List<BlockState> states) {
        boolean ids = current().tables().equals(local.tables());
        boolean sameStates = states().equals(states);
        log.log("registry ids {}: ids back {}, block states back {} -> {}", step, ids, sameStates, ids && sameStates ? "OK" : "FAIL");
    }

    private static List<BlockState> states() {
        List<BlockState> states = new ArrayList<>();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            states.add(state);
        }
        return states;
    }
}
