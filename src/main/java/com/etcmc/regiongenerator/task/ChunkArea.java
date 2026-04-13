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

        int minCX = centerChunkX - radiusInChunks;
        int maxCX = centerChunkX + radiusInChunks;
        int minCZ = centerChunkZ - radiusInChunks;
        int maxCZ = centerChunkZ + radiusInChunks;

        // Iterate region by region for sequential disk writes
        int minRX = minCX >> 5;   // region = chunk / 32
        int maxRX = maxCX >> 5;
        int minRZ = minCZ >> 5;
        int maxRZ = maxCZ >> 5;

        for (int rz = minRZ; rz <= maxRZ; rz++) {
            for (int rx = minRX; rx <= maxRX; rx++) {
                // All chunks in this region that fall within our radius
                int regionChunkMinX = rx * 32;
                int regionChunkMinZ = rz * 32;

                for (int lz = 0; lz < 32; lz++) {
                    for (int lx = 0; lx < 32; lx++) {
                        int cx = regionChunkMinX + lx;
                        int cz = regionChunkMinZ + lz;

                        if (cx < minCX || cx > maxCX) continue;
                        if (cz < minCZ || cz > maxCZ) continue;

                        // Circular clip
                        double dx = cx - centerChunkX;
                        double dz = cz - centerChunkZ;
                        if (dx * dx + dz * dz <= (double) radiusInChunks * radiusInChunks) {
                            list.add(new ChunkCoord(cx, cz));
                        }
                    }
                }
            }
        }

        return Collections.unmodifiableList(list);
    }

    public List<ChunkCoord> getChunks() {
        return chunks;
    }

    public int getCenterChunkX() { return centerChunkX; }
    public int getCenterChunkZ() { return centerChunkZ; }
    public int getRadiusInChunks() { return radiusInChunks; }
    public int getTotalChunks() { return chunks.size(); }
}
