package pl.furycraft.furyminions.command;

import org.bukkit.command.Command;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.furycraft.furyminions.FuryMinions;

/**
 * Handles the {@code /minionshop} command, opening the minion purchase GUI.
 * Requires the {@code furyminions.shop} permission.
 */
public class MinionShopCommand implements CommandExecutor {

    private final FuryMinions plugin;

    public MinionShopCommand(FuryMinions plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cTa komenda jest tylko dla graczy!"));
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("furyminions.shop")) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cNie masz uprawnień do używania tej komendy!"));
            return true;
        }

        plugin.getGuiListener().openShopGUI(player);
        return true;
    }
}
