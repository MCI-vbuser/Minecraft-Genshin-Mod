package com.vbuser.genshin.client.renderer;

import java.nio.ByteBuffer;

/**
 * Pure byte grid operations for the cloud density volume.
 *
 * <p>No Minecraft or GL dependency on purpose: the copy that keeps a resized
 * volume from blanking out is exactly the kind of offset arithmetic that must be
 * verifiable without launching the game ({@code tools/cloudpreview/diag/GridCopyCheck.java}).</p>
 */
public final class DensityGridOps {

    private DensityGridOps() {
    }

    /**
     * Copies the texels that exist in both grids.
     *
     * <p>Both grids describe the same world with the same texel size, so the
     * mapping is a whole texel translation. Only the overlapping box is touched;
     * everything else in {@code destination} is left as it was (zeroed).</p>
     *
     * @return false when the boxes do not overlap at all
     */
    public static boolean copyOverlap(ByteBuffer destination,
                                      int destinationOriginX, int destinationOriginY, int destinationOriginZ,
                                      int destinationDimX, int destinationDimY, int destinationDimZ,
                                      ByteBuffer source,
                                      int sourceOriginX, int sourceOriginY, int sourceOriginZ,
                                      int sourceDimX, int sourceDimY, int sourceDimZ,
                                      int cellSize) {
        if (destination == null || source == null || cellSize <= 0) {
            return false;
        }
        int offsetX = Math.floorDiv(sourceOriginX - destinationOriginX, cellSize);
        int offsetY = Math.floorDiv(sourceOriginY - destinationOriginY, cellSize);
        int offsetZ = Math.floorDiv(sourceOriginZ - destinationOriginZ, cellSize);

        int x0 = Math.max(0, -offsetX);
        int x1 = Math.min(sourceDimX, destinationDimX - offsetX);
        int y0 = Math.max(0, -offsetY);
        int y1 = Math.min(sourceDimY, destinationDimY - offsetY);
        int z0 = Math.max(0, -offsetZ);
        int z1 = Math.min(sourceDimZ, destinationDimZ - offsetZ);
        if (x1 <= x0 || y1 <= y0 || z1 <= z0) {
            return false;
        }

        ByteBuffer from = source.duplicate();
        int length = x1 - x0;
        for (int z = z0; z < z1; z++) {
            for (int y = y0; y < y1; y++) {
                int sourceIndex = x0 + sourceDimX * (y + sourceDimY * z);
                int destinationIndex = (x0 + offsetX)
                        + destinationDimX * ((y + offsetY) + destinationDimY * (z + offsetZ));
                from.limit(sourceIndex + length).position(sourceIndex);
                destination.position(destinationIndex);
                destination.put(from);
            }
        }
        destination.clear();
        return true;
    }

    /** Index of a texel in the GL upload order used by the volume texture. */
    public static int index(int x, int y, int z, int dimX, int dimY) {
        return x + dimX * (y + dimY * z);
    }
}
