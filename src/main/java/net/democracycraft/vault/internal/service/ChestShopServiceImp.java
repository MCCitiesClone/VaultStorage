package net.democracycraft.vault.internal.service;

import com.Acrobot.ChestShop.Events.ShopDestroyedEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import com.Acrobot.ChestShop.Utils.uBlock;
import net.democracycraft.vault.api.service.ChestShopService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChestShopService} backed by the ChestShop API. Only instantiated when ChestShop is installed,
 * so that the {@code com.Acrobot} imports are never linked otherwise.
 */
public final class ChestShopServiceImp implements ChestShopService {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isShopSign(@Nullable Block block) {
        return block != null && ChestShopSign.isValid(block);
    }

    @Override
    public @NotNull List<Block> findShopSigns(@Nullable Block containerBlock) {
        if (containerBlock == null) {
            return List.of();
        }
        List<Sign> signs = uBlock.findConnectedShopSigns(containerBlock);
        if (signs == null || signs.isEmpty()) {
            return List.of();
        }
        List<Block> blocks = new ArrayList<>(signs.size());
        for (Sign sign : signs) {
            if (sign != null) {
                blocks.add(sign.getBlock());
            }
        }
        return blocks;
    }

    @Override
    public boolean removeShopSign(@Nullable Player actor, @Nullable Block signBlock) {
        if (signBlock == null || !(signBlock.getState() instanceof Sign sign)) {
            return false;
        }
        if (!ChestShopSign.isValid(sign)) {
            return false;
        }
        Container container = uBlock.findConnectedContainer(signBlock);
        Bukkit.getPluginManager().callEvent(new ShopDestroyedEvent(actor, sign, container));
        signBlock.setType(Material.AIR);
        return true;
    }
}
