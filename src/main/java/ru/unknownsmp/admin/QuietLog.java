package ru.unknownsmp.admin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Тихие команды: команды режима игры, выдачи предметов и денег от выбранных игроков не попадают
 * ни в консоль сервера (и latest.log), ни в журнал плагина. Режим наблюдателя остаётся в логах.
 */
public final class QuietLog extends AbstractFilter {
    private static final Pattern ISSUED = Pattern.compile("^(\\S+) issued server command: (.*)$", Pattern.DOTALL);
    private static final Pattern FEEDBACK = Pattern.compile("^\\[(\\S+): (.*)\\]$", Pattern.DOTALL);

    private static volatile boolean enabled;
    private static volatile Set<String> players = Set.of();
    private static volatile Set<String> commands = Set.of();
    private static volatile Set<String> usmpSubs = Set.of();
    private static boolean installed;

    private QuietLog() {}

    public static synchronized void configure(YamlConfiguration c) {
        enabled = c.getBoolean("quiet-commands.enabled", true);
        players = lower(c.getStringList("quiet-commands.players"));
        commands = lower(c.getStringList("quiet-commands.commands"));
        usmpSubs = lower(c.getStringList("quiet-commands.usmp-subcommands"));
        if (!installed) {
            try {
                ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addFilter(new QuietLog());
                installed = true;
            } catch (Throwable ignored) {
                // Нестандартное ядро без log4j-core: консоль не фильтруем, журнал плагина — фильтруем.
            }
        }
    }

    public static void shutdown() { enabled = false; }

    public static Set<String> players() { return players; }

    /**
     * Отключает на один тик правило logAdminCommands, чтобы ванильное «[ник: Set own game mode…]» не ушло
     * ни в консоль, ни другим операторам в чат. Вызывается перед выполнением тихой команды.
     */
    @SuppressWarnings({"unchecked", "deprecation"})
    public static void muteAdminBroadcast() {
        org.bukkit.GameRule<Boolean> rule = null;
        for (String n : new String[]{"logAdminCommands", "log_admin_commands", "minecraft:log_admin_commands"}) {
            try {
                org.bukkit.GameRule<?> g = org.bukkit.GameRule.getByName(n);
                if (g != null && g.getType() == Boolean.class) { rule = (org.bukkit.GameRule<Boolean>) g; break; }
            } catch (Throwable ignored) {}
        }
        if (rule == null) return;
        final org.bukkit.GameRule<Boolean> r = rule;
        java.util.List<org.bukkit.World> changed = new java.util.ArrayList<>();
        for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
            if (Boolean.TRUE.equals(w.getGameRuleValue(r))) { w.setGameRule(r, false); changed.add(w); }
        }
        if (!changed.isEmpty()) ru.unknownsmp.core.Tasks.later(1, () -> { for (org.bukkit.World w : changed) w.setGameRule(r, true); });
    }

    private static Set<String> lower(List<String> in) {
        Set<String> s = new HashSet<>();
        for (String x : in) if (x != null && !x.isBlank()) s.add(x.trim().toLowerCase(Locale.ROOT));
        return Set.copyOf(s);
    }

    public static boolean quietPlayer(String name) {
        return enabled && name != null && players.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Скрыть ли команду игрока (строка с «/» или без). */
    public static boolean hideCommand(String player, String line) {
        if (!quietPlayer(player) || line == null) return false;
        String[] a = line.trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT).split("\\s+");
        if (a.length == 0 || a[0].isEmpty()) return false;
        String cmd = a[0].contains(":") ? a[0].substring(a[0].indexOf(':') + 1) : a[0];
        if (cmd.equals("usmp") || cmd.equals("unknownsmp")) {
            if (a.length < 2 || (!usmpSubs.contains(a[1]) && !a[1].equals("god"))) return false;
            if (a[1].equals("gm")) return a.length >= 3 && Set.of("0", "s", "1", "c", "2", "a").contains(a[2]);
            return true;
        }
        if (!commands.contains(cmd)) return false;
        return !spectator(cmd, a);
    }

    private static boolean spectator(String cmd, String[] a) {
        if (cmd.equals("gmsp") || cmd.equals("spectator") || cmd.equals("spec")) return true;
        if (cmd.equals("gamemode") || cmd.equals("gm")) {
            for (int i = 1; i < a.length; i++) if (a[i].equals("spectator") || a[i].equals("sp") || a[i].equals("3")) return true;
        }
        return false;
    }

    /** Скрыть ли запись журнала плагина. */
    public static boolean hideAction(String name, String category, String action, String details) {
        if (!enabled) return false;
        if ("command".equals(category)) return hideCommand(name, details);
        if (!"admin".equals(category) || action == null) return false;
        boolean cheat = (action.equals("gamemode") && !"SPECTATOR".equals(details))
                || action.startsWith("money_") || action.startsWith("give_");
        if (!cheat) return false;
        if (quietPlayer(name)) return true;
        // Команда из консоли на скрытого игрока (например, выдача денег себе).
        if (("#system".equals(name) || name == null) && details != null) {
            String target = details.split(" ")[0];
            return quietPlayer(target);
        }
        return false;
    }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]|§.");
    /** Префикс строки лог-файла: «[20:09:57] [Server thread/INFO]: » или «[20:09:57 INFO]: ». */
    private static final Pattern FILE_PREFIX = Pattern.compile("^\\[[^\\]]*\\](?: \\[[^\\]]*\\])?: ");
    private static final Pattern CHEAT_FEEDBACK = Pattern.compile(
            "(?i).*(game ?mode|gave|given|applied|summoned|experience|enchant|effect|teleported|filled|changed the block|set the weather|set the time).*");

    /** Строка лог-файла (с меткой времени) — скрыть ли её. */
    public static boolean hideFileLine(String line) {
        if (line == null) return false;
        return hideConsoleLine(FILE_PREFIX.matcher(ANSI.matcher(line).replaceAll("")).replaceFirst(""));
    }

    public static boolean hideConsoleLine(String raw) {
        if (raw == null || !enabled) return false;
        // Paper пишет сообщения с цветами (ANSI) — без очистки строки не совпадали бы с шаблонами.
        String m = ANSI.matcher(raw).replaceAll("").trim();
        Matcher im = ISSUED.matcher(m);
        if (im.matches()) return hideCommand(im.group(1), im.group(2));
        Matcher fm = FEEDBACK.matcher(m);
        if (fm.matches()) {
            String who = fm.group(1), t = fm.group(2);
            if (t.contains("Spectator")) return false;
            // Команда самого игрока или команда из консоли/RCON, затрагивающая его.
            boolean mine = quietPlayer(who);
            if (!mine && (who.equals("Server") || who.equals("Rcon"))) {
                for (String pl : players) if (t.toLowerCase(Locale.ROOT).contains(pl)) { mine = true; break; }
            }
            return mine && CHEAT_FEEDBACK.matcher(t).matches();
        }
        return false;
    }

    @Override
    public Result filter(LogEvent event) {
        if (!enabled || event == null || event.getMessage() == null) return Result.NEUTRAL;
        try {
            return hideConsoleLine(event.getMessage().getFormattedMessage()) ? Result.DENY : Result.NEUTRAL;
        } catch (Throwable t) {
            return Result.NEUTRAL;
        }
    }
}
