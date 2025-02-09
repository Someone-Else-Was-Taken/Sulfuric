package net.caffeinemc.phosphor.mixin.chunk.light;

import it.unimi.dsi.fastutil.ints.Int2ByteMap;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterable;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntIterators;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.caffeinemc.phosphor.common.chunk.light.IReadonly;
import net.caffeinemc.phosphor.common.chunk.light.LevelPropagatorAccess;
import net.caffeinemc.phosphor.common.chunk.light.LightProviderUpdateTracker;
import net.caffeinemc.phosphor.common.chunk.light.LightStorageAccess;
import net.caffeinemc.phosphor.common.util.chunk.light.EmptyChunkNibbleArray;
import net.caffeinemc.phosphor.common.util.math.ChunkSectionPosHelper;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.SectionDistanceLevelPropagator;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.ChunkToNibbleArrayMap;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.light.LightStorage;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.StampedLock;
import java.util.function.LongPredicate;

@Mixin(LightStorage.class)
public abstract class MixinLightStorage extends SectionDistanceLevelPropagator implements LightStorageAccess {
    protected MixinLightStorage() {
        super(0, 0, 0);
    }

    @Shadow
    @Final
    protected ChunkToNibbleArrayMap<?> storage;

    @Mutable
    @Shadow
    @Final
    protected LongSet dirtySections;

    @Mutable
    @Shadow
    @Final
    protected LongSet notifySections;

    @Shadow
    protected abstract int getLevel(long id);

    @Mutable
    @Shadow
    @Final
    protected LongSet readySections;

    @Mutable
    @Shadow
    @Final
    protected LongSet markedReadySections;

    @Mutable
    @Shadow
    @Final
    protected LongSet markedNotReadySections;

    @Shadow
    protected abstract void onLoadSection(long blockPos);

    @Shadow
    protected volatile boolean hasLightUpdates;

    @Shadow
    protected volatile ChunkToNibbleArrayMap<?> uncachedStorage;

    @Shadow
    protected abstract ChunkNibbleArray createSection(long pos);

    @Shadow
    @Final
    protected Long2ObjectMap<ChunkNibbleArray> queuedSections;

    @Shadow
    protected abstract boolean hasLightUpdates();

    @Shadow
    protected abstract void onUnloadSection(long l);

    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    @Shadow
    protected abstract void removeSection(ChunkLightProvider<?, ?> storage, long blockChunkPos);

    @Shadow
    protected abstract ChunkNibbleArray getLightSection(long sectionPos, boolean cached);

    @Shadow
    @Final
    private ChunkProvider chunkProvider;

    @Shadow
    @Final
    private LightType lightType;

    @Shadow
    @Final
    private LongSet queuedEdgeSections;

    @Override
    @Invoker("getLightSection")
    public abstract ChunkNibbleArray callGetLightSection(final long sectionPos, final boolean cached);

    // A multiset of 'interesting' sections per chunk. Determines which sections to iterate over when enabling/disabling chunks, for initial lighting, etc.
    // This class tracks non-trivial lightmaps, (scheduled) non-empty chunks and nonOptimizable sections resp. Vanilla lightmaps
    @Unique
    private final Long2ObjectMap<Int2ByteMap> trackedSectionsByChunk = new Long2ObjectOpenHashMap<>();

    @Unique
    protected void trackSection(final long sectionPos) {
        this.trackSection(ChunkSectionPos.withZeroY(sectionPos), ChunkSectionPos.unpackY(sectionPos));
    }

    @Unique
    protected void trackSection(final long chunkPos, final int y) {
        final Int2ByteMap sections = this.trackedSectionsByChunk.computeIfAbsent(chunkPos, __ -> new Int2ByteOpenHashMap());
        sections.put(y, (byte) (sections.get(y) + 1));
    }

    @Unique
    protected void untrackSection(final long sectionPos) {
        this.untrackSection(ChunkSectionPos.withZeroY(sectionPos), ChunkSectionPos.unpackY(sectionPos));
    }

