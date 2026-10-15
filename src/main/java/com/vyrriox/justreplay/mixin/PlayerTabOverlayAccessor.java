package com.vyrriox.justreplay.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets recording snapshots read the tab list header and footer.
 *
 * @author vyrriox
 */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
    @Accessor("header")
    Component justreplay$getHeader();

    @Accessor("footer")
    Component justreplay$getFooter();
}
