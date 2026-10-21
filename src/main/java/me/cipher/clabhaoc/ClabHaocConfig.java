package me.cipher.clabhaoc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * A very small configuration file handler for the mod.
 *
 * <p>Defaults are written on first launch. The file is automatically reloaded whenever
 * its modification timestamp changes, so options can be toggled while the game runs.
 */
public final class ClabHaocConfig {
    private static final Logger LOGGER = LogManager.getLogger("CLabTerrainHAOC");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final ClabHaocConfig INSTANCE = new ClabHaocConfig();

    /** Master switch for the occlusion culler. */
    public boolean enabled = true;

    /** Sections closer than this many blocks to the camera are never culled or probed. */
    public float safeRadius = 56.0f;

    /** Maximum number of new occlusion queries submitted per frame. */
    public int maxQueriesPerFrame = 320;

    /** Adds a debug line to the Sodium F3 overlay when enabled. */
    public boolean showDebugLine = true;

    private Path path;
    private FileTime lastStamp;

    private ClabHaocConfig() {
    }

    public void load(Path path) {
        this.path = path;

        if (Files.exists(path)) {
            if (!this.readFromDisk()) {
                this.writeToDisk();
            }
        } else {
            this.writeToDisk();
        }

        this.lastStamp = this.readStamp();
    }

    /**
     * Reloads the configuration file if it changed on disk. Called cheaply from the render
     * tick; the stamp check is a single file-system probe every few frames.
     */
    public void checkReload() {
        if (this.path == null) {
            return;
        }

        FileTime stamp = this.readStamp();

        if (stamp != null && !stamp.equals(this.lastStamp)) {
            this.lastStamp = stamp;

            if (this.readFromDisk()) {
                LOGGER.info("CLab Terrain HAOC configuration reloaded (enabled = {}, safeRadius = {}, maxQueriesPerFrame = {})",
                        this.enabled, this.safeRadius, this.maxQueriesPerFrame);
            }
        }
    }

    private FileTime readStamp() {
        if (this.path == null) {
            return null;
        }

        try {
            return Files.getLastModifiedTime(this.path);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean readFromDisk() {
        try (Reader reader = Files.newBufferedReader(this.path, StandardCharsets.UTF_8)) {
            ClabHaocConfig loaded = GSON.fromJson(reader, ClabHaocConfig.class);

            if (loaded != null) {
                this.enabled = loaded.enabled;
                this.safeRadius = loaded.safeRadius <= 0.0f ? 56.0f : loaded.safeRadius;
                this.maxQueriesPerFrame = Math.max(16, loaded.maxQueriesPerFrame);
                this.showDebugLine = loaded.showDebugLine;
            }

            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to read {}", this.path, e);
            return false;
        }
    }

    private void writeToDisk() {
        try {
            if (this.path.getParent() != null) {
                Files.createDirectories(this.path.getParent());
            }

            try (Writer writer = Files.newBufferedWriter(this.path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write {}", this.path, e);
        }
    }
}
