package pl.furycraft.furyminions;

import lombok.Getter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import pl.furycraft.furyminions.command.MinionShopCommand;
import pl.furycraft.furyminions.config.ConfigManager;
import pl.furycraft.furyminions.database.DatabaseManager;
import pl.furycraft.furyminions.listener.BlockBreakListener;
import pl.furycraft.furyminions.listener.ChunkLoadListener;
import pl.furycraft.furyminions.listener.ChunkUnloadListener;
import pl.furycraft.furyminions.listener.GUIListener;
import pl.furycraft.furyminions.listener.IslandListener;
import pl.furycraft.furyminions.listener.MinionPlaceListener;
import pl.furycraft.furyminions.listener.MinionProtectionListener;
import pl.furycraft.furyminions.listener.PlayerQuitListener;
import pl.furycraft.furyminions.manager.ArmorStandManager;
import pl.furycraft.furyminions.manager.HologramManager;
import pl.furycraft.furyminions.manager.MinionKeys;
import pl.furycraft.furyminions.manager.MinionScheduler;
import pl.furycraft.furyminions.model.Minion;

import java.util.List;

/**
 * Main plugin class for FuryMinions.
 * Bootstraps all subsystems: database, config, managers, scheduler, listeners, and commands.
 */
@Getter
public final class FuryMinions extends JavaPlugin {

    private static FuryMinions instance;

    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private Economy economy;
    private ArmorStandManager armorStandManager;
    private MinionScheduler minionScheduler;
    private GUIListener guiListener;
    private HologramManager hologramManager;
    private pl.furycraft.furyminions.manager.MinionCacheManager minionCacheManager;
    private pl.furycraft.furyminions.manager.OfflineProgressionManager offlineProgressionManager;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("========================================");
        getLogger().info("  FuryMinions - Włączanie pluginu");
        getLogger().info("  Wersja: " + getDescription().getVersion());
        getLogger().info("  Paper API: 26.2");
        getLogger().info("  Java: 25");
        getLogger().info("========================================");

        try {
            setupEconomy();
            saveDefaultConfig();

            MinionKeys.initialize(this);

            configManager = new ConfigManager(this);
            configManager.loadConfig();

            databaseManager = new DatabaseManager(this);
            databaseManager.initialize();

            armorStandManager = new ArmorStandManager(this);
            armorStandManager.cleanupInvalidArmorStands();

            minionCacheManager = new pl.furycraft.furyminions.manager.MinionCacheManager(this);
            minionCacheManager.loadAllFromDatabase();

            offlineProgressionManager = new pl.furycraft.furyminions.manager.OfflineProgressionManager(this);

            hologramManager = new HologramManager(this);

            minionScheduler = new MinionScheduler(this);
            minionScheduler.start();

            guiListener = new GUIListener(this);

            registerCommands();
            registerListeners();

            getLogger().info("FuryMinions został poprawnie uruchomiony!");
        } catch (Exception e) {
            getLogger().severe("Błąd krytyczny podczas włączania pluginu: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("========================================");
        getLogger().info("  FuryMinions - Wyłączanie pluginu");
        getLogger().info("========================================");

        try {
            if (minionScheduler != null) {
                minionScheduler.stop();
            }

            if (hologramManager != null) {
                hologramManager.clearAllHolograms();
            }

            saveAllMinionsOnShutdown();

            if (databaseManager != null) {
                databaseManager.shutdown();
            }

            getLogger().info("Wszystkie dane zostały bezpiecznie zapisane.");
            getLogger().info("Wyłączono FuryMinions!");
        } catch (Exception e) {
            getLogger().severe("Błąd podczas wyłączania pluginu: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Synchronously persists all currently active minions before the server shuts down.
     * Uses {@code join()} intentionally to block until each save completes, guaranteeing no data loss.
     */
    private void saveAllMinionsOnShutdown() {
        getLogger().info("Zapisywanie wszystkich minionów przed wyłączeniem...");
        List<Minion> minions = new java.util.ArrayList<>(armorStandManager.getActiveMinionsList());

        for (Minion minion : minions) {
            databaseManager.saveMinionAsync(minion.toMinionData()).join();
        }

        getLogger().info("Zapisano " + minions.size() + " minionów.");
    }

    /**
     * Registers all plugin commands dynamically via the server's {@link org.bukkit.command.CommandMap}.
     * Direct CommandMap registration is used because commands are not declared in plugin.yml,
     * allowing full runtime control over aliases and descriptions.
     */
    private void registerCommands() {
        try {
            MinionShopCommand shopCommand = new MinionShopCommand(this);

            org.bukkit.command.Command command = new org.bukkit.command.Command("minionshop") {
                @Override
                public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                    return shopCommand.onCommand(sender, this, commandLabel, args);
                }
            };
            command.setDescription("Otwiera sklep z minionami");
            command.setUsage("/minionshop");
            command.setAliases(new java.util.ArrayList<>(java.util.List.of("ms")));

            org.bukkit.command.CommandMap commandMap = getServer().getCommandMap();
            commandMap.register("furyminions", command);

            pl.furycraft.furyminions.command.RemoveMinionCommand removeCommand = new pl.furycraft.furyminions.command.RemoveMinionCommand(this);
            org.bukkit.command.Command removeCmd = new org.bukkit.command.Command("removeminion") {
                @Override
                public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                    return removeCommand.onCommand(sender, this, commandLabel, args);
                }
            };
            removeCmd.setDescription("Usuwa miniony w promieniu");
            removeCmd.setUsage("/removeminion [promień]");
            commandMap.register("furyminions", removeCmd);

            getLogger().info("Zarejestrowano komendy pluginu.");
        } catch (Exception e) {
            getLogger().severe("Błąd podczas rejestracji komend: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Registers all Bukkit event listeners for the plugin lifecycle.
     */
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new ChunkLoadListener(this), this);
        getServer().getPluginManager().registerEvents(new ChunkUnloadListener(this), this);
        getServer().getPluginManager().registerEvents(new MinionProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new IslandListener(this), this);
        getServer().getPluginManager().registerEvents(guiListener, this);
        getServer().getPluginManager().registerEvents(new MinionPlaceListener(this), this);
        getServer().getPluginManager().registerEvents(new BlockBreakListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this), this);
        getLogger().info("Zarejestrowano listenery pluginu.");
    }

    /**
     * Hooks into the Vault economy service provider.
     *
     * @return {@code true} if economy was successfully set up, {@code false} otherwise
     */
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault plugin nie został znaleziony! Ekonomia nie będzie działać.");
            return false;
        }

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager()
                .getRegistration(Economy.class);

        if (rsp == null) {
            getLogger().warning("Nie znaleziono providera ekonomii Vault!");
            return false;
        }

        economy = rsp.getProvider();
        getLogger().info("Ekonomia Vault została poprawnie załadowana: " + economy.getName());
        return true;
    }

    /**
     * Returns the singleton plugin instance.
     *
     * @return the active {@link FuryMinions} instance
     */
    public static FuryMinions getInstance() {
        return instance;
    }

    public GUIListener getGuiListener() {
        return guiListener;
    }

    public pl.furycraft.furyminions.manager.MinionCacheManager getMinionCacheManager() {
        return minionCacheManager;
    }

    public pl.furycraft.furyminions.manager.OfflineProgressionManager getOfflineProgressionManager() {
        return offlineProgressionManager;
    }
}
