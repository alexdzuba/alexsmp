package ru.unknownsmp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сообщения плагина. Все строки можно переопределить в messages.yml.
 * Если ключа нет в файле, используется значение по умолчанию из кода, и ключ дописывается в файл,
 * чтобы владелец видел полный список доступных сообщений.
 */
public final class Msg {
    private Msg() {}

    private static YamlConfiguration file;
    private static File path;
    private static volatile boolean dirty;
    private static String prefix = "&8[&bALEX SMP&8] &7";
    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public static void load(File f) {
        path = f;
        file = new YamlConfiguration();
        try {
            if (f.exists()) file.loadFromString(Files.readString(f.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            UnknownSMP.get().getLogger().warning("Не удалось прочитать messages.yml: " + e.getMessage());
        }
        cache.clear();
        prefix = file.getString("prefix", prefix);
        if (prefix.contains("UNKNOWN") && !prefix.contains("ALEX")) { prefix = "&8[&bALEX SMP&8] &7"; file.set("prefix", prefix); dirty = true; }
        if (!file.contains("prefix")) { file.set("prefix", prefix); dirty = true; }
    }

    public static synchronized void saveIfDirty() {
        if (!dirty || file == null) return;
        dirty = false;
        try {
            Files.writeString(path.toPath(), file.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            UnknownSMP.get().getLogger().warning("Не удалось сохранить messages.yml: " + e.getMessage());
        }
    }

    public static String raw(String key, String def) {
        String v = cache.get(key);
        if (v != null) return v;
        synchronized (Msg.class) {
            if (file == null) return def;
            String s = file.getString(key);
            if (s == null) {
                file.set(key, def);
                dirty = true;
                s = def;
            }
            cache.put(key, s);
            return s;
        }
    }

    public static String t(String key, String def, Object... kv) {
        return Text.fill(raw(key, def), kv);
    }

    public static Component comp(String key, String def, Object... kv) {
        return Text.c(t(key, def, kv));
    }

    public static void send(CommandSender to, String key, String def, Object... kv) {
        if (to == null) return;
        to.sendMessage(Text.c(prefix + t(key, def, kv)));
    }

    /** Сообщение без префикса. */
    public static void plain(CommandSender to, String key, String def, Object... kv) {
        if (to == null) return;
        to.sendMessage(Text.c(t(key, def, kv)));
    }

    public static void raw(CommandSender to, String text) {
        if (to != null) to.sendMessage(Text.c(text));
    }

    public static void error(CommandSender to, String key, String def, Object... kv) {
        send(to, key, "&c" + def, kv);
        if (to instanceof Player p) sound(p, Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
    }

    public static void ok(CommandSender to, String key, String def, Object... kv) {
        send(to, key, "&a" + def, kv);
        if (to instanceof Player p) sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
    }

    public static void broadcast(String key, String def, Object... kv) {
        Component c = Text.c(prefix + t(key, def, kv));
        Bukkit.getConsoleSender().sendMessage(c);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (UnknownSMP.get().auth() != null && !UnknownSMP.get().auth().isAuthed(p)) continue;
            p.sendMessage(c);
        }
    }

    public static void title(Player p, String title, String sub, int fadeIn, int stay, int fadeOut) {
        p.showTitle(Title.title(Text.c(title), Text.c(sub),
                Title.Times.times(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    public static void actionBar(Player p, String text) {
        p.sendActionBar(Text.c(text));
    }

    /** Звук с учётом настроек игрока. */
    public static void sound(Player p, Sound s, float vol, float pitch) {
        var prof = UnknownSMP.get().profiles() == null ? null : UnknownSMP.get().profiles().get(p);
        if (prof != null && !prof.setting("sounds")) return;
        p.playSound(p.getLocation(), s, vol, pitch);
    }

    public static String prefix() { return prefix; }
}
