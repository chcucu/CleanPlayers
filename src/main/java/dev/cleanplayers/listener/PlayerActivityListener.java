package dev.cleanplayers.listener;

import dev.cleanplayers.CleanPlayersPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Feeds the activity gate and records name/UUID/IP mappings. The pre-login event is the key
 * safety mechanism: it marks a UUID active before Paper loads the playerdata file.
 */
public final class PlayerActivityListener implements Listener {

    private final CleanPlayersPlugin plugin;

    public PlayerActivityListener(CleanPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            plugin.activityGate().joining(event.getUniqueId());
            plugin.ipStore().record(event.getUniqueId(), event.getName(), event.getAddress().getHostAddress());
        } else {
            plugin.activityGate().clear(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            plugin.activityGate().clear(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.activityGate().online(event.getPlayer().getUniqueId());
        plugin.ipStore().record(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                event.getPlayer().getAddress() == null ? null
                        : event.getPlayer().getAddress().getAddress().getHostAddress());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.activityGate().left(event.getPlayer().getUniqueId());
    }
}
