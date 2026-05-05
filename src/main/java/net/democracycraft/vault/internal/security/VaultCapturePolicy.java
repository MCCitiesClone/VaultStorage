package net.democracycraft.vault.internal.security;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Policy evaluator for vault capture (placement) actions.
 * <p>
 * Enforces rules based on Bolt ownership and WorldGuard region participation.
 */
public final class VaultCapturePolicy {

    private VaultCapturePolicy() {}

    public record Decision(boolean allowed, boolean hasOverride, boolean actorParticipantAny,
                           boolean containerOwnerParticipantAny, boolean actorIsContainerOwner,
                           boolean disallowedOwnerSelf, boolean baseAllowed, UUID containerOwner, Reason reason,
                           List<RegionStatus> regionStatuses) implements Dto, Serializable {
        public enum Reason {
            /**
             * Allowed (no blocking condition).
             */
            ALLOWED,
            /**
             * Actor participates in an overlapping region and also is container owner (self-disallow).
             */
            OWNER_SELF_IN_REGION,
            /**
             * Container owner (original owner) is participant of at least one overlapping region, blocking participant capture.
             */
            CONTAINER_OWNER_IN_OVERLAP,
            /**
             * Actor not involved (no membership/ownership) and container is not owned by actor.
             */
            NOT_INVOLVED_NOT_OWNER,
            /**
             * Container has no Bolt owner and actor lacks override permission.
             */
            UNPROTECTED_NO_OVERRIDE,
            /**
             * The block is outside of any region.
             */
            NOT_IN_REGION,
            /**
             * The block involves a Hangable entity (ItemFrame, Painting) and the actor lacks admin permission.
             */
            ENTITIES_REQUIRE_ADMIN
        }
    }

    public record RegionStatus(String regionId, boolean actorParticipant, boolean containerOwnerParticipates) implements Dto, Serializable {
        /*
         * Per-region participation snapshot.
         * actorParticipant: true if the actor is owner or member of this specific region.
         * containerOwnerParticipates: true if the container's Bolt owner is owner or member of this specific region.
         */
    }

