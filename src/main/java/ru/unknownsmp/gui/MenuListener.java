package ru.unknownsmp.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.unknownsmp.core.Keys;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Защита всех меню: отмена перетаскивания, ограничение частоты кликов, очистка «иконок». */
public final class MenuListener implements Listener {
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_MS = 120;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu menu)) {
            // Защита: иконка меню каким-то образом оказалась в обычном инвентаре — удаляем.
            ItemStack cur = e.getCurrentItem();
            if (isMenuItem(cur)) {
                e.setCancelled(true);
                e.setCurrentItem(null);
            }
            return;
        }
        if (!(e.getWhoClicked() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < MIN_INTERVAL_MS) {
            e.setCancelled(true);
            return;
        }
        lastClick.put(p.getUniqueId(), now);
        menu.handle(e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu menu) {
            int topSize = e.getView().getTopInventory().getSize();
            for (int raw : e.getRawSlots()) {
                if (raw < topSize && !menu.allowInput(raw)) {
                    e.setCancelled(true);
                    return;
                }
            }
            if (menu.isLocked()) e.setCancelled(true);
        }
    }

    // Обработчика InventoryMoveItemEvent нет намеренно: меню — виртуальные инвентари, воронки к ним не подключаются,
    // а сам факт подписки заставлял Paper создавать событие на каждое перемещение предмета каждой воронкой.

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu menu) {
            menu.closed();
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isMenuItem(e.getItemDrop().getItemStack())) e.getItemDrop().remove();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastClick.remove(e.getPlayer().getUniqueId());
    }

    public static boolean isMenuItem(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(Keys.MENU_ITEM, PersistentDataType.BYTE);
    }
}
