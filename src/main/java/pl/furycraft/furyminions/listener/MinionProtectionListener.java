package pl.furycraft.furyminions.listener;

import org.bukkit.ChatColor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.item.MinionItem;
import pl.furycraft.furyminions.manager.ArmorStandManager;
import pl.furycraft.furyminions.model.Minion;

import java.util.UUID;

/**
 * Protects minion ArmorStands from all forms of external interference:
 * damage, explosions, piston movement, portal teleportation, and equipment manipulation.
 * <p>
 * Right-clicking a minion's ArmorStand opens the owner's management GUI;
 * left-clicking by the owner (or an admin) picks the minion up and returns it as an item.
 */
public class MinionProtectionListener implements Listener {

    private final FuryMinions plugin;
    private final ArmorStandManager armorStandManager;
    private final MinionItem minionItem;

    public MinionProtectionListener(FuryMinions plugin) {
        this.plugin = plugin;
        this.armorStandManager = plugin.getArmorStandManager();
        this.minionItem = new MinionItem(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof ArmorStand && armorStandManager.isMinionArmorStand(entity)) {
            event.setCancelled(true);
        }
    }

    /**
     * Handles left-click attacks on minion ArmorStands.
     * The owner or an admin picks up the minion; all others receive a denial message.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        Entity damager = event.getDamager();

        if (!(entity instanceof ArmorStand) || !armorStandManager.isMinionArmorStand(entity)) {
            return;
        }

        event.setCancelled(true);

        if (damager instanceof Player player) {
            UUID minionUuid = armorStandManager.getMinionUuidFromArmorStand((ArmorStand) entity);
            if (minionUuid != null) {
                Minion minion = armorStandManager.getActiveMinion(minionUuid);
                if (minion != null) {
                    boolean isOwner = minion.getOwnerUuid().equals(player.getUniqueId());
                    boolean isAdmin = player.hasPermission("furyminions.admin");

                    if (isOwner || isAdmin) {
                        plugin.getHologramManager().removeHologram(minion.getMinionUuid());

                        ItemStack minionItemStack = minionItem.createMinionItem(minion.getType().getId());
                        player.getInventory().addItem(minionItemStack);

                        minion.removeArmorStand();
                        armorStandManager.removeActiveMinion(minion.getMinionUuid());

                        plugin.getDatabaseManager().deleteMinionAsync(minion.getMinionUuid()).thenRun(() -> {
                            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &aPodniosłeś miniona: " + minion.getType().getDisplayName()));
                        });
                    } else {
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cTo nie jest Twój minion!"));
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> {
            for (Entity entity : block.getWorld().getNearbyEntities(block.getLocation(), 2, 2, 2)) {
                if (entity instanceof ArmorStand && armorStandManager.isMinionArmorStand(entity)) {
                    return true;
                }
            }
            return false;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (armorStandManager.isMinionArmorStand(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.getEntity() instanceof ArmorStand && armorStandManager.isMinionArmorStand(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Handles right-clicks on minion ArmorStands to open the management GUI.
     * Off-hand clicks are ignored to prevent the GUI from opening twice per interaction.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Entity entity = event.getRightClicked();

        if (!(entity instanceof ArmorStand) || !armorStandManager.isMinionArmorStand(entity)) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();
        UUID minionUuid = armorStandManager.getMinionUuidFromArmorStand((ArmorStand) entity);

        if (minionUuid != null) {
            Minion minion = armorStandManager.getActiveMinion(minionUuid);
            if (minion != null) {
                if (minion.getOwnerUuid().equals(player.getUniqueId())) {
                    plugin.getGuiListener().openMinionGUI(player, minion);
                } else {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cTo nie jest Twój minion!"));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.isCancelled()) return;
        if (hasMinionInPistonPath(event.getBlocks(), event.getDirection(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.isCancelled()) return;
        if (hasMinionInPistonPath(event.getBlocks(), event.getDirection(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * Checks whether any minion ArmorStand would be displaced by piston movement.
     * Checks both the piston head's destination and the destination of every pushed/pulled block,
     * plus each block's current position to handle slime-block scenarios.
     */
    private boolean hasMinionInPistonPath(java.util.List<org.bukkit.block.Block> blocks, org.bukkit.block.BlockFace direction, org.bukkit.block.Block piston) {
        org.bukkit.Location pistonHeadLoc = piston.getLocation().add(direction.getModX(), direction.getModY(), direction.getModZ());
        for (Entity entity : pistonHeadLoc.getWorld().getNearbyEntities(pistonHeadLoc, 1.0, 1.0, 1.0)) {
            if (entity instanceof ArmorStand && armorStandManager.isMinionArmorStand(entity)) {
                return true;
            }
        }

        for (org.bukkit.block.Block block : blocks) {
            org.bukkit.Location targetLoc = block.getLocation().add(direction.getModX(), direction.getModY(), direction.getModZ());
            for (Entity entity : targetLoc.getWorld().getNearbyEntities(targetLoc, 1.0, 1.0, 1.0)) {
                if (entity instanceof ArmorStand && armorStandManager.isMinionArmorStand(entity)) {
                    return true;
                }
            }
            // Also check the block's current position to handle slime blocks that can carry minions
            for (Entity entity : block.getLocation().getWorld().getNearbyEntities(block.getLocation(), 1.0, 1.0, 1.0)) {
                if (entity instanceof ArmorStand && armorStandManager.isMinionArmorStand(entity)) {
                    return true;
                }
            }
        }
        return false;
    }
}