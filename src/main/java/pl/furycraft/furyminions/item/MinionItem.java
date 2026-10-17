package pl.furycraft.furyminions.item;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.manager.MinionKeys;

import java.net.URI;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factory for creating minion {@link ItemStack} items used in the player's inventory.
 * <p>
 * Minion items are player-skull items whose {@link org.bukkit.persistence.PersistentDataContainer}
 * stores the type ID and level for identification and restoration at placement time.
 */
public class MinionItem {

    private final FuryMinions plugin;

    public MinionItem(FuryMinions plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates a level-1 minion item for the given type.
     *
     * @param typeId the minion type identifier
     * @return the configured item, or {@code null} if the type is not registered
     */
    public ItemStack createMinionItem(String typeId) {
        return createMinionItemWithLevel(typeId, 1);
    }

    /**
     * Creates a minion item for the given type and level.
     * The skull texture is extracted from the Base64-encoded config value using the Bukkit
     * {@link PlayerProfile} API (no reflection, no NMS).
     *
     * @param typeId the minion type identifier
     * @param level  the level to encode into the item's PDC
     * @return the configured item, or {@code null} if the type is not registered
     */
    public ItemStack createMinionItemWithLevel(String typeId, int level) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        if (meta != null) {
            var minionType = plugin.getConfigManager().getMinionType(typeId);
            if (minionType == null) {
                return null;
            }

            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', minionType.getDisplayName()));

            var lore = new java.util.ArrayList<String>();
            lore.add(ChatColor.GRAY + "Kliknij prawym, aby postawić");
            lore.add(ChatColor.GRAY + "miniona na swojej wyspie.");
            lore.add("");
            lore.add(ChatColor.YELLOW + "Czas reakcji: " + ChatColor.GREEN + formatTicksToTime(minionType.getActionInterval(level)));
            lore.add(ChatColor.YELLOW + "Poziom: " + ChatColor.GOLD + level);
            lore.add("");
            lore.add(ChatColor.GRAY + "Sloty magazynu: " + ChatColor.WHITE + (1 + level));

            meta.setLore(lore);

            String headTexture = minionType.getHeadTexture();
            if (headTexture != null && !headTexture.isEmpty()) {
                try {
                    PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
                    PlayerTextures textures = profile.getTextures();

                    // Base64 payload format: {"textures":{"SKIN":{"url":"..."}}}
                    byte[] decodedBytes = Base64.getDecoder().decode(headTexture);
                    String decodedString = new String(decodedBytes);

                    Matcher matcher = Pattern.compile("\"url\":\"([^\"]+)\"").matcher(decodedString);

                    if (matcher.find()) {
                        textures.setSkin(new URI(matcher.group(1)).toURL());
                        profile.setTextures(textures);
                        meta.setOwnerProfile(profile);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Nie udało się ustawić tekstury głowy dla miniona: " + typeId + " | Błąd: " + e.getMessage());
                }
            }

            meta.getPersistentDataContainer().set(MinionKeys.getMinionTypeKey(), PersistentDataType.STRING, typeId);
            meta.getPersistentDataContainer().set(MinionKeys.getMinionLevelKey(), PersistentDataType.INTEGER, level);

            item.setItemMeta(meta);
        }

        return item;
    }

    /** Formats a tick duration as {@code mm:ss}. */
    private String formatTicksToTime(int ticks) {
        int seconds = ticks / 20;
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }

    /**
     * Reads the minion type ID from the item's {@link org.bukkit.persistence.PersistentDataContainer}.
     *
     * @param item the item to inspect
     * @return the type ID string, or {@code null} if the item is not a minion item
     */
    public String getMinionType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        return meta.getPersistentDataContainer().get(MinionKeys.getMinionTypeKey(), PersistentDataType.STRING);
    }

    /**
     * Reads the encoded level from the item's {@link org.bukkit.persistence.PersistentDataContainer}.
     * Returns {@code 1} if the key is absent (e.g. legacy items created before level tracking was added).
     *
     * @param item the item to inspect
     * @return the encoded minion level, defaulting to {@code 1}
     */
    public int getMinionLevel(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 1;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(MinionKeys.getMinionLevelKey(), PersistentDataType.INTEGER)) {
            return 1;
        }

        Integer level = meta.getPersistentDataContainer().get(MinionKeys.getMinionLevelKey(), PersistentDataType.INTEGER);
        return level != null ? level : 1;
    }

    /**
     * Returns {@code true} if the given item is a valid minion item (i.e. has a type ID in its PDC).
     *
     * @param item the item to check
     */
    public boolean isMinionItem(ItemStack item) {
        return getMinionType(item) != null;
    }
}