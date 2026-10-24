package ru.unknownsmp.events;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.UnknownSMP;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Определение AFK: игрок считается активным, если недавно поворачивал камеру, ломал/ставил блоки,
 * взаимодействовал, сражался или писал в чат. Простое смещение (вода, поршни) активностью не считается.
 */
public final class AfkTracker implements Module, Listener {
    private final UnknownSMP plugin;
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();
    private long afkMs;

    public AfkTracker(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "afk"; }
    @Override public String title() { return "Определение AFK"; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void reload() {
        afkMs = plugin.configs().get("events.yml").getLong("afk-seconds", 120) * 1000L;
    }

    public boolean isAfk(Player p) {
        Long l = last.get(p.getUniqueId());
        return l == null || System.currentTimeMillis() - l > afkMs;
    }

    public void active(Player p) {
        last.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch()) active(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR) public void brk(BlockBreakEvent e) { active(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void place(BlockPlaceEvent e) { active(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void interact(PlayerInteractEvent e) { active(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void chat(AsyncChatEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void hit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) active(p);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) { last.remove(e.getPlayer().getUniqueId()); }
}
