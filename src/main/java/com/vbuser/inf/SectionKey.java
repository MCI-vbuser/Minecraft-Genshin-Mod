package com.vbuser.inf;

/**
 * Identity of one 16x16x16 section: chunk column plus the <b>full</b> section Y.
 *
 * <p>The extended region spans the whole int range, so a section Y cannot be
 * squeezed into the few spare bits of a packed long together with both chunk
 * coordinates: masking it to 12 bits made Y=256 and Y=65792 share a key, which
 * let one section reuse the other's baked mesh and made the light engine dedupe
 * two different sections into a single seed. Keeping the three ints explicit
 * removes the collision entirely.</p>
 */
public final class SectionKey {

    public final int chunkX;
    public final int sectionY;
    public final int chunkZ;

    private SectionKey(int chunkX, int sectionY, int chunkZ) {
        this.chunkX = chunkX;
        this.sectionY = sectionY;
        this.chunkZ = chunkZ;
    }

    public static SectionKey of(int chunkX, int sectionY, int chunkZ) {
        return new SectionKey(chunkX, sectionY, chunkZ);
    }

    /** Same section one block layer down/up. */
    public SectionKey offsetY(int sections) {
        return new SectionKey(this.chunkX, this.sectionY + sections, this.chunkZ);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SectionKey)) {
            return false;
        }
        SectionKey key = (SectionKey) other;
        return this.chunkX == key.chunkX
                && this.sectionY == key.sectionY
                && this.chunkZ == key.chunkZ;
    }

    @Override
    public int hashCode() {
        int hash = this.chunkX;
        hash = 31 * hash + this.chunkZ;
        hash = 31 * hash + this.sectionY;
        return hash;
    }

    @Override
    public String toString() {
        return "SectionKey[" + this.chunkX + ", " + this.sectionY + ", " + this.chunkZ + "]";
    }
}
