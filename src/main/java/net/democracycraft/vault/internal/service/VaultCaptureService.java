package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.event.PlayerVaultEvent;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.ChestShopService;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import net.democracycraft.vault.internal.util.uuid.UniqueIdentifierResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.data.type.WallSign;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.security.VaultCapturePolicy;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.session.VaultSessionManager.Mode;
import net.democracycraft.vault.internal.util.hanging.HangingVaultSupport;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import net.democracycraft.vault.internal.util.config.DataFolder;
import org.jspecify.annotations.NonNull;

/**
 * Service responsible for capturing a block into a Vault model.
 * <p>Behavior expansion: non-container blocks can now be "vaulted". They behave identically to an empty container capture:
 * Bolt protection is removed, no vault is created, the block remains in place.</p>
 * Threading policy:
 * - Must run on the main server thread because it interacts with the world (block state and inventories).
 *
 */
public class VaultCaptureService {

    /**
     * Validates that a UUID is not null and not all-zeros.
     * If invalid, attempts to resolve using the player's name via UniqueIdentifierResolver.
     * Returns a CompletableFuture with the valid UUID or null if resolution fails.
     */
    private CompletableFuture<UUID> ensureValidOwnerUUID(UUID candidateUUID, Player actor) {
        // If already valid, return immediately
        if (UniqueIdentifierResolver.isValidUUID(candidateUUID)) {
            VaultStoragePlugin.getInstance().getLogger().info(
                "[VaultCaptureService] Owner UUID is valid for " + actor.getName() + ": " + candidateUUID
            );
            return CompletableFuture.completedFuture(candidateUUID);
        }

        // UUID is invalid (null or all-zeros), attempt to resolve by player name
        var plugin = VaultStoragePlugin.getInstance();
        var resolver = new UniqueIdentifierResolver(plugin.getMojangService(), plugin.getBedrockUniqueIdentifierRetriever());

        VaultStoragePlugin.getInstance().getLogger().warning(
            "[VaultCaptureService] INVALID owner UUID detected for actor " + actor.getName() +
            " (" + candidateUUID + "). Attempting to resolve by player name..."
        );

        return resolver.resolveUuid(actor.getName()).thenApply(resolvedUUID -> {
            if (UniqueIdentifierResolver.isValidUUID(resolvedUUID)) {
                VaultStoragePlugin.getInstance().getLogger().info(
                    "[VaultCaptureService]  Successfully resolved UUID for " + actor.getName() +
                    ": " + resolvedUUID + " (Bedrock player fallback)"
                );
                return resolvedUUID;
            } else {
                VaultStoragePlugin.getInstance().getLogger().warning(
                    "[VaultCaptureService] failed to resolve valid UUID for " + actor.getName() +
                    ". Vault capture aborted. (Invalid fallback UUID: " + resolvedUUID + ")"
                );
                return null;
            }
        });
    }

    /**
     * Determines if the given block is a container and if its effective inventory is empty.
     * For non-container blocks returns true (treated as empty capture behavior).
     * <p>For chests, uses the combined {@link Chest#getBlockInventory()} ensuring double chests are evaluated as a whole.</p>
     * <p>Empty definition: no non-null ItemStack whose type != AIR and amount > 0.</p>
     * @param block target block
     * @return true if empty (or not a container), false otherwise
     */
    public boolean isContainerEmpty(Block block) {
        if (!(block.getState() instanceof Container c)) {
            // Non-container: treat as empty so capture logic will produce no vault
            return true;
        }
        // Note: checking getBlockInventory() on a Double Chest returns ONLY the half belonging to this block,
        // which is exactly what we need for split-logic.
        Inventory inv = (c instanceof Chest chest) ? chest.getBlockInventory() : c.getInventory();
        for (ItemStack stack : inv.getContents()) {
            if (stack == null) continue;
            if (stack.getType() == Material.AIR) continue;
            if (stack.getAmount() <= 0) continue;
            return false; // Found meaningful content
        }
        return true;
    }

    /**
     * Captures the given container block's inventory and metadata into a new Vault.
     * Contract:
     * - The block must be a Container; otherwise throws IllegalArgumentException. (Non-container blocks never reach this path.)
     * - Clears the block's inventory and removes the block (sets to AIR).
     * - Returns a new {@link VaultImp} with items, material, location, timestamp, and block data string.
     * - Does NOT perform an emptiness check; callers should invoke {@link #isContainerEmpty(Block)} beforehand when conditional behavior is required.
     * <p>This method must be called on the main thread.</p>
     */
    public VaultImp captureFromBlock(Player actor, Block block) {
        return captureFromBlock(actor.getUniqueId(), block);
    }

