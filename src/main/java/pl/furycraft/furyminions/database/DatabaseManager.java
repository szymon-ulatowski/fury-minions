package pl.furycraft.furyminions.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import pl.furycraft.furyminions.FuryMinions;
import pl.furycraft.furyminions.model.MinionData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manages all asynchronous persistence operations against a MariaDB database via HikariCP.
 * <p>
 * All public {@code *Async} methods offload I/O to a background thread pool,
 * returning {@link CompletableFuture} instances that callers can chain on the main thread.
 * This prevents any database latency from blocking the server tick.
 */
@Getter
public class DatabaseManager {

    private final FuryMinions plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(FuryMinions plugin) {
        this.plugin = plugin;
    }

    /**
     * Initialises the HikariCP connection pool and creates the schema if it does not exist.
     * Configuration values are read from {@code config.yml}; sensible defaults are provided.
     *
     * @throws RuntimeException if the database connection or table creation fails
     */
    public void initialize() {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(plugin.getConfig().getString("database.url", "jdbc:mariadb://localhost:3306/furyminions"));
        config.setUsername(plugin.getConfig().getString("database.username", "root"));
        config.setPassword(plugin.getConfig().getString("database.password", ""));
        config.setDriverClassName("org.mariadb.jdbc.Driver");

        config.setMaximumPoolSize(plugin.getConfig().getInt("database.pool-size", 10));
        config.setMinimumIdle(plugin.getConfig().getInt("database.min-idle", 2));
        config.setConnectionTimeout(plugin.getConfig().getLong("database.connection-timeout", 30000));
        config.setIdleTimeout(plugin.getConfig().getLong("database.idle-timeout", 600000));
        config.setMaxLifetime(plugin.getConfig().getLong("database.max-lifetime", 1800000));

        // Performance tuning for MariaDB – enables prepared statement caching and batching
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");

        try {
            dataSource = new HikariDataSource(config);
            createTables();
            plugin.getLogger().info("Połączenie z bazą danych MariaDB zostało pomyślnie zainicjalizowane.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Błąd podczas inicjalizacji połączenia z bazą danych: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Nie udało się zainicjalizować bazy danych", e);
        }
    }

