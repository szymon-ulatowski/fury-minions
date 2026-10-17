package pl.furycraft.furyminions.gui;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.item.MinionItem;
import pl.furycraft.furyminions.model.MinionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds and manages the minion shop GUI.
 * <p>
 * Minion types with an explicit {@code shop-slot} in the config are placed first;
 * remaining types are auto-filled into the next available interior slot (columns 1–7 across rows 1–4).
 */
public class ShopGUI {

    private final FuryMinions plugin;
    private static final String SHOP_TITLE = ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &8Sklep z Minionami");
    private static final int SHOP_SIZE = 54;

    public ShopGUI(FuryMinions plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens a freshly built shop inventory for the given player.
     *
     * @param player the player to show the shop to
     */
    public void openShop(Player player) {
        Inventory inventory = Bukkit.createInventory(null, SHOP_SIZE, SHOP_TITLE);

        fillBackground(inventory);
        addMinionItems(inventory);

        player.openInventory(inventory);
    }

    private void fillBackground(Inventory inventory) {
        ItemStack glassPane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glassPane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            glassPane.setItemMeta(meta);
        }

        for (int i = 0; i < SHOP_SIZE; i++) {
            inventory.setItem(i, glassPane);
        }
    }

    /**
     * Populates the shop with all registered minion types.
     * Types with an assigned {@code shop-slot} are placed first; unassigned types are
     * auto-filled into free interior slots, skipping the border columns.
     */
    private void addMinionItems(Inventory inventory) {
        MinionItem minionItem = new MinionItem(plugin);

        List<Map.Entry<String, MinionType>> unassignedMinions = new ArrayList<>();

        for (Map.Entry<String, MinionType> entry : plugin.getConfigManager().getMinionTypes().entrySet()) {
            MinionType type = entry.getValue();
            if (type.getShopSlot() != -1 && type.getShopSlot() >= 0 && type.getShopSlot() < SHOP_SIZE) {
                ItemStack item = createShopItem(minionItem, entry.getKey());
                if (item != null) {
                    inventory.setItem(type.getShopSlot(), item);
                }
            } else {
                unassignedMinions.add(entry);
            }
        }

        // Fill unassigned types into the next available interior slot (col 1–7 across all rows)
        int currentSlot = 10;
        for (Map.Entry<String, MinionType> entry : unassignedMinions) {
            while (currentSlot < 44) {
                ItemStack currentItem = inventory.getItem(currentSlot);
                if (currentItem != null && currentItem.getType() == Material.GRAY_STAINED_GLASS_PANE) {
                    break;
                }
                currentSlot = (currentSlot % 9 == 7) ? currentSlot + 3 : currentSlot + 1;
            }

            if (currentSlot >= 44) {
                break;
            }

            ItemStack item = createShopItem(minionItem, entry.getKey());
            if (item != null) {
                inventory.setItem(currentSlot, item);
            }
        }
    }

    /**
     * Creates a shop display item for the given minion type, appending price and purchase hint to its lore.
     *
     * @param minionItem helper used to build the base item
     * @param typeId     the minion type identifier
     * @return the configured {@link ItemStack}, or {@code null} if the base item cannot be created
     */
    private ItemStack createShopItem(MinionItem minionItem, String typeId) {
        ItemStack item = minionItem.createMinionItem(typeId);
        if (item == null) {
            return null;
        }

        MinionType type = plugin.getConfigManager().getMinionType(typeId);
        double price = type != null ? type.getShopPrice() : 1000.0;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GREEN + "Cena: " + ChatColor.GOLD + String.format("%.2f", price));
            lore.add("");
            lore.add(ChatColor.YELLOW + "Kliknij, aby kupić");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Processes a purchase: validates the player's balance, charges them, and gives the minion item.
     * Reopens the shop after a successful purchase so the player can continue browsing.
     * If the player's inventory is full, the item is dropped at their feet instead.
     *
     * @param player the buying player
     * @param item   the shop item the player clicked
     */
    public void handlePurchase(Player player, ItemStack item) {
        MinionItem minionItem = new MinionItem(plugin);
        String typeId = minionItem.getMinionType(item);

        if (typeId == null) {
            return;
        }

        Economy economy = plugin.getEconomy();
        if (economy == null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cEkonomia nie jest dostępna!"));
            return;
        }

        MinionType type = plugin.getConfigManager().getMinionType(typeId);
        if (type == null) {
            return;
        }
        double price = type.getShopPrice();

        if (!economy.has(player, price)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cNie masz wystarczająco pieniędzy! Potrzebujesz: " + String.format("%.2f", price)));
            return;
        }

        economy.withdrawPlayer(player, price);

        ItemStack purchasedItem = minionItem.createMinionItem(typeId);
        if (purchasedItem != null) {
            if (player.getInventory().firstEmpty() == -1) {
                player.getWorld().dropItemNaturally(player.getLocation(), purchasedItem);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &eKupiono miniona! (Spadł na ziemię - pełne inventory)"));
            } else {
                player.getInventory().addItem(purchasedItem);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &aKupiono miniona!"));
            }
        }

        openShop(player);
    }
}
