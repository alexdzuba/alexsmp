package ru.unknownsmp.lobby;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.Tasks;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;
import ru.unknownsmp.profile.Profile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Косметика: следы частиц. Открываются рангами/достижениями (unlock kind "cosmetic"). Только визуальный эффект. */
public final class CosmeticsService {
    public record Trail(String id, String name, Material icon, Particle particle, String requirement) {}

    public static final Map<String, Trail> TRAILS = new LinkedHashMap<>();

    static {
        add(new Trail("hearts", "&dСердечки", Material.PINK_DYE, Particle.HEART, "free"));
        add(new Trail("notes", "&aНоты", Material.NOTE_BLOCK, Particle.NOTE, "free"));
        add(new Trail("enchant", "&bРуны", Material.ENCHANTING_TABLE, Particle.ENCHANT, "rank"));
        add(new Trail("flame", "&6Пламя", Material.BLAZE_POWDER, Particle.FLAME, "rank"));
        add(new Trail("soul", "&3Души", Material.SOUL_LANTERN, Particle.SOUL_FIRE_FLAME, "rank"));
        add(new Trail("cherry", "&dЛепестки", Material.CHERRY_LEAVES, Particle.CHERRY_LEAVES, "rank"));
        add(new Trail("end", "&5Звёздная пыль", Material.END_ROD, Particle.END_ROD, "rank"));
        add(new Trail("villager", "&2Удача", Material.EMERALD, Particle.HAPPY_VILLAGER, "achievement"));
    }

    private static void add(Trail t) { TRAILS.put(t.id, t); }

    private final UnknownSMP plugin;
    private final Map<UUID, Location> lastPos = new ConcurrentHashMap<>();

    CosmeticsService(UnknownSMP plugin) {
        this.plugin = plugin;
    }

    void start() {
        Tasks.timer(4, 4, this::tick);
    }

    private void tick() {
        boolean survival = plugin.configs().main().getBoolean("cosmetics.show-in-survival", true);
        plugin.profiles().forEachOnline((p, pr) -> {
            if (pr.cosmetic == null || !plugin.auth().isAuthed(p)) return;
            if (!survival && !plugin.lobby().isLobby(p.getWorld())) return;
            if (p.hasPotionEffect(PotionEffectType.INVISIBILITY) || (plugin.admin() != null && plugin.admin().isVanished(p))) return;
            Trail t = TRAILS.get(pr.cosmetic);
            if (t == null) return;
            Location l = p.getLocation();
            Location prev = lastPos.put(p.getUniqueId(), l);
            if (prev == null || prev.getWorld() != l.getWorld() || prev.distanceSquared(l) < 0.01) return;
            p.getWorld().spawnParticle(t.particle, l.clone().add(0, 0.2, 0), 2, 0.2, 0.1, 0.2, 0);
        });
    }

    public boolean unlocked(Player p, Trail t) {
        Profile pr = plugin.profiles().get(p);
        if (pr == null) return false;
        return t.requirement.equals("free") || pr.has("cosmetic", t.id) || plugin.roles().has(p, "usmp.admin.cosmetics");
    }

    public void open(Player p, Menu parent) {
        new Menu(p, 3, "&8Косметика: следы") {
            @Override
            protected void build() {
                fill(Items.filler());
                int slot = 10;
                Profile pr = plugin.profiles().get(p);
                for (Trail t : TRAILS.values()) {
                    boolean un = unlocked(p, t);
                    boolean sel = pr != null && t.id.equals(pr.cosmetic);
                    String req = switch (t.requirement) {
                        case "rank" -> "&7Открывается рангом прогрессии";
                        case "achievement" -> "&7Открывается достижением";
                        default -> "&7Доступно всем";
                    };
                    set(slot++, Items.of(un ? t.icon : Material.GRAY_DYE).name(t.name).glow(sel)
                            .lore(sel ? "&aВыбрано" : un ? "&eНажмите, чтобы выбрать" : "&cЗакрыто", req).build(), c -> {
                        if (!unlocked(p, t)) { Msg.error(p, "cosmetic.locked", "Этот след ещё не открыт."); return; }
                        pr.cosmetic = t.id;
                        pr.dirty = true;
                        refresh();
                    });
                    if (slot == 17) slot = 19;
                }
                set(22, Items.of(Material.BUCKET).name("&cСнять след").build(), c -> {
                    if (pr != null) { pr.cosmetic = null; pr.dirty = true; }
                    refresh();
                });
                if (parent != null) parent(parent);
                backButton(18);
            }
        }.open();
    }
}
