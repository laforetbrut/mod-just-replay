package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.replay.ClockRates;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers the rate each clock received, see {@link ClockRates}.
 *
 * @author vyrriox
 */
@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin implements ClockRates {
    @Shadow
    @Final
    private Map<Holder<WorldClock>, ?> clocks;

    @Unique
    private final Map<Holder<WorldClock>, Float> justreplay$rates = new HashMap<>();

    @Inject(method = "handleUpdates(JLjava/util/Map;)V", at = @At("HEAD"))
    private void justreplay$onUpdates(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> updates, CallbackInfo ci) {
        updates.forEach((clock, state) -> justreplay$rates.put(clock, state.rate()));
    }

    @Override
    public float justreplay$rate(Holder<WorldClock> clock) {
        return justreplay$rates.getOrDefault(clock, 1.0F);
    }

    @Override
    public float justreplay$partialTick(Holder<WorldClock> clock) {
        Object instance = clocks.get(clock);
        return instance == null ? 0.0F : ((ClockInstanceAccessor) instance).justreplay$getPartialTick();
    }
}