    @Unique
    protected void untrackSection(final long chunkPos, final int y) {
        final Int2ByteMap sections = this.trackedSectionsByChunk.get(chunkPos);

        final byte count = (byte) (sections.get(y) - 1);

        if (count == 0) {
            sections.remove(y);

            if (sections.isEmpty()) {
                this.trackedSectionsByChunk.remove(chunkPos);
            }
        } else {
            sections.put(y, count);
        }
    }

    @Unique
    protected IntIterator getTrackedSections(final long chunkPos) {
        final Int2ByteMap sections = this.trackedSectionsByChunk.get(chunkPos);

        return sections == null ? IntIterators.EMPTY_ITERATOR : IntIterators.wrap(sections.keySet().toIntArray());
    }

    /**
     * The lock which wraps {@link #uncachedStorage}. Locking should always be
     * performed when accessing values in the aforementioned storage.
     */
    @Unique
    protected final StampedLock uncachedLightArraysLock = new StampedLock();

    /**
     * Replaces the two set of calls to unpack the XYZ coordinates from the input to just one, storing the result as local
     * variables.
     *
     * Additionally, this handles lookups for positions without an associated lightmap.
     *
     * @reason Use faster implementation
     * @author JellySquid
     */
    @Overwrite
    public int get(long blockPos) {
        int x = BlockPos.unpackLongX(blockPos);
        int y = BlockPos.unpackLongY(blockPos);
        int z = BlockPos.unpackLongZ(blockPos);

        long chunk = ChunkSectionPos.asLong(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(y), ChunkSectionPos.getSectionCoord(z));

        ChunkNibbleArray array = this.getLightSection(chunk, true);

        if (array == null) {
            return this.getLightWithoutLightmap(blockPos);
        }

        return array.get(ChunkSectionPos.getLocalCoord(x), ChunkSectionPos.getLocalCoord(y), ChunkSectionPos.getLocalCoord(z));
    }

    /**
     * An extremely important optimization is made here in regards to adding items to the pending notification set. The
     * original implementation attempts to add the coordinate of every chunk which contains a neighboring block position
     * even though a huge number of loop iterations will simply map to block positions within the same updating chunk.
     * <p>
     * Our implementation here avoids this by pre-calculating the min/max chunk coordinates so we can iterate over only
     * the relevant chunk positions once. This reduces what would always be 27 iterations to just 1-8 iterations.
     *
     * @reason Use faster implementation
     * @author JellySquid
     */
    @Overwrite
    public void set(long blockPos, int value) {
        int x = BlockPos.unpackLongX(blockPos);
        int y = BlockPos.unpackLongY(blockPos);
        int z = BlockPos.unpackLongZ(blockPos);

        long chunkPos = ChunkSectionPos.asLong(x >> 4, y >> 4, z >> 4);

        final ChunkNibbleArray lightmap = this.getOrAddLightmap(chunkPos);
        final int oldVal = lightmap.get(x & 15, y & 15, z & 15);

        this.beforeLightChange(blockPos, oldVal, value, lightmap);
        this.changeLightmapComplexity(chunkPos, this.getLightmapComplexityChange(blockPos, oldVal, value, lightmap));

        if (this.dirtySections.add(chunkPos)) {
            this.storage.replaceWithCopy(chunkPos);
        }

        ChunkNibbleArray nibble = this.getLightSection(chunkPos, true);
        nibble.set(x & 15, y & 15, z & 15, value);

        for (int z2 = (z - 1) >> 4; z2 <= (z + 1) >> 4; ++z2) {
            for (int x2 = (x - 1) >> 4; x2 <= (x + 1) >> 4; ++x2) {
                for (int y2 = (y - 1) >> 4; y2 <= (y + 1) >> 4; ++y2) {
                    this.notifySections.add(ChunkSectionPos.asLong(x2, y2, z2));
                }
            }
        }
    }

