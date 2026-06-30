package net.democracycraft.vault.internal.util.scan;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.BoltService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory filter over a region's Bolt protections. Returns the locked blocks whose owner is non-null
 * and not in {@code allowed}, using only Bolt's stored coordinates/owner — no block reads or chunk loads,
 * so it never blocks the main thread. Callers load chunks asynchronously when vaulting the results.
 */
public final class OfflineRegionScanner {

    private OfflineRegionScanner() {}

    /** A locked container flagged for vaulting: its coordinates and Bolt owner. */
    public record DisplacedContainer(int x, int y, int z, UUID owner) {}

    /** Filters the region's Bolt protections to displaced containers, or {@code null} if services are unavailable. */
    public static List<DisplacedContainer> scan(UUID worldId, BoundingBox boundingBox, Set<UUID> allowed) {
        World world = Bukkit.getWorld(worldId);
        BoltService boltService = VaultStoragePlugin.getInstance().getBoltService();
        if (world == null || boltService == null) {
            return null;
        }

        Collection<Protection> protections = boltService.getProtections(boundingBox, world);
        List<DisplacedContainer> displaced = new ArrayList<>();
        if (protections != null) {
            for (Protection protection : protections) {
                if (!(protection instanceof BlockProtection bp)) continue;
                if (bp.getY() < world.getMinHeight() || bp.getY() >= world.getMaxHeight()) continue;
                UUID owner = bp.getOwner();
                if (owner == null || allowed.contains(owner)) continue;
                displaced.add(new DisplacedContainer(bp.getX(), bp.getY(), bp.getZ(), owner));
            }
        }
        return displaced;
    }
}
