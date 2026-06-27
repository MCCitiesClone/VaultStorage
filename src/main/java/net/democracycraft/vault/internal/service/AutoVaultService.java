package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.internal.util.config.ConfigPaths;
import net.democracycraft.vault.internal.util.region.RegionKey;
import net.democracycraft.vault.internal.util.scan.OfflineRegionScanner;
import net.democracycraft.vault.internal.util.scan.OfflineRegionScanner.DisplacedContainer;
import org.bukkit.Bukkit;
import org.bukkit.World;
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

        List<DisplacedContainer> displaced = OfflineRegionScanner.scan(key.worldId(), region.boundingBox(), allowed);
        if (displaced == null || displaced.isEmpty()) {
            return;
        }
        vaultBatched(key.worldId(), displaced, initiator);
    }

    private void vaultBatched(@NotNull UUID worldId, @NotNull List<DisplacedContainer> displaced, @NotNull UUID initiator) {
        VaultCaptureService captureService = plugin.getCaptureService();
        final int perTick = Math.max(1, plugin.getConfig().getInt(ConfigPaths.SCAN_BATCH_SIZE.getPath(), 50));

        new BukkitRunnable() {
            int index = 0;

            @Override public void run() {
                World world = Bukkit.getWorld(worldId);
                if (world == null) {
                    this.cancel();
                    return;
                }

                // Launch up to perTick async chunk loads this tick; each capture runs in its completion
                // callback (back on the main thread) so the main thread never blocks on chunk I/O.
                int launched = 0;
                while (index < displaced.size() && launched < perTick) {
                    DisplacedContainer dc = displaced.get(index);
                    index++;
                    launched++;
                    world.getChunkAtAsync(dc.x() >> 4, dc.z() >> 4).thenAccept(chunk ->
                            // Tolerant of blocks changed since the scan (non-containers just drop their protection).
                            captureService.captureOfflineAsync(world.getBlockAt(dc.x(), dc.y(), dc.z()), initiator, dc.owner(), done -> {}));
                }

                if (index >= displaced.size()) {
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
