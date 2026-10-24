package ru.unknownsmp.core;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Модульная конфигурация. Каждый модуль читает свой файл.
 * При первом запуске файлы копируются из JAR; при обновлении в существующие файлы
 * дописываются только недостающие ключи (значения владельца не перезаписываются).
 */
public final class Configs {
    public static final String[] FILES = {
            "config.yml", "database.yml", "auth.yml", "economy.yml", "market.yml", "pvp.yml", "clans.yml",
            "items.yml", "events.yml", "structures.yml", "npcs.yml", "dialogues.yml", "quests.yml", "progression.yml",
            "permissions.yml", "logging.yml", "performance.yml", "duels.yml", "zhora.yml"
    };

    private final UnknownSMP plugin;
    private final Map<String, YamlConfiguration> configs = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private boolean firstRun;

    public Configs(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        problems.clear();
        File dir = plugin.getDataFolder();
        if (!dir.exists()) {
            firstRun = true;
            dir.mkdirs();
        }
        for (String name : FILES) load(name);
        Msg.load(new File(dir, "messages.yml"));
        validate();
    }

    public void load(String name) {
        File f = new File(plugin.getDataFolder(), name);
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) defaults.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            problems.add("Встроенный " + name + " повреждён: " + e.getMessage());
        }
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            if (!f.exists()) {
                if (name.equals("config.yml")) firstRun = true;
                try (InputStream in = plugin.getResource(name)) {
                    if (in != null) Files.write(f.toPath(), in.readAllBytes());
                }
            }
            if (f.exists()) cfg.loadFromString(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            // Обновление файла под новую версию плагина: старый сохраняется рядом, ставится новый.
            int want = defaults.getInt("config-version", 0);
            int have = cfg.getInt("config-version", 0);
            if (want > have && f.exists()) {
                File old = new File(plugin.getDataFolder(), name + ".old-v" + have + "-" + System.currentTimeMillis() / 1000);
                Files.copy(f.toPath(), old.toPath());
                java.util.List<String> overwrite = defaults.getStringList("upgrade-overwrite");
                if (!overwrite.isEmpty()) {
                    // Частичное обновление: меняем только перечисленные ключи, остальные настройки владельца сохраняются.
                    for (String k : overwrite) cfg.set(k, defaults.get(k));
                    cfg.set("config-version", want);
                    cfg.set("upgrade-overwrite", overwrite);
                    Files.writeString(f.toPath(), cfg.saveToString(), StandardCharsets.UTF_8);
                    plugin.getLogger().warning("[Конфигурация] " + name + " обновлён до версии " + want + " (изменены: " + String.join(", ", overwrite)
                            + "). Прежний файл сохранён как " + old.getName());
                } else {
                    try (InputStream in = plugin.getResource(name)) {
                        if (in != null) Files.write(f.toPath(), in.readAllBytes());
                    }
                    cfg = new YamlConfiguration();
                    cfg.loadFromString(Files.readString(f.toPath(), StandardCharsets.UTF_8));
                    plugin.getLogger().warning("[Конфигурация] " + name + " обновлён до версии " + want + ". Прежний файл сохранён как " + old.getName());
                }
            }
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue;
                if (!cfg.contains(key, true)) {
                    // Для списков-разделов (магазин, предметы и т.п.) не дописываем элементы,
                    // которые владелец сознательно удалил: дописываем только корневые настройки.
                    if (isUserCollection(name, key)) continue;
                    cfg.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) Files.writeString(f.toPath(), cfg.saveToString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            problems.add("Ошибка чтения " + name + ": " + e.getMessage() + " — используются значения по умолчанию.");
            cfg = defaults;
        }
        configs.put(name, cfg);
    }

    private boolean isUserCollection(String file, String key) {
        return switch (file) {
            case "market.yml" -> key.startsWith("values.") || key.startsWith("fixed-values.") || key.startsWith("tags.");
            case "items.yml" -> key.startsWith("items.") || key.startsWith("potions.") || key.startsWith("loot-tables.");
            case "npcs.yml" -> key.startsWith("npcs.");
            case "structures.yml" -> key.startsWith("structures.");
            case "dialogues.yml" -> key.startsWith("dialogues.");
            case "quests.yml" -> key.startsWith("quests.");
            case "progression.yml" -> key.startsWith("ranks.") || key.startsWith("achievements.") || key.startsWith("titles.");
            case "events.yml" -> key.startsWith("riddles.") || key.startsWith("merchant.trades.");
            case "duels.yml" -> key.startsWith("kits.");
            default -> false;
        };
    }

    public void save(String name) {
        YamlConfiguration cfg = configs.get(name);
        if (cfg == null) return;
        try {
            Files.writeString(new File(plugin.getDataFolder(), name).toPath(), cfg.saveToString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось сохранить " + name + ": " + e.getMessage());
        }
    }

    public YamlConfiguration get(String name) {
        YamlConfiguration c = configs.get(name);
        if (c == null) {
            load(name);
            c = configs.get(name);
        }
        return c;
    }

    public YamlConfiguration main() { return get("config.yml"); }

    public boolean firstRun() { return firstRun; }

    public List<String> problems() { return problems; }

    private void validate() {
        var eco = get("economy.yml");
        check(eco.getLong("start-balance", 10000) >= 0, "economy.yml: start-balance не может быть отрицательным");
        double loss = eco.getDouble("death.loss-percent", 10);
        check(loss >= 0 && loss <= 100, "economy.yml: death.loss-percent должен быть 0..100");
        var pvp = get("pvp.yml");
        check(pvp.getInt("combat-tag-seconds", 15) > 0, "pvp.yml: combat-tag-seconds должен быть > 0");
        var auth = get("auth.yml");
        check(auth.getInt("max-attempts", 10) > 0, "auth.yml: max-attempts должен быть > 0");
        check(auth.getInt("login-timeout-seconds", 60) >= 10, "auth.yml: login-timeout-seconds должен быть >= 10");
        var db = get("database.yml");
        String type = db.getString("type", "sqlite");
        check(type.equalsIgnoreCase("sqlite") || type.equalsIgnoreCase("postgresql"),
                "database.yml: type должен быть sqlite или postgresql");
        var clans = get("clans.yml");
        check(clans.getInt("max-members", 5) >= 1, "clans.yml: max-members должен быть >= 1");
        var lg = get("logging.yml");
        check(lg.getInt("backup.interval-hours", 6) >= 1, "logging.yml: backup.interval-hours должен быть >= 1");
    }

    private void check(boolean cond, String msg) {
        if (!cond) problems.add(msg);
    }
}
