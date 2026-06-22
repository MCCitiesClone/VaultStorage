package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.api.service.ChestShopService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** No-op {@link ChestShopService} used when ChestShop is not installed. */
public final class NoOpChestShopService implements ChestShopService {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean isShopSign(@Nullable Block block) {
        return false;
    }

    @Override
    public @NotNull List<Block> findShopSigns(@Nullable Block containerBlock) {
        return List.of();
    }

    @Override
    public boolean removeShopSign(@Nullable Player actor, @Nullable Block signBlock) {
        return false;
    }
}
