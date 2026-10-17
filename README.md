# ⚡ FuryMinions

High-performance minion plugin for Paper servers. Automates resource gathering with zero gameplay lag, even with hundreds of active entities.

![Java 25](https://img.shields.io/badge/Java-25-orange?logo=openjdk)
![Paper API](https://img.shields.io/badge/Paper_API-26.2-blue)
![HikariCP](https://img.shields.io/badge/HikariCP-5.1-green)
![MariaDB](https://img.shields.io/badge/MariaDB-10%2B-lightblue?logo=mariadb)
![Gradle](https://img.shields.io/badge/Build-Gradle_9-darkgreen?logo=gradle)

---

## ⚡ Key Optimizations

- **Non-blocking Database (Async SQL)**: All MariaDB reads and writes run in the background via `CompletableFuture` and a tuned HikariCP connection pool, keeping the main game thread completely free.
- **In-Memory Chunk Cache**: Minion data is cached in a chunk-keyed map (`ConcurrentHashMap`). Loading a chunk retrieves minions instantly without hitting the database.
- **Lag-Free Scheduler**: Minions take turns acting (round-robin, capped at 20 actions/second) instead of ticking all at once, preventing TPS drops.
- **Low Memory Overhead (Zero-GC hot path)**: Common items, drops, and animation poses are reused static constants—no wasteful object creation every tick.
- **Fast Offline Calculation**: When a player logs in, offline resource generation is calculated instantly using a simple time-delta formula instead of replaying ticks.
- **Thread Safety**: World changes happen safely on the main thread, while data saving and cleanup run asynchronously in the background.

---

## 🏛 Project Architecture

```
FuryMinions/
├── database/    # MariaDB connection & async queries (HikariCP)
├── cache/       # In-memory spatial cache (World -> ChunkKey)
├── scheduler/   # Fair round-robin tick scheduler
├── manager/     # Armor stands, holograms & offline progression
├── model/       # Minion data models and behaviors (Miner, Farmer, etc.)
└── listeners/   # Chunk load/unload events & protection checks
```

---

## 🛠 Tech Stack

- **Platform**: Paper API 26.2 (Minecraft 1.21+) • Java 25
- **Storage**: MariaDB 10.5+ • HikariCP
- **Integrations**: Vault (Economy), SuperiorSkyblock2 (Island protection)

---

## 📦 Build

```bash
./gradlew shadowJar
```

Compiled jar will be located in `build/libs/`.
