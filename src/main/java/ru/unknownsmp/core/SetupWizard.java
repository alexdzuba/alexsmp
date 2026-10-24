package ru.unknownsmp.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;

import java.util.ArrayList;
import java.util.List;

/**
 * Мастер первого запуска: проверки окружения и чек-лист настройки (консоль + GUI владельца).
 * Ничего не удаляет и не изменяет без явного действия владельца.
 */
public final class SetupWizard {
    private SetupWizard() {}

    public record Check(String title, boolean ok, String hint) {}

    public static List<Check> checks(UnknownSMP pl) {
        List<Check> out = new ArrayList<>();
        out.add(new Check("Версия Minecraft " + UnknownSMP.TARGET_MC, UnknownSMP.TARGET_MC.equals(Bukkit.getMinecraftVersion()),
                "Сервер: " + Bukkit.getMinecraftVersion()));
        out.add(new Check("Java 21+", Runtime.version().feature() >= 21, "Обнаружена Java " + Runtime.version().feature()));
        out.add(new Check("online-mode=false (Offline-авторизация)", !Bukkit.getOnlineMode(),
                "В server.properties установите online-mode=false и перезапустите сервер."));
        out.add(new Check("База данных подключена", pl.modules().isActive("database"),
                pl.db() != null && pl.db().lastError() != null ? pl.db().lastError() : "Проверьте database.yml"));
        long failed = pl.modules().status().values().stream().filter(s -> s.state() == ModuleManager.State.FAILED).count();
        out.add(new Check("Все модули запущены", failed == 0, failed + " модул(ей) с ошибкой — /usmp status"));
        out.add(new Check("Назначен владелец", ownerAssigned(pl), "Из консоли: usmp role <ник> owner (игрок должен хотя бы раз зайти)"));
        out.add(new Check("Конфигурация без ошибок", pl.configs().problems().isEmpty(), String.join("; ", pl.configs().problems())));
        boolean absBackup = new java.io.File(pl.configs().get("logging.yml").getString("backup.directory", "backups")).isAbsolute();
        out.add(new Check("Резервные копии на отдельном диске", absBackup, "Укажите абсолютный путь backup.directory в logging.yml (другой диск/хранилище)."));
        out.add(new Check("Спавн SMP построен/задан", pl.state() != null && (pl.state().getLong("smp_spawn_built", 0) > 0
                || pl.configs().main().getString("survival.spawn", "").length() > 0), "/usmp setspawn — на нужной точке"));
        out.add(new Check("Белый список/прокси не мешает входу", !Bukkit.hasWhitelist() || Bukkit.getWhitelistedPlayers().size() > 0, "Проверьте whitelist в server.properties"));
        return out;
    }

    private static boolean ownerAssigned(UnknownSMP pl) {
        if (pl.db() == null || !pl.modules().isActive("database")) return false;
        try {
            Long n = pl.db().queryBlocking(c -> ru.unknownsmp.db.Database.longVal(c, "SELECT COUNT(*) FROM players WHERE staff_role='owner'"));
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void consoleReport(UnknownSMP pl) {
        var log = pl.getLogger();
        log.info("===== ALEX SMP: проверка установки =====");
        for (Check c : checks(pl)) {
            if (c.ok()) log.info("  [OK]  " + c.title());
            else log.warning("  [!!]  " + c.title() + " — " + c.hint());
        }
        if (pl.configs().firstRun()) {
            log.info("Первый запуск: файлы настроек созданы в " + pl.getDataFolder().getPath());
            log.info("Шаги: 1) online-mode=false; 2) зайдите на сервер и зарегистрируйтесь; 3) в консоли: usmp role <ник> owner;");
            log.info("      4) в игре: /usmp setup — мастер настройки; 5) настройте backup.directory на другой диск.");
        }
        log.info("===========================================");
    }

    public static void open(UnknownSMP pl, Player p) {
        new Menu(p, 4, "&8Мастер настройки") {
            @Override
            protected void build() {
                fill(Items.filler());
                List<Check> cs = checks(pl);
                int slot = 10;
                for (Check c : cs) {
                    set(slot++, Items.of(c.ok() ? Material.LIME_CONCRETE : Material.RED_CONCRETE).name((c.ok() ? "&a✔ " : "&c✘ ") + c.title())
                            .lore(c.ok() ? "&7Готово" : "&7" + c.hint()).build());
                    if (slot == 17) slot = 19;
                }
                set(28, Items.of(Material.RED_BED).name("&eСпавн SMP — сюда").lore("&7Установить точку появления выживания на вашей позиции").build(), c -> viewer.performCommand("usmp setspawn"));
                set(29, Items.of(Material.PURPUR_BLOCK).name("&eТочка лобби — сюда").lore("&7Нужно стоять в мире лобби").build(), c -> viewer.performCommand("usmp setlobby"));
                set(30, Items.of(Material.MAP).name("&eПрименить границу 50 000 × 50 000").build(), c -> { pl.world().applyBorder(); Msg.ok(viewer, "panel.border", "Граница применена."); });
                set(31, Items.of(Material.CHEST).name("&eСоздать резервную копию").build(), c -> viewer.performCommand("usmp backup now"));
                set(32, Items.of(Material.REPEATER).name("&eПерепроверить").build(), c -> refresh());
                closeButton(35);
            }
        }.open();
    }
}
