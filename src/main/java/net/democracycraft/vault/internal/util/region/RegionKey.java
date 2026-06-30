package net.democracycraft.vault.internal.util.region;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.UUID;

/**
 * Identity of a region within a world, used to dedupe and cancel pending auto-vault sweeps.
 * The region id is normalized to lower-case to match WorldGuard's id convention.
 */
public record RegionKey(@NotNull UUID worldId, @NotNull String regionId) {

    public RegionKey {
        regionId = regionId.toLowerCase(Locale.ROOT);
    }
}