    /** Player-free variant of {@link #captureFromBlock(Player, Block)}; the vault is owned by {@code vaultOwnerUuid}. */
    public VaultImp captureFromBlock(UUID vaultOwnerUuid, Block block) {
        if (!(block.getState() instanceof Container container)) {
            throw new IllegalArgumentException("Target block is not a container.");
        }
        Inventory captureInv = (container instanceof Chest chest) ? chest.getBlockInventory() : container.getInventory();
        List<ItemStack> stacks = Arrays.stream(captureInv.getContents())
                .filter(Objects::nonNull)
                .filter(is -> is.getType() != Material.AIR && is.getAmount() > 0)
                .collect(Collectors.toList());

        // Clear inventory and remove block
        captureInv.clear();
        Material material = block.getType();
        var location = block.getLocation();
        Instant when = Instant.now();
        String blockDataString = block.getBlockData().getAsString();
        block.setType(Material.AIR);

        UUID vaultId = UUID.randomUUID();
        return new VaultImp(vaultOwnerUuid, vaultId, stacks, material, location, when, blockDataString);
    }

    /**
     * Immutable outcome produced by {@link #captureWithDoubleChestSupport(Player, Block, UUID)}.
     * Contract:
     * <ul>
     * <li>If {@code empty} is true: no block removed, no vault created.</li>
     * <li>{@code protectionRemoved}: true if Bolt protection was stripped, false if action was canceled/warned.</li>
     * </ul>
     */
    public record CaptureOutcome(boolean empty, boolean protectionRemoved, VaultImp vault, UUID originalOwner, UUID finalOwner, List<Block> reProtectedHalves) {}

    /**
     * Captures a block into a vault while preserving protection rules.
     * <p>Behavior:</p>
     * <ul>
     * <li>Target Empty & Other Half Full: Warns user, does nothing.</li>
     * <li>Both Empty: Removes protection from BOTH halves.</li>
     * <li>Single Empty: Removes protection.</li>
     * <li>Target Full: Captures vault, re-protects other half.</li>
     * </ul>
     */
    public CaptureOutcome captureWithDoubleChestSupport(Player actor, @NonNull Block block, UUID originalOwner) {
        return captureWithDoubleChestSupport(block, originalOwner, actor.getUniqueId(), actor::sendMessage);
    }

    /**
     * Player-free variant of {@link #captureWithDoubleChestSupport(Player, Block, UUID)}. The vault owner is
     * {@code originalOwner} when non-null, else {@code finalOwnerFallback}; {@code warn} may be null to suppress notices.
     */
    public CaptureOutcome captureWithDoubleChestSupport(@NonNull Block block, UUID originalOwner,
                                                        @NonNull UUID finalOwnerFallback,
                                                        Consumer<Component> warn) {
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        UUID finalOwner = originalOwner != null ? originalOwner : finalOwnerFallback;

        // 1. Non-container path: treat as empty capture (remove protection)
        if (!(block.getState() instanceof Container container)) {
            if (bolt != null) {
                try { bolt.removeProtection(block); } catch (Throwable ignored) {}
            }
            return new CaptureOutcome(true, true, null, originalOwner, finalOwner, List.of());
        }

        List<Block> otherHalves = getHalves(block, container);
        boolean targetIsEmpty = isContainerEmpty(block);

        // Double Chest Logic: Check the state of the other half if it exists
        if (!otherHalves.isEmpty()) {
            Block otherHalf = otherHalves.get(0); // Standard double chests only have one other half
            boolean otherHalfIsEmpty = isContainerEmpty(otherHalf);

            // If the target half is empty but the other half contains items, we abort the operation
            // to prevent accidental unprotection of the full chest. The user is guided to vault the non-empty side.
            if (targetIsEmpty && !otherHalfIsEmpty) {
                if (warn != null) {
                    warn.accept(Component.text("[Vault Capture Service] You should vault the non-empty side first.").color(NamedTextColor.YELLOW));
                }
                // Return empty=true so no vault is made, but protectionRemoved=false so we don't spam "Protection Removed".
                return new CaptureOutcome(true, false, null, originalOwner, finalOwner, List.of());
            }

            // If both halves of the double chest are empty, we proceed to remove the protection
            // from both blocks to fully unprotect the empty container.
            if (targetIsEmpty) {
                if (bolt != null) {
                    try {
                        bolt.removeProtection(block); // bolt handles 1 protection for double chests
                    } catch (Throwable ignored) {}
                }
                return new CaptureOutcome(true, true, null, originalOwner, finalOwner, List.of());
            }
        }

        // If we are here, either it's a single chest, or the target is not empty.

        if (targetIsEmpty) {
            // Single chest that is empty -> Just remove protection
            if (bolt != null) {
                try { bolt.removeProtection(block); } catch (Throwable ignored) {}
            }
            return new CaptureOutcome(true, true, null, originalOwner, finalOwner, List.of());
        }

        // Always remove protection from clicked block before capturing
        if (bolt != null) {
            try { bolt.removeProtection(block); } catch (Throwable ignored) {}
        }

        // Re-protect other half(s) immediately if they exist
        List<Block> reProtected = new ArrayList<>();
        if (bolt != null && !otherHalves.isEmpty()) {
            for (Block half : otherHalves) {
                try {
                    bolt.createProtection(half, finalOwner);
                    reProtected.add(half);
                } catch (Throwable ignored) {}
            }
        }

        // VaultImp owner carries the capturing identity; persistence uses CaptureOutcome.finalOwner().
        VaultImp vault = captureFromBlock(finalOwnerFallback, block);

        return new CaptureOutcome(false, true, vault, originalOwner, finalOwner, List.copyOf(reProtected));
    }

