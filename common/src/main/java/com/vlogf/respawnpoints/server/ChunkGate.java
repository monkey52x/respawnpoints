package com.vlogf.respawnpoints.server;

import com.vlogf.respawnpoints.Constants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.TagType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * "Is this chunk saved on disk at FULL stage, without loading or generating
 * it?" Uses vanilla's own {@code chunkScanner()} (shared region handles, no
 * deserialization past the Status field), so there are no competing file
 * handles and no stale reads. Only FULL chunks pass: loading a proto-chunk
 * would finish its generation, which is banned here.
 *
 * <p>Server thread only. Callers share one {@link Scan} per search so repeated
 * probes of the same chunk cost nothing after the first touch.
 *
 * <p>Disk probes are rate-limited per tick via {@link RespawnConfig#getNearDeathMaxScansPerTick()}
 * to avoid lag spikes when multiple players die simultaneously.
 */
public final class ChunkGate {
    private ChunkGate() {
    }

    private static final String FULL_STATUS =
            BuiltInRegistries.CHUNK_STATUS.getKey(ChunkStatus.FULL).toString();

    /** Per-search probe cache. */
    public static final class Scan {
        private final Map<Long, Boolean> full = new HashMap<>();
        private int count;
    }

    /** True when the chunk is on disk at FULL stage (memory check first, then one bounded probe). */
    public static boolean isSavedFull(ServerLevel level, int chunkX, int chunkZ, Scan scan) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Boolean cached = scan.full.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        int maxScans = RespawnConfig.getNearDeathMaxScansPerTick();
        if (scan.count < maxScans) {
            scan.count++;
            result = probeDisk(level, chunkX, chunkZ);
            scan.full.put(key, result);
        }
        return result;
    }

    private static boolean probeDisk(ServerLevel level, int chunkX, int chunkZ) {
        StatusProbe probe = new StatusProbe();
        try {
            level.getChunkSource().chunkScanner()
                    .scanChunk(new ChunkPos(chunkX, chunkZ), probe)
                    .join();
        } catch (Exception e) {
            return false;
        }
        if (probe.seenRoot && !probe.full) {
            // Non-full (or unexpected) stage: visible at debug so a Status
            // string drift never fails silently again.
            Constants.LOG.debug(
                    "ChunkGate: chunk [{}, {}] stage '{}', need '{}'",
                    chunkX, chunkZ, probe.foundStatus, FULL_STATUS);
        }
        return probe.full;
    }

    /**
     * Records whether the chunk root was visited and whether its Status field
     * is FULL. Halts the stream as soon as the Status value is read (it sits
     * near the start of chunk NBT, so almost nothing is parsed). Absent chunks
     * never call the visitor at all.
     */
    private static final class StatusProbe implements StreamTagVisitor {
        boolean seenRoot;
        boolean full;
        String foundStatus = "";
        private boolean awaitingStatus;

        @Override
        public ValueResult visitRootEntry(TagType<?> type) {
            seenRoot = true;
            // Descend: the Status field lives inside the root compound.
            // (Returning HALT here would stop everything and Status would
            // never be read — a past bug that rejected every disk chunk.)
            return ValueResult.CONTINUE;
        }

        @Override
        public EntryResult visitEntry(TagType<?> type, String id) {
            if ("Status".equals(id)) {
                awaitingStatus = true;
            }
            return EntryResult.ENTER;
        }

        @Override
        public ValueResult visit(String entry) {
            if (awaitingStatus) {
                awaitingStatus = false;
                foundStatus = entry;
                full = FULL_STATUS.equals(entry);
                return ValueResult.HALT;
            }
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visitEnd() {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visit(byte entry) {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visit(short entry) {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visit(int entry) {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visit(long entry) {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visit(float entry) {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visit(double entry) {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visit(byte[] entry) {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visit(int[] entry) {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visit(long[] entry) {
            return ValueResult.CONTINUE;
        }

        @Override
        public ValueResult visitList(TagType<?> type, int size) {
            awaitingStatus = false;
            return ValueResult.CONTINUE;
        }

        @Override
        public EntryResult visitEntry(TagType<?> type) {
            awaitingStatus = false;
            return EntryResult.ENTER;
        }

        @Override
        public EntryResult visitElement(TagType<?> type, int size) {
            awaitingStatus = false;
            return EntryResult.ENTER;
        }

        @Override
        public ValueResult visitContainerEnd() {
            awaitingStatus = false;
            return ValueResult.CONTINUE;
        }
    }
}