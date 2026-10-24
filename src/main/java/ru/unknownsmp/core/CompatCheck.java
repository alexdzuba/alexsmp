package ru.unknownsmp.core;

import org.bukkit.Bukkit;

/** Проверка версии Minecraft, ядра Paper и Java перед запуском модулей. */
public final class CompatCheck {
    private CompatCheck() {}

    /** @return описание проблемы или null, если окружение совместимо. */
    public static String check(UnknownSMP pl) {
        int java = Runtime.version().feature();
        if (java < 21) {
            return "Требуется Java 21 или новее (Paper " + UnknownSMP.TARGET_MC + "), обнаружена Java " + java + ".";
        }
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
        } catch (ClassNotFoundException e) {
            return "Требуется ядро Paper. Spigot/CraftBukkit не поддерживаются.";
        }
        String mc = Bukkit.getMinecraftVersion();
        boolean allowOther = pl.configs().main().getBoolean("compatibility.allow-other-versions", false);
        if (!UnknownSMP.TARGET_MC.equals(mc)) {
            if (allowOther) {
                pl.getLogger().warning("Версия Minecraft " + mc + " отличается от целевой " + UnknownSMP.TARGET_MC
                        + ". Запуск разрешён настройкой compatibility.allow-other-versions — работа не гарантируется.");
                return null;
            }
            return "Плагин рассчитан на Minecraft " + UnknownSMP.TARGET_MC + ", а сервер работает на " + mc
                    + ". Установите Paper " + UnknownSMP.TARGET_MC + " (или включите compatibility.allow-other-versions на свой риск).";
        }
        return null;
    }
}
