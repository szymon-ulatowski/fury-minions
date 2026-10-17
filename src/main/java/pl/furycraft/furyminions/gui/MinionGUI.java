package pl.furycraft.furyminions.gui;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.item.MinionItem;
import pl.furycraft.furyminions.model.Minion;
import pl.furycraft.furyminions.model.MinionType;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and manages the 54-slot minion management GUI.
 * Provides inventory rendering and handles upgrade, collect, remove, rotate, and wood-type actions.
 */
public class MinionGUI {

    private final FuryMinions plugin;
    private static final String GUI_TITLE = ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &8Zarządzanie Minionem");
    private static final int GUI_SIZE = 54;

    public MinionGUI(FuryMinions plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens a freshly built management GUI for the given player and minion.
     *
     * @param player the player to show the GUI to
     * @param minion the minion being managed
     */
    public void openGUI(Player player, Minion minion) {
        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);

        fillBackground(inventory);
        addMinionInfo(inventory, minion);
        addInventorySection(inventory, minion);
        addUpgradeButton(inventory, minion, player);
        addCollectButton(inventory, minion, player);
        addRemoveButton(inventory, minion, player);

        if (minion.getType().getEffectiveBehavior() == pl.furycraft.furyminions.model.MinionBehavior.TREE_FARMER) {
            addWoodSelectorButton(inventory, minion, player);
        }

        // Rotation controls are only shown for minions that hold a fixed facing direction;
        // MINER, TREE_FARMER, and FISHER rotate dynamically to their targets each tick.
        if (supportsFixedFacing(minion)) {
            addRotationButtons(inventory, minion);
        }

        player.openInventory(inventory);
    }

    /**
     * Refreshes the already-open GUI for the given player without reopening it.
     * No-op if the player's open inventory is not the minion GUI.
     *
     * @param player the player whose GUI should be refreshed
     * @param minion the minion whose data to reflect
     */
    public void updateGUI(Player player, Minion minion) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (inventory != null && player.getOpenInventory().getTitle().contains("Zarządzanie Minionem")) {
            addMinionInfo(inventory, minion);
            addInventorySection(inventory, minion);
            addUpgradeButton(inventory, minion, player);
            addCollectButton(inventory, minion, player);
            addRemoveButton(inventory, minion, player);

            if (minion.getType().getEffectiveBehavior() == pl.furycraft.furyminions.model.MinionBehavior.TREE_FARMER) {
                addWoodSelectorButton(inventory, minion, player);
            }

            if (supportsFixedFacing(minion)) {
                addRotationButtons(inventory, minion);
            }
        }
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
     * Places the minion's head item with its stats in slot 13 (the centre of the top row).
     * The skull texture is decoded from the Base64 config value using the Paper {@link PlayerProfile} API.
     */
    private void addMinionInfo(Inventory inventory, Minion minion) {
        MinionType type = minion.getType();

        ItemStack infoItem = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) infoItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', type.getDisplayName()));

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Poziom: " + ChatColor.GOLD + minion.getLevel());
            lore.add(ChatColor.GRAY + "Czas reakcji: " + ChatColor.GREEN + formatTicksToTime(type.getActionInterval(minion.getLevel())));
            lore.add(ChatColor.GRAY + "Sloty magazynu: " + ChatColor.YELLOW + minion.getInventory().size() + "/" + minion.getInventorySlots());
            lore.add("");
            lore.add(ChatColor.GRAY + "Właściciel: " + ChatColor.WHITE + Bukkit.getOfflinePlayer(minion.getOwnerUuid()).getName());

            meta.setLore(lore);

            String headTexture = type.getHeadTexture();
            if (headTexture != null && !headTexture.isEmpty()) {
                try {
                    PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
                    PlayerTextures textures = profile.getTextures();

                    byte[] decodedBytes = Base64.getDecoder().decode(headTexture);
                    String decodedString = new String(decodedBytes);

                    Matcher matcher = Pattern.compile("\"url\":\"([^\"]+)\"").matcher(decodedString);

                    if (matcher.find()) {
                        String textureUrl = matcher.group(1);
                        textures.setSkin(new URI(textureUrl).toURL());
                        profile.setTextures(textures);
                        meta.setOwnerProfile(profile);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Nie udało się ustawić tekstury głowy w GUI dla miniona: " + type.getId() + " | Błąd: " + e.getMessage());
                }
            }

            infoItem.setItemMeta(meta);
        }

