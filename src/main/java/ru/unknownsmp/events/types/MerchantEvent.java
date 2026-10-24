package ru.unknownsmp.events.types;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.Text;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.economy.EconomyService;
import ru.unknownsmp.events.EventService;
import ru.unknownsmp.events.GameEvent;
import ru.unknownsmp.db.Database;
import ru.unknownsmp.gui.ConfirmMenu;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Странствующий торговец: редкие товары ограниченным тиражом. Вклад — совершённые сделки. */
public final class MerchantEvent extends GameEvent {
    private record Offer(String id, String item, long price, int stock) {}

    private WanderingTrader trader;
    private final List<Offer> offers = new ArrayList<>();
    private final Map<String, Integer> sold = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private int deals;

    public MerchantEvent(UnknownSMP plugin, EventService s) {
        super(plugin, s);
        ConfigurationSection sec = plugin.configs().get("events.yml").getConfigurationSection("merchant.trades");
        if (sec != null) for (String id : sec.getKeys(false)) {
            offers.add(new Offer(id, sec.getString(id + ".item"), sec.getLong(id + ".price", 1000), sec.getInt(id + ".stock", 3)));
        }
    }

    @Override public String type() { return "merchant"; }
    @Override public String title() { return "Странствующий торговец"; }
    @Override public String warning() { return "Слышен звон колокольчиков: по дорогам идёт загадочный торговец."; }
    @Override public int minutes() { return plugin.configs().get("events.yml").getInt("types.merchant.minutes", 20); }
    @Override public String description() { return "Редкие товары — пока не разобрали."; }

    @Override
    protected boolean setup() {
        if (offers.isEmpty()) return false;
        trader = loc.getWorld().spawn(loc, WanderingTrader.class, t -> {
            t.customName(Text.c("&6Загадочный торговец"));
            t.setCustomNameVisible(true);
            t.setInvulnerable(true);
            t.setAI(false);
            t.setDespawnDelay(0);
            t.setRecipes(List.of());
        });
        mark(trader);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void click(PlayerInteractEntityEvent e) {
        if (trader == null || !e.getRightClicked().equals(trader)) return;
        e.setCancelled(true);
        open(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void damage(EntityDamageEvent e) {
        if (trader != null && e.getEntity().equals(trader)) e.setCancelled(true);
    }

    private void open(Player p) {
        new Menu(p, 3, "&8Загадочный торговец") {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                for (Offer o : offers) {
                    int left = o.stock() - sold.getOrDefault(o.id(), 0);
                    ItemStack icon = plugin.items().resolve(o.item().startsWith("unique:") ? null : o.item(), "", "").getNow(null);
                    Material mat = icon == null ? Material.NETHER_STAR : icon.getType();
                    String name = o.item().startsWith("unique:") ? describeUnique(o.item().substring(7)) : icon == null ? o.item() : Items.itemName(icon);
                    set(slot++, Items.of(mat).name("&e" + name).lore("&7Цена: &6" + Text.money(o.price()), "&7Осталось: &f" + Math.max(0, left), "",
                            left > 0 ? "&aНажмите, чтобы купить" : "&cРаспродано").build(), c -> {
                        if (left <= 0) return;
                        ConfirmMenu.open(viewer, "&8Купить за " + Text.money(o.price()) + "?", null, List.of("&7" + name), () -> buy(viewer, o));
                    });
                    if (slot == 17) break;
                }
                closeButton(22);
            }
        }.open();
    }

    private String describeUnique(String id) {
        var d = plugin.items().defs().get(id);
        return d == null ? id : Text.strip(plugin.items().rarityColor(d.rarity()) + d.name());
    }

    private void buy(Player p, Offer o) {
        if (finished) return;
        if (!plugin.economy().tradeAllowed(p)) return;
        if (sold.getOrDefault(o.id(), 0) >= o.stock()) { Msg.error(p, "merchant.sold-out", "Товар распродан."); return; }
        if (!busy.add(p.getUniqueId())) return;
        sold.merge(o.id(), 1, Integer::sum);
        final UUID u = p.getUniqueId();
        Tasks.then(plugin.db().tx(c -> EconomyService.debit(c, u, o.price(), "merchant", o.id())), bal -> {
            plugin.economy().apply(u, bal);
            Tasks.then(plugin.items().resolve(o.item(), p.getName(), "merchant"), it -> {
                busy.remove(u);
                if (it == null) {
                    // Предмет не удалось создать (например, лимит экземпляров) — возвращаем деньги.
                    plugin.economy().deposit(u, o.price(), "merchant_refund", o.id());
                    Msg.error(p, "merchant.refund", "Товар недоступен — деньги возвращены.");
                    return;
                }
                plugin.mail().giveOrMail(p, it, "event");
                deals++;
                contribute(p, Math.max(1, o.price() / 1000.0), "buy");
                Msg.ok(p, "merchant.bought", "Сделка заключена: {i}.", "i", Items.itemName(it));
                plugin.logs().log(p, "market", "merchant_buy", p.getLocation(), o.id() + " " + o.price());
            }, err -> {
                busy.remove(u);
                plugin.economy().deposit(u, o.price(), "merchant_refund", o.id());
            });
        }, err -> {
            busy.remove(u);
            sold.merge(o.id(), -1, Integer::sum);
            if (err instanceof Database.TxAbort ab) Msg.error(p, ab.key, ab.def);
            else Msg.error(p, "merchant.error", "Сделка не состоялась.");
        });
    }

    @Override
    protected boolean succeeded() { return deals > 0; }
}
