package net.democracycraft.vault.api.service;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Service for integrating with the optional ChestShop plugin.
 * Resolves to a no-op implementation when ChestShop is not installed.
 */
public interface ChestShopService extends Service {

    /** @return true if ChestShop is installed and this integration is active. */
    boolean isAvailable();

    /** @return true if the block is a valid ChestShop sign. */
    boolean isShopSign(@Nullable Block block);

    /** @return the shop sign blocks attached to the container, or an empty list. */
    @NotNull List<Block> findShopSigns(@Nullable Block containerBlock);

    /**
     * Notifies ChestShop and removes the shop sign block. Must run on the main thread.
     * @return true if the block was a shop sign and was removed
     */
    boolean removeShopSign(@Nullable Player actor, @Nullable Block signBlock);
}
