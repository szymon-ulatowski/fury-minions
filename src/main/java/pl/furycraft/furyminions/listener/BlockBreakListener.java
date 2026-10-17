package pl.furycraft.furyminions.listener;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.manager.ArmorStandManager;
import pl.furycraft.furyminions.model.Minion;

import java.util.List;

/**
 * Prevents players from breaking blocks within the operating platform of a minion they do not own.
 * The protected area is a flat region at the platform level (cy-1) within the minion's configured radius.
 */
public class BlockBreakListener implements Listener {

    private final FuryMinions plugin;
    private final ArmorStandManager armorStandManager;

    public BlockBreakListener(FuryMinions plugin) {
        this.plugin = plugin;
        this.armorStandManager = plugin.getArmorStandManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location blockLocation = block.getLocation();

        List<Minion> minions = armorStandManager.getActiveMinionsList();
        String worldName = blockLocation.getWorld().getName();

        for (Minion minion : minions) {
            Location minionLocation = minion.getLocation();
            if (!minionLocation.getWorld().getName().equals(worldName)) {
                continue;
            }
            int radius = minion.getType().getPlatformRadius();

            Location platformCenter = minionLocation.clone().subtract(0, 1, 0);

            double dx = Math.abs(blockLocation.getX() - platformCenter.getX());
            double dz = Math.abs(blockLocation.getZ() - platformCenter.getZ());
            double dy = Math.abs(blockLocation.getY() - platformCenter.getY());

            if (dx <= radius && dz <= radius && dy <= 1) {
                Player player = event.getPlayer();

                if (!minion.getOwnerUuid().equals(player.getUniqueId())) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cNie możesz niszczyć bloków na platformie cudzego miniona!"));
                    return;
                }
            }
        }
    }
}
