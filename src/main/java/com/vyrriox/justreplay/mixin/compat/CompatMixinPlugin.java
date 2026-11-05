package com.vyrriox.justreplay.mixin.compat;

import com.vyrriox.justreplay.compat.CompatTargets;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the mixins of an integration only with its mod, on a loader where that integration has been read.
 *
 * @author vyrriox
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".Armourers")) {
            return CompatTargets.isActive("armourers_workshop");
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