        inventory.setItem(13, infoItem);
    }

    /**
     * Renders the minion's inventory content into the two-row item area (slots 19–25 and 28–34).
     * Filled slots show the actual items; empty unlocked slots show a placeholder pane;
     * locked slots (above the current level's capacity) show a red pane with an unlock hint.
     * The maximum visible capacity is fixed at 11 slots (level 10).
     */
    private void addInventorySection(Inventory inventory, Minion minion) {
        int startSlot = 19;
        int maxSlots = minion.getInventorySlots();
        int currentItems = minion.getInventory().size();

        // Layout: first 7 slots in row 1 (slots 19–25), remaining in row 2 (slots 28–34)
        for (int i = startSlot; i <= 25; i++) {
            inventory.setItem(i, new ItemStack(Material.AIR));
        }
        for (int i = 28; i <= 34; i++) {
            inventory.setItem(i, new ItemStack(Material.AIR));
        }

        for (int i = 0; i < Math.min(currentItems, maxSlots); i++) {
            ItemStack item = minion.getInventory().get(i);
            int slotPosition = i < 7 ? startSlot + i : 28 + (i - 7);
            inventory.setItem(slotPosition, item);
        }

        for (int i = currentItems; i < maxSlots; i++) {
            int slotPosition = i < 7 ? startSlot + i : 28 + (i - 7);
            ItemStack emptySlot = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = emptySlot.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GRAY + "Pusty slot");
                emptySlot.setItemMeta(meta);
            }
            inventory.setItem(slotPosition, emptySlot);
        }

        for (int i = maxSlots; i < 11; i++) {
            int slotPosition = i < 7 ? startSlot + i : 28 + (i - 7);
            ItemStack lockedSlot = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta meta = lockedSlot.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.RED + "Zablokowany slot");
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Odblokuj ulepszając miniona!");
                meta.setLore(lore);
                lockedSlot.setItemMeta(meta);
            }
            inventory.setItem(slotPosition, lockedSlot);
        }

        // Slots 32–34 are always outside the functional area; fill with background glass
        for (int i = 11; i < 14; i++) {
            int slotPosition = 28 + (i - 7);
            ItemStack unusedSlot = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = unusedSlot.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(" ");
                unusedSlot.setItemMeta(meta);
            }
            inventory.setItem(slotPosition, unusedSlot);
        }
    }

    private void addUpgradeButton(Inventory inventory, Minion minion, Player player) {
        MinionType type = minion.getType();
        double upgradeCost = type.getUpgradeCost(minion.getLevel());

        ItemStack upgradeItem;
        if (upgradeCost == -1) {
            upgradeItem = new ItemStack(Material.BARRIER);
            ItemMeta meta = upgradeItem.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.RED + "Maksymalny poziom!");
                upgradeItem.setItemMeta(meta);
            }
        } else {
            upgradeItem = new ItemStack(Material.ENCHANTED_BOOK);
            ItemMeta meta = upgradeItem.getItemMeta();
            if (meta != null) {
                Economy economy = plugin.getEconomy();
                String costDisplay = economy != null ? String.format("%.2f", upgradeCost) : "Niedostępne";

                meta.setDisplayName(ChatColor.GREEN + "Ulepsz Miniona");

                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Koszt: " + ChatColor.GOLD + costDisplay);
                lore.add(ChatColor.GRAY + "Następny poziom: " + ChatColor.AQUA + (minion.getLevel() + 1));
                lore.add("");
                lore.add(ChatColor.YELLOW + "Kliknij, aby ulepszyć");

                meta.setLore(lore);
                upgradeItem.setItemMeta(meta);
            }
        }

        inventory.setItem(38, upgradeItem);
    }

    private void addCollectButton(Inventory inventory, Minion minion, Player player) {
        ItemStack collectItem = new ItemStack(Material.CHEST);
        ItemMeta meta = collectItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Zbierz Surowce");

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Zbiera wszystkie surowce");
            lore.add(ChatColor.GRAY + "z magazynu miniona");
            lore.add("");
            lore.add(ChatColor.YELLOW + "Kliknij, aby zebrać");

            meta.setLore(lore);
            collectItem.setItemMeta(meta);
        }

        inventory.setItem(40, collectItem);
    }

    private void addRemoveButton(Inventory inventory, Minion minion, Player player) {
        ItemStack removeItem = new ItemStack(Material.BARRIER);
        ItemMeta meta = removeItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Zabierz Miniona");

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Usuwa miniona i przywraca");
            lore.add(ChatColor.GRAY + "oryginalny teren");
            lore.add("");
            lore.add(ChatColor.RED + "Kliknij, aby usunąć");

            meta.setLore(lore);
            removeItem.setItemMeta(meta);
        }

        inventory.setItem(42, removeItem);
    }

    private void addWoodSelectorButton(Inventory inventory, Minion minion, Player player) {
        ItemStack woodItem = new ItemStack(Material.OAK_LOG);
        ItemMeta meta = woodItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Wybierz Typ Drewna");

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Zmienia rodzaj drewna,");
            lore.add(ChatColor.GRAY + "który będzie wydobywany.");
            lore.add("");
            lore.add(ChatColor.YELLOW + "Obecnie: " + ChatColor.AQUA + minion.getSelectedLogType().name());
            lore.add("");
            lore.add(ChatColor.GOLD + "Kliknij, aby otworzyć menu!");

            meta.setLore(lore);
            woodItem.setItemMeta(meta);
        }

        inventory.setItem(44, woodItem);
    }

    /**
     * Returns {@code true} for minion types that support a player-configured static facing direction.
     * MINER, TREE_FARMER, and FISHER rotate dynamically towards their targets.
     */
    private boolean supportsFixedFacing(Minion minion) {
        return switch (minion.getType().getEffectiveBehavior()) {
            case MINER, TREE_FARMER, FISHER -> false;
            default -> true;
        };
    }

    private void addRotationButtons(Inventory inventory, Minion minion) {
        inventory.setItem(48, createTexturedHead(ChatColor.AQUA + "Obróć w lewo",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGUxZGZjMTFhODM3MTExZDIyYjAwMWExNDQ2MWY5YTdmYzA5MzUyMmY4OGM1OGZhZWZkNmFkZWZmY2Q0ZTlhYiJ9fX0="));

        inventory.setItem(50, createTexturedHead(ChatColor.AQUA + "Obróć w prawo",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvN2M2OWQ0MTA3NmE4ZGVhNGYwNmQzZjFhOWFjNDdjYzk5Njk4OGI3NGEwOTEzYWIyYWMxYTc0Y2FmNzA4MTkxOCJ9fX0="));
    }

    /**
     * Creates a custom player skull with the given display name and Base64-encoded texture.
     *
     * @param name   the display name for the skull item
     * @param base64 Base64-encoded texture string
     * @return the configured {@link ItemStack}
     */
    private ItemStack createTexturedHead(String name, String base64) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null && base64 != null && !base64.isEmpty()) {
            meta.setDisplayName(name);
            try {
                PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
                PlayerTextures textures = profile.getTextures();
                byte[] decodedBytes = Base64.getDecoder().decode(base64);
                String decodedString = new String(decodedBytes);
                Matcher matcher = Pattern.compile("\"url\":\"([^\"]+)\"").matcher(decodedString);
                if (matcher.find()) {
                    textures.setSkin(new URI(matcher.group(1)).toURL());
                    profile.setTextures(textures);
                    meta.setOwnerProfile(profile);
                }
            } catch (Exception ignored) {}
            head.setItemMeta(meta);
        }
        return head;
    }

    /** Formats a tick duration as {@code mm:ss}. */
    private String formatTicksToTime(int ticks) {
        int seconds = ticks / 20;
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }

    /**
     * Processes an upgrade action: validates funds, charges the player, increments the level,
     * persists the change, and refreshes the GUI.
     *
     * @param player the player performing the upgrade
     * @param minion the minion to upgrade
     */
    public void handleUpgrade(Player player, Minion minion) {
        MinionType type = minion.getType();
        double upgradeCost = type.getUpgradeCost(minion.getLevel());

        if (upgradeCost == -1) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cMinion osiągnął już maksymalny poziom!"));
            return;
        }

        Economy economy = plugin.getEconomy();
        if (economy == null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cEkonomia nie jest dostępna!"));
            return;
        }

        if (!economy.has(player, upgradeCost)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &cNie masz wystarczająco pieniędzy! Potrzebujesz: " + String.format("%.2f", upgradeCost)));
            return;
        }

        economy.withdrawPlayer(player, upgradeCost);
        minion.setLevel(minion.getLevel() + 1);

        plugin.getDatabaseManager().saveMinionAsync(minion.toMinionData());
        plugin.getHologramManager().updateHologram(minion);

        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &aUlepszono miniona do poziomu " + minion.getLevel() + "!"));
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        minion.getLocation().getWorld().spawnParticle(Particle.HAPPY_VILLAGER, minion.getLocation().clone().add(0, 1.5, 0), 30, 0.5, 0.5, 0.5, 0.1);
        updateGUI(player, minion);
    }

    /**
     * Transfers all items from the minion's inventory to the player's inventory,
     * dropping overflow items at the player's feet. Persists the now-empty inventory state.
     *
     * @param player the player collecting the resources
     * @param minion the minion to collect from
     */
    public void handleCollect(Player player, Minion minion) {
        if (minion.getInventory().isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &eMagazyn miniona jest pusty!"));
            return;
        }

        var collected = minion.collectInventory();

        for (ItemStack item : collected) {
            if (player.getInventory().firstEmpty() == -1) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            } else {
                player.getInventory().addItem(item);
            }
        }

        minion.setFull(false);
        plugin.getDatabaseManager().saveMinionAsync(minion.toMinionData());
        plugin.getHologramManager().updateHologram(minion);

        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &aZebrano przedmioty z magazynu miniona!"));
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        updateGUI(player, minion);
    }

    /**
     * Removes the minion: closes the GUI, returns all inventory items and the minion item
     * (preserving its level) to the player, and deletes the record from the database.
     *
     * @param player the player removing the minion
     * @param minion the minion to remove
     */
    public void handleRemove(Player player, Minion minion) {
        player.closeInventory();

        var collected = minion.collectInventory();
        for (ItemStack item : collected) {
            if (player.getInventory().firstEmpty() == -1) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            } else {
                player.getInventory().addItem(item);
            }
        }

        MinionItem minionItemCreator = new MinionItem(plugin);
        ItemStack minionItemStack = minionItemCreator.createMinionItemWithLevel(minion.getType().getId(), minion.getLevel());
        if (minionItemStack != null) {
            player.getInventory().addItem(minionItemStack);
        }

        plugin.getHologramManager().removeHologram(minion.getMinionUuid());
        minion.removeArmorStand();
        plugin.getArmorStandManager().removeActiveMinion(minion.getMinionUuid());

        plugin.getDatabaseManager().deleteMinionAsync(minion.getMinionUuid()).thenRun(() -> {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e&lFC &7» &aPodniosłeś miniona: " + minion.getType().getDisplayName()));
        });
    }

    /**
     * Rotates the minion's fixed facing direction one step left or right and updates the ArmorStand yaw.
     *
     * @param player the player issuing the rotation
     * @param minion the minion to rotate
     * @param right  {@code true} to rotate clockwise (right), {@code false} for counter-clockwise (left)
     */
    public void handleRotate(Player player, Minion minion, boolean right) {
        String[] dirs = {"NORTH", "EAST", "SOUTH", "WEST"};
        int idx = 0;
        for (int i = 0; i < dirs.length; i++) {
            if (dirs[i].equals(minion.getFacingDirection())) {
                idx = i;
                break;
            }
        }

        idx = right ? (idx + 1) % 4 : (idx - 1 + 4) % 4;
        String newDir = dirs[idx];
        minion.setFacingDirection(newDir);

        org.bukkit.entity.ArmorStand as = minion.getArmorStand();
        if (as != null) {
            org.bukkit.Location loc = as.getLocation();
            switch (newDir) {
                case "NORTH" -> loc.setYaw(180);
                case "EAST"  -> loc.setYaw(-90);
                case "SOUTH" -> loc.setYaw(0);
                case "WEST"  -> loc.setYaw(90);
            }
            as.teleport(loc);
        }

        plugin.getDatabaseManager().saveMinionAsync(minion.toMinionData());
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_PISTON_EXTEND, 1.0f, 1.2f);
    }
}