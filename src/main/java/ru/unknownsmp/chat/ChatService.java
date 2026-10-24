package ru.unknownsmp.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.unknownsmp.clans.Clan;
import ru.unknownsmp.core.Module;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.profile.Profile;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Чат: глобальный, локальный и клановый; формат с рангом, кланом и титулом; защита от флуда; мут. */
public final class ChatService implements Module, Listener {
    private final UnknownSMP plugin;
    private final Map<UUID, long[]> rate = new ConcurrentHashMap<>();
    private final Map<UUID, String[]> lastMsgs = new ConcurrentHashMap<>();
    private int localRadius;
    private long minInterval;

    public ChatService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "chat"; }
    @Override public String title() { return "Чат"; }
    @Override public String[] depends() { return new String[]{"profiles"}; }

    @Override
    public void enable() {
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().add("chat").player().perm("usmp.player.chat").module("chat").cat("Чат").usage("/chat <global|local|clan>")
                .desc("Режим чата (! — в глобальный, @ — в клановый)").exec((s, a) -> {
                    Player p = (Player) s;
                    Profile pr = plugin.profiles().get(p);
                    if (pr == null) return;
                    if (a.length == 0) {
                        Msg.send(p, "chat.mode", "&7Режим чата: &f{m}&7. Префиксы: &f! &7— глобальный, &f@ &7— клановый.", "m", modeName(pr.chatMode));
                        return;
                    }
                    String m = switch (a[0].toLowerCase(Locale.ROOT)) {
                        case "g", "global", "глобальный" -> "global";
                        case "l", "local", "локальный" -> "local";
                        case "c", "clan", "клан" -> "clan";
                        default -> null;
                    };
                    if (m == null) { Msg.error(p, "chat.usage", "Режимы: global, local, clan"); return; }
                    pr.chatMode = m;
                    pr.dirty = true;
                    Msg.ok(p, "chat.mode-set", "Режим чата: {m}", "m", modeName(m));
                }).tab((s, a) -> List.of("global", "local", "clan"));
    }

    @Override
    public void reload() {
        var c = plugin.configs().main();
        localRadius = c.getInt("chat.local-radius", 100);
        minInterval = c.getLong("chat.min-interval-ms", 1000);
    }

    public static String modeName(String m) {
        return switch (m == null ? "global" : m) {
            case "local" -> "локальный";
            case "clan" -> "клановый";
            default -> "глобальный";
        };
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void chat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Profile pr = plugin.profiles().get(p);
        if (pr == null) { e.setCancelled(true); return; }
        if (pr.isMuted()) {
            e.setCancelled(true);
            long left = pr.muteUntil < 0 ? -1 : pr.muteUntil - System.currentTimeMillis();
            Msg.error(p, "chat.muted", "Вы не можете писать в чат: {r} ({t})", "r", pr.muteReason == null ? "мут" : pr.muteReason,
                    "t", left < 0 ? "навсегда" : Text.duration(left));
            return;
        }
        String text = Text.plain(e.message()).trim();
        if (text.isEmpty()) { e.setCancelled(true); return; }
        boolean staff = plugin.roles().isStaff(p);
        long now = System.currentTimeMillis();
        long[] r = rate.computeIfAbsent(p.getUniqueId(), k -> new long[]{0});
        if (!staff && now - r[0] < minInterval) {
            e.setCancelled(true);
            Msg.error(p, "chat.too-fast", "Не так быстро.");
            return;
        }
        r[0] = now;
        String[] last = lastMsgs.computeIfAbsent(p.getUniqueId(), k -> new String[]{"", "", "0"});
        if (!staff && text.equalsIgnoreCase(last[0]) && text.equalsIgnoreCase(last[1]) && now - Long.parseLong(last[2]) < 15000) {
            e.setCancelled(true);
            Msg.error(p, "chat.repeat", "Не повторяйте одно и то же сообщение.");
            plugin.logs().suspicious(p, "chat_spam", text);
            return;
        }
        last[1] = last[0];
        last[0] = text;
        last[2] = String.valueOf(now);

        String mode = pr.chatMode == null ? "global" : pr.chatMode;
        if (text.startsWith("!") && text.length() > 1) { mode = "global"; text = text.substring(1).trim(); }
        else if (text.startsWith("@") && text.length() > 1) { mode = "clan"; text = text.substring(1).trim(); }
        boolean lobby = plugin.lobby() != null && plugin.lobby().isLobby(p.getWorld());
        if (lobby && mode.equals("local")) mode = "global";
        final String fmode = mode;
        final String ftext = text;
        if (mode.equals("clan")) {
            e.setCancelled(true);
            Tasks.sync(() -> plugin.clans().clanChat(p, ftext));
            return;
        }
        // Получатели.
        e.viewers().removeIf(a -> {
            if (!(a instanceof Player v)) return false;
            if (plugin.auth() != null && !plugin.auth().isAuthed(v)) return true;
            Profile vp = plugin.profiles().get(v);
            if (fmode.equals("local")) {
                if (!v.getWorld().equals(p.getWorld()) || v.getLocation().distanceSquared(p.getLocation()) > (double) localRadius * localRadius) return true;
                return vp != null && !vp.setting("chat_local") && !v.equals(p);
            }
            return vp != null && !vp.setting("chat_global") && !v.equals(p);
        });
        // Коротко: [Л] тир [КЛАН] Ник: текст. Подробности — во всплывающей подсказке на нике.
        String prefix = fmode.equals("local") ? Msg.t("chat.local-tag", "&8[&aЛ&8] ") : "";
        String tier = plugin.duels() == null ? "" : plugin.duels().tierTag(p);
        Clan cl = plugin.clans() == null ? null : plugin.clans().clanOf(p);
        String clan = cl == null ? "" : "&8[&d" + cl.tag + "&8] ";
        String role = plugin.roles().roleOf(p).id().equals("player") ? "" : "&c✦ ";
        String nameColor = plugin.display() == null ? "&f" : plugin.display().nameColor(p);
        Component name = Text.c(nameColor + p.getName());
        if (plugin.display() != null) name = name.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(plugin.display().card(p)));
        name = name.clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/msg " + p.getName() + " "));
        Component head = Text.c(prefix + role + tier + clan).append(name).append(Text.c("&8 » "));
        Component body = ru.unknownsmp.chat.ChatExtras.decorate(plugin, p, ftext, fmode.equals("local"));
        Component line = head.append(body);
        e.renderer(ChatRenderer.viewerUnaware((source, displayName, msg) -> line));
        if (fmode.equals("local") && e.viewers().stream().filter(a -> a instanceof Player).count() <= 1) {
            Tasks.sync(() -> Msg.actionBar(p, "&7Вас никто не услышал. &f!сообщение&7 — в глобальный чат"));
        }
        plugin.logs().log(p, "chat", fmode, null, ftext);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        rate.remove(e.getPlayer().getUniqueId());
        lastMsgs.remove(e.getPlayer().getUniqueId());
    }
}
