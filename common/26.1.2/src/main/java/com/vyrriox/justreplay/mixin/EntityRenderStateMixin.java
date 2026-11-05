package com.vyrriox.justreplay.mixin;

import com.vyrriox.justreplay.editor.RenderStateEntity;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every entity render state its entity, see {@link RenderStateEntity}.
 *
 * @author vyrriox
 */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements RenderStateEntity {
    @Unique
    private Entity justreplay$entity;

    @Override
    public Entity justreplay$entity() {
        return justreplay$entity;
    }

    @Override
    public void justreplay$setEntity(Entity entity) {
        justreplay$entity = entity;
    }
}
