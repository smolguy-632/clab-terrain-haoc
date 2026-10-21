package me.cipher.clabhaoc.haoc;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cipher.clabhaoc.ClabHaocConfig;
import me.cipher.clabhaoc.ClabHaocMod;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBufferUsage;
import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.DrawCommandList;
import me.jellysquid.mods.sodium.client.gl.device.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.GlShader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlTessellation;
import me.jellysquid.mods.sodium.client.gl.tessellation.TessellationBinding;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Hardware Accelerated Occlusion Culling (HAOC) for terrain chunk sections.
 *
 * <p>Every frame, a bounded number of sections which Sodium's CPU visibility graph considers
 * (potentially) visible are re-tested against the depth buffer containing the opaque terrain
 * rendered during the solid pass of the current frame. Each test renders the section's
 * axis-aligned bounding box as a depth-tested probe with color writes disabled, wrapped in an
 * occlusion query ({@code GL_SAMPLES_PASSED}). A section whose probe produces zero depth
 * samples cannot be seen from the camera, and is filtered out of the render lists until a
 * later probe proves otherwise.
 *
 * <p>Sections which are being probed are excluded from the terrain draw batches of the frame
 * in which their test runs, so a section's own geometry can never satisfy its own query
 * (self-occlusion). The probe pass runs between the solid and cutout passes, so only
 * fully-opaque terrain acts as an occluder; cutout layers (leaves, glass, ...) are
 * intentionally not present in the depth buffer at that point and never hide terrain behind
 * them.
 *
 * <p>The design follows the occlusion-query approach used by CLab (CipherXOR) for entities:
 * pooled queries, deferred result reads with one frame of latency, distance-tiered re-test
 * frequency, and a hard "safe radius" around the camera which is never culled.
 */
public final class TerrainHaocManager {
    private static final TerrainHaocManager INSTANCE = new TerrainHaocManager();

    public static TerrainHaocManager getInstance() {
        return INSTANCE;
    }

    // The probe cube mesh is baked in the [0, SECTION_SIZE] range so that a probe only needs a
    // translation to be positioned over a chunk section.
    private static final int SECTION_SIZE = 16;
    private static final int FACE_QUADS = 6;
    private static final int CUBE_TRIANGLE_COUNT = FACE_QUADS * 2;
    private static final int CUBE_VERTEX_COUNT = CUBE_TRIANGLE_COUNT * 3;
    private static final int CUBE_INDEX_COUNT = CUBE_VERTEX_COUNT;

    // Distance tiers used to select a re-test delay. Distances are measured from the camera
    // to the center of the section.
    private static final float TIER_NEAR = 64.0f;
    private static final float TIER_MID = 192.0f;
    private static final float TIER_NEAR_SQUARED = TIER_NEAR * TIER_NEAR;
    private static final float TIER_MID_SQUARED = TIER_MID * TIER_MID;

    // Re-test delays (in frames) for currently visible sections. These are safe to be
    // infrequent: stale "visible" results only waste draw work, they never hide terrain.
    private static final int RETEST_DELAY_NEAR = 4;
    private static final int RETEST_DELAY_MID = 8;
    private static final int RETEST_DELAY_FAR = 12;

    // Re-test delays for currently hidden sections. Recovery has to be reasonably fast so
    // that terrain which became visible does not stay hidden for long.
    private static final int RECOVERY_DELAY_NEAR = 2;
    private static final int RECOVERY_DELAY_MID = 4;
    private static final int RECOVERY_DELAY_FAR = 8;

    // How long a test result is trusted before the section falls back to "always visible".
    private static final int TEST_RESULT_MAX_AGE = 64;

    // The query pool grows by this many handles whenever it is exhausted.
    private static final int QUERY_POOL_GROWTH = 256;

    private static final int GL_SAMPLES_PASSED = 0x8914;
    private static final int GL_QUERY_RESULT = 0x8866;
    private static final int GL_QUERY_RESULT_AVAILABLE = 0x8867;
    private static final int GL_TRUE = 1;

