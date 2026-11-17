package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.CompatTargets;
import com.vyrriox.justreplay.platform.EarlyMods;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Leaves out the optional mixins that cannot apply with some mods: the section draw guard targets the vanilla section
 * renderer, which Sodium and Embeddium replace. The GeckoLib, Jade and Iris mixins apply only with their mod, and only
 * on a loader where CompatTargets lists the integration. A mixin of an integration that is off on this loader never
 * applies.
 *
 * @author vyrriox
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
    private static boolean loaded(String modId) {
        return EarlyMods.isLoaded(modId);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".SectionBufferGuardMixin")) {
            return !loaded("sodium") && !loaded("embeddium");
        }
        if (mixinClassName.contains(".GeckoLib")) {
            return CompatTargets.isActive("geckolib");
        }
        if (mixinClassName.contains(".Jade")) {
            return CompatTargets.isActive("jade");
        }
        if (mixinClassName.contains(".Iris")) {
            return CompatTargets.isActive("iris");
        }
        if (mixinClassName.contains(".Bobby")) {
            return CompatTargets.isSupported("bobby");
        }
        if (mixinClassName.contains(".PlayerAnim")) {
            return CompatTargets.isSupported(CompatTargets.PLAYER_ANIMATOR);
        }
        if (mixinClassName.contains(".WaterFrames")) {
            return CompatTargets.isSupported("waterframes");
        }
        return true;
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
