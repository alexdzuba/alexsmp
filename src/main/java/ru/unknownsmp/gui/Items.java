package ru.unknownsmp.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.unknownsmp.core.Keys;
import ru.unknownsmp.core.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Конструктор иконок для GUI. */
public final class Items {
    private final ItemStack item;
    private final List<String> lore = new ArrayList<>();
    private String name;
    private boolean glow;

    private Items(ItemStack it) {
        this.item = it;
    }

    public static Items of(Material m) {
        return new Items(new ItemStack(m == null || !m.isItem() ? Material.BARRIER : m));
    }

    public static Items of(ItemStack base) {
        return new Items(base.clone());
    }

    public static Items head(OfflinePlayer p) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        sm.setOwningPlayer(p);
        it.setItemMeta(sm);
        return new Items(it);
    }

    public static Items head(UUID uuid) {
        return head(Bukkit.getOfflinePlayer(uuid));
    }

    public Items name(String n) { this.name = n; return this; }

    public Items lore(String... lines) { lore.addAll(Arrays.asList(lines)); return this; }

    public Items lore(List<String> lines) { if (lines != null) lore.addAll(lines); return this; }

    public Items amount(int a) { item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), a))); return this; }

    public Items glow() { this.glow = true; return this; }

    public Items glow(boolean g) { this.glow = g; return this; }

    public ItemStack build() {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (name != null) meta.displayName(Text.item(name));
        if (!lore.isEmpty()) {
            List<String> merged = new ArrayList<>();
            for (String l : lore) merged.addAll(Arrays.asList(l.split("\n")));
            var existing = meta.lore();
            var comps = new ArrayList<>(existing == null ? List.of() : existing);
            comps.addAll(Text.lore(merged));
            meta.lore(comps);
        }
        if (glow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        }
        meta.addItemFlags(ItemFlag.values());
        meta.getPersistentDataContainer().set(Keys.MENU_ITEM, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Отображение реального предмета (лот, почта) с дополнительными строками описания, без флагов скрытия. */
    public static ItemStack display(ItemStack real, List<String> extraLore) {
        ItemStack it = real.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        var comps = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        comps.addAll(Text.lore(extraLore));
        meta.lore(comps);
        meta.getPersistentDataContainer().set(Keys.MENU_ITEM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack filler() {
        return of(Material.BLACK_STAINED_GLASS_PANE).name(" ").build();
    }

    public static ItemStack filler(Material m) {
        return of(m).name(" ").build();
    }

    public static String itemName(ItemStack it) {
        if (it == null) return "?";
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return Text.plain(meta.displayName());
        if (meta != null && meta.hasItemName()) return Text.plain(meta.itemName());
        return materialName(it.getType());
    }

    public static String materialName(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
