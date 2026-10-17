package pl.furycraft.furyminions.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.gui.MinionGUI;
import pl.furycraft.furyminions.gui.ShopGUI;
import pl.furycraft.furyminions.gui.WoodSelectorGUI;
import pl.furycraft.furyminions.model.Minion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central GUI event router. Tracks which GUI is open for each player and dispatches
 * inventory click events to the appropriate handler ({@link MinionGUI}, {@link ShopGUI},
 * or {@link WoodSelectorGUI}).
 * <p>
 * Title-based fallback detection ({@code inventoryTitle.contains(...)}) ensures clicks are
 * still routed correctly even if the player's UUID is not yet in the open-GUI maps,
 * e.g. when a GUI was opened from a different code path.
 */
public class GUIListener implements Listener {

    private final FuryMinions plugin;
    private final MinionGUI minionGUI;
    private final ShopGUI shopGUI;
    private final WoodSelectorGUI woodSelectorGUI;
    private final Map<UUID, Minion> openMinionGuis;
    private final Map<UUID, Minion> openWoodSelectorGuis;
    private final Map<UUID, Boolean> openShopGuis;

    public GUIListener(FuryMinions plugin) {
        this.plugin = plugin;
        this.minionGUI = new MinionGUI(plugin);
        this.shopGUI = new ShopGUI(plugin);
        this.woodSelectorGUI = new WoodSelectorGUI(plugin);
        this.openMinionGuis = new HashMap<>();
        this.openWoodSelectorGuis = new HashMap<>();
        this.openShopGuis = new HashMap<>();
    }

    /**
     * Opens the minion management GUI for the given player and registers the session.
     *
     * @param player the player opening the GUI
     * @param minion the minion being managed
     */
    public void openMinionGUI(Player player, Minion minion) {
        minionGUI.openGUI(player, minion);
        openMinionGuis.put(player.getUniqueId(), minion);
    }

    /**
     * Opens the wood type selector GUI for a TREE_FARMER minion.
     *
     * @param player the player opening the GUI
     * @param minion the TREE_FARMER minion whose log type is being configured
     */
    public void openWoodSelectorGUI(Player player, Minion minion) {
        woodSelectorGUI.openGUI(player, minion);
        openWoodSelectorGuis.put(player.getUniqueId(), minion);
    }

    /**
     * Opens the minion shop for the given player.
     *
     * @param player the player opening the shop
     */
    public void openShopGUI(Player player) {
        shopGUI.openShop(player);
        openShopGuis.put(player.getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        UUID playerUuid = player.getUniqueId();
        String inventoryTitle = event.getView().getTitle();

        boolean isMinionGUI = inventoryTitle.contains("Zarządzanie Minionem");
        boolean isWoodSelectorGUI = inventoryTitle.contains("Wybór Drewna");
        boolean isShopGUI = inventoryTitle.contains("Sklep z Minionami");

        if (openMinionGuis.containsKey(playerUuid) || isMinionGUI) {
            event.setCancelled(true);
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
                return;
            }
            Minion minion = openMinionGuis.get(playerUuid);
            int slot = event.getSlot();

            switch (slot) {
                case 38:
                    if (minion != null) minionGUI.handleUpgrade(player, minion);
                    break;
                case 40:
                    if (minion != null) minionGUI.handleCollect(player, minion);
                    break;
                case 42:
                    if (minion != null) minionGUI.handleRemove(player, minion);
                    break;
                case 44:
                    if (minion != null && minion.getType().getEffectiveBehavior() == pl.furycraft.furyminions.model.MinionBehavior.TREE_FARMER) {
                        openWoodSelectorGUI(player, minion);
                    }
                    break;
                case 48:
                    if (minion != null && supportsFixedFacing(minion)) minionGUI.handleRotate(player, minion, false);
                    break;
                case 50:
                    if (minion != null && supportsFixedFacing(minion)) minionGUI.handleRotate(player, minion, true);
                    break;
            }
        } else if (openWoodSelectorGuis.containsKey(playerUuid) || isWoodSelectorGUI) {
            event.setCancelled(true);
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
                return;
            }
            Minion minion = openWoodSelectorGuis.get(playerUuid);
            if (minion != null) {
                woodSelectorGUI.handleSelect(player, minion, event.getSlot());
            }
        } else if (openShopGuis.containsKey(playerUuid) || isShopGUI) {
            event.setCancelled(true);
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
                return;
            }
            if (event.getCurrentItem() != null) {
                shopGUI.handlePurchase(player, event.getCurrentItem());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        openMinionGuis.remove(player.getUniqueId());
        openWoodSelectorGuis.remove(player.getUniqueId());
        openShopGuis.remove(player.getUniqueId());
    }

    /**
     * Returns {@code true} for minion types that support a player-configured static facing direction.
     * MINER, TREE_FARMER, and FISHER rotate dynamically and do not expose rotation controls.
     */
    private boolean supportsFixedFacing(Minion minion) {
        return switch (minion.getType().getEffectiveBehavior()) {
            case MINER, TREE_FARMER, FISHER -> false;
            default -> true;
        };
    }
}
