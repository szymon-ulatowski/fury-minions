package pl.furycraft.furyminions.listener;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.events.IslandDisbandEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.Minion;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Integrates with SuperiorSkyblock2 to enforce island-based placement permissions
 * and to clean up all minions when an island is disbanded.
 * <p>
 * When SuperiorSkyblock2 is absent, all placement checks pass unconditionally.
 */
public class IslandListener implements Listener {

    private final FuryMinions plugin;

    public IslandListener(FuryMinions plugin) {
        this.plugin = plugin;
    }

    /**
     * Deletes all database records and despawns all active minions owned by the disbanded island's owner.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onIslandDisband(IslandDisbandEvent event) {
        Island island = event.getIsland();
        UUID ownerUuid = island.getOwner().getUniqueId();

        plugin.getLogger().info("Wyspa gracza " + ownerUuid + " została usunięta. Usuwam minionów...");

        plugin.getDatabaseManager().deleteMinionsByOwnerAsync(ownerUuid).thenRun(() -> {
            List<Minion> minions = new java.util.ArrayList<>(plugin.getArmorStandManager().getActiveMinionsList());

            for (Minion minion : minions) {
                if (minion.getOwnerUuid().equals(ownerUuid)) {
                    plugin.getHologramManager().removeHologram(minion.getMinionUuid());
                    minion.removeArmorStand();
                    plugin.getArmorStandManager().removeActiveMinion(minion.getMinionUuid());
                }
            }

            plugin.getLogger().info("Usunięto wszystkich minionów gracza: " + ownerUuid);
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Błąd podczas usuwania minionów po usunięciu wyspy: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Synchronously checks whether the given player is allowed to place a minion at the given location.
     * Fails if SuperiorSkyblock2 is enabled and the location is outside the player's island membership.
     *
     * @param player   the player attempting to place
     * @param location the intended placement location
     * @return {@code true} if placement is permitted
     */
    public boolean canPlaceMinion(Player player, Location location) {
        if (!Bukkit.getPluginManager().isPluginEnabled("SuperiorSkyblock2")) {
            return true;
        }

        Island island = SuperiorSkyblockAPI.getIslandAt(location);

        if (island == null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cNie możesz postawić miniona poza terenem wyspy!"));
            return false;
        }

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);
        if (superiorPlayer == null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cWystąpił błąd podczas weryfikacji uprawnień."));
            return false;
        }

        if (!island.isMember(superiorPlayer)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cNie możesz postawić miniona na cudzej wyspie!"));
            return false;
        }

        return true;
    }

    /**
     * Asynchronously checks whether the player identified by {@code playerUuid} is a member
     * of the island at the given location. Used in async placement flows.
     *
     * @param playerUuid the UUID of the player to check
     * @param location   the target location
     * @return a future resolving to {@code true} if placement is permitted
     */
    public CompletableFuture<Boolean> canPlaceMinionAsync(UUID playerUuid, Location location) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Bukkit.getPluginManager().isPluginEnabled("SuperiorSkyblock2")) {
                return true;
            }

            Island island = SuperiorSkyblockAPI.getIslandAt(location);

            if (island == null) {
                return false;
            }

            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUuid);
            if (superiorPlayer == null) {
                return false;
            }

            return island.isMember(superiorPlayer);
        });
    }
}
