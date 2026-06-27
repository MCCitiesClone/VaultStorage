package net.democracycraft.vault.internal.listener;

import io.github.md5sha256.realty.api.event.RealtyRegionEvent;
import io.github.md5sha256.realty.api.event.RegionBoughtEvent;
import io.github.md5sha256.realty.api.event.RegionRentedEvent;
import io.github.md5sha256.realty.api.event.TenantSetEvent;
import io.github.md5sha256.realty.api.event.TitleTransferredEvent;
import net.democracycraft.vault.internal.service.AutoVaultService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Listens for Realty owner/tenant changes and triggers an {@link AutoVaultService} sweep for the new
 * occupant. References Realty API types, so it is only instantiated when the Realty plugin is present.
 */
public final class RealtyOccupantChangeListener implements Listener {

    private final AutoVaultService autoVaultService;

    public RealtyOccupantChangeListener(@NotNull AutoVaultService autoVaultService) {
        this.autoVaultService = autoVaultService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTitleTransferred(TitleTransferredEvent event) {
        submit(event, event.getNewTitleHolderId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegionBought(RegionBoughtEvent event) {
        submit(event, event.getBuyerId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegionRented(RegionRentedEvent event) {
        submit(event, event.getTenantId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTenantSet(TenantSetEvent event) {
        // A null new tenant means the tenancy was cleared (out of scope); skip.
        submit(event, event.getNewTenantId());
    }

    private void submit(@NotNull RealtyRegionEvent event, @Nullable UUID newOccupant) {
        if (newOccupant == null) {
            return;
        }
        autoVaultService.submit(event.getWorldId(), event.getRegionId(), newOccupant);
    }
}
