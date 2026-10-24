package ru.unknownsmp.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Украшения сообщений: упоминания @ник и показ предмета [i]. Цветовые коды игроков не применяются. */
public final class ChatExtras {
    private ChatExtras() {}

    private static final Pattern TOKEN = Pattern.compile("(@[A-Za-z0-9_]{3,16})|(\\[(?:i|item|предмет)\\])", Pattern.CASE_INSENSITIVE);

    public static Component decorate(UnknownSMP plugin, Player sender, String text, boolean local) {
        NamedTextColor base = local ? NamedTextColor.GRAY : NamedTextColor.WHITE;
        Component out = Component.empty();
        Matcher m = TOKEN.matcher(text);
        int last = 0;
        boolean itemShown = false;
        while (m.find()) {
            if (m.start() > last) out = out.append(Component.text(text.substring(last, m.start()), base));
            if (m.group(1) != null) {
                String nick = m.group(1).substring(1);
                Player t = Bukkit.getPlayerExact(nick);
                if (t != null && plugin.auth().isAuthed(t) && !(plugin.admin() != null && plugin.admin().isVanished(t))) {
                    out = out.append(Component.text("@" + t.getName(), NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));
                    Tasks.sync(() -> {
                        if (!t.isOnline()) return;
                        var pr = plugin.profiles().get(t);
                        if (pr != null && !pr.setting("sounds")) return;
                        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.6f);
                        Msg.actionBar(t, "&e" + sender.getName() + " упомянул вас в чате");
                    });
                } else {
                    out = out.append(Component.text(m.group(1), base));
                }
            } else if (!itemShown) {
                itemShown = true;
                ItemStack hand = sender.getInventory().getItemInMainHand().clone();
                if (hand.getType().isAir()) {
                    out = out.append(Component.text("[пусто]", NamedTextColor.DARK_GRAY));
                } else {
                    String name = (hand.getAmount() > 1 ? hand.getAmount() + "x " : "") + Items.itemName(hand);
                    out = out.append(Component.text("[" + name + "]", NamedTextColor.AQUA).hoverEvent(hand.asHoverEvent()));
                }
            } else {
                out = out.append(Component.text(m.group(), base));
            }
            last = m.end();
        }
        if (last < text.length()) out = out.append(Component.text(text.substring(last), base));
        return out;
    }
}
