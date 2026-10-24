package ru.unknownsmp.core;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Управляет жизненным циклом модулей с изоляцией ошибок. */
public final class ModuleManager {
    public enum State { ACTIVE, FAILED, DISABLED }

    public record Status(Module module, State state, String error, long since) {}

    private final UnknownSMP plugin;
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Map<String, Status> status = new LinkedHashMap<>();

    public ModuleManager(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    public <T extends Module> T add(T m) {
        modules.put(m.id(), m);
        return m;
    }

    public void enableAll() {
        for (Module m : modules.values()) enable(m);
    }

    private void enable(Module m) {
        for (String dep : m.depends()) {
            if (!isActive(dep)) {
                fail(m, "Зависимость '" + dep + "' недоступна");
                return;
            }
        }
        try {
            m.enable();
            status.put(m.id(), new Status(m, State.ACTIVE, null, System.currentTimeMillis()));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Модуль " + m.id() + " не запустился", t);
            fail(m, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Переводит модуль в аварийное состояние: опасные операции модуля блокируются. */
    public void fail(Module m, String reason) {
        status.put(m.id(), new Status(m, State.FAILED, reason, System.currentTimeMillis()));
        plugin.getLogger().severe("[Модуль " + m.title() + "] ОТКЛЮЧЁН: " + reason);
        if (plugin.logs() != null) plugin.logs().sys("system", "module_fail", null, m.id() + ": " + reason);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (plugin.roles() != null && plugin.roles().has(p, "usmp.admin.monitor")) {
                    Msg.send(p, "module.failed", "&cМодуль &f{m}&c аварийно отключён: &7{r}", "m", m.title(), "r", reason);
                }
            }
        });
        // Зависимые модули тоже блокируются.
        for (Module other : modules.values()) {
            if (other == m) continue;
            for (String d : other.depends()) {
                if (d.equals(m.id()) && isActive(other.id())) {
                    try { other.disable(); } catch (Throwable ignored) {}
                    fail(other, "Зависимость '" + m.id() + "' отключена");
                }
            }
        }
    }

    public void fail(String id, String reason) {
        Module m = modules.get(id);
        if (m != null && isActive(id)) fail(m, reason);
    }

    public boolean isActive(String id) {
        Status s = status.get(id);
        return s != null && s.state == State.ACTIVE;
    }

    /** Проверка перед выполнением действия модуля. Сообщает игроку, если модуль недоступен. */
    public boolean require(CommandSender s, String id) {
        if (isActive(id)) return true;
        Module m = modules.get(id);
        Msg.error(s, "module.unavailable", "Функция временно недоступна (модуль {m} отключён). Администрация уведомлена.",
                "m", m == null ? id : m.title());
        return false;
    }

    public void disableAll() {
        List<Module> list = new ArrayList<>(modules.values());
        java.util.Collections.reverse(list);
        for (Module m : list) {
            if (!isActive(m.id())) continue;
            try { m.disable(); } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Ошибка при остановке модуля " + m.id(), t);
            }
            status.put(m.id(), new Status(m, State.DISABLED, null, System.currentTimeMillis()));
        }
    }

    public void reloadAll(CommandSender who) {
        for (Module m : modules.values()) {
            if (!isActive(m.id())) continue;
            try {
                m.reload();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Ошибка перезагрузки модуля " + m.id(), t);
                Msg.error(who, "reload.module-error", "Модуль {m}: ошибка перезагрузки — {e}. Используются прежние настройки.",
                        "m", m.title(), "e", t.getMessage());
            }
        }
    }

    public Map<String, Status> status() {
        Map<String, Status> out = new LinkedHashMap<>();
        for (Module m : modules.values()) {
            out.put(m.id(), status.getOrDefault(m.id(), new Status(m, State.DISABLED, "не запускался", 0)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(String id) {
        return (T) modules.get(id);
    }
}
