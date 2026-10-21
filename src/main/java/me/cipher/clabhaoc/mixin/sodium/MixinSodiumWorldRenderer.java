package me.cipher.clabhaoc.mixin.sodium;

import me.cipher.clabhaoc.haoc.TerrainHaocManager;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Viewport;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the per-frame tick of the occlusion culler.
 *
 * <ul>
 *     <li>{@code setupTerrain} (HEAD): runs the culler tick before Sodium updates the
 *     visibility graph and rebuilds the render lists, so suppression decisions made by the
 *     culler are visible to {@code ChunkRenderList.add} within the same frame. The frame
 *     counter is taken from Sodium itself.</li>
 *     <li>{@code setWorld} (RETURN): wipes all culling state whenever the renderer changes
 *     worlds. Outstanding GPU queries belong to the previous world's renderer and are
 *     deleted.</li>
 * </ul>
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRenderer {
    @Shadow(remap = false)
    private RenderSectionManager renderSectionManager;

    @Unique
    private ClientWorld clabhaoc$lastSeenWorld;

    @Inject(
            method = "setupTerrain",
            at = @At("HEAD"),
            remap = false
    )
    private void clabhaoc$onSetupTerrain(Camera camera, Viewport viewport, int frame,
                                         boolean spectator, boolean updateChunksImmediately,
                                         CallbackInfo ci) {
        if (this.renderSectionManager != null) {
            TerrainHaocManager.getInstance().tick(this.renderSectionManager, camera, frame);
        }
    }

    @Inject(
            method = "setWorld",
            at = @At("RETURN"),
            remap = false
    )
    private void clabhaoc$onSetWorld(ClientWorld world, CallbackInfo ci) {
        // Only reset when the world instance actually changed; setWorld() returns early
        // when the renderer is already attached to this world.
        if (world != this.clabhaoc$lastSeenWorld) {
            this.clabhaoc$lastSeenWorld = world;
            TerrainHaocManager.getInstance().onWorldChanged();
        }
    }
}
