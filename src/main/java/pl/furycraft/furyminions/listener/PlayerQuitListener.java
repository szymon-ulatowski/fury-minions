package pl.furycraft.furyminions.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.Minion;

import java.util.List;
import java.util.UUID;

/**
 * Persists all minions owned by a player when they disconnect.
 * Saving is performed asynchronously to avoid blocking the main thread during the quit event.
 */
public class PlayerQuitListener implements Listener {

    private final FuryMinions plugin;

    public PlayerQuitListener(FuryMinions plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        List<Minion> minions = new java.util.ArrayList<>(plugin.getArmorStandManager().getActiveMinionsList());

        for (Minion minion : minions) {
            if (minion.getOwnerUuid().equals(playerUuid)) {
                plugin.getDatabaseManager().saveMinionAsync(minion.toMinionData()).thenRun(() -> {
                    plugin.getLogger().info("Zapisano miniona " + minion.getMinionUuid() + " gracza " + player.getName() + " przed wyjściem.");
                }).exceptionally(ex -> {
                    plugin.getLogger().severe("Błąd podczas zapisywania miniona przed wyjściem gracza: " + ex.getMessage());
                    return null;
                });
            }
        }
    }
}
