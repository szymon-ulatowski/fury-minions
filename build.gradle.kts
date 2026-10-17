plugins {
    id("java")
    id("com.gradleup.shadow") version "9.5.1"
    id("io.freefair.lombok") version "8.6"
}

group = "pl.furycraft"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "bg-repo"
        url = uri("https://repo.bg-software.com/repository/api/")
    }
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io/")
    }
    maven {
        name = "vault-repo"
        url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
    maven {
        name = "essentials-repo"
        url = uri("https://repo.essentialsx.net/releases/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    compileOnly("com.bgsoftware:SuperiorSkyblockAPI:2026.2")

    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit")
        exclude(group = "org.spigotmc")
    }
    compileOnly("net.essentialsx:EssentialsX:2.20.1") {
        exclude(group = "org.bukkit")
        exclude(group = "org.spigotmc")
    }

    compileOnly("com.zaxxer:HikariCP:5.1.0")
    compileOnly("com.github.ben-manes.caffeine:caffeine:3.1.8")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.5.1")

    compileOnly("org.yaml:snakeyaml:2.2")

    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

lombok {
    version.set("1.18.46")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.build {
    dependsOn("shadowJar")
}