    /**
     * @author PhiPro
     * @reason Move large parts of the logic to other methods
     */
    @Overwrite
    public void setLevel(long id, int level) {
        int oldLevel = this.getLevel(id);

        if (oldLevel != 0 && level == 0) {
            this.readySections.add(id);
            this.markedReadySections.remove(id);
        }

        if (oldLevel == 0 && level != 0) {
            this.readySections.remove(id);
            this.markedNotReadySections.remove(id);
            this.untrackSection(id);
        }

        if (oldLevel >= 2 && level < 2) {
            this.nonOptimizableSections.add(id);

            if (this.enabledChunks.contains(ChunkSectionPos.withZeroY(id))) {
                if (!this.vanillaLightmapsToRemove.remove(id)) {
                    if (this.getLightSection(id, true) == null) {
                        this.storage.put(id, this.createTrivialVanillaLightmap(id));
                        this.dirtySections.add(id);
                        this.storage.clearCache();
                    }

                    this.trackSection(id);
                }
            } else {
                this.trackSection(id);
            }
        }

        if (oldLevel < 2 && level >= 2) {
            this.nonOptimizableSections.remove(id);

            if (this.enabledChunks.contains(ChunkSectionPos.withZeroY(id))) {
                final ChunkNibbleArray lightmap = this.getLightSection(id, true);

                if (lightmap != null && ((IReadonly) lightmap).isReadonly()) {
                    this.vanillaLightmapsToRemove.add(id);
                    this.markForLightUpdates();
                } else {
                    this.untrackSection(id);
                }
            } else {
                this.untrackSection(id);
            }
        }
    }

    /**
     * @reason Drastically improve efficiency by making removals O(n) instead of O(16*16*16)
     * @author JellySquid
     */
    @Inject(method = "removeSection", at = @At("HEAD"), cancellable = true)
    protected void preRemoveSection(ChunkLightProvider<?, ?> provider, long pos, CallbackInfo ci) {
        if (provider instanceof LightProviderUpdateTracker) {
            ((LightProviderUpdateTracker) provider).cancelUpdatesForChunk(pos);

            ci.cancel();
        }
    }

    @Override
    public void runCleanups() {
        this.runCleanups(null);
    }

    @Unique
    protected void runCleanups(final ChunkLightProvider<?, ?> lightProvider) {
        if (!this.hasLightUpdates) {
            return;
        }

        this.removeTrivialLightmaps(lightProvider);
        this.removeVanillaLightmaps(lightProvider);

        if (lightProvider == null) {
            this.checkForUpdates();
        }
    }

    /**
     * @author PhiPro
     * @reason Re-implement completely
     */
    @Overwrite
    public void updateLight(final ChunkLightProvider<?, ?> lightProvider, final boolean doSkylight, final boolean skipEdgeLightPropagation) {
        if (!this.hasLightUpdates()) {
            return;
        }

        this.initializeChunks();
        this.addQueuedLightmaps(lightProvider);
        this.runCleanups(lightProvider);

        final LongIterator it;

        if (!skipEdgeLightPropagation) {
            it = this.queuedSections.keySet().iterator();
        } else {
            it = this.queuedEdgeSections.iterator();
        }
        
        while (it.hasNext()) {
            this.updateSection(lightProvider, it.nextLong());
        }

        this.queuedEdgeSections.clear();
        this.queuedSections.clear();

        // Vanilla would normally iterate back over the map of light arrays to remove those we worked on, but
        // that is unneeded now because we removed them earlier.

        this.hasLightUpdates = false;
    }

