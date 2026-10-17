package pl.furycraft.furyminions.listener;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.item.MinionItem;
import pl.furycraft.furyminions.manager.ArmorStandManager;
import pl.furycraft.furyminions.model.Minion;
import pl.furycraft.furyminions.model.MinionType;

import java.util.UUID;

/**
 * Handles minion placement when a player right-clicks a block while holding a minion item.
 * <p>
 * Validates island ownership (via SuperiorSkyblock2), enforces minimum inter-minion spacing,
 * checks the player's minion limit, and then spawns the ArmorStand and hologram asynchronously.
 */
public class MinionPlaceListener implements Listener {

    private final FuryMinions plugin;
    private final MinionItem minionItem;
    private final ArmorStandManager armorStandManager;
    private final IslandListener islandListener;

    public MinionPlaceListener(FuryMinions plugin) {
        this.plugin = plugin;
        this.minionItem = new MinionItem(plugin);
        this.armorStandManager = plugin.getArmorStandManager();
        this.islandListener = new IslandListener(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || !minionItem.isMinionItem(item)) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();
        Location clickedBlock = event.getClickedBlock().getLocation();
        Location minionLocation = clickedBlock.add(0.5, 1, 0.5);

        if (!islandListener.canPlaceMinion(player, minionLocation)) {
            return;
        }

        String typeId = minionItem.getMinionType(item);
        MinionType minionType = plugin.getConfigManager().getMinionType(typeId);

        if (minionType == null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cNieprawidłowy typ miniona!"));
            return;
        }

        for (Minion activeMinion : armorStandManager.getActiveMinionsList()) {
            if (activeMinion.getLocation().getWorld().equals(minionLocation.getWorld())) {
                double distance = activeMinion.getLocation().distance(minionLocation);
                int minDistance = activeMinion.getType().getPlatformRadius() + minionType.getPlatformRadius() + 1;

                if (distance < minDistance) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cJesteś za blisko innego miniona! Spróbuj trochę dalej."));
                    return;
                }
            }
        }

        plugin.getDatabaseManager().loadMinionsByOwnerAsync(player.getUniqueId()).thenAccept(minionsData -> {
            int currentMinions = minionsData.size();
            int limit = getMinionLimit(player);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (currentMinions >= limit) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cOsiągnąłeś limit minionów! Twój limit: " + limit));
                    return;
                }

                UUID minionUuid = UUID.randomUUID();
                UUID ownerUuid = player.getUniqueId();

                int level = minionItem.getMinionLevel(item);

                Minion minion = new Minion(
                    minionUuid,
                    ownerUuid,
                    minionType,
                    level,
                    minionLocation,
                    "NORTH"
                );

                armorStandManager.spawnMinionArmorStand(minion).thenAccept(armorStand -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (armorStand != null) {
                            armorStandManager.addActiveMinion(minion);
                            plugin.getHologramManager().createHologram(minion);

                            plugin.getDatabaseManager().saveMinionAsync(minion.toMinionData()).thenRun(() -> {
                                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &aPostawiłeś miniona: " + minionType.getDisplayName()));

                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    Location particleLoc = minionLocation.clone().add(0, 1, 0);
                                    World world = minionLocation.getWorld();
                                    world.playSound(minionLocation, Sound.BLOCK_PISTON_EXTEND, 1.0f, 1.2f);
                                    world.playSound(minionLocation, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 0.5f);
                                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, particleLoc, 40, 0.25, 0.25, 0.25, 0.05);
                                    world.spawnParticle(Particle.HAPPY_VILLAGER, particleLoc, 20, 0.25, 0.25, 0.25, 0.1);

                                    ItemStack mainHandItem = player.getInventory().getItemInMainHand();
                                    if (mainHandItem != null && minionItem.isMinionItem(mainHandItem)) {
                                        mainHandItem.setAmount(mainHandItem.getAmount() - 1);
                                        if (mainHandItem.getAmount() <= 0) {
                                            player.getInventory().setItemInMainHand(null);
                                        }
                                    }
                                });
                            }).exceptionally(ex -> {
                                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cBłąd podczas zapisywania miniona do bazy danych!"));
                                return null;
                            });
                        } else {
                            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cBłąd podczas spawnowania miniona!"));
                        }
                    });
                }).exceptionally(ex -> {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cBłąd podczas spawnowania miniona: " + ex.getMessage()));
                    return null;
                });
            });
        });
    }

    /**
     * Resolves the maximum minion count allowed for the given player based on their permission node.
     * Permission hierarchy (highest wins): {@code furyminions.limit.admin} → {@code mvp} → {@code vip} → default.
     *
     * @param player the player to check
     * @return the effective minion placement limit
     */
    private int getMinionLimit(Player player) {
        if (player.hasPermission("furyminions.limit.admin")) {
            return plugin.getConfig().getInt("minion-limits.admin", 50);
        } else if (player.hasPermission("furyminions.limit.mvp")) {
            return plugin.getConfig().getInt("minion-limits.mvp", 15);
        } else if (player.hasPermission("furyminions.limit.vip")) {
            return plugin.getConfig().getInt("minion-limits.vip", 10);
        } else {
            return plugin.getConfig().getInt("minion-limits.default", 5);
        }
    }
}
