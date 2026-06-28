package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.internal.util.config.ConfigPaths;
import net.democracycraft.vault.internal.util.region.RegionKey;
import net.democracycraft.vault.internal.util.scan.OfflineRegionScanner;
import net.democracycraft.vault.internal.util.scan.OfflineRegionScanner.DisplacedContainer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Schedules and runs the delayed, batched auto-vaulting of Bolt-locked containers (and, when enabled,
 * item frames and paintings) when a region's owner/tenant changes. A lock is vaulted when its Bolt owner
 * is not in the allowed set ({new occupant} plus the region's WorldGuard owners/members). Runs on the
 * main thread; chunk loads are async.
 */
public class AutoVaultService {

    private final VaultStoragePlugin plugin;
    // Main-thread only: populated from the Realty event handler and the scheduled sweep task.
    private final Map<RegionKey, BukkitTask> pending = new HashMap<>();

    public AutoVaultService(@NotNull VaultStoragePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Schedules an auto-vault sweep of {@code regionId} after the configured grace delay.
     * Cancels any pending sweep already queued for the same region+world.
     *
     * @param worldId   world the region lives in
     * @param regionId  WorldGuard region id
     * @param initiator the new occupant (owner/tenant); recorded as vault creator and exempt from vaulting
     */
    public void submit(@NotNull UUID worldId, @NotNull String regionId, @NotNull UUID initiator) {
        if (!plugin.getConfig().getBoolean(ConfigPaths.AUTOVAULT_ENABLED.getPath(), true)) {
            return;
        }
        RegionKey key = new RegionKey(worldId, regionId);
        long delayTicks = Math.max(0L, plugin.getConfig().getLong(ConfigPaths.AUTOVAULT_DELAY_TICKS.getPath(), 1200L));

        BukkitTask task = new BukkitRunnable() {
            @Override public void run() {
                // A fired task is always the current pending entry (a resubmit cancels the prior one).
                pending.remove(key);
                runSweep(key, initiator);
            }
        }.runTaskLater(plugin, delayTicks);
        BukkitTask previous = pending.put(key, task);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void runSweep(@NotNull RegionKey key, @NotNull UUID initiator) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) {
            return;
        }
        WorldGuardService wgs = plugin.getWorldGuardService();
        if (wgs == null) {
            return;
        }
        VaultRegion region = wgs.getRegionById(key.regionId(), world);
        if (region == null) {
            // Region was deleted during the delay window; nothing to sweep.
            return;
        }

        Set<UUID> allowed = new HashSet<>();
        allowed.addAll(region.owners());
        allowed.addAll(region.members());
        allowed.add(initiator);

        List<DisplacedContainer> displaced = OfflineRegionScanner.scan(key.worldId(), region.boundingBox(), allowed);
        if (displaced != null && !displaced.isEmpty()) {
            vaultBlocks(key.worldId(), displaced, initiator);
        }
        if (plugin.getConfig().getBoolean(ConfigPaths.AUTOVAULT_INCLUDE_HANGINGS.getPath(), true)) {
            vaultHangings(key.worldId(), region.boundingBox(), allowed, initiator);
        }
    }

    /** Vaults each displaced container, loading its chunk asynchronously. */
    private void vaultBlocks(@NotNull UUID worldId, @NotNull List<DisplacedContainer> displaced, @NotNull UUID initiator) {
        VaultCaptureService captureService = plugin.getCaptureService();
        forEachChunkPaced(worldId, displaced,
                dc -> new int[]{dc.x() >> 4, dc.z() >> 4},
                (world, chunk, dc) ->
                        // Tolerant of blocks changed since the scan (non-containers just drop their protection).
                        captureService.captureOfflineAsync(world.getBlockAt(dc.x(), dc.y(), dc.z()), initiator, dc.owner()));
    }

    /**
     * Vaults displaced item frames and paintings across the region. Entity protections are not indexed
     * by coordinate, so every chunk in the region's bounds is loaded and its hanging entities inspected.
     */
    private void vaultHangings(@NotNull UUID worldId, @NotNull BoundingBox box, @NotNull Set<UUID> allowed, @NotNull UUID initiator) {
        VaultCaptureService captureService = plugin.getCaptureService();
        BoltService bolt = plugin.getBoltService();

        List<int[]> chunks = new ArrayList<>();
        int minChunkX = (int) Math.floor(box.getMinX()) >> 4;
        int minChunkZ = (int) Math.floor(box.getMinZ()) >> 4;
        int maxChunkX = (int) Math.floor(box.getMaxX()) >> 4;
        int maxChunkZ = (int) Math.floor(box.getMaxZ()) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunks.add(new int[]{cx, cz});
            }
        }

        forEachChunkPaced(worldId, chunks,
                coords -> coords,
                (world, chunk, coords) -> {
                    for (Entity entity : chunk.getEntities()) {
                        if (!(entity instanceof ItemFrame) && !(entity instanceof Painting)) continue;
                        UUID owner = bolt.getOwner(entity);
                        if (owner == null || allowed.contains(owner)) continue;
                        captureService.captureHangingOfflineAsync((Hanging) entity, owner, initiator);
                    }
                });
    }

    /**
     * Runs {@code work} for each item, loading the chunk given by {@code chunkOf} asynchronously and keeping
     * at most {@code scan.batch-size} loads in flight. {@code work} runs on the main thread once that chunk is
     * loaded. Stops if the world unloads.
     */
    private <T> void forEachChunkPaced(@NotNull UUID worldId, @NotNull List<T> items,
                                       @NotNull Function<T, int[]> chunkOf, @NotNull ChunkWork<T> work) {
        final int maxInFlight = Math.max(1, plugin.getConfig().getInt(ConfigPaths.SCAN_BATCH_SIZE.getPath(), 50));

        new BukkitRunnable() {
            int index = 0;
            int inFlight = 0; // pending async chunk loads; mutated only on the main thread

            @Override public void run() {
                World world = Bukkit.getWorld(worldId);
                if (world == null) {
                    this.cancel();
                    return;
                }
                while (index < items.size() && inFlight < maxInFlight) {
                    T item = items.get(index);
                    index++;
                    int[] chunkCoords = chunkOf.apply(item);
                    inFlight++;
                    world.getChunkAtAsync(chunkCoords[0], chunkCoords[1]).thenAccept(chunk -> {
                        inFlight--;
                        work.run(world, chunk, item);
                    });
                }
                if (index >= items.size() && inFlight == 0) {
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    @FunctionalInterface
    private interface ChunkWork<T> {
        void run(World world, Chunk chunk, T item);
    }

    /** Cancels all pending sweeps. Call from {@code onDisable}. */
    public void shutdown() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
    }
}
