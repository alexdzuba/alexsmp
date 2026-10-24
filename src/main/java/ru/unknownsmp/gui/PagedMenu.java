package ru.unknownsmp.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Меню со страницами: 45 слотов содержимого + нижняя панель навигации. */
public abstract class PagedMenu extends Menu {
    public record Entry(ItemStack icon, Handler handler) {}

    protected int page;

    protected PagedMenu(Player viewer, String title) {
        super(viewer, 6, title);
    }

    /** Элементы для показа (пересчитываются при каждом построении). */
    protected abstract List<Entry> entries();

    /** Дополнительные кнопки нижней панели (слоты 45..53, кроме 45/53, занятых навигацией, и 49). */
    protected void bottomBar() {}

    @Override
    protected void build() {
        List<Entry> all = entries();
        if (all == null) all = new ArrayList<>();
        int pages = Math.max(1, (all.size() + 44) / 45);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        int from = page * 45;
        for (int i = 0; i < 45 && from + i < all.size(); i++) {
            Entry en = all.get(from + i);
            set(i, en.icon(), en.handler());
        }
        for (int i = 45; i < 54; i++) set(i, Items.filler(Material.GRAY_STAINED_GLASS_PANE));
        if (page > 0) {
            set(45, Items.of(Material.SPECTRAL_ARROW).name("&eПредыдущая страница").lore("&7Страница " + page + " из " + pages).build(), c -> { page--; refresh(); });
        }
        if (page < pages - 1) {
            set(53, Items.of(Material.SPECTRAL_ARROW).name("&eСледующая страница").lore("&7Страница " + (page + 2) + " из " + pages).build(), c -> { page++; refresh(); });
        }
        if (all.isEmpty()) {
            set(22, Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE).name("&7Здесь пока пусто").build());
        }
        backButton(49);
        bottomBar();
    }
}
