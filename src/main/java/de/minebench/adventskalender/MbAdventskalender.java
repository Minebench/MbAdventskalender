package de.minebench.adventskalender;

/*
 * MbAdventskalender
 * Copyright (c) 2018 Max Lee aka Phoenix616 (mail@moep.tv)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import de.themoep.inventorygui.DynamicGuiElement;
import de.themoep.inventorygui.GuiElement;
import de.themoep.inventorygui.GuiElementGroup;
import de.themoep.inventorygui.InventoryGui;
import de.themoep.inventorygui.StaticGuiElement;
import de.themoep.minedown.MineDown;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

public final class MbAdventskalender extends JavaPlugin implements Listener {
    private ConfigAccessor playerConfig;
    private final Multimap<UUID, Integer> retrievedDays = MultimapBuilder.hashKeys().linkedHashSetValues(24).build();

    private ItemStack filler;

    private Map<String, StaticGuiElement> elements = new HashMap<>();
    private List<Integer> dayOrder = new ArrayList<>();
    private int missedDays;
    private int notificationDelay;

    @Override
    public void onEnable() {
        for (int i = 1; i <= 24; i++) {
            dayOrder.add(i);
        }
        Collections.shuffle(dayOrder, new Random(Calendar.getInstance().get(Calendar.YEAR)));

        playerConfig = new ConfigAccessor(this, "players.yml");
        loadConfig();
        getCommand("adventskalender").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    public void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        missedDays = getConfig().getInt("missed-days");
        notificationDelay = getConfig().getInt("notification-delay");
        filler = getConfig().getItemStack("gui.filler");

        for (String key : elements.keySet()) {
            elements.put(key, buildElement(key));
        }

        playerConfig.saveDefaultConfig();
        playerConfig.reloadConfig();

        retrievedDays.clear();
        for (String key : playerConfig.getConfig().getConfigurationSection("players").getKeys(false)) {
            try {
                retrievedDays.putAll(UUID.fromString(key), playerConfig.getConfig().getIntegerList("players." + key));
            } catch (IllegalArgumentException e) {
                getLogger().log(Level.WARNING, key + " is not a valid player UUID in players.yml?");
            }
        }
    }

    private StaticGuiElement buildElement(String key) {
        String[] parts = key.split("\\.");
        return new StaticGuiElement('d',
                getConfig().getItemStack("gui.icons." + key),
                getText(parts[0]) + (parts.length > 1 ? "\n" + getText(parts[1]) : "")
        );
    }

    private StaticGuiElement getElement(String key, String... replacements) {
        StaticGuiElement element = elements.computeIfAbsent(key, this::buildElement);
        return new StaticGuiElement(element.getSlotChar(), element.getRawItem(), replaceIn(element.getText(), replacements));
    }

    private String getText(String key, String... replacements) {
        return TextComponent.toLegacyText(getComponents(key, replacements));
    }

    private BaseComponent[] getComponents(String key, String... replacements) {
        return MineDown.parse(getConfig().getString("text." + key), replacements);
    }

    private String[] replaceIn(String[] text, String... replacements) {
        String[] replaced = new String[text.length];
        for (int i = 0; i < text.length; i++) {
            replaced[i] = replaceIn(text[i], replacements);
        }
        return replaced;
    }

    private String replaceIn(String string, String... replacements) {
        for (int i = 0; i+1 < replacements.length; i+=2) {
            string = string.replace("%" + replacements[i] + "%", replacements[i+1]);
        }
        return string;
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player target = null;
        if (args.length > 0) {
            if ("reload".equalsIgnoreCase(args[0]) && sender.hasPermission("mbadventskalender.command.reload")) {
                loadConfig();
                sender.sendMessage(ChatColor.YELLOW + "Config reloaded!");
                return true;
            } else if ("show".equalsIgnoreCase(args[0]) && sender.hasPermission("mbadventskalender.command.others")) {
                if (args.length > 1) {
                    target = getServer().getPlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "Player " + args[1] + " is not online!");                        
                        return true;
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "/" + label + " show <player>");
                    return true;
                }
            }
        }
        if (!(sender instanceof Player)) {
            return false;
        }

        InventoryGui gui = new InventoryGui(this, getConfig().getString("gui.title"), getConfig().getStringList("gui.layout").toArray(new String[0]));

        gui.setFiller(filler);
        gui.addElement(new GuiElementGroup('d', buildElements(target)));
        gui.show(target);
        return true;
    }

    private GuiElement[] buildElements(Player target) {
        List<GuiElement> elements = new ArrayList<>();
        for (int day : dayOrder) {
            String defType = "day";
            int advent = 0;
            if (day == 24) {
                defType = "christmas";
            } else {
                Calendar dayCal = Calendar.getInstance();
                dayCal.set(Calendar.MONTH, Calendar.DECEMBER);
                dayCal.set(Calendar.DAY_OF_MONTH, day);
                if (dayCal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                    defType = "advent";
                    advent = 4 - ((24 - day) / 7);
                }
            }

            String type = getConfig().getString("days." + day + ".type", defType);
            int finalAdvent = advent;
            elements.add(new DynamicGuiElement('d', () -> {
                int currentDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
                StaticGuiElement element;
                String[] replacements = {
                        "day", String.valueOf(day),
                        "advent", String.valueOf(finalAdvent),
                        "title", getConfig().getString("days." + day + ".title", "")
                };
                if (currentDay < day) {
                    element = getElement(type + ".unavailable", replacements);
                } else if (hasRetrieved(target, day)) {
                    element = getElement(type + ".retrieved", replacements);
                } else if (currentDay - missedDays > day) {
                    element = getElement(type + ".missed", replacements);
                } else {
                    element = getElement(type + ".available", replacements);
                    element.setAction(click -> {
                        giveRewards(click.getEvent().getWhoClicked(), day);
                        click.getGui().draw();
                        return true;
                    });
                }
                element.setNumber(day);
                return element;
            }));
        }
        return elements.toArray(new GuiElement[0]);
    }

    private void giveRewards(HumanEntity player, int day) {
        retrievedDays.put(player.getUniqueId(), day);
        playerConfig.getConfig().set("players." + player.getUniqueId(), retrievedDays.get(player.getUniqueId()));
        playerConfig.saveConfig();

        List<Map<?, ?>> mapList = getConfig().getMapList("days." + day + ".reward");
        for (Map<?, ?> map : mapList) {
            ItemStack item = ItemStack.deserialize((Map<String, Object>) map);
            if (item.getType() != null) {
                for (ItemStack rest : player.getInventory().addItem(item).values()) {
                    player.getLocation().getWorld().dropItem(player.getLocation(), rest);
                }
            }
        }

        player.sendMessage(getText("reward-received", "day", String.valueOf(day)));
        if (player instanceof Player) {
            ((Player) player).playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Runnable run = () -> {
            int currentDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
            if (!hasRetrieved(event.getPlayer(), currentDay)) {
                if (currentDay > 1 && !hasRetrieved(event.getPlayer(), currentDay - 1)) {
                    event.getPlayer().sendMessage(getComponents("notification.today-and-before", "day", String.valueOf(currentDay)));
                } else {
                    event.getPlayer().sendMessage(getComponents("notification.today", "day", String.valueOf(currentDay)));
                }
            } else if (currentDay > 1 && !hasRetrieved(event.getPlayer(), currentDay - 1)) {
                event.getPlayer().sendMessage(getComponents("notification.before", "day", String.valueOf(currentDay - 1)));
            }
        };
        if (notificationDelay == 0) {
            run.run();
        } else if (notificationDelay > 0) {
            getServer().getScheduler().runTaskLater(this, run, notificationDelay * 20);
        }
    }

    private boolean hasRetrieved(Player player, int day) {
        return retrievedDays.containsEntry(player.getUniqueId(), day);
    }
}
