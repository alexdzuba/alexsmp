package ru.unknownsmp.core;

import org.bukkit.NamespacedKey;

/** Ключи PersistentDataContainer. */
public final class Keys {
    private Keys() {}

    public static NamespacedKey ITEM_TYPE, ITEM_UID, ITEM_SIG, NPC_ID, HOLOGRAM, EVENT_ENTITY, ENHANCED_MOB,
            MENU_ITEM, RITUAL_MOB, GUARD_MOB, TROLL_ENTITY, LOBBY_ENTITY, ATTR_PREFIX;

    public static void init(UnknownSMP pl) {
        ITEM_TYPE = new NamespacedKey(pl, "item_type");
        ITEM_UID = new NamespacedKey(pl, "item_uid");
        ITEM_SIG = new NamespacedKey(pl, "item_sig");
        NPC_ID = new NamespacedKey(pl, "npc_id");
        HOLOGRAM = new NamespacedKey(pl, "hologram");
        EVENT_ENTITY = new NamespacedKey(pl, "event_entity");
        ENHANCED_MOB = new NamespacedKey(pl, "enhanced");
        MENU_ITEM = new NamespacedKey(pl, "menu_item");
        RITUAL_MOB = new NamespacedKey(pl, "ritual_mob");
        GUARD_MOB = new NamespacedKey(pl, "guard_mob");
        TROLL_ENTITY = new NamespacedKey(pl, "troll");
        LOBBY_ENTITY = new NamespacedKey(pl, "lobby");
        ATTR_PREFIX = new NamespacedKey(pl, "attr");
    }

    public static NamespacedKey of(String k) {
        return new NamespacedKey(UnknownSMP.get(), k);
    }
}
