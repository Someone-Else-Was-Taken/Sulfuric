package net.caffeinemc.phosphor.common.util.chunk.light;

import net.minecraft.world.chunk.ChunkNibbleArray;

public class SkyLightChunkNibbleArray extends ReadonlyChunkNibbleArray {
    public SkyLightChunkNibbleArray(final byte[] inheritedLightmap) {
        super(inheritedLightmap);
    }

    public SkyLightChunkNibbleArray(final ChunkNibbleArray inheritedLightmap) {
        this(inheritedLightmap.asByteArray());
    }

    @Override
    public int get(final int x, final int y, final int z) {
        return super.get(x, 0, z);
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public byte[] asByteArray() {
        byte[] byteArray = new byte[2048];

        for(int i = 0; i < 16; ++i) {
            System.arraycopy(this.bytes, 0, byteArray, i * 128, 128);
        }

        return byteArray;
    }
}
