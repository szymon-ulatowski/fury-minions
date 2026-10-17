package pl.furycraft.furyminions.listener;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.Minion;

import java.util.List;
import java.util.UUID;

/**
 * Persists and despawns minions whose chunk is being unloaded.
 * <p>
 * Saving is done asynchronously to avoid stalling the main thread during chunk unload.
 * The minion's hologram and ArmorStand are removed synchronously in the same tick.
 */
public class ChunkUnloadListener implements Listener {

    private final FuryMinions plugin;

    public ChunkUnloadListener(FuryMinions plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChunkUnload(ChunkUnloadEvent event) {
        List<Minion> minions = new java.util.ArrayList<>(plugin.getArmorStandManager().getActiveMinionsList());
        long chunkKey = event.getChunk().getChunkKey();
        String worldName = event.getChunk().getWorld().getName();

        for (Minion minion : minions) {
            Location loc = minion.getLocation();
            if (!loc.getWorld().getName().equals(worldName)) continue;

            int mChunkX = ((int) Math.floor(loc.getX())) >> 4;
            int mChunkZ = ((int) Math.floor(loc.getZ())) >> 4;
            long minionChunkKey = org.bukkit.Chunk.getChunkKey(mChunkX, mChunkZ);

            if (minionChunkKey == chunkKey) {
                plugin.getDatabaseManager().saveMinionAsync(minion.toMinionData()).exceptionally(ex -> {
                    plugin.getLogger().severe("Błąd podczas zapisywania miniona przed wyładowaniem chunka: " + ex.getMessage());
                    return null;
                });
                plugin.getHologramManager().removeHologram(minion.getMinionUuid());
                plugin.getArmorStandManager().removeMinionArmorStand(minion.getMinionUuid());
            }
        }
    }
}
