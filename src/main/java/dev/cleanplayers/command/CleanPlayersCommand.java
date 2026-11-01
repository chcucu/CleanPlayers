package dev.cleanplayers.command;

import dev.cleanplayers.CleanPlayersPlugin;
import dev.cleanplayers.clean.BackupService;
import dev.cleanplayers.data.ProtectionStore;
import dev.cleanplayers.util.TimeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CleanPlayersCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[CleanPlayers] ";

    private final CleanPlayersPlugin plugin;

    public CleanPlayersCommand(CleanPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "dryrun", "dry-run" -> {
                if (!require(sender, "cleanplayers.use")) {
                    return true;
                }
                plugin.cleanupService().runAsync(true, sender);
            }
            case "run" -> {
                if (!require(sender, "cleanplayers.use")) {
                    return true;
                }
                plugin.cleanupService().runAsync(false, sender);
            }
            case "restore" -> {
                if (!require(sender, "cleanplayers.restore")) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(PREFIX + "Usage: /cleanplayers restore <player name or uuid>");
                    return true;
                }
                plugin.restoreService().restoreAsync(args[1], sender);
            }
            case "protect" -> handleProtect(sender, args);
            case "backup" -> handleBackup(sender, args);
            case "reload" -> {
                if (!require(sender, "cleanplayers.reload")) {
                    return true;
                }
                plugin.reload(sender);
            }
            default -> sender.sendMessage(PREFIX + "Unknown subcommand. Use /cleanplayers help.");
        }
        return true;
    }

    private void handleProtect(CommandSender sender, String[] args) {
        if (!require(sender, "cleanplayers.protect")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Usage: /cleanplayers protect <add|remove|list> [player|uuid|ip]");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 3) {
                    sender.sendMessage(PREFIX + "Usage: /cleanplayers protect add <player name|uuid|ip>");
                    return;
                }
                try {
                    ProtectionStore.EntryType type = plugin.protectionStore().add(args[2]);
                    String label = switch (type) {
                        case NAME -> "player name";
                        case UUID -> "uuid";
                        case IP -> "ip";
                    };
                    sender.sendMessage(PREFIX + "Added " + label + " '" + args[2] + "' to the protection list.");
                    plugin.saveProtectionAsync();
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage(PREFIX + "Could not add '" + args[2] + "': " + ex.getMessage());
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(PREFIX + "Usage: /cleanplayers protect remove <player name|uuid|ip>");
                    return;
                }
                boolean removed;
                try {
                    removed = plugin.protectionStore().remove(args[2]);
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage(PREFIX + "Invalid value: " + ex.getMessage());
                    return;
                }
                sender.sendMessage(PREFIX + (removed
                        ? "Removed '" + args[2] + "' from the protection list."
                        : "'" + args[2] + "' was not in the protection list."));
                if (removed) {
                    plugin.saveProtectionAsync();
                }
            }
            case "list" -> listProtection(sender);
            default -> sender.sendMessage(PREFIX + "Usage: /cleanplayers protect <add|remove|list> [player|uuid|ip]");
        }
    }

    private void listProtection(CommandSender sender) {
        ProtectionStore.Snapshot snapshot = plugin.protectionStore().snapshot();
        sender.sendMessage(PREFIX + "Protection list: " + snapshot.names().size()
                + " name(s), " + snapshot.uuids().size() + " uuid(s), "
                + snapshot.ips().size() + " ip(s).");
        sendCapped(sender, "Names", snapshot.names(), 50);
        sendCapped(sender, "UUIDs", snapshot.uuids(), 50);
        sendCapped(sender, "IPs", snapshot.ips(), 50);
    }

    private void handleBackup(CommandSender sender, String[] args) {
        if (!require(sender, "cleanplayers.use")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Usage: /cleanplayers backup <list|purge>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<BackupService.BackupRunInfo> runs = plugin.backupService().listRuns(20);
                    List<String> lines = new ArrayList<>();
                    int keepDays = plugin.settings().backupKeepDays();
                    lines.add(PREFIX + "Backups (newest first, up to 20):");
                    if (runs.isEmpty()) {
                        lines.add("  (no backups yet)");
                    }
                    long now = System.currentTimeMillis();
                    for (BackupService.BackupRunInfo run : runs) {
                        long age = now - run.createdAtMillis(java.time.ZoneId.systemDefault());
                        boolean expired = keepDays > 0 && age > (long) keepDays * TimeUtil.MILLIS_PER_DAY;
                        lines.add("  - " + run.id() + ": " + run.players() + " player(s), "
                                + dev.cleanplayers.util.FileUtil.formatBytes(run.bytes())
                                + (expired ? " [expired]" : ""));
                    }
                    sendLater(sender, lines);
                });
            }
            case "purge" -> {
                if (!require(sender, "cleanplayers.backup.purge")) {
                    return;
                }
                int keepDays = plugin.settings().backupKeepDays();
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    BackupService.PurgeResult result = plugin.backupService()
                            .purgeExpired(keepDays);
                    List<String> lines = new ArrayList<>();
                    if (keepDays <= 0) {
                        lines.add(PREFIX + "backup-keep-days is 0, backups are kept forever.");
                    } else {
                        lines.add(PREFIX + "Purged " + result.runsDeleted() + " backup run(s), freed "
                                + dev.cleanplayers.util.FileUtil.formatBytes(result.bytesFreed()) + ".");
                    }
                    sendLater(sender, lines);
                });
            }
            default -> sender.sendMessage(PREFIX + "Usage: /cleanplayers backup <list|purge>");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "Offline player data cleaner.");
        sender.sendMessage("/cleanplayers dryrun - simulate a cleanup and list affected players/files");
        sender.sendMessage("/cleanplayers run - execute a real cleanup (backup first)");
        sender.sendMessage("/cleanplayers restore <name|uuid> - restore the latest backup of a player");
        sender.sendMessage("/cleanplayers protect add|remove|list <name|uuid|ip> - manage the protection list");
        sender.sendMessage("/cleanplayers backup list|purge - list or purge backups");
        sender.sendMessage("/cleanplayers reload - reload config.yml and the protection list");
        sender.sendMessage("Permissions: cleanplayers.use, .protect, .restore, .bypass, .admin");
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("cleanplayers.admin")) {
            return true;
        }
        sender.sendMessage(PREFIX + "You do not have permission: " + permission);
        return false;
    }

    private void sendCapped(CommandSender sender, String label, List<String> values, int cap) {
        if (values.isEmpty()) {
            return;
        }
        int shown = Math.min(cap, values.size());
        for (int i = 0; i < shown; i++) {
            sender.sendMessage("  " + label + ": " + values.get(i));
        }
        if (values.size() > shown) {
            sender.sendMessage("  ... and " + (values.size() - shown) + " more " + label.toLowerCase(Locale.ROOT));
        }
    }

    private void sendLater(CommandSender sender, List<String> lines) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (String line : lines) {
                sender.sendMessage(line);
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("dryrun", "run", "restore", "protect", "backup", "reload", "help"),
                    args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "protect" -> filter(List.of("add", "remove", "list"), args[1]);
                case "backup" -> filter(List.of("list", "purge"), args[1]);
                case "restore" -> filter(knownNames(true), args[1]);
                default -> Collections.emptyList();
            };
        }
        if (args.length == 3 && "protect".equalsIgnoreCase(args[0])
                && "remove".equalsIgnoreCase(args[1])) {
            return filter(knownNames(false), args[2]);
        }
        return Collections.emptyList();
    }

    private List<String> knownNames(boolean includeOnline) {
        Set<String> names = new LinkedHashSet<>();
        var scan = plugin.lastScan();
        if (scan != null) {
            for (var cacheEntry : scan.userCacheEntries()) {
                if (cacheEntry.name() != null) {
                    names.add(cacheEntry.name());
                }
            }
            for (var entry : scan.entries().values()) {
                if (entry.name() != null) {
                    names.add(entry.name());
                }
            }
        }
        names.addAll(plugin.ipStore().knownNames());
        if (includeOnline) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                names.add(player.getName());
            }
        }
        ProtectionStore.Snapshot snapshot = plugin.protectionStore().snapshot();
        names.addAll(snapshot.names());
        List<String> result = new ArrayList<>(names);
        if (result.size() > 80) {
            return result.subList(0, 80);
        }
        return result;
    }

    private static List<String> filter(List<String> options, String token) {
        String lower = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
