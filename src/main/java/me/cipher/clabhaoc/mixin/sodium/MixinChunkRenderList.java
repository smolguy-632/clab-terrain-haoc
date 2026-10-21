package me.cipher.clabhaoc.mixin.sodium;

import me.cipher.clabhaoc.haoc.TerrainHaocManager;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Filters hidden chunk sections out of the terrain render lists.
 *
 * <p>{@code ChunkRenderList.add} is invoked for every section which Sodium's CPU visibility
 * graph decides to draw. Cancelling the call for a section with a negative occlusion query
 * result keeps that section out of the render list entirely, which is what materializes the
 * culling decision: the section is neither drawn in later passes nor iterated again.
 */
@Mixin(value = ChunkRenderList.class, remap = false)
public abstract class MixinChunkRenderList {
    @Inject(
            method = "add(Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void clabhaoc$filterSuppressed(RenderSection section, CallbackInfo ci) {
        if (TerrainHaocManager.getInstance().isSuppressed(section)) {
            ci.cancel();
        }
    }
}
