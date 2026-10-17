package pl.furycraft.furyminions.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.Minion;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for selecting the wood type harvested by a TREE_FARMER minion.
 * Higher-tier wood species require a minimum minion level; locked options are shown as barriers.
 */
public class WoodSelectorGUI {

    private final FuryMinions plugin;
    private static final String GUI_TITLE = ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &8Wybór Drewna");
    private static final int GUI_SIZE = 27;

    public WoodSelectorGUI(FuryMinions plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens the wood selector inventory for the given player and minion.
     *
     * @param player the player opening the GUI
     * @param minion the TREE_FARMER minion whose log type is being configured
     */
    public void openGUI(Player player, Minion minion) {
        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);

        fillBackground(inventory);

        int minionLevel = minion.getLevel();

        addWoodItem(inventory, 10, Material.OAK_LOG,      "Dębowe",       minionLevel, 1,  minion);
        addWoodItem(inventory, 11, Material.BIRCH_LOG,    "Brzozowe",     minionLevel, 2,  minion);
        addWoodItem(inventory, 12, Material.SPRUCE_LOG,   "Świerkowe",    minionLevel, 3,  minion);
        addWoodItem(inventory, 13, Material.JUNGLE_LOG,   "Dżunglowe",    minionLevel, 4,  minion);
        addWoodItem(inventory, 14, Material.ACACIA_LOG,   "Akacjowe",     minionLevel, 5,  minion);
        addWoodItem(inventory, 15, Material.DARK_OAK_LOG, "Ciemnodębowe", minionLevel, 6,  minion);
        addWoodItem(inventory, 16, Material.MANGROVE_LOG, "Namorzynowe",  minionLevel, 8,  minion);
        addWoodItem(inventory, 17, Material.CHERRY_LOG,   "Wiśniowe",     minionLevel, 10, minion);

        ItemStack backButton = new ItemStack(Material.ARROW);
        ItemMeta meta = backButton.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Powrót");
            backButton.setItemMeta(meta);
        }
        inventory.setItem(22, backButton);

        player.openInventory(inventory);
    }

    private void fillBackground(Inventory inventory) {
        ItemStack glassPane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glassPane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            glassPane.setItemMeta(meta);
        }

        for (int i = 0; i < GUI_SIZE; i++) {
            inventory.setItem(i, glassPane);
        }
    }

    /**
     * Places a single wood-type selection item in the given slot.
     * Items below the required level are shown as barriers to indicate they are locked.
     *
     * @param inventory     the target inventory
     * @param slot          the slot to place the item in
     * @param material      the log material for this option
     * @param name          localised Polish name of the wood type
     * @param currentLevel  the minion's current level
     * @param requiredLevel minimum level required to unlock this wood type
     * @param minion        the minion being configured (used to highlight the currently selected type)
     */
    private void addWoodItem(Inventory inventory, int slot, Material material, String name,
                             int currentLevel, int requiredLevel, Minion minion) {
        boolean unlocked = currentLevel >= requiredLevel;
        boolean selected = material.name().equals(minion.getExtraData())
                || (minion.getExtraData().isEmpty() && material == Material.OAK_LOG);

        ItemStack item = new ItemStack(unlocked ? material : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((unlocked ? ChatColor.GREEN : ChatColor.RED) + "Drewno " + name);
            List<String> lore = new ArrayList<>();

            if (selected) {
                lore.add(ChatColor.AQUA + "▶ Obecnie wybrane ◀");
            }

            if (unlocked) {
                lore.add(ChatColor.GRAY + "Kliknij, aby wybrać ten rodzaj drewna.");
            } else {
                lore.add(ChatColor.RED + "Wymagany poziom miniona: " + requiredLevel);
                lore.add(ChatColor.GRAY + "Ulepsz miniona, aby odblokować.");
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    /**
     * Handles a slot click: unlocks and applies the selected wood type, or navigates back.
     * Plays a denial sound if the selection is locked for the minion's current level.
     *
     * @param player the player who clicked
     * @param minion the minion being configured
     * @param slot   the clicked inventory slot
     */
    public void handleSelect(Player player, Minion minion, int slot) {
        Material selectedMaterial = null;
        int requiredLevel = 1;

        switch (slot) {
            case 10: selectedMaterial = Material.OAK_LOG;      requiredLevel = 1;  break;
            case 11: selectedMaterial = Material.BIRCH_LOG;    requiredLevel = 2;  break;
            case 12: selectedMaterial = Material.SPRUCE_LOG;   requiredLevel = 3;  break;
            case 13: selectedMaterial = Material.JUNGLE_LOG;   requiredLevel = 4;  break;
            case 14: selectedMaterial = Material.ACACIA_LOG;   requiredLevel = 5;  break;
            case 15: selectedMaterial = Material.DARK_OAK_LOG; requiredLevel = 6;  break;
            case 16: selectedMaterial = Material.MANGROVE_LOG; requiredLevel = 8;  break;
            case 17: selectedMaterial = Material.CHERRY_LOG;   requiredLevel = 10; break;
            case 22:
                plugin.getGuiListener().openMinionGUI(player, minion);
                return;
            default: return;
        }

        if (minion.getLevel() < requiredLevel) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cTen rodzaj drewna wymaga miniona na " + requiredLevel + " poziomie!"));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        minion.setExtraData(selectedMaterial.name());
        plugin.getDatabaseManager().saveMinionAsync(minion.toMinionData());
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &aPomyślnie zmieniono rodzaj wydobywanego drewna!"));
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);

        plugin.getGuiListener().openWoodSelectorGUI(player, minion);
    }
}
