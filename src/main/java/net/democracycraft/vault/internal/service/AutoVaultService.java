package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.ScanResult;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.internal.util.config.ConfigPaths;
import net.democracycraft.vault.internal.util.region.RegionKey;
import net.democracycraft.vault.internal.util.scan.OfflineRegionScanner;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Schedules and runs the delayed, batched auto-vaulting of Bolt-locked containers when a region's
 * owner/tenant changes. A container is vaulted when its Bolt owner is not in the allowed set
 * ({new occupant} plus the region's WorldGuard owners/members). Runs on the main thread.
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

        BukkitTask previous = pending.remove(key);
        if (previous != null && !previous.isCancelled()) {
            previous.cancel();
        }

        BukkitTask task = new BukkitRunnable() {
            @Override public void run() {
                // A fired task is always the current pending entry (a resubmit cancels the prior one).
                pending.remove(key);
                runSweep(key, initiator);
            }
        }.runTaskLater(plugin, delayTicks);
        pending.put(key, task);
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

        new OfflineRegionScanner(key.worldId(), region.boundingBox(), allowed, results -> {
            if (results == null || results.isEmpty()) {
                return;
            }
            vaultBatched(results, initiator);
        }).start();
    }

    private void vaultBatched(@NotNull List<ScanResult> results, @NotNull UUID initiator) {
        VaultCaptureService captureService = plugin.getCaptureService();
        final int batchSize = plugin.getConfig().getInt(ConfigPaths.SCAN_BATCH_SIZE.getPath(), 50);

        new BukkitRunnable() {
            int index = 0;
            final Set<Location> processed = new HashSet<>();

            @Override public void run() {
                long startTime = System.currentTimeMillis();
                int handled = 0;

                while (index < results.size() && handled < batchSize) {
                    if (System.currentTimeMillis() - startTime > 2) {
                        break;
                    }
                    ScanResult result = results.get(index);
                    index++;
                    handled++;

                    Block block = result.block();
                    Location loc = block.getLocation();
                    // Skip blocks already consumed this run (e.g. the far half of a double chest).
                    if (!processed.add(loc)) continue;

                    World world = block.getWorld();
                    if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                        world.getChunkAt(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
                    }
                    // Tolerant of blocks changed since the scan (non-containers just drop their protection).
                    captureService.captureOfflineAsync(block, initiator, result.owner(), done -> {});
                }

                if (index >= results.size()) {
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Cancels all pending sweeps. Call from {@code onDisable}. */
    public void shutdown() {
        for (BukkitTask task : new ArrayList<>(pending.values())) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        pending.clear();
    }
}
