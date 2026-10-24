package ru.unknownsmp.chat;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import ru.unknownsmp.clans.Clan;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.events.GameEvent;
import ru.unknownsmp.profile.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Отображение: персональный scoreboard (боковая панель), TAB (шапка, подвал, имена с рангом и кланом),
 * титулы над головой (суффикс команды).
 */
public final class DisplayService implements Module, Listener {
    private final UnknownSMP plugin;
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();

    public DisplayService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "display"; }
    @Override public String title() { return "TAB и scoreboard"; }
    @Override public String[] depends() { return new String[]{"profiles"}; }

    @Override
    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.auth().onLogin(p -> Tasks.later(2, () -> {
            setup(p);
            for (Player o : Bukkit.getOnlinePlayers()) refresh(o);
        }));
        for (Player p : Bukkit.getOnlinePlayers()) setup(p);
        Tasks.timer(40, 40, this::tick);
    }

    @Override
    public void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        boards.clear();
    }

    private void setup(Player p) {
        if (!p.isOnline()) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        boards.put(p.getUniqueId(), sb);
        p.setScoreboard(sb);
        for (Player o : Bukkit.getOnlinePlayers()) applyTeam(sb, o);
        sidebar(p);
    }

    private static String teamName(Player p) {
        return "u" + Integer.toHexString(p.getUniqueId().hashCode());
    }

    private void applyTeam(Scoreboard sb, Player target) {
        if (!plugin.auth().isAuthed(target)) return;
        Profile pr = plugin.profiles().get(target);
        if (pr == null) return;
        String tn = teamName(target);
        Team t = sb.getTeam(tn);
        if (t == null) t = sb.registerNewTeam(tn);
        if (!t.hasEntry(target.getName())) t.addEntry(target.getName());
        Clan cl = plugin.clans() == null ? null : plugin.clans().clanOf(target);
        String tier = plugin.duels() == null ? "" : plugin.duels().tierTag(target);
        String role = plugin.roles().roleOf(target).id().equals("player") ? "" : "&c✦ ";
        t.prefix(Text.c(role + tier + (cl == null ? "" : "&8[&d" + cl.tag + "&8] ")));
        t.color(colorOf(nameColor(target)));
        String title = pr.title == null || plugin.progression() == null ? "" : " &8«" + plugin.progression().titleName(pr.title) + "&8»";
        t.suffix(Text.c(title));
    }

    /** Обновить отображение игрока у всех (смена клана, ранга, титула). */
    public void refresh(Player target) {
        if (target == null || !target.isOnline()) return;
        for (Scoreboard sb : boards.values()) applyTeam(sb, target);
        Profile pr = plugin.profiles().get(target);
        if (pr != null && plugin.auth().isAuthed(target)) {
            Clan cl = plugin.clans() == null ? null : plugin.clans().clanOf(target);
            String tier = plugin.duels() == null ? "" : plugin.duels().tierTag(target);
            target.playerListName(Text.c(tier + (cl == null ? "" : "&8[&d" + cl.tag + "&8] ") + nameColor(target) + target.getName()));
        }
    }

    /** Цвет ника: цвет ранга прогрессии (персонал — красный). */
    public String nameColor(Player p) {
        if (!plugin.roles().roleOf(p).id().equals("player")) return "&c";
        Profile pr = plugin.profiles().get(p);
        if (pr == null || plugin.progression() == null) return "&f";
        String rank = plugin.progression().rankName(pr.progRank);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&([0-9a-fA-F])").matcher(rank);
        return m.find() ? "&" + m.group(1) : "&f";
    }

    private static net.kyori.adventure.text.format.NamedTextColor colorOf(String code) {
        char c = code.length() > 1 ? Character.toLowerCase(code.charAt(1)) : 'f';
        return switch (c) {
            case '0' -> net.kyori.adventure.text.format.NamedTextColor.BLACK;
            case '1' -> net.kyori.adventure.text.format.NamedTextColor.DARK_BLUE;
            case '2' -> net.kyori.adventure.text.format.NamedTextColor.DARK_GREEN;
            case '3' -> net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA;
            case '4' -> net.kyori.adventure.text.format.NamedTextColor.DARK_RED;
            case '5' -> net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE;
            case '6' -> net.kyori.adventure.text.format.NamedTextColor.GOLD;
            case '7' -> net.kyori.adventure.text.format.NamedTextColor.GRAY;
            case '8' -> net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY;
            case '9' -> net.kyori.adventure.text.format.NamedTextColor.BLUE;
            case 'a' -> net.kyori.adventure.text.format.NamedTextColor.GREEN;
            case 'b' -> net.kyori.adventure.text.format.NamedTextColor.AQUA;
            case 'c' -> net.kyori.adventure.text.format.NamedTextColor.RED;
            case 'd' -> net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE;
            case 'e' -> net.kyori.adventure.text.format.NamedTextColor.YELLOW;
            default -> net.kyori.adventure.text.format.NamedTextColor.WHITE;
        };
    }

    /** Карточка игрока для подсказки при наведении на ник в чате. */
    public Component card(Player p) {
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return Text.c("&7" + p.getName());
        Clan cl = plugin.clans() == null ? null : plugin.clans().clanOf(p);
        StringBuilder sb = new StringBuilder();
        sb.append(nameColor(p)).append("&l").append(p.getName()).append("\n");
        if (!plugin.roles().roleOf(p).id().equals("player")) sb.append("&7Роль: ").append(plugin.roles().roleOf(p).display()).append("\n");
        sb.append("&7Ранг: ").append(plugin.progression().rankName(pr.progRank)).append("\n");
        if (pr.title != null) sb.append("&7Титул: ").append(plugin.progression().titleName(pr.title)).append("\n");
        sb.append("&7Уровень: &b").append(pr.level).append("  &7Репутация: &b").append(pr.reputation).append("\n");
        sb.append("&7Клан: ").append(cl == null ? "&8нет" : "&d[" + cl.tag + "] " + cl.name).append("\n");
        sb.append("&7Убийств: &f").append(pr.stat("kills")).append("  &7Смертей: &f").append(pr.stat("deaths")).append("\n");
        String tier = plugin.duels() == null ? null : plugin.duels().bestTier(p);
        sb.append("&7Тир дуэлей: ").append(tier == null ? "&8нет" : ru.unknownsmp.duels.DuelService.tierColor(tier) + tier).append("\n");
        sb.append("&8Нажмите, чтобы написать в личку");
        return Text.c(sb.toString());
    }

    private void tick() {
        int online = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.auth().isAuthed(p)) online++;
        double tps = Math.min(20, Bukkit.getTPS()[0]);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.auth().isAuthed(p)) continue;
            Component header = Text.c(plugin.configs().main().getString("tab.header", "&5&lALEX SMP\n&7Доверяй осторожно"));
            String mode = plugin.lobby().isLobby(p.getWorld()) ? "Лобби" : plugin.duels() != null && plugin.duels().isDuelWorld(p.getWorld()) ? "Дуэли" : "SMP";
            Component footer = Text.c("&7Онлайн: &f" + online + " &8| &7Режим: &f" + mode + " &8| &7TPS: &f" + String.format("%.1f", tps)
                    + "\n" + plugin.configs().main().getString("tab.footer", "&8/menu — меню сервера"));
            p.sendPlayerListHeaderAndFooter(header, footer);
            sidebar(p);
        }
    }

    private void sidebar(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        Profile pr = plugin.profiles().get(p);
        if (sb == null || pr == null) return;
        Objective old = sb.getObjective("usmp");
        if (!pr.setting("scoreboard") || !plugin.auth().isAuthed(p)) {
            if (old != null) old.unregister();
            return;
        }
        Objective o = old;
        if (o == null) {
            o = sb.registerNewObjective("usmp", Criteria.DUMMY, Text.c("&b&lALEX SMP"), RenderType.INTEGER);
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
        }
        List<String> lines = new ArrayList<>();
        Clan cl = plugin.clans() == null ? null : plugin.clans().clanOf(p);
        lines.add("&8" + Text.date(System.currentTimeMillis()));
        lines.add(" ");
        lines.add("&7Баланс: &6" + Text.money(pr.balance));
        lines.add("&7Ранг: " + plugin.progression().rankName(pr.progRank));
        lines.add("&7Уровень: &b" + pr.level + " &7Реп.: &b" + pr.reputation);
        lines.add("&7Клан: " + (cl == null ? "&8нет" : "&d[" + cl.tag + "]"));
        lines.add("&7Убийств: &f" + pr.stat("kills"));
        if (plugin.duels() != null) { String bt = plugin.duels().bestTier(p); if (bt != null) lines.add("&7Тир: " + ru.unknownsmp.duels.DuelService.tierColor(bt) + bt); }
        // Активное задание ближе всего к завершению.
        if (plugin.quests() != null) {
            var track = plugin.quests().tracked(p);
            if (track != null) {
                lines.add("  ");
                lines.add("&e✦ " + track[0]);
                lines.add("&7" + track[1]);
            }
        }
        if (plugin.combat() != null && plugin.combat().isTagged(p)) lines.add("&c⚔ В бою: " + Text.duration(plugin.combat().remaining(p)));
        GameEvent ev = plugin.events() == null ? null : plugin.events().active();
        if (ev != null) {
            lines.add("  ");
            lines.add("&d✦ " + ev.title());
            lines.add("&7" + ev.location().getBlockX() + ", " + ev.location().getBlockZ());
        }
        lines.add("   ");
        lines.add(plugin.configs().main().getString("scoreboard.footer", "&5unknown-smp"));
        // Перезаписываем строки по индексу (без мерцания).
        int n = lines.size();
        for (int i = 0; i < 15; i++) {
            String entry = "§" + Integer.toHexString(i) + "§r";
            if (i < n) {
                var score = o.getScore(entry);
                score.setScore(n - i);
                score.customName(Text.c(lines.get(i)));
            } else {
                sb.resetScores(entry);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        String tn = teamName(e.getPlayer());
        for (Scoreboard sb : boards.values()) {
            Team t = sb.getTeam(tn);
            if (t != null) t.unregister();
        }
    }
}
