package net.democracycraft.vault.internal.util.scan;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.ScanResult;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.internal.util.config.ConfigPaths;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Player-free, batched scan of a region's Bolt-protected blocks. Collects every {@link BlockProtection}
 * in the bounding box whose owner is non-null and not in {@code allowed}, delivering the result to
 * {@code callback} on the main thread (or {@code null} if the world unloaded mid-scan). Batched per tick
 * via {@code scan.batch-size} with a time budget and on-demand chunk loading.
 */
public class OfflineRegionScanner {

    private final UUID worldId;
    private final BoundingBox boundingBox;
    private final Set<UUID> allowed;
    private final Consumer<List<ScanResult>> callback;
    private BukkitTask task;

    public OfflineRegionScanner(UUID worldId, BoundingBox boundingBox, Set<UUID> allowed, Consumer<List<ScanResult>> callback) {
        this.worldId = worldId;
        this.boundingBox = boundingBox;
        this.allowed = allowed;
        this.callback = callback;
    }

    public void start() {
        World world = Bukkit.getWorld(worldId);
        BoltService boltService = VaultStoragePlugin.getInstance().getBoltService();
        if (world == null || boltService == null) {
            callback.accept(null);
            return;
        }

        Collection<Protection> protections = boltService.getProtections(boundingBox, world);
        if (protections == null || protections.isEmpty()) {
            callback.accept(new ArrayList<>());
            return;
        }

        List<BlockProtection> blockProtections = new ArrayList<>();
        for (Protection protection : protections) {
            if (protection instanceof BlockProtection bp) {
                blockProtections.add(bp);
            }
        }

        this.task = new ScanTask(blockProtections).runTaskTimer(VaultStoragePlugin.getInstance(), 1L, 1L);
    }

    public void cancel() {
        if (this.task != null && !this.task.isCancelled()) {
            this.task.cancel();
        }
    }

    private class ScanTask extends BukkitRunnable {
        private final List<BlockProtection> protections;
        private final List<ScanResult> results = new ArrayList<>();
        private int index = 0;
        private final int batchSize;

        private ScanTask(List<BlockProtection> protections) {
            this.protections = protections;
            this.batchSize = VaultStoragePlugin.getInstance().getConfig().getInt(ConfigPaths.SCAN_BATCH_SIZE.getPath(), 50);
        }

        @Override
        public void run() {
            World world = Bukkit.getWorld(worldId);
            if (world == null) {
                this.cancel();
                callback.accept(null);
                return;
            }

            long startTime = System.currentTimeMillis();
            int processed = 0;

            while (index < protections.size() && processed < batchSize) {
                if (System.currentTimeMillis() - startTime > 2) {
                    break;
                }

                BlockProtection bp = protections.get(index);
                index++;
                processed++;

                int x = bp.getX();
                int y = bp.getY();
                int z = bp.getZ();

                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;

                UUID owner = bp.getOwner();
                if (owner == null || allowed.contains(owner)) continue;

                // Load chunk if needed
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    world.getChunkAt(x >> 4, z >> 4);
                }

                Block block = world.getBlockAt(x, y, z);
                results.add(new ScanResult(block, owner, block.getType()));
            }

            if (index >= protections.size()) {
                this.cancel();
                callback.accept(results);
            }
        }
    }
}