    /**
     * Creates the {@code minions_data} table if absent and attempts to add the {@code extra_data}
     * column for schema migrations from older plugin versions. The {@code ALTER TABLE} failure is
     * intentionally swallowed when the column already exists.
     */
    private void createTables() throws SQLException {
        String createMinionsTable = """
            CREATE TABLE IF NOT EXISTS minions_data (
                minion_uuid VARCHAR(36) PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                type VARCHAR(64) NOT NULL,
                level INT NOT NULL DEFAULT 1,
                world VARCHAR(64) NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                pitch FLOAT NOT NULL,
                yaw FLOAT NOT NULL,
                facing_direction VARCHAR(16) NOT NULL,
                inventory_content TEXT NOT NULL,
                extra_data TEXT,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                INDEX idx_owner (owner_uuid),
                INDEX idx_location (world, x, y, z)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(createMinionsTable)) {
            statement.execute();

            try (PreparedStatement alterStmt = connection.prepareStatement("ALTER TABLE minions_data ADD COLUMN extra_data TEXT")) {
                alterStmt.execute();
            } catch (SQLException ignored) {
                // Column already exists – expected on any run after the first
            }

            plugin.getLogger().info("Tabela 'minions_data' została zweryfikowana/utworzona.");
        }
    }

    /**
     * Closes the HikariCP connection pool. Should be called on plugin shutdown.
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Pula połączeń HikariCP została zamknięta.");
        }
    }

    /**
     * Acquires a connection from the pool.
     *
     * @return an active {@link Connection}
     * @throws SQLException if the pool is closed or no connection is available
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Pula połączeń nie jest zainicjalizowana lub została zamknięta.");
        }
        return dataSource.getConnection();
    }

    /**
     * Persists a minion record asynchronously using an upsert statement.
     * Also updates the in-memory cache immediately so chunk-load lookups reflect the latest state.
     *
     * @param minionData the data to save
     * @return a future that completes when the write finishes
     */
    public CompletableFuture<Void> saveMinionAsync(MinionData minionData) {
        long currentTime = System.currentTimeMillis();
        minionData.setUpdatedAt(currentTime);

        if (plugin.getMinionCacheManager() != null) {
            plugin.getMinionCacheManager().addMinionToCache(minionData);
        }
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO minions_data (minion_uuid, owner_uuid, type, level, world, x, y, z, pitch, yaw, facing_direction, inventory_content, extra_data, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    type = VALUES(type),
                    level = VALUES(level),
                    world = VALUES(world),
                    x = VALUES(x),
                    y = VALUES(y),
                    z = VALUES(z),
                    pitch = VALUES(pitch),
                    yaw = VALUES(yaw),
                    facing_direction = VALUES(facing_direction),
                    inventory_content = VALUES(inventory_content),
                    extra_data = VALUES(extra_data),
                    updated_at = VALUES(updated_at)
            """;

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, minionData.getMinionUuid().toString());
                statement.setString(2, minionData.getOwnerUuid().toString());
                statement.setString(3, minionData.getType());
                statement.setInt(4, minionData.getLevel());
                statement.setString(5, minionData.getWorld());
                statement.setDouble(6, minionData.getX());
                statement.setDouble(7, minionData.getY());
                statement.setDouble(8, minionData.getZ());
                statement.setFloat(9, minionData.getPitch());
                statement.setFloat(10, minionData.getYaw());
                statement.setString(11, minionData.getFacingDirection());
                statement.setString(12, minionData.getInventoryContent());
                statement.setString(13, minionData.getExtraData());
                statement.setLong(14, currentTime);
                statement.setLong(15, currentTime);

                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Błąd podczas zapisywania miniona: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Loads a single minion record from the database by its UUID.
     *
     * @param minionUuid the UUID of the minion to load
     * @return a future resolving to the loaded {@link MinionData}, or {@code null} if not found
     */
    public CompletableFuture<MinionData> loadMinionAsync(UUID minionUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM minions_data WHERE minion_uuid = ?";

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, minionUuid.toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return buildMinionData(resultSet);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Błąd podczas ładowania miniona: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        });
    }

    /**
     * Loads all minion records from the database. Used during startup to populate the cache.
     *
     * @return a future resolving to the full list of stored {@link MinionData} records
     */
    public CompletableFuture<List<MinionData>> loadAllMinionsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            List<MinionData> minions = new ArrayList<>();
            String sql = "SELECT * FROM minions_data";

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {

                while (resultSet.next()) {
                    minions.add(buildMinionData(resultSet));
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Błąd podczas ładowania wszystkich minionów: " + e.getMessage());
                e.printStackTrace();
            }
            return minions;
        });
    }

    /**
     * Loads all minion records belonging to a specific player.
     *
     * @param ownerUuid the UUID of the player whose minions to load
     * @return a future resolving to the player's list of {@link MinionData} records
     */
    public CompletableFuture<List<MinionData>> loadMinionsByOwnerAsync(UUID ownerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<MinionData> minions = new ArrayList<>();
            String sql = "SELECT * FROM minions_data WHERE owner_uuid = ?";

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, ownerUuid.toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        minions.add(buildMinionData(resultSet));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Błąd podczas ładowania minionów gracza: " + e.getMessage());
                e.printStackTrace();
            }
            return minions;
        });
    }

    /**
     * Deletes a single minion record from the database and evicts it from the in-memory cache.
     *
     * @param minionUuid the UUID of the minion to delete
     * @return a future that completes when the deletion finishes
     */
    public CompletableFuture<Void> deleteMinionAsync(UUID minionUuid) {
        if (plugin.getMinionCacheManager() != null) {
            plugin.getMinionCacheManager().removeMinionFromCache(minionUuid);
        }
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM minions_data WHERE minion_uuid = ?";

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, minionUuid.toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Błąd podczas usuwania miniona: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Deletes all minion records owned by the given player (e.g. on island disband).
     *
     * @param ownerUuid the UUID of the player whose minions should be deleted
     * @return a future that completes when the deletion finishes
     */
    public CompletableFuture<Void> deleteMinionsByOwnerAsync(UUID ownerUuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM minions_data WHERE owner_uuid = ?";

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, ownerUuid.toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Błąd podczas usuwania minionów gracza: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Checks whether a minion already exists at the exact given coordinates.
     * Used to prevent double-placement at the same location.
     *
     * @param world the world name
     * @param x     X coordinate
     * @param y     Y coordinate
     * @param z     Z coordinate
     * @return a future resolving to {@code true} if a minion is registered at that position
     */
    public CompletableFuture<Boolean> minionExistsAtLocationAsync(String world, double x, double y, double z) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM minions_data WHERE world = ? AND x = ? AND y = ? AND z = ?";

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, world);
                statement.setDouble(2, x);
                statement.setDouble(3, y);
                statement.setDouble(4, z);

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1) > 0;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Błąd podczas sprawdzania istnienia miniona: " + e.getMessage());
                e.printStackTrace();
            }
            return false;
        });
    }

    /**
     * Constructs a {@link MinionData} instance from the current row of the given {@link ResultSet}.
     */
    private MinionData buildMinionData(ResultSet rs) throws SQLException {
        return MinionData.builder()
            .minionUuid(UUID.fromString(rs.getString("minion_uuid")))
            .ownerUuid(UUID.fromString(rs.getString("owner_uuid")))
            .type(rs.getString("type"))
            .level(rs.getInt("level"))
            .world(rs.getString("world"))
            .x(rs.getDouble("x"))
            .y(rs.getDouble("y"))
            .z(rs.getDouble("z"))
            .pitch(rs.getFloat("pitch"))
            .yaw(rs.getFloat("yaw"))
            .facingDirection(rs.getString("facing_direction"))
            .inventoryContent(rs.getString("inventory_content"))
            .extraData(rs.getString("extra_data"))
            .updatedAt(rs.getLong("updated_at"))
            .build();
    }
}