    private static final String VERTEX_SHADER_SOURCE = """
            #version 330 core

            layout(location = 0) in vec3 a_Position;

            uniform mat4 u_ModelViewMatrix;
            uniform mat4 u_ProjectionMatrix;

            void main() {
                gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(a_Position, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER_SOURCE = """
            #version 330 core

            out vec4 out_FragColor;

            void main() {
                out_FragColor = vec4(1.0);
            }
            """;

    // Per-section state, keyed by object identity. RenderSection never survives a renderer
    // rebuild, so pruned entries must be removed to avoid leaks.
    private final IdentityHashMap<RenderSection, HaocState> states = new IdentityHashMap<>();

    private final Deque<Integer> availableQueries = new ArrayDeque<>();
    private final ObjectArrayList<RenderSection> inFlightQueries = new ObjectArrayList<>();
    private final ObjectArrayList<RenderSection> queuedTests = new ObjectArrayList<>();
    private final ObjectArrayList<RenderSection> hiddenSections = new ObjectArrayList<>();

    // GL resources (created lazily on the render thread).
    private boolean initialized;
    private GlProgram<ProbeShaderInterface> program;
    private GlMutableBuffer vertexBuffer;
    private GlMutableBuffer indexBuffer;
    private GlTessellation tessellation;
    private final MultiDrawBatch drawBatch = new MultiDrawBatch(1);
    private final Matrix4f scratchMatrix = new Matrix4f();

    // Current frame state.
    private int currentFrame;
    private double cameraX;
    private double cameraY;
    private double cameraZ;

    // Statistics / cached debug line.
    private boolean wasEnabled;
    private Object lastWorld;
    private int tickCounter;
    private boolean stateChangedByPlanning;
    private int occludedVisibleCount;
    private int submittedCount;
    private int skippedCount;
    private String debugLine;

    private TerrainHaocManager() {
    }

    /**
     * Renders the per-frame tick of the occlusion culler. Runs at the very start of
     * {@code SodiumWorldRenderer.setupTerrain}: by the time Sodium decides to rebuild the
     * render lists for this frame, the ground truth about which sections are hidden is
     * already up to date.
     */
    public void tick(RenderSectionManager renderSectionManager, Camera camera, int frame) {
        RenderSystem.assertOnRenderThread();

        if (renderSectionManager == null) {
            return;
        }

        this.currentFrame = frame;
        this.debugLine = null;

        var config = ClabHaocConfig.INSTANCE;

        config.checkReload();

        // Detect a world change and forget everything attached to the previous world.
        var world = MinecraftClient.getInstance().world;

        if (world != this.lastWorld) {
            this.lastWorld = world;
            this.resetAll(true);
        }

        if (!config.enabled) {
            if (this.wasEnabled) {
                // Disabled while running: clear every cached decision so no section stays
                // hidden while the culler is off.
                this.resetAll(true);
                this.wasEnabled = false;
            }

            return;
        }

        this.wasEnabled = true;

        Vec3d pos = camera.getPos();
        this.cameraX = pos.getX();
        this.cameraY = pos.getY();
        this.cameraZ = pos.getZ();

        if (++this.tickCounter % 64 == 0) {
            this.pruneDisposedSections();
        }

        boolean changed = this.processResults();

        int submitted = this.planTests(renderSectionManager, config);

        // Any state change (a section became hidden/shown, or a new test was scheduled) must
        // be folded into this frame's render lists. Sodium only rebuilds the lists when the
        // graph is dirty, so force the rebuild here.
        if (changed || this.stateChangedByPlanning || submitted > 0) {
            renderSectionManager.markGraphDirty();
        }

        this.debugLine = config.showDebugLine
                ? String.format("HAOC[CLab]: hidden=%d probes=%d queued=%d skipped=%d queries=%d",
                        this.occludedVisibleCount, submitted, this.inFlightQueries.size(),
                        this.skippedCount, this.availableQueries.size())
                : null;
    }

    /**
     * Reads finished query results and returns true if any visibility state changed.
     */
    private boolean processResults() {
        boolean changed = false;

        Iterator<RenderSection> iterator = this.inFlightQueries.iterator();

        while (iterator.hasNext()) {
            RenderSection section = iterator.next();
            HaocState state = this.states.get(section);

            if (state == null || section.isDisposed()) {
                if (state != null && state.queryId != HaocState.QUERY_NONE) {
                    GL33C.glDeleteQueries(state.queryId);
                    state.queryId = HaocState.QUERY_NONE;
                }

                this.states.remove(section);
                iterator.remove();
                continue;
            }

            if (!this.isQueryResultAvailable(state.queryId)
                    && (this.currentFrame - state.submittedFrame) <= 2) {
                // The GPU has not finished this query yet; read it back next frame.
                continue;
            }

            int samples = this.readQueryResult(state.queryId);
            boolean visible = samples > 0;

            this.releaseQuery(state);

            int status = visible ? HaocState.STATUS_VISIBLE : HaocState.STATUS_OCCLUDED;

            if (state.status != status) {
                changed = true;
            }

            state.status = status;
            state.testFrame = this.currentFrame;

            iterator.remove();

            if (visible) {
                // The section re-appears in the render lists; it no longer needs a
                // recovery slot.
                this.hiddenSections.remove(section);
            } else if (!this.hiddenSections.contains(section)) {
                // Track hidden sections outside the render lists: they must be re-probed
                // periodically or they could never become visible again.
                this.hiddenSections.add(section);
            }
        }

        return changed;
    }

    private boolean isQueryResultAvailable(int queryId) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.mallocInt(1);
            GL33C.glGetQueryObjectiv(queryId, GL_QUERY_RESULT_AVAILABLE, buffer);

            return buffer.get(0) == GL_TRUE;
        }
    }

    private int readQueryResult(int queryId) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.mallocInt(1);
            GL33C.glGetQueryObjectiv(queryId, GL_QUERY_RESULT, buffer);

            return buffer.get(0);
        }
    }

    private void releaseQuery(HaocState state) {
        if (state.queryId != HaocState.QUERY_NONE) {
            this.availableQueries.add(state.queryId);
            state.queryId = HaocState.QUERY_NONE;
        }
    }

    /**
     * Plans which sections will be probed during this frame, in two phases:
     *
     * <ol>
     *     <li><b>Recovery</b>: hidden sections (which are no longer part of the render
     *     lists) are re-probed so they can become visible again.</li>
     *     <li><b>Discovery</b>: sections from the current render lists are probed either
     *     because they were never tested or because their re-test timer expired.</li>
     * </ol>
     *
     * <p>Tests are planned far-to-near so that distant sections (which benefit most from
     * culling) get first pick of the per-frame query budget.
     */
    private int planTests(RenderSectionManager renderSectionManager, ClabHaocConfig config) {
        // Abandon tests which were planned for a previous frame but never executed (e.g.
        // because rendering was interrupted). Their sections become un-suppressed and are
        // re-planned here.
        for (RenderSection section : this.queuedTests) {
            var state = this.states.get(section);

            if (state != null) {
                this.releaseQuery(state);
            }
        }

        this.queuedTests.clear();
        this.submittedCount = 0;
        this.skippedCount = 0;
        this.occludedVisibleCount = 0;
        this.stateChangedByPlanning = false;

        int budget = config.maxQueriesPerFrame;

        float safeRadiusSquared = config.safeRadius * config.safeRadius;

        // Phase 1: recovery for sections which are currently hidden.
        //
        // These sections are being suppressed and are therefore no longer part of the
        // terrain render lists; if they were only planned from those lists, an occluded
        // section could never recover its visibility. A dedicated collection tracks them.
        //
        // Half of the per-frame budget is reserved for recovery so that hidden terrain
        // always heals, even when many new sections want to be tested.
        int recoveryBudget = Math.max(1, budget / 2);

        for (int i = this.hiddenSections.size() - 1; i >= 0; i--) {
            RenderSection section = this.hiddenSections.get(i);
            HaocState state = this.states.get(section);

            // Drop entries which vanished from the world or no longer qualify.
            if (state == null || section.isDisposed() || state.status != HaocState.STATUS_OCCLUDED) {
                this.hiddenSections.remove(i);
                continue;
            }

            if (state.isTestPending() || budget <= 0) {
                continue;
            }

            float squaredDistance = section.getSquaredDistance(
                    (float) this.cameraX, (float) this.cameraY, (float) this.cameraZ);

            if (squaredDistance < safeRadiusSquared) {
                // The camera moved close to a hidden section; reveal it immediately.
                state.status = HaocState.STATUS_VISIBLE;
                state.testFrame = this.currentFrame;
                this.hiddenSections.remove(i);
                this.stateChangedByPlanning = true;
                continue;
            }

            if (this.currentFrame - state.submittedFrame < this.getRetestDelay(state, section, squaredDistance)) {
                continue;
            }

            int queryId = this.acquireQuery();

            if (queryId == HaocState.QUERY_NONE) {
                break;
            }

            state.queryId = queryId;
            state.submittedFrame = this.currentFrame;

            this.queuedTests.add(section);
            budget--;
            recoveryBudget--;
            this.submittedCount++;

            if (recoveryBudget <= 0) {
                break;
            }
        }

        // Phase 2: discovery and re-testing for sections of the current render lists.
        var renderLists = renderSectionManager.getRenderLists();
        Iterator<ChunkRenderList> listIterator = renderLists.iterator(true);

        while (listIterator.hasNext() && budget > 0) {
            ChunkRenderList renderList = listIterator.next();
            RenderRegion region = renderList.getRegion();

            var sectionIterator = renderList.sectionsWithGeometryIterator(true);

            if (sectionIterator == null) {
                continue;
            }

            while (sectionIterator.hasNext() && budget > 0) {
                int sectionIndex = sectionIterator.nextByteAsInt();
                RenderSection section = region.getSection(sectionIndex);

                if (section == null || section.isDisposed()) {
                    continue;
                }

                var state = this.states.get(section);

                if (state == null) {
                    state = new HaocState();
                    this.states.put(section, state);
                }

                if (state.isTestPending()) {
                    continue;
                }

                if (state.status == HaocState.STATUS_OCCLUDED) {
                    // Hidden sections are planned by the recovery phase above.
                    this.occludedVisibleCount++;
                    continue;
                }

                float squaredDistance = section.getSquaredDistance(
                        (float) this.cameraX, (float) this.cameraY, (float) this.cameraZ);

                // The terrain around the camera is never culled nor probed.
                if (squaredDistance < safeRadiusSquared) {
                    this.skippedCount++;
                    continue;
                }

                // A stale result is discarded so it can never be used for culling again.
                if (state.status != HaocState.STATUS_UNKNOWN
                        && (this.currentFrame - state.testFrame) > TEST_RESULT_MAX_AGE) {
                    state.status = HaocState.STATUS_UNKNOWN;
                    state.testFrame = -1;
                    this.skippedCount++;
                    continue;
                }

                if (state.status != HaocState.STATUS_UNKNOWN
                        && (this.currentFrame - state.submittedFrame)
                                < this.getRetestDelay(state, section, squaredDistance)) {
                    this.skippedCount++;
                    continue;
                }

                int queryId = this.acquireQuery();

                if (queryId == HaocState.QUERY_NONE) {
                    // The pool is exhausted; the remaining sections are skipped from
                    // testing this frame and will be reconsidered in later frames.
                    break;
                }

                state.queryId = queryId;
                state.submittedFrame = this.currentFrame;

                this.queuedTests.add(section);
                budget--;
                this.submittedCount++;
            }
        }

        return this.submittedCount;
    }

    private int getRetestDelay(HaocState state, RenderSection section, float squaredDistance) {
        int delay;

        if (state.status == HaocState.STATUS_OCCLUDED) {
            if (squaredDistance < TIER_NEAR_SQUARED) {
                delay = RECOVERY_DELAY_NEAR;
            } else if (squaredDistance < TIER_MID_SQUARED) {
                delay = RECOVERY_DELAY_MID;
            } else {
                delay = RECOVERY_DELAY_FAR;
            }
        } else {
            if (squaredDistance < TIER_NEAR_SQUARED) {
                delay = RETEST_DELAY_NEAR;
            } else if (squaredDistance < TIER_MID_SQUARED) {
                delay = RETEST_DELAY_MID;
            } else {
                delay = RETEST_DELAY_FAR;
            }
        }

        // Stagger the tests of adjacent sections so that the query workload is spread
        // evenly over consecutive frames.
        delay += this.getSectionJitter(section) % delay;

        return delay;
    }

    private int getSectionJitter(RenderSection section) {
        int hash = section.getChunkX() * 73856093
                ^ section.getChunkY() * 19349663
                ^ section.getChunkZ() * 83492791;

        return hash & 0x7;
    }

    /**
     * Returns true when the section should be excluded by the terrain renderer. Called
     * from the mixin into {@code ChunkRenderList.add} for every section added to the
     * render lists.
     */
    public boolean isSuppressed(RenderSection section) {
        if (!this.wasEnabled || this.states.isEmpty()) {
            return false;
        }

        HaocState state = this.states.get(section);

        if (state == null) {
            return false;
        }

        // The terrain around the camera is never suppressed, regardless of state.
        if (section.getSquaredDistance((float) this.cameraX, (float) this.cameraY, (float) this.cameraZ)
                < ClabHaocConfig.INSTANCE.safeRadius * ClabHaocConfig.INSTANCE.safeRadius) {
            return false;
        }

        if (state.isTestPending()) {
            return true;
        }

        if (state.status == HaocState.STATUS_OCCLUDED) {
            // Stale decisions are never trusted (see TEST_RESULT_MAX_AGE).
            return (this.currentFrame - state.testFrame) <= TEST_RESULT_MAX_AGE;
        }

        return false;
    }

    /**
     * Executes the planned occlusion tests. Runs in between the solid and cutout render
     * passes, so that the depth buffer contains only fully-opaque terrain at the time the
     * probes are drawn.
     */
    public void runProbePass(TerrainRenderPass pass, ChunkRenderMatrices matrices, double x, double y, double z) {
        if (!this.wasEnabled
                || pass != DefaultTerrainRenderPasses.SOLID
                || this.queuedTests.isEmpty()) {
            return;
        }

        RenderSystem.assertOnRenderThread();

        CommandList commandList = RenderDevice.INSTANCE.createCommandList();

        if (!this.initialized) {
            this.initialize(commandList);
        }

        this.program.getInterface().projectionMatrix.set(matrices.projection());

        this.bindProbeState();

        try (DrawCommandList drawCommandList = commandList.beginTessellating(this.tessellation)) {
            while (!this.queuedTests.isEmpty()) {
                RenderSection section = this.queuedTests.remove(this.queuedTests.size() - 1);
                HaocState state = this.states.get(section);

                if (state == null || section.isDisposed()) {
                    if (state != null) {
                        this.releaseQuery(state);
                    }

                    continue;
                }

                // Position the probe cube over the section, in camera-relative space
                // (the model-view matrix given to terrain rendering contains no translation).
                Matrix4f modelView = this.scratchMatrix.set(matrices.modelView());
                modelView.translate(
                        (float) (section.getOriginX() - x),
                        (float) (section.getOriginY() - y),
                        (float) (section.getOriginZ() - z));

                this.program.getInterface().modelViewMatrix.set(modelView);

                MemoryUtil.memPutAddress(this.drawBatch.pElementPointer, 0);
                MemoryUtil.memPutInt(this.drawBatch.pElementCount, CUBE_INDEX_COUNT);
                MemoryUtil.memPutInt(this.drawBatch.pBaseVertex, 0);
                this.drawBatch.size = 1;

                GL33C.glBeginQuery(GL_SAMPLES_PASSED, state.queryId);
                drawCommandList.multiDrawElementsBaseVertex(this.drawBatch, GlIndexType.UNSIGNED_INT);
                GL33C.glEndQuery(GL_SAMPLES_PASSED);

                this.drawBatch.clear();

                // The query is running; its result will be consumed by processResults() in
                // a later frame while the section stays suppressed from the draw lists.
                this.inFlightQueries.add(section);
            }
        }

        commandList.flush();

        this.unbindProbeState();
    }

    private void bindProbeState() {
        // Only the write masks are touched: the terrain solid pass has already put the GL
        // state machine into exactly the configuration the probes need (depth test enabled,
        // LEQUAL depth function, no blending, back-face culling).
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL33C.GL_LEQUAL);
        GlStateManager._colorMask(false, false, false, false);
        GlStateManager._depthMask(false);

        this.program.bind();
    }

    private void unbindProbeState() {
        this.program.unbind();

        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._depthMask(true);
    }

    /**
     * Forgets all state and (optionally) deletes every outstanding query. Called when the
     * renderer is torn down, when the world changes, or when the mod is disabled at runtime.
     */
    private void resetAll(boolean deleteQueries) {
        // Free any query handles which are still assigned to sections.
        Iterator<RenderSection> inFlight = this.inFlightQueries.iterator();

        while (inFlight.hasNext()) {
            RenderSection section = inFlight.next();
            HaocState state = this.states.get(section);

            if (state != null && state.queryId != HaocState.QUERY_NONE && deleteQueries) {
                GL33C.glDeleteQueries(state.queryId);
            }

            inFlight.remove();
        }

        for (RenderSection section : this.queuedTests) {
            HaocState state = this.states.get(section);

            if (state != null && state.queryId != HaocState.QUERY_NONE && deleteQueries) {
                GL33C.glDeleteQueries(state.queryId);
            }
        }

        this.queuedTests.clear();

        this.hiddenSections.clear();

        if (deleteQueries) {
            while (!this.availableQueries.isEmpty()) {
                int queryId = this.availableQueries.poll();
                GL33C.glDeleteQueries(queryId);
            }
        } else {
            this.availableQueries.clear();
        }

        this.states.clear();

        this.occludedVisibleCount = 0;
        this.submittedCount = 0;
        this.skippedCount = 0;
    }

    /**
     * Full reset invoked when the renderer switches worlds. Any queries which are still
     * running become invalid, so every outstanding handle is deleted.
     */
    public void onWorldChanged() {
        this.lastWorld = null;
        this.resetAll(true);
    }

    private void pruneDisposedSections() {
        Iterator<Map.Entry<RenderSection, HaocState>> iterator = this.states.entrySet().iterator();

        while (iterator.hasNext()) {
            var entry = iterator.next();

            if (entry.getKey().isDisposed()) {
                var state = entry.getValue();

                if (state.queryId != HaocState.QUERY_NONE) {
                    GL33C.glDeleteQueries(state.queryId);
                    state.queryId = HaocState.QUERY_NONE;
                }

                iterator.remove();
            }
        }
    }

    private int acquireQuery() {
        if (this.availableQueries.isEmpty()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer queries = stack.mallocInt(QUERY_POOL_GROWTH);
                GL33C.glGenQueries(QUERY_POOL_GROWTH, queries);

                for (int i = QUERY_POOL_GROWTH - 1; i >= 0; i--) {
                    this.availableQueries.add(queries.get(i));
                }
            }
        }

        Integer queryId = this.availableQueries.poll();

        return queryId == null ? HaocState.QUERY_NONE : queryId;
    }

    private void initialize(CommandList commandList) {
        this.vertexBuffer = commandList.createMutableBuffer();
        commandList.uploadData(this.vertexBuffer, this.createCubeVertices(), GlBufferUsage.STATIC_DRAW);

        this.indexBuffer = commandList.createMutableBuffer();
        commandList.uploadData(this.indexBuffer, this.createCubeIndices(), GlBufferUsage.STATIC_DRAW);

        var positionAttribute = new GlVertexAttribute(
                GlVertexAttributeFormat.FLOAT, 3, false, 0, 12, false);

        this.tessellation = commandList.createTessellation(GlPrimitiveType.TRIANGLES, new TessellationBinding[] {
                TessellationBinding.forVertexBuffer(this.vertexBuffer, new GlVertexAttributeBinding[] {
                        new GlVertexAttributeBinding(0, positionAttribute)
                }),
                TessellationBinding.forElementBuffer(this.indexBuffer)
        });

        var vertexShader = new GlShader(ShaderType.VERTEX,
                new Identifier(ClabHaocMod.MOD_ID, "haoc_probe_vertex"),
                VERTEX_SHADER_SOURCE);
        var fragmentShader = new GlShader(ShaderType.FRAGMENT,
                new Identifier(ClabHaocMod.MOD_ID, "haoc_probe_fragment"),
                FRAGMENT_SHADER_SOURCE);

        this.program = GlProgram.builder(new Identifier(ClabHaocMod.MOD_ID, "haoc_probe"))
                .attachShader(vertexShader)
                .attachShader(fragmentShader)
                .bindFragmentData("out_FragColor", 0)
                .link(ProbeShaderInterface::new);

        vertexShader.delete();
        fragmentShader.delete();

        this.initialized = true;
    }

    /**
     * Builds the vertex data of a unit cube spanning one whole chunk section
     * (three floats per vertex: X, Y, Z), drawn without an index buffer.
     */
    private ByteBuffer createCubeVertices() {
        // The corner positions of a unit cube; each face is emitted as two triangles.
        // Face winding is irrelevant because face culling is left enabled (which culls
        // back faces) — the front faces alone are sufficient for the depth test.
        ByteBuffer buffer = MemoryUtil.memAlloc(CUBE_VERTEX_COUNT * 3 * 4);

        float[][] corners = {
                { 0, 0, 0 }, { 1, 0, 0 }, { 1, 1, 0 },       // triangle A
                { 0, 0, 0 }, { 1, 1, 0 }, { 0, 1, 0 },       // triangle B
                { 1, 0, 1 }, { 0, 0, 1 }, { 0, 1, 1 },
                { 1, 0, 1 }, { 0, 1, 1 }, { 1, 1, 1 },
                { 1, 1, 0 }, { 0, 1, 0 }, { 0, 1, 1 },
                { 1, 1, 0 }, { 0, 1, 1 }, { 1, 1, 1 },
                { 0, 0, 0 }, { 0, 0, 1 }, { 0, 1, 1 },
                { 0, 0, 0 }, { 0, 1, 1 }, { 0, 1, 0 },
                { 1, 0, 1 }, { 1, 0, 0 }, { 1, 1, 0 },
                { 1, 0, 1 }, { 1, 1, 0 }, { 1, 1, 1 },
                { 0, 0, 0 }, { 1, 0, 0 }, { 1, 0, 1 },
                { 0, 0, 0 }, { 1, 0, 1 }, { 0, 0, 1 }
        };

        for (float[] corner : corners) {
            buffer.putFloat(corner[0] * SECTION_SIZE);
            buffer.putFloat(corner[1] * SECTION_SIZE);
            buffer.putFloat(corner[2] * SECTION_SIZE);
        }

        buffer.flip();

        return buffer;
    }

    private ByteBuffer createCubeIndices() {
        ByteBuffer buffer = MemoryUtil.memAlloc(CUBE_INDEX_COUNT * 4);

        for (int i = 0; i < CUBE_INDEX_COUNT; i++) {
            buffer.putInt(i);
        }

        buffer.flip();

        return buffer;
    }

    public String getDebugLine() {
        return this.debugLine;
    }

    /**
     * Bindings for the uniforms of the probe shader program.
     */
    private static final class ProbeShaderInterface {
        public final GlUniformMatrix4f modelViewMatrix;
        public final GlUniformMatrix4f projectionMatrix;

        private ProbeShaderInterface(ShaderBindingContext context) {
            this.modelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
            this.projectionMatrix = context.bindUniform("u_ProjectionMatrix", GlUniformMatrix4f::new);
        }
    }
}
