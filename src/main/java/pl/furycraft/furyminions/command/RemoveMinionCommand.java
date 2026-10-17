package pl.furycraft.furyminions.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.manager.ArmorStandManager;
import pl.furycraft.furyminions.model.Minion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles the {@code /removeminion [radius]} admin command.
 * Removes all active minions within the specified radius of the executing player,
 * despawning their ArmorStands and holograms and deleting their database records asynchronously.
 * Requires the {@code furyminions.admin} permission.
 */
public class RemoveMinionCommand implements CommandExecutor {

    private final FuryMinions plugin;
    private final ArmorStandManager armorStandManager;

    public RemoveMinionCommand(FuryMinions plugin) {
        this.plugin = plugin;
        this.armorStandManager = plugin.getArmorStandManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("furyminions.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cNie masz uprawnień do tej komendy!"));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cTa komenda może być użyta tylko przez gracza!"));
            return true;
        }

        Player player = (Player) sender;
        int radius = 5;

        if (args.length > 0) {
            try {
                radius = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cNieprawidłowy promień! Użyj: /removeminion [promień]"));
                return true;
            }
        }

        int removedCount = removeMinionsInRadius(player, radius);

        if (removedCount > 0) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &aUsunięto " + removedCount + " minionów w promieniu " + radius + " kratek."));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &eNie znaleziono minionów w promieniu " + radius + " kratek."));
        }

        return true;
    }

    /**
     * Removes all active minions within the given radius of the player.
     * Iterates over a snapshot of the active minion list to avoid {@link java.util.ConcurrentModificationException}.
     * Database deletion is asynchronous; ArmorStand and hologram removal happen immediately.
     *
     * @param player the reference point for radius calculation
     * @param radius the search radius in blocks
     * @return the number of minions removed
     */
    private int removeMinionsInRadius(Player player, int radius) {
        int count = 0;

        List<Minion> minionsToRemove = new ArrayList<>(armorStandManager.getActiveMinions().values());

        for (Minion minion : minionsToRemove) {
            if (minion.getLocation().getWorld().equals(player.getWorld())
                    && minion.getLocation().distance(player.getLocation()) <= radius) {

                plugin.getHologramManager().removeHologram(minion.getMinionUuid());
                minion.removeArmorStand();
                armorStandManager.removeActiveMinion(minion.getMinionUuid());

                UUID minionUuid = minion.getMinionUuid();
                plugin.getDatabaseManager().deleteMinionAsync(minionUuid).thenRun(() ->
                    plugin.getLogger().info("Usunięto miniona " + minionUuid + " przez komendę admina.")
                );

                count++;
            }
        }

        return count;
    }
}
