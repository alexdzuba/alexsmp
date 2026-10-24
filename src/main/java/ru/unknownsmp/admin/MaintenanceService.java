package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.db.Database;

/**
 * Безопасный режим обслуживания: блокирует новые входы (кроме персонала), уведомляет игроков,
 * останавливает приём новых критических операций (рынок, банки), дожидается записи очереди БД,
 * и только затем (если настроено) отключает игроков.
 */
public final class MaintenanceService implements Module, Listener {
    private final UnknownSMP plugin;
    private volatile boolean on;
    private volatile String reason = "";

    public MaintenanceService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "maintenance"; }
    @Override public String title() { return "Режим обслуживания"; }
    @Override public String[] depends() { return new String[]{"database", "state"}; }

    @Override
    public void enable() {
        on = plugin.state().getBool("maintenance");
        reason = plugin.state().get("maintenance_reason", "");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean isOn() { return on; }

    /** Новые критические операции запрещены во время обслуживания. */
    public boolean blocksCritical() { return on; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void preLogin(AsyncPlayerPreLoginEvent e) {
        if (!on || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            String role = plugin.db().queryBlocking(c -> Database.strVal(c, "SELECT staff_role FROM players WHERE uuid=?", e.getUniqueId().toString()));
            if (role != null && plugin.roles().role(role).weight() >= plugin.roles().role("moderator").weight() && !role.equals("player")) return;
        } catch (Exception ignored) {}
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Text.c(Msg.t("maintenance.kick", "&eНа сервере идут технические работы.\n&7{r}\n&7Попробуйте позже.", "r", reason)));
    }

    public void enable(CommandSender who, String why, int kickAfterSec) {
        on = true;
        reason = why == null ? "" : why;
        plugin.state().set("maintenance", true);
        plugin.state().set("maintenance_reason", reason);
        Msg.broadcast("maintenance.on", "&e&lТехнические работы: &f{r}. &7Торговые операции приостановлены.", "r", reason.isEmpty() ? "обслуживание сервера" : reason);
        plugin.logs().log(who instanceof Player p ? p : null, "admin", "maintenance_on", null, reason);
        if (kickAfterSec <= 0) return;
        final int[] left = {kickAfterSec};
        final org.bukkit.scheduler.BukkitTask[] t = new org.bukkit.scheduler.BukkitTask[1];
        t[0] = Tasks.timer(20, 20, () -> {
            if (!on) { t[0].cancel(); return; }
            left[0]--;
            if (left[0] == 30 || left[0] == 10 || left[0] == 5)
                Msg.broadcast("maintenance.countdown", "&eОтключение для работ через {s} сек.", "s", left[0]);
            if (left[0] > 0) return;
            if (plugin.db().queued() > 0) return; // ждём завершения записи
            t[0].cancel();
            plugin.profiles().flushAll();
            Tasks.later(40, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (plugin.roles().isStaff(p)) continue;
                    p.kick(Text.c(Msg.t("maintenance.kick", "&eНа сервере идут технические работы.\n&7{r}\n&7Попробуйте позже.", "r", reason)));
                }
            });
        });
    }

    public void disable(CommandSender who) {
        on = false;
        plugin.state().set("maintenance", false);
        Msg.broadcast("maintenance.off", "&aТехнические работы завершены. Все функции снова доступны.");
        plugin.logs().log(who instanceof Player p ? p : null, "admin", "maintenance_off", null, "");
    }
}
