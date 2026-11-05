package com.vyrriox.justreplay.mixin.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Map;

/**
 * Pehkui writes a scale packet from the live scale data of the entity, so a packet read from the network (which only
 * keeps the received values) is written back empty. A recorder writes every received packet again, and a seek copies
 * them, so scale changes were lost. A packet that has only received values now writes those values, in the format
 * Pehkui reads. Packets built by Pehkui itself are untouched. Optional: does nothing when Pehkui is absent.
 *
 * @author vyrriox
 */
@Pseudo
@Mixin(targets = "virtuoel.pehkui.network.ScalePacket", remap = false)
public abstract class PehkuiPacketWriteMixin {
    @Shadow
    @Final
    public int entityId;
    @Shadow
    @Final
    public Collection<?> scales;
    @Shadow
    @Final
    public Map<Identifier, CompoundTag> syncedScales;

    @Inject(method = "write", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void justreplay$writeReceived(FriendlyByteBuf buf, CallbackInfo ci) {
        if (!scales.isEmpty() || syncedScales.isEmpty()) {
            return;
        }
        buf.writeVarInt(entityId);
        buf.writeInt(syncedScales.size());
        for (Map.Entry<Identifier, CompoundTag> entry : syncedScales.entrySet()) {
            CompoundTag tag = entry.getValue();
            buf.writeResourceLocation(entry.getKey());
            buf.writeFloat(tag.getFloat("scale"));
            buf.writeFloat(tag.getFloat("previous"));
            buf.writeFloat(tag.getFloat("initial"));
            buf.writeFloat(tag.getFloat("target"));
            buf.writeInt(tag.getInt("ticks"));
            buf.writeInt(tag.getInt("total_ticks"));
            ListTag modifiers = tag.getList("baseValueModifiers", Tag.TAG_STRING);
            buf.writeInt(modifiers.size());
            for (int i = 0; i < modifiers.size(); i++) {
                buf.writeUtf(modifiers.getString(i));
            }
            buf.writeByte(tag.contains("persistent") ? (tag.getBoolean("persistent") ? 1 : 0) : -1);
            boolean easing = tag.contains("easing");
            buf.writeBoolean(easing);
            if (easing) {
                buf.writeUtf(tag.getString("easing"));
            }
        }
        ci.cancel();
    }
}