    /**
     * @reason Avoid integer boxing, reduce map lookups and iteration as much as possible
     * @author JellySquid
     */
    @Overwrite
    private void updateSection(final ChunkLightProvider<?, ?> chunkLightProvider, long pos) {
        if (this.hasSection(pos)) {
            final LevelPropagatorAccess levelPropagator = (LevelPropagatorAccess) chunkLightProvider;

            int x = ChunkSectionPos.getBlockCoord(ChunkSectionPos.unpackX(pos));
            int y = ChunkSectionPos.getBlockCoord(ChunkSectionPos.unpackY(pos));
            int z = ChunkSectionPos.getBlockCoord(ChunkSectionPos.unpackZ(pos));

            for (Direction dir : DIRECTIONS) {
                long adjPos = ChunkSectionPos.offset(pos, dir);

                // Avoid updating initializing chunks unnecessarily
                if (this.queuedSections.containsKey(adjPos)) {
                    continue;
                }

                // If there is no light data for this section yet, skip it
                if (!this.hasSection(adjPos)) {
                    continue;
                }

                for (int u1 = 0; u1 < 16; ++u1) {
                    for (int u2 = 0; u2 < 16; ++u2) {
                        long a;
                        long b;

                        switch (dir) {
                            case DOWN:
                                a = BlockPos.asLong(x + u2, y, z + u1);
                                b = BlockPos.asLong(x + u2, y - 1, z + u1);
                                break;
                            case UP:
                                a = BlockPos.asLong(x + u2, y + 15, z + u1);
                                b = BlockPos.asLong(x + u2, y + 16, z + u1);
                                break;
                            case NORTH:
                                a = BlockPos.asLong(x + u1, y + u2, z);
                                b = BlockPos.asLong(x + u1, y + u2, z - 1);
                                break;
                            case SOUTH:
                                a = BlockPos.asLong(x + u1, y + u2, z + 15);
                                b = BlockPos.asLong(x + u1, y + u2, z + 16);
                                break;
                            case WEST:
                                a = BlockPos.asLong(x, y + u1, z + u2);
                                b = BlockPos.asLong(x - 1, y + u1, z + u2);
                                break;
                            case EAST:
                                a = BlockPos.asLong(x + 15, y + u1, z + u2);
                                b = BlockPos.asLong(x + 16, y + u1, z + u2);
                                break;
                            default:
                                continue;
                        }

                        levelPropagator.propagateLevel(a, b, false);
                        levelPropagator.propagateLevel(b, a, false);
                    }
                }
            }

            levelPropagator.checkForUpdates();
        }
    }

    /**
     * @reason
     * @author JellySquid
     */
    @Overwrite
    public void notifyChanges() {
        if (!this.dirtySections.isEmpty()) {
            // This could result in changes being flushed to various arrays, so write lock.
            long stamp = this.uncachedLightArraysLock.writeLock();

            try {
                // This only performs a shallow copy compared to before
                ChunkToNibbleArrayMap<?> map = this.storage.copy();
                map.disableCache();

                this.uncachedStorage = map;
            } finally {
                this.uncachedLightArraysLock.unlockWrite(stamp);
            }

            this.dirtySections.clear();
        }

        if (!this.notifySections.isEmpty()) {
            LongIterator it = this.notifySections.iterator();

            while(it.hasNext()) {
                long pos = it.nextLong();

                this.chunkProvider.onLightUpdate(this.lightType, ChunkSectionPos.from(pos));
            }

            this.notifySections.clear();
        }
    }

    @Override
    public int getLightWithoutLightmap(final long blockPos) {
        return 0;
    }

