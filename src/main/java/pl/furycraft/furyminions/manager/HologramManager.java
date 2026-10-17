package pl.furycraft.furyminions.manager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.Minion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages floating {@link TextDisplay} holograms rendered above each active minion.
 * Holograms show the minion's type, level, inventory fill status, and platform warnings.
 */
public class HologramManager {

    private final FuryMinions plugin;
    private final Map<UUID, Entity> holograms;

    public HologramManager(FuryMinions plugin) {
        this.plugin = plugin;
        this.holograms = new HashMap<>();
    }

    /**
     * Creates and spawns a new hologram for the given minion.
     * Any pre-existing hologram at that position (TextDisplay or legacy marker ArmorStand)
     * is removed first to prevent ghost entities from accumulating.
     *
     * @param minion the minion to create the hologram for
     */
    public void createHologram(Minion minion) {
        if (holograms.containsKey(minion.getMinionUuid())) {
            removeHologram(minion.getMinionUuid());
        }

        // TextDisplay renders slightly differently than ArmorStands; Y offset tuned for visual centering
        Location hologramLocation = minion.getLocation().clone().add(0, 2.0, 0);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (hologramLocation.isWorldLoaded()) {
                for (Entity entity : hologramLocation.getWorld().getNearbyEntities(hologramLocation, 0.5, 0.5, 0.5)) {
                    if (entity instanceof ArmorStand as && as.isMarker() && !as.isVisible()) {
                        as.remove();
                    } else if (entity instanceof TextDisplay) {
                        entity.remove();
                    }
                }
            }

            TextDisplay hologram = (TextDisplay) hologramLocation.getWorld().spawnEntity(hologramLocation, EntityType.TEXT_DISPLAY);

            hologram.setText(buildHologramText(minion));
            hologram.setBillboard(Display.Billboard.CENTER);
            hologram.setAlignment(TextDisplay.TextAlignment.CENTER);
            hologram.setDefaultBackground(false);
            // Fully transparent background – pure text without Minecraft's dark box
            hologram.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));

            holograms.put(minion.getMinionUuid(), hologram);
        });
    }

    /**
     * Updates the text of an existing hologram to reflect the minion's current state.
     * Creates a new hologram if one does not yet exist.
     *
     * @param minion the minion whose hologram should be refreshed
     */
    public void updateHologram(Minion minion) {
        if (!holograms.containsKey(minion.getMinionUuid())) {
            createHologram(minion);
            return;
        }

        Entity hologram = holograms.get(minion.getMinionUuid());
        if (hologram != null && hologram.isValid()) {
            if (hologram instanceof TextDisplay textDisplay) {
                textDisplay.setText(buildHologramText(minion));
            }
        }
    }

    /**
     * Safely removes and despawns the active hologram associated with the given minion.
     *
     * @param minionUuid unique identifier of the target minion
     */
    public void removeHologram(UUID minionUuid) {
        Entity hologram = holograms.remove(minionUuid);
        if (hologram != null && hologram.isValid()) {
            hologram.remove();
        }
    }

    /**
     * Builds the display text for the hologram, including type, level, inventory status,
     * and an optional platform requirement warning.
     */
    private String buildHologramText(Minion minion) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.translateAlternateColorCodes('&',
                "&e" + minion.getType().getDisplayName() + "\n" +
                "&fPoziom: &a" + minion.getLevel() + "\n" +
                "&fMagazyn: &e" + minion.getInventory().size() + "/" + minion.getInventorySlots()
        ));

        if (minion.isFull()) {
            sb.append(ChatColor.translateAlternateColorCodes('&', "\n&cJestem pełny!"));
        }

        String platformWarning = checkPlatformWarning(minion);
        if (platformWarning != null) {
            sb.append("\n").append(ChatColor.translateAlternateColorCodes('&', platformWarning));
        }

        return sb.toString();
    }

    /**
     * Performs a lightweight block-scan around the minion's platform to detect missing
     * environment requirements (e.g. water, soul sand, farmland).
     * This check reads only a few blocks per call and is safe to run on the main thread.
     *
     * @param minion the minion to inspect
     * @return a formatted warning string if the platform is invalid, or {@code null} if satisfied
     */
    private String checkPlatformWarning(Minion minion) {
        if (minion.getLocation() == null || minion.getLocation().getWorld() == null) return null;

        org.bukkit.World world = minion.getLocation().getWorld();
        int cx = minion.getLocation().getBlockX();
        int cy = minion.getLocation().getBlockY() - 1;
        int cz = minion.getLocation().getBlockZ();
        int radius = minion.getType().getPlatformRadius();

        switch (minion.getType().getEffectiveBehavior()) {
            case FISHER -> {
                boolean hasWater = false;
                outer:
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x == 0 && z == 0) continue;
                        if (world.getType(cx + x, cy, cz + z) == org.bukkit.Material.WATER) {
                            hasWater = true;
                            break outer;
                        }
                    }
                }
                if (!hasWater) return "&cBrak wody! Postaw platformę 5x5 z wody.";
            }
            case NETHER_HERB -> {
                boolean hasSoulSand = false;
                outer:
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (world.getType(cx + x, cy, cz + z) == org.bukkit.Material.SOUL_SAND) {
                            hasSoulSand = true;
                            break outer;
                        }
                    }
                }
                if (!hasSoulSand) return "&cBrak Soul Sand! Postaw go w promieniu " + radius + " bloków.";
            }
            case CANE_FARMER -> {
                boolean hasDirtWithWater = false;
                outer:
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        org.bukkit.Material base = world.getType(cx + x, cy, cz + z);
                        if (base == org.bukkit.Material.DIRT || base == org.bukkit.Material.SAND
                                || base == org.bukkit.Material.GRASS_BLOCK) {
                            int bx = cx + x, bz = cz + z;
                            if (world.getType(bx + 1, cy, bz) == org.bukkit.Material.WATER
                                    || world.getType(bx - 1, cy, bz) == org.bukkit.Material.WATER
                                    || world.getType(bx, cy, bz + 1) == org.bukkit.Material.WATER
                                    || world.getType(bx, cy, bz - 1) == org.bukkit.Material.WATER) {
                                hasDirtWithWater = true;
                                break outer;
                            }
                        }
                    }
                }
                if (!hasDirtWithWater) return "&cBrak platformy! Postaw dirt z wodą obok.";
            }
            case CROP_FARMER -> {
                boolean hasFarmland = false;
                outer:
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (world.getType(cx + x, cy, cz + z) == org.bukkit.Material.FARMLAND) {
                            hasFarmland = true;
                            break outer;
                        }
                    }
                }
                if (!hasFarmland) return "&cBrak farmland! Postaw ziemię uprawną.";
            }
            case CACTUS_FARMER -> {
                boolean hasSand = false;
                outer:
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        org.bukkit.Material base = world.getType(cx + x, cy, cz + z);
                        if (base == org.bukkit.Material.SAND || base == org.bukkit.Material.RED_SAND) {
                            hasSand = true;
                            break outer;
                        }
                    }
                }
                if (!hasSand) return "&cBrak piasku! Postaw bloki piasku pod kaktusy.";
            }
            case MELON_FARMER -> {
                boolean hasGround = false;
                outer:
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        org.bukkit.Material base = world.getType(cx + x, cy, cz + z);
                        if (base == org.bukkit.Material.FARMLAND || base == org.bukkit.Material.DIRT || base == org.bukkit.Material.GRASS_BLOCK) {
                            hasGround = true;
                            break outer;
                        }
                    }
                }
                if (!hasGround) return "&cBrak podłoża! Postaw ziemię uprawną, dirt lub trawę.";
            }
            // MINER, CAVE_MINER, NETHER_MINER, TREE_FARMER – no platform requirement
            default -> { }
        }
        return null;
    }

    /**
     * Removes and despawns all active holograms. Called on plugin shutdown.
     */
    public void clearAllHolograms() {
        for (Entity hologram : holograms.values()) {
            if (hologram != null && hologram.isValid()) {
                hologram.remove();
            }
        }
        holograms.clear();
    }
}