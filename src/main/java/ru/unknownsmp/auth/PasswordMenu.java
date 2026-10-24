package ru.unknownsmp.auth;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import ru.unknownsmp.core.ChatInput;
import ru.unknownsmp.core.Msg;
import ru.unknownsmp.core.UnknownSMP;
import ru.unknownsmp.gui.Items;
import ru.unknownsmp.gui.Menu;

/** GUI смены пароля. Ввод идёт через скрытый чат-ввод (сообщения не публикуются и не журналируются). */
public final class PasswordMenu extends Menu {
    public PasswordMenu(Player viewer) {
        super(viewer, 3, "&8Безопасность аккаунта");
    }

    @Override
    protected void build() {
        fill(Items.filler());
        set(11, Items.of(Material.TRIPWIRE_HOOK).name("&eСменить пароль")
                .lore("&7Потребуется ввести старый пароль,", "&7затем новый дважды.", "", "&7Все сессии будут отозваны.").build(), c -> start());
        set(15, Items.of(Material.OAK_DOOR).name("&cЗавершить сессию")
                .lore("&7Отозвать доверенную сессию и выйти.", "&7При следующем входе нужен пароль.").build(),
                c -> viewer.performCommand("logout"));
        backButton(22);
    }

    private void start() {
        ChatInput.ask(viewer, Msg.t("auth.gui.old", "&fВведите &eстарый&f пароль:"), old ->
                ChatInput.ask(viewer, Msg.t("auth.gui.new", "&fВведите &eновый&f пароль:"), neu ->
                        ChatInput.ask(viewer, Msg.t("auth.gui.repeat", "&fПовторите &eновый&f пароль:"), rep -> {
                            if (!neu.equals(rep)) {
                                Msg.error(viewer, "auth.pw-mismatch", "Пароли не совпадают.");
                                return;
                            }
                            UnknownSMP.get().auth().changePassword(viewer, old, neu);
                        })));
    }
}
