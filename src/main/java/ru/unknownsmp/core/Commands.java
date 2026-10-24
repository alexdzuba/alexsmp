package ru.unknownsmp.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Регистрация команд: проверка авторизации, модуля, прав и частоты вызова — на стороне сервера. */
public final class Commands implements CommandExecutor, TabCompleter {
    public interface Exec { void run(CommandSender s, String[] args); }
    public interface Tab { List<String> tab(CommandSender s, String[] args); }

    public static final class Cmd {
        final String name;
        String perm = "";
        String module;
        boolean playerOnly;
        boolean unauthed;
        String usage = "";
        String desc = "";
        String category = "Прочее";
        long cooldownMs;
        boolean hidden;
        Exec exec;
        Tab tab;

        Cmd(String name) { this.name = name; }

        public Cmd perm(String p) { perm = p; return this; }
        public Cmd module(String m) { module = m; return this; }
        public Cmd player() { playerOnly = true; return this; }
        public Cmd unauthed() { unauthed = true; return this; }
        public Cmd usage(String u) { usage = u; return this; }
        public Cmd desc(String d) { desc = d; return this; }
        public Cmd cat(String c) { category = c; return this; }
        public Cmd cooldown(long ms) { cooldownMs = ms; return this; }
        public Cmd hidden() { hidden = true; return this; }
        public Cmd exec(Exec e) { exec = e; return this; }
        public Cmd tab(Tab t) { tab = t; return this; }

        public String name() { return name; }
        public String perm() { return perm; }
        public String usage() { return usage; }
        public String desc() { return desc; }
        public String category() { return category; }
        public boolean hiddenCmd() { return hidden; }
        public String module() { return module; }
    }

    private final UnknownSMP plugin;
    private final Map<String, Cmd> cmds = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> rate = new ConcurrentHashMap<>();

    public Commands(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    public Cmd add(String name) {
        Cmd c = new Cmd(name);
        cmds.put(name, c);
        PluginCommand pc = plugin.getCommand(name);
        if (pc == null) {
            plugin.getLogger().warning("Команда /" + name + " не объявлена в plugin.yml");
        } else {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        return c;
    }

    public Cmd get(String name) { return cmds.get(name); }

    public java.util.Collection<Cmd> all() { return cmds.values(); }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Cmd c = cmds.get(command.getName().toLowerCase(Locale.ROOT));
        if (c == null || c.exec == null) {
            Msg.error(s, "cmd.unavailable", "Команда недоступна.");
            return true;
        }
        if (s instanceof Player p) {
            if (!c.unauthed && plugin.auth() != null && !plugin.auth().isAuthed(p)) {
                Msg.error(s, "auth.need-login", "Сначала войдите: /login <пароль>");
                return true;
            }
            // Общий лимит частоты: не более 8 команд за 2 секунды.
            long now = System.currentTimeMillis();
            long[] r = rate.computeIfAbsent(p.getUniqueId(), k -> new long[]{now, 0});
            if (now - r[0] > 2000) { r[0] = now; r[1] = 0; }
            if (++r[1] > 8) {
                Msg.error(s, "cmd.rate", "Слишком часто. Подождите немного.");
                if (r[1] == 9 && plugin.logs() != null) plugin.logs().suspicious(p, "command_flood", "/" + label);
                return true;
            }
        } else if (c.playerOnly) {
            Msg.error(s, "cmd.player-only", "Команда доступна только игрокам.");
            return true;
        }
        if (c.module != null && !plugin.modules().require(s, c.module)) return true;
        if (!plugin.roles().check(s, c.perm)) return true;
        if (c.cooldownMs > 0 && s instanceof Player p && !plugin.roles().has(p, "usmp.admin.bypass-cooldown")) {
            Map<String, Long> m = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
            Long until = m.get(c.name);
            long now = System.currentTimeMillis();
            if (until != null && until > now) {
                Msg.error(s, "cmd.cooldown", "Подождите ещё {t}.", "t", Text.duration(until - now));
                return true;
            }
            m.put(c.name, now + c.cooldownMs);
        }
        try {
            c.exec.run(s, args);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка команды /" + label, t);
            if (plugin.monitor() != null) plugin.monitor().error("command:" + c.name, t);
            Msg.error(s, "cmd.error", "Внутренняя ошибка команды. Операция не выполнена; администрация уведомлена.");
        }
        return true;
    }

    /** Сбросить кулдаун (например, если команда фактически не выполнилась). */
    public void resetCooldown(Player p, String cmd) {
        Map<String, Long> m = cooldowns.get(p.getUniqueId());
        if (m != null) m.remove(cmd);
    }

    public void setCooldown(Player p, String cmd, long ms) {
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>()).put(cmd, System.currentTimeMillis() + ms);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Cmd c = cmds.get(command.getName().toLowerCase(Locale.ROOT));
        if (c == null) return List.of();
        if (s instanceof Player p && !c.unauthed && plugin.auth() != null && !plugin.auth().isAuthed(p)) return List.of();
        if (!plugin.roles().has(s, c.perm)) return List.of();
        List<String> out;
        if (c.tab != null) out = c.tab.tab(s, args);
        else out = players(args.length == 0 ? "" : args[args.length - 1]);
        if (out == null) return List.of();
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> f = new ArrayList<>();
        for (String o : out) if (o.toLowerCase(Locale.ROOT).startsWith(last)) f.add(o);
        return f;
    }

    public static List<String> players(String prefix) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (UnknownSMP.get().auth() != null && !UnknownSMP.get().auth().isAuthed(p)) continue;
            out.add(p.getName());
        }
        return out;
    }

    public static List<String> of(String... opts) { return List.of(opts); }

    public void forget(UUID u) {
        cooldowns.remove(u);
        rate.remove(u);
    }
}
