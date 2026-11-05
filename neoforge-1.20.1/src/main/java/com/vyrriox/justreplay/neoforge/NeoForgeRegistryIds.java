package com.vyrriox.justreplay.neoforge;

import com.google.common.collect.Multimap;
import com.vyrriox.justreplay.JustReplay;
import com.vyrriox.justreplay.format.RegistryIdMap;
import com.vyrriox.justreplay.format.RegistryIdsBackend;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry ids through NeoForge's snapshots. The file is a format byte, a var int count, then for each registry its name,
 * its ids and its aliases.
 * <p>
 * {@link GameData#injectSnapshot} removes every entry it is not given from the active registry, and the next snapshot
 * then sees it as unknown. The frozen data is therefore injected with it, as NeoForge does for a world loaded from disk,
 * so an entry the recording lacks stays in the registry with a free id, whatever {@code RegistryIds.complete} covered.
 * <p>
 * An entry a recording names but this game lacks is removed from the snapshot here. NeoForge would otherwise post
 * {@code MissingMappingsEvent} to the mods on a live game and refuse the whole snapshot when nothing claims the entry.
 * Its id stays free, and reads as the registry's default value.
 * <p>
 * NeoForge puts the local ids back when the replay's host server stops ({@code ServerLifecycleHooks.handleServerStopped}),
 * and when a level of a remote connection closes.
 *
 * @author vyrriox
 */
final class NeoForgeRegistryIds implements RegistryIdsBackend {
    /** Above the format byte of Fabric. A NeoForge file starts with its registry count, which is never read as this. */
    private static final int FORMAT = 2;

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
                buf.writeMap(table.aliases(), FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeResourceLocation);
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
                throw new IllegalArgumentException("Registry ids not written on NeoForge, format " + format);
            }
            int count = buf.readVarInt();
            Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>();
            for (int i = 0; i < count; i++) {
                ResourceLocation name = buf.readResourceLocation();
                Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>(
                        buf.readMap(FriendlyByteBuf::readVarInt, FriendlyByteBuf::readResourceLocation));
                Map<ResourceLocation, ResourceLocation> aliases = buf.readMap(FriendlyByteBuf::readResourceLocation, FriendlyByteBuf::readResourceLocation);
                tables.put(name, new RegistryIdMap.Table(ids, aliases));
            }
            return new RegistryIdMap(tables);
        } finally {
            buf.release();
        }
    }

    @Override
    public RegistryIdMap current() {
        Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>();
        RegistryManager.ACTIVE.takeSnapshot(false).forEach((name, snapshot) -> {
            Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>();
            snapshot.ids.forEach((key, id) -> ids.put(id.intValue(), key));
            tables.put(name, new RegistryIdMap.Table(ids, new HashMap<>(snapshot.aliases)));
        });
        return new RegistryIdMap(tables);
    }

    @Override
    public boolean apply(RegistryIdMap ids) {
        Map<ResourceLocation, ForgeRegistry.Snapshot> snapshots = new HashMap<>();
        int missing = 0;
        ResourceLocation example = null;
        for (Map.Entry<ResourceLocation, RegistryIdMap.Table> entry : ids.tables().entrySet()) {
            ForgeRegistry<Object> registry = RegistryManager.ACTIVE.getRegistry(entry.getKey());
            if (registry == null) {
                continue;
            }
            Set<ResourceLocation> known = registry.getKeys();
            ForgeRegistry.Snapshot snapshot = new ForgeRegistry.Snapshot();
            for (Int2ObjectMap.Entry<ResourceLocation> id : entry.getValue().ids().int2ObjectEntrySet()) {
                if (known.contains(id.getValue())) {
                    snapshot.ids.put(id.getValue(), id.getIntKey());
                } else {
                    missing++;
                    example = id.getValue();
                }
            }
            snapshot.aliases.putAll(entry.getValue().aliases());
            // The mod that overrides an entry is a matter of this game, not of the recording.
            registry.makeSnapshot().overrides.forEach((key, owner) -> {
                if (snapshot.ids.containsKey(key)) {
                    snapshot.overrides.put(key, owner);
                }
            });
            snapshots.put(entry.getKey(), snapshot);
        }
        if (missing > 0) {
            JustReplay.LOGGER.warn("{} registry entries of this replay are unknown here (mod missing?), e.g. {}", missing, example);
        }
        try {
            Multimap<ResourceLocation, ResourceLocation> refused = GameData.injectSnapshot(snapshots, true, false);
            if (!refused.isEmpty()) {
                JustReplay.LOGGER.error("NeoForge refused the registry ids, keeping the ids of this game: {}", refused);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            JustReplay.LOGGER.error("Cannot apply the registry ids, keeping the ids of this game", e);
            GameData.revertToFrozen();
            return false;
        }
    }

    @Override
    public void selfCheck(CheckLog log) {
        RegistryIdsBackend.super.selfCheck(log);
        checkFile(log);
        checkMissingEntries(log);
    }

    /** The file format: what {@link #encode()} writes reads back as the same ids. */
    private void checkFile(CheckLog log) {
        byte[] bytes = encode();
        boolean same = bytes != null && decode(bytes).tables().equals(current().tables());
        log.log("registry ids file: {} bytes read back the same -> {}", bytes == null ? -1 : bytes.length, same ? "OK" : "FAIL");
    }

    /**
     * A recording naming entries this game lacks, in the two places that behave differently: inside the local ids and
     * above them. Then what leaving a world does (the revert) and what joining the next one does (another apply). The
     * last block keeps the last place in the state ids, so a replay running during this check keeps its chunks right.
     */
    private void checkMissingEntries(CheckLog log) {
        GameData.revertToFrozen();
        RegistryIdMap local = current();
        List<BlockState> states = states();

        RegistryIdMap recorded = withMissingEntries(local);
        boolean applied = apply(recorded);
        log.log("registry ids with missing entries applied: {} -> {}", applied, applied ? "OK" : "FAIL");
        logMissingEntries(log, "first apply", local, states.size());

        GameData.revertToFrozen();
        logRestored(log, "revert after missing entries", local, states);

        applied = apply(recorded);
        log.log("registry ids reconnect after revert: applied {} -> {}", applied, applied ? "OK" : "FAIL");
        logMissingEntries(log, "reconnect", local, states.size());

        GameData.revertToFrozen();
        logRestored(log, "second revert", local, states);
    }

    private static RegistryIdMap withMissingEntries(RegistryIdMap local) {
        Map<ResourceLocation, RegistryIdMap.Table> tables = new HashMap<>(local.tables());
        for (Registry<?> registry : List.of(BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM)) {
            ResourceLocation name = registry.key().location();
            RegistryIdMap.Table table = tables.get(name);
            Int2ObjectSortedMap<ResourceLocation> ids = new Int2ObjectRBTreeMap<>(table.ids());
            int last = ids.lastIntKey();
            ResourceLocation lastKey = ids.remove(last);
            ids.put(last, new ResourceLocation("justreplay", "selftest_missing_inside"));
            ids.put(last + 1, lastKey);
            ids.put(last + 2, new ResourceLocation("justreplay", "selftest_missing_above"));
            tables.put(name, new RegistryIdMap.Table(ids, table.aliases()));
        }
        return new RegistryIdMap(tables);
    }

    private static void logMissingEntries(CheckLog log, String step, RegistryIdMap local, int stateCount) {
        for (DefaultedRegistry<?> registry : List.of(BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM)) {
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

    /** No entry is lost, the moved entry has its recorded id, and an id without an entry reads as the default value. */
    private static <T> void logMissingEntries(CheckLog log, String step, DefaultedRegistry<T> registry, Int2ObjectSortedMap<ResourceLocation> local) {
        int iterated = 0;
        for (T ignored : registry) {
            iterated++;
        }
        int last = local.lastIntKey();
        T moved = registry.get(local.get(last));
        T fallback = registry.get(registry.getDefaultKey());
        boolean ok = iterated == local.size() && registry.byId(last) == fallback && registry.byId(last + 2) == fallback
                && registry.getId(moved) == last + 1;
        log.log("registry ids {} {}: iterated {} of {}, last entry at {} (was {}) -> {}", step, registry.key().location(),
                iterated, local.size(), registry.getId(moved), last, ok ? "OK" : "FAIL");
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
