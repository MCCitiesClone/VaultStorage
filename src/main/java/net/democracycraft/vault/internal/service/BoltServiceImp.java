package net.democracycraft.vault.internal.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.BoltService;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.popcraft.bolt.BoltAPI;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.*;

/**
 * Service implementation that integrates with Bolt to query and manage block protections.
 * This implementation delegates double chest handling to higher-level services.
 */
public class BoltServiceImp implements BoltService {

    private final BoltAPI api;
    private final VaultStoragePlugin plugin;

    /**
     * Constructs a new BoltService implementation that integrates with the Bolt plugin.
     *
     * @param plugin the VaultStorage plugin instance (not null)
     * @throws IllegalStateException if the BoltAPI is not found or not registered
     */
    public BoltServiceImp(@NotNull VaultStoragePlugin plugin) {
        this.plugin = plugin;
        BoltAPI loaded = this.plugin.getServer().getServicesManager().load(BoltAPI.class);
        if (loaded == null) {
            throw new IllegalStateException("BoltAPI service not found. Ensure the Bolt plugin is installed and registered.");
        }
        this.api = loaded;
    }

    /**
     * Returns the underlying BoltAPI instance.
     *
     * @return the BoltAPI instance (never null)
     */
    public @NotNull BoltAPI getApi() {
        return api;
    }

    /**
     * Gets the owner of the given block's protection, if one exists.
     *
     * @param block the block to check (not null)
     * @return the owner's UUID, or null if the block is not protected
     */
    @Override
    public @Nullable UUID getOwner(@NotNull Block block) {
        Objects.requireNonNull(block, "block");
        Protection protection = api.findProtection(block);
        return protection != null ? protection.getOwner() : null;
    }

    /**
     * Gets the owner of the given entity's protection, if one exists.
     *
     * @param entity the entity to check (not null)
     * @return the owner's UUID, or null if the block is not protected
     */
    @Override
    public @Nullable UUID getOwner(@NotNull Entity entity) {
        Objects.requireNonNull(entity, "entity");
        Protection protection = api.findProtection(entity);
        return protection != null ? protection.getOwner() : null;
    }

    /**
     * Checks whether the given player is the owner of the block's protection.
     *
     * @param playerUUID the player's UUID (not null)
     * @param block      the block to check (not null)
     * @return true if the player owns the block's protection; false otherwise
     */
    @Override
    public boolean isOwner(@NotNull UUID playerUUID, @NotNull Block block) {
        Objects.requireNonNull(playerUUID, "playerUUID");
        Objects.requireNonNull(block, "block");
        Protection protection = api.findProtection(block);
        UUID owner = protection != null ? protection.getOwner() : null;
        return playerUUID.equals(owner);
    }

    /**
     * Checks whether the given player is the owner of the entity's protection.
     *
     * @param playerUUID the player's UUID (not null)
     * @param entity     the entity to check (not null)
     * @return true if the player owns the entity's protection; false otherwise
     */
    @Override
    public boolean isOwner(@NotNull UUID playerUUID, @NotNull Entity entity) {
        Objects.requireNonNull(playerUUID, "playerUUID");
        Objects.requireNonNull(entity, "entity");
        Protection protection = api.findProtection(entity);
        UUID owner = protection != null ? protection.getOwner() : null;
        return playerUUID.equals(owner);
    }

