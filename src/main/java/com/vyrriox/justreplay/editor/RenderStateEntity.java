package com.vyrriox.justreplay.editor;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Render states no longer know their entity, while every actor and entity edit is looked up by entity. Each state
 * keeps the entity it was extracted from until the end of its frame, so the hooks that only receive a state (model
 * posing, rotations, layers, submission) reach the same data as on versions that pass the entity.
 *
 * @author vyrriox
 */
public interface RenderStateEntity {
    Entity justreplay$entity();

    void justreplay$setEntity(Entity entity);

    /** The entity this state was extracted from, or null for a state made outside the extraction. */
    static Entity of(EntityRenderState state) {
        return ((RenderStateEntity) (Object) state).justreplay$entity();
    }
}
