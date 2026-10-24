package ru.unknownsmp.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Подтверждение действия. */
public final class ConfirmMenu extends Menu {
    private final ItemStack subject;
    private final List<String> description;
    private final Runnable onYes;
    private final Runnable onNo;

    public ConfirmMenu(Player viewer, String title, ItemStack subject, List<String> description, Runnable onYes, Runnable onNo) {
        super(viewer, 3, title);
        this.subject = subject;
        this.description = description;
        this.onYes = onYes;
        this.onNo = onNo;
    }

    public static void open(Player p, String title, ItemStack subject, List<String> desc, Runnable yes) {
        new ConfirmMenu(p, title, subject, desc, yes, p::closeInventory).open();
    }

    @Override
    protected void build() {
        fill(Items.filler());
        if (subject != null) set(13, subject);
        else set(13, Items.of(Material.PAPER).name("&fПодтверждение").lore(description).build());
        set(11, Items.of(Material.LIME_CONCRETE).name("&a&lПодтвердить").lore(description).build(), c -> {
            lock();
            viewer.closeInventory();
            onYes.run();
        });
        set(15, Items.of(Material.RED_CONCRETE).name("&c&lОтмена").build(), c -> {
            if (onNo != null) onNo.run();
            else viewer.closeInventory();
        });
    }
}