    /**
     * Returns all blocks protected by the specified player within the given bounding box and world.
     *
     * @param playerUUID  the owner's UUID (not null)
     * @param boundingBox the search area (not null)
     * @param world       the world (not null)
     * @return list of protected blocks (never null)
     */
    @Override
    public @NotNull List<Block> getProtectedBlocks(@NotNull UUID playerUUID, @NotNull BoundingBox boundingBox, @NotNull World world) {
        Objects.requireNonNull(playerUUID, "playerUUID");
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(world, "world");

        Collection<Protection> protections = api.findProtections(world, boundingBox);
        if (protections == null || protections.isEmpty()) {
            return List.of();
        }

        List<Block> protectedBlocks = new ArrayList<>();
        for (Protection protection : protections) {
            if (protection instanceof BlockProtection blockProtection && playerUUID.equals(protection.getOwner())) {
                int x = blockProtection.getX();
                int y = blockProtection.getY();
                int z = blockProtection.getZ();

                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;

                Chunk chunk = world.getChunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                if (!chunk.isLoaded()) chunk.load();

                Block b = chunk.getBlock(x & 15, y, z & 15);
                protectedBlocks.add(b);
            }
        }

        return protectedBlocks;
    }

    /**
     * Checks if any entities within the given bounding box and world are protected.
     *
     * @param world       the world (not null)
     * @param boundingBox the search area (not null)
     * @return list of protected entities (never null)
     */

    @Override
    public List<EntityProtection> getEntityProtections(@NotNull World world, @NotNull BoundingBox boundingBox) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(boundingBox, "boundingBox");

        Collection<Protection> protections = api.findProtections(world, boundingBox);

        return protections.stream()
                 .filter(protection -> protection instanceof EntityProtection)
                 .map(protection -> (EntityProtection) protection)
                 .toList();
    }

    @Override
    public Map<EntityProtection, Entity> getProtectedEntities(@NotNull World world, @NotNull BoundingBox boundingBox) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(boundingBox, "boundingBox");

        Collection<Protection> protections = api.findProtections(world, boundingBox);
        Map<EntityProtection, Entity> protectedEntities = new HashMap<>();

        for (Protection protection : protections) {
            if (protection instanceof EntityProtection entityProtection) {


                UUID entityUUID = entityProtection.getId();

                Entity entity = world.getEntity(entityUUID);
                if (entity != null) {
                    protectedEntities.put(entityProtection, entity);
                }
            }
        }

        return protectedEntities;
    }


    /**
     * Returns all protected blocks within the given bounding box and world, regardless of owner.
     *
     * @param boundingBox the search area (not null)
     * @param world       the world (not null)
     * @return list of protected blocks (never null)
     */
    @Override
    public @NotNull List<Block> getProtectedBlocksIn(@NotNull BoundingBox boundingBox, @NotNull World world) {
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(world, "world");

        Collection<Protection> protections = api.findProtections(world, boundingBox);
        if (protections == null || protections.isEmpty()) {
            return List.of();
        }

        List<Block> protectedBlocks = new ArrayList<>();
        for (Protection protection : protections) {
            if (protection instanceof BlockProtection bp) {
                int x = bp.getX();
                int y = bp.getY();
                int z = bp.getZ();

                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;

                Chunk chunk = world.getChunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                if (!chunk.isLoaded()) chunk.load();

                Block b = chunk.getBlock(x & 15, y, z & 15);
                protectedBlocks.add(b);
            }
        }

        return protectedBlocks;
    }

    /**
     * Removes a protection for the given block using BoltAPI.
     *
     * @param block the block whose protection should be removed (not null)
     */
    @Override
    public void removeProtection(@NotNull Block block) {
        Objects.requireNonNull(block, "block");
        Protection protection = api.findProtection(block);
        if (protection != null) {
            api.removeProtection(protection);
        }
    }

    /**
     * Creates a protection for the given block owned by the specified player.
     *
     * @param block     the block to protect (not null)
     * @param ownerUuid the UUID of the protection owner (not null)
     */
    @Override
    public void createProtection(@NotNull Block block, @NotNull UUID ownerUuid) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        BlockProtection protection = api.createProtection(block, ownerUuid, "private");
        api.saveProtection(protection);
    }

    @Override
    public void removeProtection(Protection protection) {
        api.removeProtection(protection);
    }

    @Override
    public Collection<Protection> getProtections(BoundingBox boundingBox, World world) {
        return api.findProtections(world, boundingBox);
    }
}
