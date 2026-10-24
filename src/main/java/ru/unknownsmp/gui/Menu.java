package ru.unknownsmp.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Text;

import java.util.HashMap;
import java.util.Map;

/**
 * Базовое меню-инвентарь. Каждое нажатие обрабатывается на сервере; содержимому клиентского
 * инвентаря не доверяем. Во время асинхронной операции меню блокируется (lock), чтобы повторные
 * клики, смена страниц и закрытие не приводили к дублированию.
 */
public abstract class Menu implements InventoryHolder {
    public record Click(Player player, ClickType type, int slot, InventoryClickEvent event) {
        public boolean left() { return type.isLeftClick(); }
        public boolean right() { return type.isRightClick(); }
        public boolean shift() { return type.isShiftClick(); }
    }

    @FunctionalInterface
    public interface Handler {
        void click(Click c);
    }

    protected final Player viewer;
    protected Inventory inv;
    protected final Map<Integer, Handler> handlers = new HashMap<>();
    private final int rows;
    private String title;
    private boolean locked;
    protected Menu parent;

    protected Menu(Player viewer, int rows, String title) {
        this.viewer = viewer;
        this.rows = Math.max(1, Math.min(6, rows));
        this.title = title;
    }

    public Menu parent(Menu p) { this.parent = p; return this; }

    protected abstract void build();

    public void open() {
        inv = Bukkit.createInventory(this, rows * 9, Text.c(title));
        handlers.clear();
        build();
        viewer.openInventory(inv);
    }

    /** Перестроить содержимое без переоткрытия. */
    public void refresh() {
        if (inv == null) { open(); return; }
        inv.clear();
        handlers.clear();
        build();
    }

    public void retitle(String t) { this.title = t; }

    protected void set(int slot, ItemStack it, Handler h) {
        if (slot < 0 || slot >= inv.getSize()) return;
        inv.setItem(slot, it);
        if (h != null) handlers.put(slot, h);
        else handlers.remove(slot);
    }

    protected void set(int slot, ItemStack it) { set(slot, it, null); }

    protected void fill(ItemStack it) {
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, it);
    }

    protected void border() {
        ItemStack f = Items.filler();
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) if (inv.getItem(i) == null) inv.setItem(i, f);
        }
    }

    protected int size() { return rows * 9; }

    protected void backButton(int slot) {
        if (parent != null) {
            set(slot, Items.of(Material.ARROW).name("&eНазад").build(), c -> parent.open());
        } else {
            set(slot, Items.of(Material.BARRIER).name("&cЗакрыть").build(), c -> viewer.closeInventory());
        }
    }

    protected void closeButton(int slot) {
        set(slot, Items.of(Material.BARRIER).name("&cЗакрыть").build(), c -> viewer.closeInventory());
    }

    /** Вызывается слушателем. */
    void handle(InventoryClickEvent e) {
        e.setCancelled(true);
        if (locked) {
            Msg.actionBar(viewer, "&eОперация выполняется, подождите...");
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != inv) {
            onBottomClick(e);
            return;
        }
        if (allowInput(e.getRawSlot())) {
            e.setCancelled(false);
            onInputChange();
            return;
        }
        Handler h = handlers.get(e.getRawSlot());
        if (h != null) {
            Msg.sound(viewer, Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
            h.click(new Click(viewer, e.getClick(), e.getRawSlot(), e));
        }
    }

    /** Клик по нижнему (инвентарю игрока). По умолчанию запрещён. */
    protected void onBottomClick(InventoryClickEvent e) {}

    /** Слоты, в которые игрок может класть предметы (меню скупки). */
    protected boolean allowInput(int rawSlot) { return false; }

    protected void onInputChange() {}

    protected void onClose() {}

    void closed() { onClose(); }

    public void lock() { locked = true; }

    public void unlock() { locked = false; }

    public boolean isLocked() { return locked; }

    public Player viewer() { return viewer; }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }
}
