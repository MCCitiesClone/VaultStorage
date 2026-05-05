package net.democracycraft.vault.api.service;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface BoltService extends Service {

    UUID getOwner(Block block);
    UUID getOwner(Entity entity);

    boolean isOwner(UUID playerUUID, Block block);
    boolean isOwner(UUID playerUUID, Entity entity);

    List<Block> getProtectedBlocks(UUID playerUUID, BoundingBox boundingBox, World world);

    /**
     * Returns all protected blocks within the given bounding box in a world, regardless of owner.
     */
    List<Block> getProtectedBlocksIn(BoundingBox boundingBox, World world);

    /**
     * Removes the Bolt protection for the given block if one exists.
     * Implementations should be safe to call on the main thread.
     * @param block target block
     */
    void removeProtection(Block block);


    List<EntityProtection> getEntityProtections(@NotNull World world, @NotNull BoundingBox boundingBox);


    Map<EntityProtection, Entity> getProtectedEntities(@NotNull World world, @NotNull BoundingBox boundingBox);

    /**
     * Creates a Bolt protection for the given block owned by the specified player.
     * Implementations should be safe to call on the main thread.
     * @param block target block
     * @param ownerUuid owner of the protection
     */
    void createProtection(Block block, UUID ownerUuid);

    void removeProtection(Protection protection);

    Collection<Protection> getProtections(BoundingBox boundingBox, World world);

}
