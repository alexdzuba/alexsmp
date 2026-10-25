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

    private static boolean hideConsoleLine(String m) {
        if (m == null) return false;
        Matcher im = ISSUED.matcher(m);
        if (im.matches()) return hideCommand(im.group(1), im.group(2));
        Matcher fm = FEEDBACK.matcher(m);
        if (fm.matches() && quietPlayer(fm.group(1))) {
            String t = fm.group(2);
            if (t.contains("Spectator")) return false;
            return t.contains("game mode") || t.startsWith("Gave") || t.startsWith("Given") || t.startsWith("Applied")
                    || t.startsWith("Summoned") || t.contains("experience") || t.startsWith("Enchanted");
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