    /**
     * This method has an equivalent effect to calling {@link #onLoadSection(long)} on all lightmaps in the specified chunk.
     * Additional data can also be set up before enabling the chunk.
     */
    @Unique
    protected void beforeChunkEnabled(final long chunkPos) {
        for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
            final long sectionPos = ChunkSectionPosHelper.updateYLong(chunkPos, it.nextInt());

            if (this.hasLightmap(sectionPos)) {
                this.onLoadSection(sectionPos);
            }
        }
    }

    /**
     * This method has an equivalent effect to calling {@link #onUnloadSection(long)} on all (already removed) lightmaps in the specified chunk.
     * Additional data can also be removed upon disabling the chunk.
     */
    @Unique
    protected void afterChunkDisabled(final long chunkPos, final IntIterable removedLightmaps) {
        for (IntIterator it = removedLightmaps.iterator(); it.hasNext(); ) {
            this.onUnloadSection(ChunkSectionPosHelper.updateYLong(chunkPos, it.nextInt()));
        }
    }

    @Unique
    protected final LongSet enabledChunks = new LongOpenHashSet();
    @Unique
    protected final Long2IntMap lightmapComplexities = Util.make(new Long2IntOpenHashMap(), map -> map.defaultReturnValue(-1));

    @Unique
    private final LongSet markedEnabledChunks = new LongOpenHashSet();
    @Unique
    private final LongSet trivialLightmaps = new LongOpenHashSet();
    @Unique
    private final LongSet vanillaLightmapsToRemove = new LongOpenHashSet();

    // This is put here since the relevant methods to overwrite are located in LightStorage
    @Unique
    protected final LongSet nonOptimizableSections = new LongOpenHashSet();

    @Unique
    protected ChunkNibbleArray getOrAddLightmap(final long sectionPos) {
        ChunkNibbleArray lightmap = this.getLightSection(sectionPos, true);

        if (lightmap == null) {
            lightmap = this.createSection(sectionPos);
        } else {
            if (((IReadonly) lightmap).isReadonly()) {
                lightmap = lightmap.copy();

                if (this.vanillaLightmapsToRemove.remove(sectionPos)) {
                    this.untrackSection(sectionPos);
                }
            } else {
                return lightmap;
            }
        }

        this.storage.put(sectionPos, lightmap);
        this.trackSection(sectionPos);
        this.dirtySections.add(sectionPos);
        this.storage.clearCache();

        this.onLoadSection(sectionPos);
        this.setLightmapComplexity(sectionPos, 0);

        return lightmap;
    }

    @Unique
    protected void setLightmapComplexity(final long sectionPos, final int complexity) {
        int oldComplexity = this.lightmapComplexities.put(sectionPos, complexity);

        if (oldComplexity == 0) {
            this.trivialLightmaps.remove(sectionPos);
        }

        if (complexity == 0) {
            this.trivialLightmaps.add(sectionPos);
            this.markForLightUpdates();
        }
    }

    @Unique
    private void checkForUpdates() {
        this.hasLightUpdates = !this.trivialLightmaps.isEmpty() || !this.vanillaLightmapsToRemove.isEmpty() || !this.markedEnabledChunks.isEmpty() || !this.queuedSections.isEmpty();
    }

    @Unique
    private void markForLightUpdates() {
        // Avoid volatile writes
        if (!this.hasLightUpdates) {
            this.hasLightUpdates = true;
        }
    }

    @Unique
    protected void changeLightmapComplexity(final long sectionPos, final int amount) {
        int complexity = this.lightmapComplexities.get(sectionPos);

        if (complexity == 0) {
            this.trivialLightmaps.remove(sectionPos);
        }

        complexity += amount;
        this.lightmapComplexities.put(sectionPos, complexity);

        if (complexity == 0) {
            this.trivialLightmaps.add(sectionPos);
            this.markForLightUpdates();
        }
    }

    @Unique
    protected ChunkNibbleArray getLightmap(final long sectionPos) {
        final ChunkNibbleArray lightmap = this.getLightSection(sectionPos, true);
        return lightmap == null || ((IReadonly) lightmap).isReadonly() ? null : lightmap;
    }

    @Unique
    protected boolean hasLightmap(final long sectionPos) {
        return this.getLightmap(sectionPos) != null;
    }

    /**
     * Set up lightmaps and adjust complexities as needed for the given light change.
     * Additional data for the given <code>blockPos</code> itself need to be updated manually, whereas the main lightmap complexity will be updated automatically.
     */
    @Unique
    protected void beforeLightChange(final long blockPos, final int oldVal, final int newVal, final ChunkNibbleArray lightmap) {
    }

    @Unique
    protected int getLightmapComplexityChange(final long blockPos, final int oldVal, final int newVal, final ChunkNibbleArray lightmap) {
        return 0;
    }

    /**
     * Set up lightmaps and adjust complexities as needed for the given lightmap change.
     * If <code>oldLightmap == null</code>, then {@link #onLoadSection(long)} will be called later on. Otherwise, additional data for the given <code>sectionPos</code> itself need to be updated manually.
     * The main lightmap complexity will always be updated automatically.
     */
    @Unique
    protected void beforeLightmapChange(final long sectionPos, final ChunkNibbleArray oldLightmap, final ChunkNibbleArray newLightmap) {
    }

    @Unique
    protected int getInitialLightmapComplexity(final long sectionPos, final ChunkNibbleArray lightmap) {
        return 0;
    }

    @Invoker("hasSection")
    @Override
    public abstract boolean callHasSection(long sectionPos);

    /**
     * Determines whether light updates should be propagated into the given section.
     * @author PhiPro
     * @reason Method completely changed. Allow child mixins to properly extend this.
     */
    @Overwrite
    public boolean hasSection(final long sectionPos) {
        return this.enabledChunks.contains(ChunkSectionPos.withZeroY(sectionPos));
    }

    @Shadow
    protected abstract void setColumnEnabled(long columnPos, boolean enabled);

    /**
     * @author PhiPro
     * @reason Re-implement completely, ensuring data consistency
     */
    @Overwrite
    public void setSectionStatus(final long sectionPos, final boolean notReady) {
        if (notReady) {
            if (this.markedReadySections.remove(sectionPos)) {
                this.untrackSection(sectionPos);
            } else if (!this.readySections.contains(sectionPos) || !this.markedNotReadySections.add(sectionPos)) {
                return;
            }

            this.updateLevel(Long.MAX_VALUE, sectionPos, 2, false);
        } else {
            if (!this.markedNotReadySections.remove(sectionPos)) {
                if (this.readySections.contains(sectionPos) || !this.markedReadySections.add(sectionPos)) {
                    return;
                }

                this.trackSection(sectionPos);
            }

            this.updateLevel(Long.MAX_VALUE, sectionPos, 0, true);
        }
    }

    @Override
    @Invoker("setColumnEnabled")
    public abstract void invokeSetColumnEnabled(final long chunkPos, final boolean enabled);

    @Override
    public void enableLightUpdates(final long chunkPos) {
        if (!this.enabledChunks.contains(chunkPos)){
            this.markedEnabledChunks.add(chunkPos);
            this.markForLightUpdates();
        }
    }

    @Unique
    private void initializeChunks() {
        this.storage.clearCache();

        for (final LongIterator cit = this.markedEnabledChunks.iterator(); cit.hasNext(); ) {
            final long chunkPos = cit.nextLong();

            // First need to register all lightmaps as this data is needed for calculating the initial complexity

            this.beforeChunkEnabled(chunkPos);

            // Now the initial complexities can be computed

            for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
                final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.unpackX(chunkPos), it.nextInt(), ChunkSectionPos.unpackZ(chunkPos));

                if (this.hasLightmap(sectionPos)) {
                    this.setLightmapComplexity(sectionPos, this.getInitialLightmapComplexity(sectionPos, this.getLightSection(sectionPos, true)));
                }
            }

            // Add lightmaps for vanilla compatibility and try to recover stripped data from vanilla saves

            for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
                final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.unpackX(chunkPos), it.nextInt(), ChunkSectionPos.unpackZ(chunkPos));

                if (this.nonOptimizableSections.contains(sectionPos) && this.getLightSection(sectionPos, true) == null) {
                    this.storage.put(sectionPos, this.createInitialVanillaLightmap(sectionPos));
                    this.dirtySections.add(sectionPos);
                }
            }

            this.enabledChunks.add(chunkPos);
        }

        this.storage.clearCache();

        this.markedEnabledChunks.clear();
    }

    @Unique
    protected ChunkNibbleArray createInitialVanillaLightmap(final long sectionPos) {
        return this.createTrivialVanillaLightmap(sectionPos);
    }

    @Unique
    protected ChunkNibbleArray createTrivialVanillaLightmap(final long sectionPos) {
        return new EmptyChunkNibbleArray();
    }

    @Override
    public void disableChunkLight(final long chunkPos, final ChunkLightProvider<?, ?> lightProvider) {
        if (this.markedEnabledChunks.remove(chunkPos) || !this.enabledChunks.contains(chunkPos)) {
            for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
                final int y = it.nextInt();
                final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.unpackX(chunkPos), y, ChunkSectionPos.unpackZ(chunkPos));

                if (this.storage.removeChunk(sectionPos) != null) {
                    this.untrackSection(chunkPos, y);
                    this.dirtySections.add(sectionPos);
                }
            }

            this.setColumnEnabled(chunkPos, false);
            this.removeBlockData(chunkPos);
        } else {
            // First need to remove all pending light updates before changing any light value

            for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
                final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.unpackX(chunkPos), it.nextInt(), ChunkSectionPos.unpackZ(chunkPos));

                if (this.hasSection(sectionPos)) {
                    this.removeSection(lightProvider, sectionPos);
                }
            }

            // Now the chunk can be disabled

            this.enabledChunks.remove(chunkPos);

            // Now lightmaps can be removed

            final IntList removedLightmaps = new IntArrayList();

            for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
                final int y = it.nextInt();
                final long sectionPos = ChunkSectionPosHelper.updateYLong(chunkPos, y);

                if (this.removeLightmap(sectionPos)) {
                    removedLightmaps.add(y);
                }
            }

            // These maps are cleared every update and should hence be small
            this.queuedSections.keySet().removeIf((LongPredicate) sectionPos -> ChunkSectionPos.withZeroY(sectionPos) == chunkPos);
            this.queuedEdgeSections.removeIf((LongPredicate) sectionPos -> ChunkSectionPos.withZeroY(sectionPos) == chunkPos);

            this.storage.clearCache();
            this.setColumnEnabled(chunkPos, false);

            // Remove all additional data

            this.afterChunkDisabled(chunkPos, removedLightmaps);
            this.removeBlockData(chunkPos);
        }
    }

    /**
     * Removes the lightmap associated to the provided <code>sectionPos</code>, but does not call {@link #onUnloadSection(long)} or {@link ChunkToNibbleArrayMap#clearCache()}
     * @return Whether a lightmap was removed
     */
    @Unique
    protected boolean removeLightmap(final long sectionPos) {
        if (this.storage.removeChunk(sectionPos) == null) {
            return false;
        }

        this.dirtySections.add(sectionPos);

        if (this.lightmapComplexities.remove(sectionPos) == -1) {
            if (this.vanillaLightmapsToRemove.remove(sectionPos)) {
                this.untrackSection(sectionPos);
            }

            return false;
        } else {
            this.trivialLightmaps.remove(sectionPos);
            this.untrackSection(sectionPos);
            return true;
        }
    }

    @Unique
    private void removeBlockData(final long chunkPos) {
        for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
            this.setSectionStatus(ChunkSectionPosHelper.updateYLong(chunkPos, it.nextInt()), true);
        }
    }

    @Unique
    private void removeTrivialLightmaps(final ChunkLightProvider<?, ?> lightProvider) {
        for (final LongIterator it = this.trivialLightmaps.iterator(); it.hasNext(); ) {
            final long sectionPos = it.nextLong();

            this.storage.removeChunk(sectionPos);
            this.lightmapComplexities.remove(sectionPos);
            this.untrackSection(sectionPos);
            this.dirtySections.add(sectionPos);
        }

        this.storage.clearCache();

        // Calling onUnloadSection() after removing all the lightmaps is slightly more efficient

        for (final LongIterator it = this.trivialLightmaps.iterator(); it.hasNext(); ) {
            this.onUnloadSection(it.nextLong());
        }

        // Add trivial lightmaps for vanilla compatibility

        for (final LongIterator it = this.trivialLightmaps.iterator(); it.hasNext(); ) {
            final long sectionPos = it.nextLong();

            if (this.nonOptimizableSections.contains(sectionPos)) {
                this.storage.put(sectionPos, this.createTrivialVanillaLightmap(sectionPos));
            }
        }

        this.storage.clearCache();

        // Remove pending light updates for sections that no longer support light propagations

        if (lightProvider != null) {
            for (final LongIterator it = this.trivialLightmaps.iterator(); it.hasNext(); ) {
                final long sectionPos = it.nextLong();

                if (!this.hasSection(sectionPos)) {
                    this.removeSection(lightProvider, sectionPos);
                }
            }
        }

        this.trivialLightmaps.clear();
    }

    @Unique
    private void removeVanillaLightmaps(final ChunkLightProvider<?, ?> lightProvider) {
        for (final LongIterator it = this.vanillaLightmapsToRemove.iterator(); it.hasNext(); ) {
            final long sectionPos = it.nextLong();

            this.storage.removeChunk(sectionPos);
            this.untrackSection(sectionPos);
            this.dirtySections.add(sectionPos);
        }

        this.storage.clearCache();

        // Remove pending light updates for sections that no longer support light propagations

        if (lightProvider != null) {
            for (final LongIterator it = this.vanillaLightmapsToRemove.iterator(); it.hasNext(); ) {
                final long sectionPos = it.nextLong();

                if (!this.hasSection(sectionPos)) {
                    this.removeSection(lightProvider, sectionPos);
                }
            }
        }

        this.vanillaLightmapsToRemove.clear();
    }

    @Unique
    private void addQueuedLightmaps(final ChunkLightProvider<?, ?> lightProvider) {
        for (final ObjectIterator<Long2ObjectMap.Entry<ChunkNibbleArray>> it = Long2ObjectMaps.fastIterator(this.queuedSections); it.hasNext(); ) {
            final Long2ObjectMap.Entry<ChunkNibbleArray> entry = it.next();

            final long sectionPos = entry.getLongKey();
            final ChunkNibbleArray lightmap = entry.getValue();

            final ChunkNibbleArray oldLightmap = this.getLightmap(sectionPos);

            if (lightmap != oldLightmap) {
                this.removeSection(lightProvider, sectionPos);

                this.beforeLightmapChange(sectionPos, oldLightmap, lightmap);

                this.storage.put(sectionPos, lightmap);
                this.storage.clearCache();
                this.dirtySections.add(sectionPos);

                if (oldLightmap == null) {
                    this.trackSection(sectionPos);
                    this.onLoadSection(sectionPos);
                }

                if (this.vanillaLightmapsToRemove.remove(sectionPos)) {
                    this.untrackSection(sectionPos);
                }

                this.setLightmapComplexity(sectionPos, this.getInitialLightmapComplexity(sectionPos, lightmap));
            }
        }
    }

    /**
     * @author PhiPro
     * @reason Add lightmaps for disabled chunks directly to the world
     */
    @Overwrite
    public void enqueueSectionData(final long sectionPos, final ChunkNibbleArray array, final boolean bl) {
        final boolean chunkEnabled = this.enabledChunks.contains(ChunkSectionPos.withZeroY(sectionPos));

        if (array != null) {
            if (chunkEnabled) {
                this.queuedSections.put(sectionPos, array);
                this.markForLightUpdates();
            } else {
                this.storage.put(sectionPos, array);
                this.trackSection(sectionPos);
                this.dirtySections.add(sectionPos);
            }

            if (!bl) {
                this.queuedEdgeSections.add(sectionPos);
            }
        } else {
            if (chunkEnabled) {
                this.queuedSections.remove(sectionPos);
            } else {
                if (this.storage.removeChunk(sectionPos) != null) {
                    this.untrackSection(sectionPos);
                    this.dirtySections.add(sectionPos);
                }
            }
        }
    }

    // Queued lightmaps are only added to the world via updateLightmaps()
    @Redirect(
        method = "createSection(J)Lnet/minecraft/world/chunk/ChunkNibbleArray;",
        slice = @Slice(
            from = @At(
                value = "FIELD",
                target = "Lnet/minecraft/world/chunk/light/LightStorage;queuedSections:Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;",
                opcode = Opcodes.GETFIELD
            )
        ),
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;get(J)Ljava/lang/Object;",
            ordinal = 0,
            remap = false
        )
    )
    private Object cancelLightmapLookupFromQueue(final Long2ObjectMap<ChunkNibbleArray> lightmapArray, final long pos) {
        return null;
    }

    @Redirect(
        method = "getLevel(J)I",
        slice = @Slice(
            from = @At(
                value = "FIELD",
                target = "Lnet/minecraft/world/chunk/light/LightStorage;storage:Lnet/minecraft/world/chunk/ChunkToNibbleArrayMap;",
                opcode = Opcodes.GETFIELD
            )
        ),
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/ChunkToNibbleArrayMap;containsKey(J)Z",
            ordinal = 0
        )
    )
    private boolean isNonOptimizable(final ChunkToNibbleArrayMap<?> lightmapArray, final long sectionPos) {
        return this.nonOptimizableSections.contains(sectionPos);
    }
}
