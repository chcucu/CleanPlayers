package dev.cleanplayers.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/** All on-disk locations used by CleanPlayers. */
public final class PluginPaths {

    private final Path serverRoot;
    private final Path dataFolder;
    private final Path backups;
    private final Path data;
    private final Path logs;
    private final Path protectFile;
    private final Path ipFile;
    private final Path userCacheFile;

    public PluginPaths(JavaPlugin plugin) {
        this.serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        this.dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        this.backups = dataFolder.resolve("backups");
        this.data = dataFolder.resolve("data");
        this.logs = dataFolder.resolve("logs");
        this.protectFile = data.resolve("protect.yml");
        this.ipFile = data.resolve("player-ips.json");
        this.userCacheFile = serverRoot.resolve("usercache.json");
    }

    public Path serverRoot() {
        return serverRoot;
    }

    public Path dataFolder() {
        return dataFolder;
    }

    public Path backups() {
        return backups;
    }

    public Path data() {
        return data;
    }

    public Path logs() {
        return logs;
    }

    public Path protectFile() {
        return protectFile;
    }

    public Path ipFile() {
        return ipFile;
    }

    public Path userCacheFile() {
        return userCacheFile;
    }

    public Path worldDirectory(String world) {
        return serverRoot.resolve(world);
    }

    public Path rollbackRoot() {
        return backups.resolve("_restore-rollback");
    }
}
