package me.cipher.clabhaoc.mixin.sodium;

import me.cipher.clabhaoc.haoc.TerrainHaocManager;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Hooks the two entry points of the terrain renderer inside {@link RenderSectionManager}:
 *
 * <ul>
 *     <li>{@code renderLayer}: the probe pass of the occlusion culler runs right after the
 *     solid terrain layer has finished drawing, so the depth buffer at that point contains
 *     exactly the fully-opaque terrain of the current frame.</li>
 *     <li>{@code getDebugStrings}: appends the culler's statistics line to the Sodium debug
 *     overlay.</li>
 * </ul>
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinRenderSectionManager {
    @Inject(
            method = "renderLayer",
            at = @At("RETURN"),
            remap = false
    )
    private void clabhaoc$runProbePass(ChunkRenderMatrices matrices, TerrainRenderPass pass,
                                       double x, double y, double z, CallbackInfo ci) {
        TerrainHaocManager.getInstance().runProbePass(pass, matrices, x, y, z);
    }

    @Inject(
            method = "getDebugStrings()Ljava/util/Collection;",
            at = @At("RETURN"),
            remap = false
    )
    private void clabhaoc$appendDebugLine(CallbackInfoReturnable<Collection<String>> cir) {
        String debugLine = TerrainHaocManager.getInstance().getDebugLine();

        if (debugLine != null) {
            cir.getReturnValue().add(debugLine);
        }
    }
}