    /**
     * Evaluate vaulting permission for the given actor and block.
     * Rules recap: requires Bolt owner; requires region participation unless override is present.
     *
     */
    public static Decision evaluate(Player actor, Block block) {
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        UUID originalOwner = null;
        if (bolt != null) {
            try {
                originalOwner = bolt.getOwner(block);
            } catch (Throwable t) {
                VaultStoragePlugin.getInstance().getLogger().warning("[VaultCapturePolicy] Error obtaining Bolt owner for block in "
                        + formatBlock(block) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }
        }

        // Log actor UUID for debugging Bedrock player issues
        UUID actorUUID = actor.getUniqueId();
        if (actorUUID.toString().matches("0{8}-0{4}-0{4}-0{4}-0{12}")) {
            VaultStoragePlugin.getInstance().getLogger().warning("[VaultCapturePolicy] Invalid actor UUID for player " + actor.getName() + ": " + actorUUID);
        }

        // Check for Hanging (ItemFrames, Paintings) attached to or intersecting the block
        // Non-admins are not allowed to unlock/vault the block itself while hangings remain.
        boolean hangableRestricted = computeHangableRestricted(actor, block, originalOwner);

        return evaluateBlockPolicy(actor, block, originalOwner, hangableRestricted);
    }

    /**
     * Same Bolt and WorldGuard rules as {@link #evaluate(Player, Block)}, but does not apply the
     * "hangings near block" restriction (used when vaulting the hanging entity itself).
     * @param actor player performing capture
     * @param supportingBlock block the hanging is attached to
     */
    public static Decision evaluateHangingCapture(Player actor, Hanging hanging, Block supportingBlock) {
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        UUID originalOwner = null;
        if (bolt != null) {
            try {
                originalOwner = bolt.getOwner(hanging);
                if (originalOwner == null) {
                    originalOwner = bolt.getOwner(supportingBlock);
                }
            } catch (Throwable t) {
                VaultStoragePlugin.getInstance().getLogger().warning("[VaultCapturePolicy] Error obtaining Bolt owner for hanging entity in "
                        + formatBlock(supportingBlock) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }
        }

        UUID actorUUID = actor.getUniqueId();
        if (actorUUID.toString().matches("0{8}-0{4}-0{4}-0{4}-0{12}")) {
            VaultStoragePlugin.getInstance().getLogger().warning("[VaultCapturePolicy] Invalid actor UUID for player " + actor.getName() + ": " + actorUUID);
        }

        return evaluateBlockPolicy(actor, supportingBlock, originalOwner, false);
    }

    private static boolean computeHangableRestricted(Player actor, Block block, UUID originalOwner) {
        if (originalOwner == null || VaultPermission.ADMIN.has(actor)) {
            return false;
        }
        try {
            var nearby = block.getWorld().getNearbyEntities(block.getBoundingBox().expand(0.2));
            for (Entity entity : nearby) {
                if (entity instanceof Hanging) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Decision evaluateBlockPolicy(Player actor, Block block, UUID originalOwner, boolean hangableRestricted) {
        boolean hasOverride = VaultPermission.ACTION_PLACE_OVERRIDE.has(actor);
        boolean actorIsContainerOwner = originalOwner != null && originalOwner.equals(actor.getUniqueId());
        WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();

        boolean actorParticipantAny = false;
        boolean containerOwnerParticipantAny = false;
        boolean inAnyRegion = false;

        List<RegionStatus> perRegionDebug = List.of();

        if (wgs != null) {
            List<VaultRegion> regions = List.of();
            try {
                regions = wgs.getRegionsAt(block);
            } catch (Throwable t) {
                VaultStoragePlugin.getInstance().getLogger().warning("[VaultCapturePolicy] Error obtaining WorldGuard regions for block in "
                        + formatBlock(block) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            }

            inAnyRegion = !regions.isEmpty();
            UUID actorUuid = actor.getUniqueId();
            List<RegionStatus> debugList = new ArrayList<>();

            for (var region : regions) {
                boolean actorParticipant = false;
                boolean containerOwnerParticipates = false;
                try {
                    actorParticipant = region.isPartOfRegion(actorUuid);
                    if (originalOwner != null) {
                        containerOwnerParticipates = region.isPartOfRegion(originalOwner);
                    }
                } catch (Throwable t) {
                    VaultStoragePlugin.getInstance().getLogger().warning("[VaultCapturePolicy] Error evaluating participation for region "
                            + region.id() + " para bloque en " + formatBlock(block) + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
                }

                BoundingBox box;
                try {
                    box = region.boundingBox();
                } catch (Throwable t) {
                    VaultStoragePlugin.getInstance().getLogger().warning("[VaultCapturePolicy] Error obtaining boundingBox for region "
                            + region.id() + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
                    debugList.add(new RegionStatus(region.id(), actorParticipant, containerOwnerParticipates));
                    if (actorParticipant) actorParticipantAny = true;
                    if (containerOwnerParticipates) containerOwnerParticipantAny = true;
                    continue;
                }

                final BoundingBox currentBox = box;
                boolean isParent = false;
                boolean isChild = false;
                try {
                    isParent = regions.stream().anyMatch(r -> r != region && safeContains(currentBox, r));
                    isChild = regions.stream().anyMatch(r -> r != region && safeContains(r.boundingBox(), region));
                } catch (Throwable t) {
                    VaultStoragePlugin.getInstance().getLogger().warning("[VaultCapturePolicy] Error evaluating parent/child for region "
                            + region.id() + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
                }

                boolean actorCountedHere = actorParticipant && !isParent;
                if (actorCountedHere) {
                    actorParticipantAny = true;
                }

                if (containerOwnerParticipates) {
                    containerOwnerParticipantAny = true;
                }

                debugList.add(new RegionStatus(region.id(), actorParticipant, containerOwnerParticipates));
            }

            perRegionDebug = Collections.unmodifiableList(debugList);
        }

        boolean disallowedOwnerSelf = actorParticipantAny && actorIsContainerOwner;
        boolean baseAllowed;

        if (actorParticipantAny) {
            // Actor is a participant of at least one relevant (non-parent) region
            // Can vault if: block has owner, actor is not the owner, and owner is not participant of any relevant region
            baseAllowed = (originalOwner != null) && !actorIsContainerOwner && !containerOwnerParticipantAny;
        } else {
            // Actor is not a participant of any relevant region
            // Can only vault their own blocks
            baseAllowed = actorIsContainerOwner;
        }


        boolean allowed = (originalOwner != null)
                && !disallowedOwnerSelf
                && !hangableRestricted
                && ((inAnyRegion && baseAllowed) || hasOverride);

        Decision.Reason reason;
        if (allowed) {
            reason = Decision.Reason.ALLOWED;
        } else if (hangableRestricted) {
            reason = Decision.Reason.ENTITIES_REQUIRE_ADMIN;
        } else if (!inAnyRegion) {
            reason = Decision.Reason.NOT_IN_REGION;
        } else if (disallowedOwnerSelf) {
            reason = Decision.Reason.OWNER_SELF_IN_REGION;
        } else if (actorParticipantAny && containerOwnerParticipantAny) {
            reason = Decision.Reason.CONTAINER_OWNER_IN_OVERLAP;
        } else if (originalOwner == null) {
            reason = Decision.Reason.UNPROTECTED_NO_OVERRIDE;
        } else {
            reason = Decision.Reason.NOT_INVOLVED_NOT_OWNER;
        }

        return new Decision(
                allowed,
                hasOverride,
                actorParticipantAny,
                containerOwnerParticipantAny,
                actorIsContainerOwner,
                disallowedOwnerSelf,
                baseAllowed,
                originalOwner,
                reason,
                perRegionDebug
        );
    }

    /**
     * Evaluate vaulting permission for a specific entity (e.g. from Scan Menu).
     */
    public static Decision evaluate(Player actor, Entity entity) {
        boolean hasOverride = VaultPermission.ACTION_PLACE_OVERRIDE.has(actor);
        boolean isAdmin = VaultPermission.ADMIN.has(actor);

        if (entity instanceof Hanging && !isAdmin) {
             return new Decision(false, hasOverride, false, false, false, false, false, null, Decision.Reason.ENTITIES_REQUIRE_ADMIN, List.of());
        }

        if(!isAdmin) {
            return new Decision(false, hasOverride, false, false, false, false, false, null, Decision.Reason.ENTITIES_REQUIRE_ADMIN, List.of());
        }


        return new Decision(true, hasOverride, false, false, false, false, true, null, Decision.Reason.ALLOWED, List.of());
    }

    private static String formatBlock(Block block) {
        if (block == null) {
            return "<null>";
        } else {
            block.getWorld();
        }
        return block.getWorld().getName() + "@" + block.getX() + "," + block.getY() + "," + block.getZ() + "[" + block.getType() + "]";
    }

    private static boolean safeContains(BoundingBox outer, VaultRegion innerRegion) {
        try {
            BoundingBox inner = innerRegion.boundingBox();
            return outer.contains(inner);
        } catch (Throwable t) {
            VaultStoragePlugin.getInstance().getLogger().warning("[VaultCapturePolicy] Error in safeContains for region "
                    + innerRegion.id() + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            return false;
        }
    }
}

