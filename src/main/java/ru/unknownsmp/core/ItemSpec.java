package ru.unknownsmp.core;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

/**
 * Разбор предметов из конфигурации: {@code MATERIAL[:количество][#зачарование=уровень,...]}.
 * Пример: {@code DIAMOND_SWORD#sharpness=3,unbreaking=2}, {@code ARROW:16}.
 */
public final class ItemSpec {
    private ItemSpec() {}

    public static ItemStack parse(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String s = spec.trim();
        String ench = null;
        int hash = s.indexOf('#');
        if (hash >= 0) { ench = s.substring(hash + 1); s = s.substring(0, hash); }
        int amount = 1;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            try { amount = Integer.parseInt(s.substring(colon + 1)); } catch (NumberFormatException ignored) {}
            s = s.substring(0, colon);
        }
        // Зелья: SPLASH_POTION@strong_healing — базовый тип зелья.
        String potion = null;
        int at = s.indexOf('@');
        if (at >= 0) { potion = s.substring(at + 1); s = s.substring(0, at); }
        Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
        if (m == null || !m.isItem() || m.isAir()) {
            UnknownSMP.get().getLogger().warning("Неизвестный предмет в конфигурации: " + spec);
            return null;
        }
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(m.getMaxStackSize(), amount)));
        if (potion != null && it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) {
            org.bukkit.potion.PotionType pt = Registry.POTION.get(NamespacedKey.minecraft(potion.trim().toLowerCase(Locale.ROOT)));
            if (pt == null) UnknownSMP.get().getLogger().warning("Неизвестный тип зелья: " + potion + " в " + spec);
            else { pm.setBasePotionType(pt); it.setItemMeta(pm); }
        }
        if (ench != null) {
            ItemMeta meta = it.getItemMeta();
            for (String part : ench.split(",")) {
                String[] kv = part.split("=");
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0].trim().toLowerCase(Locale.ROOT)));
                if (e == null) {
                    UnknownSMP.get().getLogger().warning("Неизвестное зачарование: " + kv[0] + " в " + spec);
                    continue;
                }
                int lvl = kv.length > 1 ? Integer.parseInt(kv[1].trim()) : 1;
                meta.addEnchant(e, lvl, true);
            }
            it.setItemMeta(meta);
        }
        return it;
    }
}
