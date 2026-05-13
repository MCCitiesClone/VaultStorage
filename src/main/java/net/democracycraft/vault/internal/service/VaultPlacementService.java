package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.internal.database.entity.VaultEntity;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Service responsible for placing a Vault back into the world.
 * Threading policy:
 * - World modifications MUST run on the main server thread.
 */
public class VaultPlacementService {

    /**
     * Immutable result for placement operations.
     */
    public record Result(boolean success, String message) {}

    /**
     * Places the vault identified by its database id at a custom target location (relative placement),
     * reconstructing state from DB. World modifications happen on main thread; DB on async.
     * Deletes the vault record after success.
     * @param vaultUuid vault id
     * @param targetLoc location to place (must be in a loaded world)
     * @param callback result consumer (main thread), may be null
     */
    public void placeFromDatabaseRelativeAsync(UUID vaultUuid, Location targetLoc, Consumer<Result> callback) {
        var plugin = VaultStoragePlugin.getInstance();
        new BukkitRunnable() {
            @Override public void run() {
                VaultService vaultService = plugin.getVaultService();
                var opt = vaultService.get(vaultUuid);
                if (opt.isEmpty()) {
                    new BukkitRunnable(){
                        @Override public void run()
                        {
                            if (callback!=null) callback.accept(new Result(false, "Vault not found."));
                        }
                    }.runTask(plugin);
                    return;
                }
                VaultEntity vaultEntity = opt.get();
                World world = targetLoc.getWorld();
                if (world == null) {
                    new BukkitRunnable(){
                        @Override public void run()
                        {
                            if (callback!=null) callback.accept(new Result(false, "Target world not available."));
                        }
                    }.runTask(plugin);
                    return;
                }
                Material mat = null;
                if (vaultEntity.material != null && !vaultEntity.material.isBlank()) {
                    try { mat = Material.valueOf(vaultEntity.material); } catch (IllegalArgumentException ignored) {}
                }
                if (mat == null || !mat.isBlock()) {
                    // Safe default; also handles item-only material names (e.g. legacy vault rows).
                    mat = Material.CHEST;
                }
                UUID ownerUuid = vaultService.getOwner(vaultUuid);
                List<VaultItemEntity> rows = vaultService.listItems(vaultUuid);
                List<ItemStack> contents = new ArrayList<>();
                int maxSlot = -1;
                for (VaultItemEntity row : rows) maxSlot = Math.max(maxSlot, row.slot);
                int size = Math.max(0, maxSlot + 1);
                for (int i=0;i<size;i++) contents.add(null);
                for (VaultItemEntity row : rows) {
                    ItemStack it = ItemSerialization.fromBytes(row.item);
                    if (row.slot >=0 && row.slot < contents.size()) contents.set(row.slot, it);
                }
                // Build ephemeral Vault for placement (location overridden)
                Material finalMat = mat;
                String blockDataString = vaultEntity.blockData;
                List<ItemStack> finalContents = contents.stream().map(it -> it == null ? null : it.clone()).toList();
                new BukkitRunnable(){
                    @Override public void run() {
                        Result result = placeAt(finalMat, finalContents, targetLoc, blockDataString);
                        if (!result.success()) {
                            if (callback!=null) callback.accept(result);
                            return;
                        }
                        // Recreate Bolt protection with original owner if present
                        BoltService bolt = plugin.getBoltService();
                        if (bolt != null && ownerUuid != null) {
                            Block placed = targetLoc.getBlock();
                            try { bolt.createProtection(placed, ownerUuid); } catch (Throwable ignored) {}
                        }
                        // Delete vault record then callback on main
                        new BukkitRunnable(){
                            @Override public void run(){
                                try {
                                    vaultService.delete(vaultUuid);
                                } catch(Throwable ignored){}
                                new BukkitRunnable(){
                                    @Override public void run() {
                                        if (callback!=null) {

                                            callback.accept(result);
                                        }
                                    }
                                }.runTask(plugin);
                            }
                        }.runTaskAsynchronously(plugin);
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Core placement logic at arbitrary location with given material, contents and blockData.
     * If the block is a chest, it resets its facing/type so it doesn't merge into a double chest.
     */
    private Result placeAt(Material mat, List<ItemStack> contents, Location loc, String blockDataString) {
        if (mat == null) return new Result(false, "Material missing.");
        if (loc == null || loc.getWorld() == null) return new Result(false, "Invalid location.");

        World world = loc.getWorld();
        Chunk chunk = world.getChunkAt(loc);
        if (!chunk.isLoaded()) chunk.load();

        Block target = world.getBlockAt(loc);
        if (target.getType() != Material.AIR) {
            return new Result(false, "Target not empty.");
        }

        target.setType(mat, false);

        // --- Apply and sanitize BlockData ---
        if (blockDataString != null && !blockDataString.isBlank()) {
            try {
                BlockData data = Bukkit.createBlockData(blockDataString);

                if (data instanceof Chest chestData) {
                    // Force it to be a single chest, so it doesn't merge
                    chestData.setType(Chest.Type.SINGLE);
                }

                target.setBlockData(data, false);
            } catch (IllegalArgumentException ignored) {}
        }

        // --- Fill container contents ---
        if (target.getState() instanceof Container container) {
            Inventory inv = container.getInventory();
            inv.clear();
            int size = inv.getSize();
            int i = 0;

            for (ItemStack it : contents) {
                if (it == null) continue;
                if (i >= size) {
                    world.dropItemNaturally(loc, it.clone());
                    continue;
                }
                inv.setItem(i++, it.clone());
            }

            return new Result(true, "Vault placed.");
        }

        return new Result(true, "Block placed.");
    }

}
