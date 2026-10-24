package ru.unknownsmp.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Ввод текста через чат для GUI (суммы, имена, причины). «отмена» — отменить ввод. */
public final class ChatInput implements Listener {
    private record Pending(Consumer<String> handler, Runnable onCancel, long until) {}

    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public static void ask(Player p, String prompt, Consumer<String> handler) {
        ask(p, prompt, handler, null);
    }

    public static void ask(Player p, String prompt, Consumer<String> handler, Runnable onCancel) {
        p.closeInventory();
        pending.put(p.getUniqueId(), new Pending(handler, onCancel, System.currentTimeMillis() + 60_000));
        Msg.raw(p, Msg.prefix() + prompt);
        Msg.plain(p, "input.hint", "&8(Введите значение в чат. Напишите &7отмена&8, чтобы отменить. 60 секунд)");
    }

    public static boolean isWaiting(Player p) {
        return pending.containsKey(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending pd = pending.remove(e.getPlayer().getUniqueId());
        if (pd == null) return;
        e.setCancelled(true);
        String text = Text.plain(e.message()).trim();
        Player p = e.getPlayer();
        if (System.currentTimeMillis() > pd.until) {
            Tasks.sync(() -> Msg.error(p, "input.expired", "Время ввода истекло."));
            return;
        }
        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
            Tasks.sync(() -> {
                Msg.send(p, "input.cancelled", "&7Ввод отменён.");
                if (pd.onCancel != null) pd.onCancel.run();
            });
            return;
        }
        Tasks.sync(() -> {
            if (p.isOnline()) pd.handler.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
