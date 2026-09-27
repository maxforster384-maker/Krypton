package com.krypton.afkdiagnose;

import java.util.Locale;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/** Kleiner, begrenzter Scan bereits geladener Client-Chunks. Keine Raycasts oder Interaktionen. */
final class SpawnerSnapshot {
    static final int RADIUS = 16;
    static final int ENTRY_LIMIT = 4096;
    static final long TIME_LIMIT_NANOS = 2_000_000L;

    private SpawnerSnapshot() { }

    static String read(MinecraftClient client) {
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        int minX = ((int) Math.floor(x - RADIUS)) >> 4;
        int maxX = ((int) Math.floor(x + RADIUS)) >> 4;
        int minZ = ((int) Math.floor(z - RADIUS)) >> 4;
        int maxZ = ((int) Math.floor(z + RADIUS)) >> 4;
        int expected = (maxX - minX + 1) * (maxZ - minZ + 1);
        int loaded = 0, inspected = 0, count = 0;
        boolean budgetExceeded = false;
        double nearest = Double.POSITIVE_INFINITY;
        BlockPos nearestPos = null;
        long started = System.nanoTime();

        scan:
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (System.nanoTime() - started >= TIME_LIMIT_NANOS) { budgetExceeded = true; break scan; }
                // false: kein Nachladen und keine leeren Ersatz-Chunks als "geladen" zaehlen.
                WorldChunk chunk = client.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                loaded++;
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    if (++inspected > ENTRY_LIMIT || (inspected % 32 == 0
                            && System.nanoTime() - started >= TIME_LIMIT_NANOS)) {
                        budgetExceeded = true;
                        break scan;
                    }
                    BlockEntity entity = entry.getValue();
                    if (!(entity instanceof MobSpawnerBlockEntity)) continue;
                    BlockPos pos = entry.getKey();
                    if (!chunk.getBlockState(pos).isOf(Blocks.SPAWNER)) continue;
                    double dx = pos.getX() + 0.5 - x, dy = pos.getY() + 0.5 - y, dz = pos.getZ() + 0.5 - z;
                    double squared = dx * dx + dy * dy + dz * dz;
                    if (squared > RADIUS * RADIUS) continue;
                    count++;
                    if (squared < nearest) { nearest = squared; nearestPos = pos; }
                }
            }
        }
        String completeness = budgetExceeded ? "TEILMESSUNG (Zeit-/Mengenlimit)"
                : loaded < expected ? "TEILMESSUNG (Chunks fehlen)" : "vollstaendig im Client";
        String distance = nearestPos == null ? "kein Treffer in geprueften Daten"
                : String.format(Locale.ROOT, "%.2f Bloecke bei %d,%d,%d", Math.sqrt(nearest),
                        nearestPos.getX(), nearestPos.getY(), nearestPos.getZ());
        return "Radius=" + RADIUS + "; Spawner-BLOCKPOSITIONEN=" + count + "; Naechster=" + distance
                + "; Chunks=" + loaded + "/" + expected + "; Pruefung=" + completeness
                + "; DauerMs=" + String.format(Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1_000_000.0)
                + "; Loot/Stackmenge/Serverproduktion=NICHT GEMESSEN";
    }
}
