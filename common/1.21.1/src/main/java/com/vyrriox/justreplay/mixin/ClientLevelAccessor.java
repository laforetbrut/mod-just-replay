package com.vyrriox.justreplay.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Reads the map contents the client holds, for snapshots.
 *
 * @author vyrriox
 */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
    @Accessor("mapData")
    Map<MapId, MapItemSavedData> justreplay$getMapData();
}
