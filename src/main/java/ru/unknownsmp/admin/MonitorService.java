package ru.unknownsmp.admin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.ModuleManager;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/** Мониторинг: ошибки, задержки, состояние модулей, SQL, фоновые задачи, резервные копии. */
public final class MonitorService implements Module {
    public record Alert(long at, String text) {}

    private final UnknownSMP plugin;
    private final Map<String, AtomicLong> errors = new ConcurrentHashMap<>();
    private final Deque<Alert> alerts = new ConcurrentLinkedDeque<>();
    private final AtomicLong suspicious = new AtomicLong();
    private volatile long lastBackupAt;
    private volatile boolean lastBackupOk = true;
    private volatile String lastBackupInfo = "ещё не выполнялось";
    private volatile double worstMspt;

    public MonitorService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "monitor"; }
    @Override public String title() { return "Мониторинг"; }

    @Override
    public void enable() {
        Tasks.timer(20 * 60, 20 * 60, () -> {
            double mspt = Bukkit.getAverageTickTime();
            if (mspt > worstMspt) worstMspt = mspt;
            int warn = plugin.configs().get("performance.yml").getInt("monitor.mspt-warning", 45);
            if (mspt > warn) alert("Высокая нагрузка: MSPT " + String.format("%.1f", mspt));
            int qWarn = plugin.configs().get("performance.yml").getInt("monitor.db-queue-warning", 500);
            if (plugin.db() != null && plugin.db().queued() > qWarn) alert("Очередь записи БД: " + plugin.db().queued());
        });
    }

    public void error(String source, Throwable t) {
        errors.computeIfAbsent(source, k -> new AtomicLong()).incrementAndGet();
        writeErrorFile(source, t);
    }

    private void writeErrorFile(String source, Throwable t) {
        try {
            File dir = new File(plugin.getDataFolder(), "logs");
            dir.mkdirs();
            File f = new File(dir, "errors-" + LocalDate.now(Text.zone()) + ".log");
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, StandardCharsets.UTF_8, true))) {
                pw.println("[" + Text.date(System.currentTimeMillis()) + "] " + source);
                t.printStackTrace(pw);
                pw.println();
            }
        } catch (Exception ignored) {}
    }

    public void alert(String text) {
        alerts.addFirst(new Alert(System.currentTimeMillis(), text));
        while (alerts.size() > 100) alerts.removeLast();
        plugin.getLogger().warning("[Мониторинг] " + text);
        Tasks.sync(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (plugin.roles() != null && plugin.roles().has(p, "usmp.admin.monitor")) {
                    Msg.send(p, "monitor.alert", "&c[Мониторинг] &f{t}", "t", text);
                }
            }
        });
    }

    public void suspicious() { suspicious.incrementAndGet(); }

    public void backupResult(boolean ok, String info) {
        lastBackupAt = System.currentTimeMillis();
        lastBackupOk = ok;
        lastBackupInfo = info;
        if (!ok) alert("Резервное копирование НЕ УДАЛОСЬ: " + info);
    }

    public List<Alert> alerts() { return new ArrayList<>(alerts); }

    public List<String> lines() {
        List<String> out = new ArrayList<>();
        double[] tps = Bukkit.getTPS();
        out.add("&7TPS: &f" + String.format("%.1f / %.1f / %.1f", tps[0], tps[1], tps[2]) + " &7MSPT: &f"
                + String.format("%.1f", Bukkit.getAverageTickTime()) + " &8(худший: " + String.format("%.1f", worstMspt) + ")");
        out.add("&7Игроков онлайн: &f" + Bukkit.getOnlinePlayers().size());
        Runtime rt = Runtime.getRuntime();
        out.add("&7Память: &f" + (rt.totalMemory() - rt.freeMemory()) / 1048576 + " / " + rt.maxMemory() / 1048576 + " МБ");
        if (plugin.db() != null) {
            out.add("&7БД: &f" + plugin.db().dialect() + " &7операций: &f" + plugin.db().ops() + " &7ср.: &f"
                    + String.format("%.2f", plugin.db().avgMs()) + " мс &7макс.: &f" + String.format("%.1f", plugin.db().maxMs()) + " мс");
            out.add("&7Очередь записи БД: &f" + plugin.db().queued() + " &7ошибок БД: &f" + plugin.db().failures()
                    + (plugin.db().lastError() == null ? "" : " &8(" + plugin.db().lastError() + ")"));
        }
        if (plugin.logs() != null) out.add("&7Очередь журналов: &f" + plugin.logs().queueSize());
        out.add("&7Резервная копия: " + (lastBackupOk ? "&a" : "&c") + lastBackupInfo + " &8(" + Text.date(lastBackupAt) + ")");
        out.add("&7Подозрительных действий: &f" + suspicious.get());
        long errs = errors.values().stream().mapToLong(AtomicLong::get).sum();
        out.add("&7Ошибок задач/команд: &f" + errs);
        for (var e : plugin.modules().status().entrySet()) {
            ModuleManager.Status s = e.getValue();
            String col = s.state() == ModuleManager.State.ACTIVE ? "&a" : s.state() == ModuleManager.State.FAILED ? "&c" : "&7";
            if (s.state() != ModuleManager.State.ACTIVE)
                out.add("  " + col + s.module().title() + ": " + s.state() + (s.error() == null ? "" : " &8— " + s.error()));
        }
        long active = plugin.modules().status().values().stream().filter(s -> s.state() == ModuleManager.State.ACTIVE).count();
        out.add("&7Модулей активно: &a" + active + "&7 из &f" + plugin.modules().status().size());
        return out;
    }

    public Map<String, AtomicLong> errors() { return errors; }
}