    private static @NotNull List<Block> getHalves(Block block, Container container) {
        List<Block> otherHalves = new ArrayList<>();
        if (container instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest dc) {
                InventoryHolder leftHolder = dc.getLeftSide();
                InventoryHolder rightHolder = dc.getRightSide();
                Chest left = (leftHolder instanceof Chest l) ? l : null;
                Chest right = (rightHolder instanceof Chest r) ? r : null;
                if (left != null && right != null) {
                    Block leftBlock = left.getBlock();
                    Block rightBlock = right.getBlock();
                    if (block.equals(leftBlock)) {
                        otherHalves.add(rightBlock);
                    } else if (block.equals(rightBlock)) {
                        otherHalves.add(leftBlock);
                    }
                }
            }
        }
        return otherHalves;
    }

    /**
     * YAML-backed, configurable texts for interactive capture session and action bar.
     * Provide values already formatted with MiniMessage or plain text; placeholders are resolved by the caller.
     */
    public static class SessionTexts implements Dto {
        public String captureCancelled = "Capture cancelled.";
        public String notAllowed = "<red>Not allowed: region/block rules.</red>";
        public String signNotAllowed = "<red>Sign not allowed: region/block rules.</red>";
        public String containerNotAllowed = "<red>Container not allowed: region/block rules.</red>";
        public String signUnlocked = "Sign protection removed.";
        public String chestShopSignRemoved = "ChestShop sign removed.";
        public String emptyCaptureSkipped = "Protection removed.";
        public String capturedOk = "Vault captured.";
        public String noBoltOwner = "<yellow>No Bolt owner found; you will be set as the vault owner.</yellow>";
        public String actionBarIdle = "<yellow>Capture mode</yellow> - Right-click a block, item frame, or painting. <gray>Left-click to cancel.</gray>";
        public String actionBarContainer = "<gray>Owner:</gray> <white>%owner%</white> <gray>| Vaultable:</gray> <white>%vaultable%</white><gray>%reasonSegment%</gray>%admin%";
        public String actionBarReasonSegmentTemplate = " | Reason: %reason%";
        public String actionBarReasonAllowedBlank = "";
        public String reasonOwnerSelfInRegion = "you own this block but are a member of '%regions%'";
        public String reasonContainerOwnerInOverlap = "%owner% owns this block and is a member of '%regions%'";
        public String reasonNotInvolvedNotOwner = "you don't own this block and aren't a member of its region";
        public String reasonUnprotectedNoOverride = "this block has no owner";
        public String reasonNotInRegion = "block is not inside any protected region";
        public String reasonHangableRequiresAdmin = "vaulting this entity requires an administrator";
        public String reasonFallback = "vault not permitted";
        public String actionBarUnprotectedOwner = "unprotected";
        public String actionBarVaultableYes = "yes";
        public String actionBarVaultableNo = "no";
        /**
         * One-time notification shown when starting a capture session and the actor has admin override permission.
         * Use MiniMessage formatting if desired; leave empty to disable.
         */
        public String adminModeEnabled = "<gold>Admin mode enabled.</gold>";
        /**
         * Optional tag appended to the action bar while admin override is active.
         * Injected via the %admin% placeholder in actionBarContainer. Leave empty to hide.
         */
        public String actionBarAdminModeTag = "<gray> |</gray> <gold> ADMIN</gold>";
    }

    private static final String HEADER = String.join("\n",
            "VaultCaptureService session texts configuration.",
            "All strings accept MiniMessage or plain text.",
            "Placeholders:",
            "- %owner% -> Bolt owner name or UUID when unavailable",
            "- %vaultable% -> yes/no from actionBarVaultableYes/No",
            "- %reason% -> human-readable reason (in actionBarReasonSegmentTemplate)",
            "- %reasonCode% -> policy code",
            "- %regions% -> comma-separated region ids",
            "- %admin% -> admin override tag (actionBarAdminModeTag) when override is active" );

    private static final AutoYML<SessionTexts> YML = AutoYML.create(SessionTexts.class, "VaultCaptureSession", DataFolder.MENUS, HEADER);
    private static SessionTexts cfg() { return YML.loadOrCreate(SessionTexts::new); }
    /** Ensures the YAML for session texts exists. */
    public static void ensureSessionConfig() { YML.loadOrCreate(SessionTexts::new); }

    /** Exposes current configured texts. */
    public SessionTexts sessionTexts() { return cfg(); }

    /**
     * Starts an interactive capture session for the given player, with default texts.
     * The session remains active until the player cancels with a left-click.
     */
    public void startCaptureSession(@NotNull Player actor) { startCaptureSession(actor, cfg()); }

    /**
     * Starts an interactive capture session for the given player using provided texts.
     * - Installs a per-player dynamic listener listening for left/right clicks.
     * - Shows an action bar with live vaultability.
     * - Applies VaultCapturePolicy on right-click; performs capture & DB persistence.
     * - Session stays active for multiple captures until canceled with left-click.
     */
    public void startCaptureSession(@NotNull Player actor, @NotNull SessionTexts texts) {
        if (!VaultPermission.ACTION_CAPTURE.has(actor)) {
            actor.sendMessage("You don't have permission to capture blocks.");
            return;
        }
        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId());
        session.switchTo(Mode.CAPTURE);

        // Notify admin mode if override is active
        if (VaultPermission.ADMIN.has(actor)) {
            String msg = texts.adminModeEnabled;
            if (msg != null && !msg.isBlank()) {
                actor.sendMessage(MiniMessageUtil.parseOrPlain(msg));
            }
        }

        final BukkitTask[] actionbarTask = new BukkitTask[1];
        final boolean[] busy = new boolean[]{false};
        // Cooldown: block cancellation for a short period after capture to prevent phantom events from other plugins
        final long[] cancelCooldownUntil = new long[]{0L};

        session.getDynamicListener().setListener(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
            public void onInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;

                // Ignore off-hand events to prevent duplicate processing
                if (event.getHand() == EquipmentSlot.OFF_HAND) return;

                // Verify session is still in CAPTURE mode (may have been changed externally)
                if (session.getMode() != Mode.CAPTURE) return;

                Action action = event.getAction();

                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    // Prevent cancellation during cooldown (protects against phantom events after capture)
                    long now = System.currentTimeMillis();
                    if (now < cancelCooldownUntil[0]) {
                        event.setCancelled(true);
                        return; // Ignore this left-click, still in cooldown
                    }
                    event.setCancelled(true);
                    session.getDynamicListener().stop();
                    if (actionbarTask[0] != null) actionbarTask[0].cancel();
                    session.clearActionBarTask();
                    session.switchTo(Mode.NONE);
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.captureCancelled));
                    return;
                }

                if (action != Action.RIGHT_CLICK_BLOCK) return;
                event.setCancelled(true);
                Block block = event.getClickedBlock();
                if (block == null) return;
                if (busy[0]) return;
                busy[0] = true;

                // Set cooldown to prevent phantom left-click events from canceling capture (~2 ticks / 100ms)
                cancelCooldownUntil[0] = System.currentTimeMillis() + 100L;

                Block captureBlock = block;
                boolean clickedSign = isSignBlock(block);
                ChestShopService chestShop = VaultStoragePlugin.getInstance().getChestShopService();
                boolean clickedChestShopSign = clickedSign && chestShop.isShopSign(block);

                // Sequential sign->container policy flow:
                // 1) Evaluate sign (non-container) policy and optionally unlock sign.
                // 2) Evaluate container policy on attached container (if present).
                if (clickedChestShopSign) {
                    // Shop sign is handled through its container; it is removed once the container unlocks.
                    Block attachedContainer = resolveAttachedContainerBlock(block);
                    if (attachedContainer == null) {
                        busy[0] = false;
                        return;
                    }
                    captureBlock = attachedContainer;
                } else if (clickedSign) {
                    VaultCapturePolicy.Decision signDecision = VaultCapturePolicy.evaluate(actor, block);
                    UUID signOwner = signDecision.containerOwner();
                    if (signDecision.allowed()) {
                        CaptureOutcome signOutcome = captureWithDoubleChestSupport(actor, block, signOwner);
                        if (signOutcome.protectionRemoved()) {
                            actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.signUnlocked));
                        }
                    } else {
                        actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.signNotAllowed));
                    }

                    Block attachedContainer = resolveAttachedContainerBlock(block);
                    if (attachedContainer == null) {
                        busy[0] = false;
                        return;
                    }
                    captureBlock = attachedContainer;
                }

                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, captureBlock);
                UUID originalOwner = decision.containerOwner();
                if (!decision.allowed()) {
                    // A shop sign on an empty, unprotected container can still be removed on region rights alone.
                    if (chestShop.isAvailable() && isContainerEmpty(captureBlock)) {
                        List<Block> shopSigns = chestShop.findShopSigns(captureBlock);
                        if (!shopSigns.isEmpty()
                                && VaultCapturePolicy.evaluateChestShopSignRemoval(actor, captureBlock).allowed()) {
                            removeAttachedChestShopSigns(actor, shopSigns);
                            actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.chestShopSignRemoved));
                            busy[0] = false;
                            return;
                        }
                    }
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(clickedSign ? texts.containerNotAllowed : texts.notAllowed));
                    busy[0] = false;
                    return;
                }

                CaptureOutcome outcome = captureWithDoubleChestSupport(actor, captureBlock, originalOwner);
                if (outcome.empty()) {
                    // Only send "Protection removed" if we actually removed protection.
                    // If protectionRemoved is false, it means we sent a warning (e.g., "Vault the other side first").
                    if (outcome.protectionRemoved()) {
                        // Drop any shop sign left on the now-unlocked container.
                        if (chestShop.isAvailable()) {
                            List<Block> shopSigns = chestShop.findShopSigns(captureBlock);
                            if (!shopSigns.isEmpty()) {
                                removeAttachedChestShopSigns(actor, shopSigns);
                                actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.chestShopSignRemoved));
                            }
                        }
                        actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.emptyCaptureSkipped));
                    }
                    busy[0] = false;
                    return;
                }

                if (originalOwner == null && decision.hasOverride()) {
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.noBoltOwner));
                }

                final Block finalCaptureBlock = captureBlock;
                VaultImp vault = outcome.vault();
                var plugin = VaultStoragePlugin.getInstance();
                UUID finalOwner = outcome.finalOwner();

                ensureValidOwnerUUID(finalOwner, actor).thenAccept(validatedOwner -> {
                    if (validatedOwner == null) {

                        new BukkitRunnable(){

                            @Override
                            public void run() {
                                actor.sendMessage(MiniMessageUtil.parseOrPlain(
                                        "<red>Error: Could not determine vault owner. Capture aborted.</red>"
                                ));
                                plugin.getLogger().warning(
                                        "[VaultCaptureService] Vault capture aborted for " + actor.getName() +
                                                " at " + finalCaptureBlock.getWorld().getName() + ":" + finalCaptureBlock.getX() + "," + finalCaptureBlock.getY() + "," + finalCaptureBlock.getZ() +
                                                " - could not validate owner UUID"
                                );
                                busy[0] = false;
                            }
                        }.runTask(plugin);
                        return;
                    }

                    new BukkitRunnable() {
                        @Override public void run() {
                            var vaultService = plugin.getVaultService();
                            UUID worldId = finalCaptureBlock.getWorld().getUID();
                            UUID newId;
                            {
                                var created = vaultService.createVault(worldId, actor.getUniqueId(), finalCaptureBlock.getX(), finalCaptureBlock.getY(), finalCaptureBlock.getZ(), validatedOwner,
                                        vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                        vault.blockDataString());
                                newId = created.uuid;
                                plugin.getLogger().info(
                                    "[VaultCaptureService] Vault created successfully: ID=" + newId + " Owner=" + validatedOwner + " for player " + actor.getName()
                                );
                            }
                            List<VaultItemEntity> batch = toItemBatch(newId, vault.contents());
                            if (!batch.isEmpty()) {
                                vaultService.putItems(newId, batch);
                            }
                            new BukkitRunnable() {
                                @Override public void run() {
                                    var dto = new VaultDtoImp(newId, validatedOwner, List.of(),
                                            vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                            null, System.currentTimeMillis());
                                    VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId()).setLastVaultDto(dto);
                                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.capturedOk));

                                    new PlayerVaultEvent(actor, vault).callEvent();

                                    busy[0] = false;
                                }
                            }.runTask(plugin);
                        }
                    }.runTaskAsynchronously(plugin);
                });
            }

            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
            public void onInteractEntity(PlayerInteractEntityEvent event) {
                if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;
                if (event.getHand() == EquipmentSlot.OFF_HAND) return;
                if (session.getMode() != Mode.CAPTURE) return;
                if (busy[0]) return;

                Entity clicked = event.getRightClicked();
                if (!(clicked instanceof ItemFrame) && !(clicked instanceof Painting)) return;

                event.setCancelled(true);

                Hanging hang = (Hanging) clicked;
                Block supporting = HangingVaultSupport.resolveSupportingBlock(hang);

                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluateHangingCapture(actor, hang, supporting);
                if (!decision.allowed()) {
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.notAllowed));
                    return;
                }

                // Empty item frame: unlock supporting block only (like empty non-container); do not vault or remove entity.
                if (hang instanceof ItemFrame frame && HangingVaultSupport.isItemFrameDisplayEmpty(frame)) {
                    BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
                    if (bolt != null) {
                        try {
                            bolt.removeProtection(supporting);
                        } catch (Throwable ignored) {}
                    }
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.emptyCaptureSkipped));
                    return;
                }

                List<ItemStack> stacks = HangingVaultSupport.itemStacksFrom(hang);
                if (stacks.isEmpty()) {
                    return;
                }

                busy[0] = true;
                cancelCooldownUntil[0] = System.currentTimeMillis() + 100L;
                ensureValidOwnerUUID(decision.containerOwner(), actor).thenAccept(validatedOwner -> {
                    if (validatedOwner == null) {
                        new BukkitRunnable() {
                            @Override public void run() {
                                actor.sendMessage(MiniMessageUtil.parseOrPlain(
                                        "<red>Error: Could not determine vault owner. Capture aborted.</red>"
                                ));
                                busy[0] = false;
                            }
                        }.runTask(VaultStoragePlugin.getInstance());
                        return;
                    }

                    new BukkitRunnable() {
                        @Override public void run() {
                            var plugin = VaultStoragePlugin.getInstance();
                            UUID vaultUuid = persistHangingStacks(stacks, validatedOwner, actor.getUniqueId(), supporting);

                            new BukkitRunnable() {
                                @Override public void run() {
                                    if (hang.isValid()) {
                                        hang.remove();
                                    }
                                    Material vm = stacks.get(0).getType();
                                    VaultImp vaultEvt = new VaultImp(
                                            validatedOwner,
                                            vaultUuid,
                                            stacks,
                                            vm,
                                            supporting.getLocation(),
                                            Instant.now(),
                                            supporting.getBlockData().getAsString()
                                    );
                                    VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId())
                                            .setLastVaultDto(new VaultDtoImp(vaultUuid, validatedOwner, List.of(),
                                                    vm.name(), null, System.currentTimeMillis()));
                                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.capturedOk));
                                    new PlayerVaultEvent(actor, vaultEvt).callEvent();
                                    busy[0] = false;
                                }
                            }.runTask(plugin);
                        }
                    }.runTaskAsynchronously(VaultStoragePlugin.getInstance());
                });
            }
        });

        actionbarTask[0] = new BukkitRunnable() {
            @Override public void run() {
                if (!actor.isOnline()) {
                    cancel();
                    return;
                }
                Block target = actor.getTargetBlockExact(6);
                if (target == null) {
                    actor.sendActionBar(MiniMessageUtil.parseOrPlain(texts.actionBarIdle));
                    return;
                }
                ChestShopService chestShop = VaultStoragePlugin.getInstance().getChestShopService();
                if (chestShop.isAvailable() && chestShop.isShopSign(target)) {
                    Block container = resolveAttachedContainerBlock(target);
                    if (container != null) target = container;
                }
                var boltSvc = VaultStoragePlugin.getInstance().getBoltService();
                UUID owner = boltSvc != null ? boltSvc.getOwner(target) : null;
                String ownerName = ownerDisplayNameAsync(owner);

                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, target);
                String vaultable = decision.allowed() ? texts.actionBarVaultableYes : texts.actionBarVaultableNo;
                String regionsList = decision.regionStatuses().stream().map(VaultCapturePolicy.RegionStatus::regionId).sorted().reduce((a,b)->a+", "+b).orElse("");
                String reasonSegment = getReasonSegment(decision, ownerName, regionsList, texts);
                String adminTag = decision.hasOverride() ? texts.actionBarAdminModeTag : "";
                Map<String,String> ph = Map.of(
                        "%owner%", ownerName,
                        "%vaultable%", vaultable,
                        "%reasonSegment%", reasonSegment,
                        "%admin%", adminTag
                );
                actor.sendActionBar(MiniMessageUtil.parseOrPlain(texts.actionBarContainer, ph));
            }
        }.runTaskTimer(VaultStoragePlugin.getInstance(), 0L, 5L);
        session.setActionBarTask(actionbarTask[0]);

        session.getDynamicListener().start();
    }

    /** Simple in-memory cache for UUID->username for actionbar display. */
    private static final ConcurrentHashMap<UUID, String> NAME_CACHE = new ConcurrentHashMap<>();

    /**
     * Returns a display name for the UUID without blocking the main thread.
     * Uses cache if present; otherwise returns a short UUID and schedules an async resolve via MojangService.
     */
    private static String ownerDisplayNameAsync(UUID uuid) {
        if (uuid == null) return cfg().actionBarUnprotectedOwner;
        String cached = NAME_CACHE.get(uuid);
        if (cached != null) return cached;
        String fallback = uuid.toString().substring(0, 8);
        // Async resolve and cache using CompletableFuture API
        var mojangService = VaultStoragePlugin.getInstance().getMojangService();
        if (mojangService != null) {
            mojangService.getName(uuid).thenAccept(name -> {
                if (name != null && !name.isBlank()) NAME_CACHE.put(uuid, name);
            });
        }
        return fallback;
    }

    private static String getReasonSegment(VaultCapturePolicy.@NonNull Decision decision, String ownerName, String regionsList, SessionTexts cfg) {
        String reasonText;
        if (decision.allowed()) {
            reasonText = cfg.actionBarReasonAllowedBlank;
        } else {
            switch (decision.reason()) {
                case OWNER_SELF_IN_REGION -> reasonText = cfg.reasonOwnerSelfInRegion;
                case CONTAINER_OWNER_IN_OVERLAP -> reasonText = cfg.reasonContainerOwnerInOverlap;
                case NOT_INVOLVED_NOT_OWNER -> reasonText = cfg.reasonNotInvolvedNotOwner;
                case UNPROTECTED_NO_OVERRIDE -> reasonText = cfg.reasonUnprotectedNoOverride;
                case NOT_IN_REGION -> reasonText = cfg.reasonNotInRegion;
                case ENTITIES_REQUIRE_ADMIN -> reasonText = cfg.reasonHangableRequiresAdmin;
                default -> reasonText = cfg.reasonFallback;
            }
            Map<String,String> reasonPh = Map.of(
                    "%owner%", ownerName,
                    "%regions%", regionsList,
                    "%reasonCode%", decision.reason().name()
            );
            String resolved = reasonText;
            for (var e : reasonPh.entrySet()) resolved = resolved.replace(e.getKey(), e.getValue());
            reasonText = resolved;
        }
        return decision.allowed() ? cfg.actionBarReasonAllowedBlank : cfg.actionBarReasonSegmentTemplate.replace("%reason%", reasonText);
    }

    private static final BlockFace[] ADJACENT_FACES = new BlockFace[]{
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private static boolean isSignBlock(@NotNull Block block) {
        return block.getState() instanceof Sign;
    }

    /** Unlocks and removes the given shop signs, notifying ChestShop. {@code actor} may be null for automated captures. Must run on the main thread. */
    private void removeAttachedChestShopSigns(@Nullable Player actor, @NotNull List<Block> shopSigns) {
        ChestShopService chestShop = VaultStoragePlugin.getInstance().getChestShopService();
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        for (Block signBlock : shopSigns) {
            if (bolt != null) {
                try { bolt.removeProtection(signBlock); } catch (Throwable ignored) {}
            }
            chestShop.removeShopSign(actor, signBlock);
        }
    }

    private static Block resolveAttachedContainerBlock(@NotNull Block signBlock) {
        if (!isSignBlock(signBlock)) return null;

        if (signBlock.getBlockData() instanceof WallSign wallSign) {
            Block attached = signBlock.getRelative(wallSign.getFacing().getOppositeFace());
            if (attached.getState() instanceof Container) {
                return attached;
            }
        }

        // Conservative fallback for non-wall signs: check immediate neighbors.
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = signBlock.getRelative(face);
            if (neighbor.getState() instanceof Container) {
                return neighbor;
            }
        }
        return null;
    }

    /**
     * Direct one-shot capture with messages and DB persistence.
     * Applies policy, captures/removes the block, persists items when applicable, and sends user feedback.
     * The callback is invoked on the main thread with true when a vault was persisted (non-empty container), false otherwise.
     */
    public void captureDirectAsync(@NotNull Player actor, @NotNull Block block, @NotNull SessionTexts texts, @NotNull Consumer<Boolean> onDoneMain) {
        VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, block);
        UUID originalOwner = decision.containerOwner();
        if (!decision.allowed()) {
            actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.notAllowed));
            onDoneMain.accept(false);
            return;
        }
        CaptureOutcome outcome = captureWithDoubleChestSupport(actor, block, originalOwner);
        if (outcome.empty()) {
            // Update to respect the protectionRemoved flag
            if (outcome.protectionRemoved()) {
                actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.emptyCaptureSkipped));
            }
            onDoneMain.accept(false);
            return;
        }
        if (originalOwner == null && decision.hasOverride()) {
            actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.noBoltOwner));
        }
        VaultImp vault = outcome.vault();
        UUID finalOwner = outcome.finalOwner();
        var plugin = VaultStoragePlugin.getInstance();

        // CRITICAL: Validate finalOwner UUID before persisting
        ensureValidOwnerUUID(finalOwner, actor).thenAccept(validatedOwner -> {
            if (validatedOwner == null) {
                actor.sendMessage(MiniMessageUtil.parseOrPlain(
                    "<red>Error: Could not determine vault owner. Capture aborted.</red>"
                ));
                plugin.getLogger().warning(
                    "[VaultCaptureService] Direct vault capture aborted for " + actor.getName() +
                    " at " + block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ() +
                    " - could not validate owner UUID"
                );
                onDoneMain.accept(false);
                return;
            }

            new BukkitRunnable(){
                @Override public void run() {
                    VaultService vaultService = plugin.getVaultService();
                    UUID worldId = block.getWorld().getUID();
                    UUID newId;
                    {
                        var created = vaultService.createVault(worldId, actor.getUniqueId(), block.getX(), block.getY(), block.getZ(), validatedOwner,
                                vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                vault.blockDataString());
                        newId = created.uuid;
                        plugin.getLogger().info(
                            "[VaultCaptureService] Direct vault created: ID=" + newId + " Owner=" + validatedOwner + " for player " + actor.getName()
                        );
                    }
                    List<VaultItemEntity> batch = toItemBatch(newId, vault.contents());
                    if (!batch.isEmpty()) vaultService.putItems(newId, batch);

                    new BukkitRunnable(){
                        @Override public void run() {
                            VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId())
                                    .setLastVaultDto(new VaultDtoImp(newId, validatedOwner, List.of(),
                                            vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                            null, System.currentTimeMillis()));
                            actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.capturedOk));
                            new PlayerVaultEvent(actor, vault).callEvent();
                            onDoneMain.accept(true);
                        }
                    }.runTask(plugin);
                }
            }.runTaskAsynchronously(plugin);
        });
    }

    /**
     * Offline-safe capture: no live {@link Player} and no policy evaluation; the caller decides what to vault.
     * The vault is owned by {@code boltOwner} (falls back to {@code initiatorUuid} when null) and created by
     * {@code initiatorUuid}. Must be called on the main thread; persistence runs async (fire-and-forget).
     */
    public void captureOfflineAsync(@NotNull Block block,
                                    @NotNull UUID initiatorUuid,
                                    UUID boltOwner,
                                    @NotNull Runnable onVaulted) {
        var plugin = VaultStoragePlugin.getInstance();

        CaptureOutcome outcome = captureWithDoubleChestSupport(block, boltOwner, initiatorUuid, null);
        if (outcome.empty()) {
            return;
        }

        // Drop any ChestShop sign left on the now-captured container.
        ChestShopService chestShop = plugin.getChestShopService();
        if (chestShop != null && chestShop.isAvailable()) {
            List<Block> shopSigns = chestShop.findShopSigns(block);
            if (!shopSigns.isEmpty()) {
                removeAttachedChestShopSigns(null, shopSigns);
            }
        }

        UUID finalOwner = outcome.finalOwner();
        if (!UniqueIdentifierResolver.isValidUUID(finalOwner)) {
            plugin.getLogger().warning(
                    "[VaultCaptureService] Offline capture aborted at " + block.getWorld().getName() + ":"
                            + block.getX() + "," + block.getY() + "," + block.getZ()
                            + " - invalid vault owner UUID: " + finalOwner);
            return;
        }

        VaultImp vault = outcome.vault();
        new BukkitRunnable() {
            @Override public void run() {
                VaultService vaultService = plugin.getVaultService();
                UUID worldId = block.getWorld().getUID();
                var created = vaultService.createVault(worldId, initiatorUuid, block.getX(), block.getY(), block.getZ(), finalOwner,
                        vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                        vault.blockDataString());
                UUID newId = created.uuid;
                plugin.getLogger().info(
                        "[VaultCaptureService] Offline vault created: ID=" + newId + " owner=" + finalOwner + " initiator=" + initiatorUuid);

                List<VaultItemEntity> batch = toItemBatch(newId, vault.contents());
                if (!batch.isEmpty()) vaultService.putItems(newId, batch);
                // No PlayerVaultEvent: this is an automated capture. Signal the occupant notifier that a vault was made.
                onVaulted.run();
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Offline-safe capture of a Bolt-locked hanging entity (item frame or painting) with no live
     * {@link Player} and no policy evaluation. Its contents (the frame/painting plus any displayed item)
     * are stored in a vault owned by {@code boltOwner}, merged into an existing vault when one has room,
     * and the entity is removed (which also disposes its Bolt protection).
     * <p>Must be called on the main thread with the entity loaded; persistence runs async.</p>
     */
    public void captureHangingOfflineAsync(@NotNull Hanging hang, @NotNull UUID boltOwner, @NotNull UUID initiatorUuid,
                                           @NotNull Runnable onVaulted) {
        List<ItemStack> stacks = HangingVaultSupport.itemStacksFrom(hang);
        if (stacks.isEmpty()) {
            return;
        }

        var plugin = VaultStoragePlugin.getInstance();
        Block supporting = HangingVaultSupport.resolveSupportingBlock(hang);
        new BukkitRunnable() {
            @Override public void run() {
                persistHangingStacks(stacks, boltOwner, initiatorUuid, supporting);
                onVaulted.run();
                new BukkitRunnable() {
                    @Override public void run() {
                        if (hang.isValid()) hang.remove();
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Stores hanging stacks into an existing vault owned by {@code boltOwner} that has room, or a new vault
     * anchored at {@code supporting} and created by {@code creatorUuid}. Runs on the async persistence thread;
     * returns the target vault id.
     */
    private UUID persistHangingStacks(@NotNull List<ItemStack> stacks, @NotNull UUID boltOwner, @NotNull UUID creatorUuid, @NotNull Block supporting) {
        var plugin = VaultStoragePlugin.getInstance();
        VaultService vaultService = plugin.getVaultService();
        var target = HangingVaultSupport.findFirstVaultWithSpace(vaultService, boltOwner, stacks.size());
        UUID vaultUuid;
        int startSlot;
        if (target.isPresent()) {
            vaultUuid = target.get().vaultUuid();
            startSlot = target.get().startSlot();
        } else {
            // Persisted material must be a block; placement uses Block#setType (item names are invalid).
            var created = vaultService.createVault(supporting.getWorld().getUID(), creatorUuid,
                    supporting.getX(), supporting.getY(), supporting.getZ(), boltOwner, Material.CHEST.name(), null);
            vaultUuid = created.uuid;
            startSlot = 0;
            plugin.getLogger().info("[VaultCaptureService] Hanging capture created vault ID=" + vaultUuid + " owner=" + boltOwner);
        }
        vaultService.putItems(vaultUuid, toItemBatch(vaultUuid, stacks, startSlot));
        return vaultUuid;
    }

    /** Serializes a vault's contents into persistable item rows starting at slot 0, skipping empty slots. */
    private static List<VaultItemEntity> toItemBatch(UUID vaultId, List<ItemStack> items) {
        return toItemBatch(vaultId, items, 0);
    }

    /** Serializes items into persistable rows assigned consecutive slots from {@code startSlot}, skipping empty slots. */
    private static List<VaultItemEntity> toItemBatch(UUID vaultId, List<ItemStack> items, int startSlot) {
        List<VaultItemEntity> batch = new ArrayList<>(items.size());
        int slot = startSlot;
        for (ItemStack itemStack : items) {
            if (itemStack == null) continue;
            VaultItemEntity vie = new VaultItemEntity();
            vie.vaultUuid = vaultId;
            vie.slot = slot++;
            vie.amount = itemStack.getAmount();
            vie.item = ItemSerialization.toBytes(itemStack);
            batch.add(vie);
        }
        return batch;
    }
}