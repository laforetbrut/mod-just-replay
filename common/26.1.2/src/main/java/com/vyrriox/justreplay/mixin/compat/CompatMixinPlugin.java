package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.CompatTargets;
import com.vyrriox.justreplay.platform.EarlyMods;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Chooses the optional mixins. A mixin of an integration applies only while that integration may run: its mod is
 * installed (for most of them), the integration is supported on this loader and the {@code compat} section of the
 * config leaves it on (see {@link CompatTargets#mixinsApply}). The section draw guard targets the vanilla section
 * renderer, which Sodium and Embeddium replace. An error while choosing leaves the mixin out: a mixin that is not
 * applied costs a feature, a wrong one could stop the game from starting.
 *
 * @author vyrriox
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
    private static boolean loaded(String modId) {
        return EarlyMods.isLoaded(modId);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            if (mixinClassName.endsWith(".SectionBufferGuardMixin")) {
                return !loaded("sodium") && !loaded("embeddium");
            }
            String owner = CompatTargets.mixinOwner(mixinClassName);
            return owner == null || CompatTargets.mixinsApply(owner);
        } catch (Throwable t) {
            LoggerFactory.getLogger(CompatMixinPlugin.class).warn("Leaving out the mixin {}: {}", mixinClassName, t.toString());
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
