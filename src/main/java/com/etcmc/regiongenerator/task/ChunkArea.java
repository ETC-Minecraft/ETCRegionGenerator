package com.etcmc.regiongenerator.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Calculates and stores the ordered list of chunk coordinates that
 * need to be generated for a given centre + radius (in blocks).
 *
 * <p>Coordinates are sorted region-first (32×32 chunks per .mca file)
 * so writes are sequential on disk and page-cache friendly.</p>
 */
public final class ChunkArea {

    public record ChunkCoord(int x, int z) {}

    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radiusInChunks;
    private final List<ChunkCoord> chunks;

    /**
     * @param centerX      block X of the centre
     * @param centerZ      block Z of the centre
     * @param radiusBlocks radius in blocks (will be rounded up to the next full chunk)
     */
    public ChunkArea(int centerX, int centerZ, int radiusBlocks) {
        this.centerChunkX = centerX >> 4;
        this.centerChunkZ = centerZ >> 4;
        this.radiusInChunks = (int) Math.ceil(radiusBlocks / 16.0);
        this.chunks = buildSortedList();
    }

    // Overload that accepts radius directly in chunks (used when restoring from save)
    public static ChunkArea fromChunks(int centerChunkX, int centerChunkZ, int radiusChunks) {
        return new ChunkArea(centerChunkX << 4, centerChunkZ << 4, radiusChunks * 16);
    }

    private List<ChunkCoord> buildSortedList() {
        List<ChunkCoord> list = new ArrayList<>();

        // Spiral order (center → outward rings).
        // Each new chunk's neighbors are already generated or in-flight,
        // minimising cascading internal loads inside Minecraft's chunk pipeline.
        // Ring 0 = center chunk, ring N = chunks at Chebyshev distance N.
        for (int ring = 0; ring <= radiusInChunks; ring++) {
            if (ring == 0) {
                addIfInCircle(list, 0, 0);
                continue;
            }
            // Top edge (z = -ring), left-to-right
            for (int dx = -ring; dx <= ring; dx++)
                addIfInCircle(list, dx, -ring);
            // Right edge (x = +ring), top-to-bottom (skip corners already added)
            for (int dz = -ring + 1; dz <= ring; dz++)
                addIfInCircle(list, ring, dz);
            // Bottom edge (z = +ring), right-to-left (skip corner)
            for (int dx = ring - 1; dx >= -ring; dx--)
                addIfInCircle(list, dx, ring);
            // Left edge (x = -ring), bottom-to-top (skip both corners)
            for (int dz = ring - 1; dz >= -ring + 1; dz--)
                addIfInCircle(list, -ring, dz);
        }

        return Collections.unmodifiableList(list);
    }

    private void addIfInCircle(List<ChunkCoord> list, int dx, int dz) {
        // Circular clip
        if ((double) dx * dx + (double) dz * dz > (double) radiusInChunks * radiusInChunks) return;
        list.add(new ChunkCoord(centerChunkX + dx, centerChunkZ + dz));
    }

    public List<ChunkCoord> getChunks() {
        return chunks;
    }

    public int getCenterChunkX() { return centerChunkX; }
    public int getCenterChunkZ() { return centerChunkZ; }
    public int getRadiusInChunks() { return radiusInChunks; }
    public int getTotalChunks() { return chunks.size(); }
}
