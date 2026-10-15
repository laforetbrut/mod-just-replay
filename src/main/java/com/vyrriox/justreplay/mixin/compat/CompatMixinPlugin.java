package com.vyrriox.justreplay.mixin.compat;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Leaves out the optional mixins that cannot apply with some mods: the section draw guard targets the vanilla section
 * renderer, which Sodium and Embeddium replace.
 *
 * @author vyrriox
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
    private static boolean loaded(String modId) {
        var list = FMLLoader.getLoadingModList();
        return list != null && list.getModFileById(modId) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".SectionBufferGuardMixin")) {
            return !loaded("sodium") && !loaded("embeddium");
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
