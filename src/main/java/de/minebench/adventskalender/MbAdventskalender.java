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
import de.themoep.inventorygui.GuiStorageElement;
import de.themoep.inventorygui.InventoryGui;
import de.themoep.inventorygui.StaticGuiElement;
import de.themoep.minedown.adventure.MineDown;
import de.themoep.minedown.adventure.Replacer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
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
    private ConfigAccessor daysConfig;
    private final Multimap<UUID, Integer> retrievedDays = MultimapBuilder.hashKeys().linkedHashSetValues(24).build();
    private final Multimap<String, ItemStack> dayRewards = MultimapBuilder.hashKeys().linkedListValues().build();

    private PluginCommand command;

    private InventoryGui gui;
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
        daysConfig = new ConfigAccessor(this, "days.yml");
        loadConfig();
        (command = getCommand("adventskalender")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    public void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        missedDays = getConfig().getInt("missed-days");
        notificationDelay = getConfig().getInt("notification-delay");
        filler = getConfig().getItemStack("gui.filler");
        if (filler != null) {
            filler.editMeta(meta -> meta.setHideTooltip(true));
        }

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

        daysConfig.saveDefaultConfig();
        daysConfig.reloadConfig();

        dayRewards.clear();

        for (String day : daysConfig.getConfig().getKeys(false)) {
            List<?> mapList = daysConfig.getConfig().getList(day + ".reward");
            for (Object o : mapList) {
                ItemStack item = o instanceof ItemStack ? (ItemStack) o : null;
                if (item != null && !isEmpty(item)) {
                    dayRewards.put(day, item);
                } else {
                    getLogger().log(Level.WARNING, "An item for day " + day + " is invalid! (" + o + ")");
                }
            }
        }

        gui = new InventoryGui(this, getConfig().getString("gui.title"), getConfig().getStringList("gui.layout").toArray(new String[0]));

        gui.setItemNameSetter((meta, string) -> meta.displayName(MineDown.parse(string).decoration(TextDecoration.ITALIC, false)));
        gui.setItemLoreSetter((meta, stringList) -> meta.lore(stringList.stream().map(line -> MineDown.parse(line).decoration(TextDecoration.ITALIC, false)).toList()));

        gui.setFiller(filler);
        gui.addElement(new GuiElementGroup('d', buildElements()));
    }

    private StaticGuiElement buildElement(String key) {
        String[] parts = key.split("\\.");
        return new StaticGuiElement('d',
                getConfig().getItemStack("gui.icons." + key),
                getRawText(parts[0]) + (parts.length > 1 ? "\n" + getRawText(parts[1]) : "")
        );
    }

    private StaticGuiElement getElement(String key, String... replacements) {
        StaticGuiElement element = elements.computeIfAbsent(key, this::buildElement);
        return new StaticGuiElement(element.getSlotChar(), element.getRawItem(), replaceIn(element.getText(), replacements));
    }

    private String getRawText(String key) {
        return getConfig().getString("text." + key);
    }

    private Component getComponents(String key, String... replacements) {
        return MineDown.parse(getRawText(key), replacements);
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
        if (target == null) {
            if (sender instanceof Player) {
                target = (Player) sender;
            } else {
                return false;
            }
        }

        gui.show(target);
        return true;
    }

    private GuiElement[] buildElements() {
        List<GuiElement> elements = new ArrayList<>();
        for (int day : dayOrder) {
            String defType = "day";
            int advent = 0;
            if (day == 24) {
                defType = "christmas";
            } else {
                if ((advent = getAdvent(day)) > 0) {
                    defType = "advent";
                }
            }

            String type = getConfig().getString("days." + day + ".type", defType);
            int finalAdvent = advent;
            elements.add(new DynamicGuiElement('d', (target) -> {
                Calendar calendar = Calendar.getInstance();
                int currentDay = calendar.get(Calendar.DAY_OF_MONTH);
                int currentMonth = calendar.get(Calendar.MONTH);
                int currentYear = calendar.get(Calendar.YEAR);
                StaticGuiElement element;
                String[] replacements = {
                        "day", String.valueOf(day),
                        "year", String.valueOf(currentYear),
                        "advent", String.valueOf(finalAdvent),
                        "title", getConfig().getString("days." + day + ".title", "")
                };
                if (currentMonth < Calendar.DECEMBER || currentDay < day) {
                    element = getElement(type + ".unavailable", replacements);
                } else if (hasRetrieved(target, day)) {
                    element = getElement(type + ".retrieved", replacements);
                } else if (currentDay - missedDays > day) {
                    element = getElement(type + ".missed", replacements);
                } else {
                    element = getElement(type + ".available", replacements);
                    element.setAction(click -> {
                        if (click.getType() == ClickType.MIDDLE || click.getType() == ClickType.RIGHT) {
                            return true;
                        }
                        giveRewards(click.getRawEvent().getWhoClicked(), day);
                        click.getGui().draw();
                        return true;
                    });
                }
                if (target.hasPermission("mbadventskalender.admin")) {
                    String[] text = Arrays.copyOf(element.getText(), element.getText().length + 1);
                    text[text.length - 1] = getRawText("edit");
                    element.setText(text);
                    GuiElement.Action adminAction = click -> {
                        if (click.getType() != ClickType.MIDDLE || click.getType() != ClickType.RIGHT) {
                            return true;
                        }
                        List<ItemStack> rewards = (List<ItemStack>) dayRewards.get(String.valueOf(day));
                        int rows = Math.min(Math.max((rewards.size() + 1) / 9 + 1, 3), 6);
                        InventoryGui adminInv = new InventoryGui(this, day + ". Rewards", Collections.nCopies(rows, String.join("", Collections.nCopies(9, "i"))).toArray(new String[0]));
                        Inventory dayEditInventory = getServer().createInventory(null, rows * 9);
                        GuiStorageElement dayStorageElement = new GuiStorageElement('i', dayEditInventory);
                        dayStorageElement.setApplyStorage(() -> {
                            daysConfig.getConfig().set(day + ".reward", Arrays.stream(dayEditInventory.getContents())
                                    .filter(item -> item != null && !isEmpty(item)).toList());
                            daysConfig.saveConfig();
                        });
                        adminInv.addElement(dayStorageElement);
                        adminInv.show(click.getRawEvent().getWhoClicked());
                        return true;
                    };
                    if (element.getAction(target) == null) {
                        element.setAction(adminAction);
                    } else {
                        GuiElement.Action action = element.getAction(target);
                        element.setAction(click -> action.onClick(click) && adminAction.onClick(click));
                    }
                }
                element.setNumber(day);
                return element;
            }));
        }
        return elements.toArray(new GuiElement[0]);
    }

    private int getAdvent(int day) {
        Calendar dayCal = Calendar.getInstance();
        dayCal.set(Calendar.MONTH, Calendar.DECEMBER);
        dayCal.set(Calendar.DAY_OF_MONTH, day);
        if (dayCal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            return 4 - ((24 - day) / 7);
        }
        return 0;
    }

    private static boolean isEmpty(ItemStack itemStack) {
        return itemStack == null || itemStack.getType() == null || itemStack.isEmpty();
    }

    private void giveRewards(HumanEntity player, int day) {
        retrievedDays.put(player.getUniqueId(), day);
        playerConfig.getConfig().set("players." + player.getUniqueId(), new ArrayList<>(retrievedDays.get(player.getUniqueId())));
        playerConfig.saveConfig();

        List<ItemStack> rewards = new ArrayList<>();
        if (dayRewards.containsKey(String.valueOf(day))) {
            rewards.addAll(dayRewards.get(String.valueOf(day)));
        } else {
            player.sendMessage(ChatColor.RED + "No rewards defined for day " + day + "? :(");
        }

        int advent = getAdvent(day);
        if (day == 1 && advent == 0) {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.MONTH, Calendar.DECEMBER);
            calendar.set(Calendar.DAY_OF_MONTH, 24);
            if (calendar.get(Calendar.DAY_OF_WEEK) > Calendar.TUESDAY) {
                advent = 1;
            }
        }
        if (advent > 0) {
            String key = "advent-" + advent;
            if (dayRewards.containsKey(key)) {
                rewards.addAll(dayRewards.get(key));
            }
        }

        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        Replacer replacer = new Replacer().replace("year", String.valueOf(currentYear));
        rewards.stream().map(item -> {
            ItemStack clone = item.clone();

            clone.editMeta(meta -> {
                meta.displayName(replacer.replaceIn(meta.displayName()));
                meta.lore(replacer.replaceIn(meta.lore()));
            });
            return clone;
        }).map(item -> player.getInventory().addItem(item)).forEach(rest -> {
            if (!rest.isEmpty()) {
                rest.values().forEach(v -> player.getWorld().dropItem(player.getLocation(), v).setOwner(player.getUniqueId()));
            }
        });

        player.sendMessage(getComponents("reward-received", "day", String.valueOf(day)));
        if (player instanceof Player) {
            ((Player) player).playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!command.testPermissionSilent(event.getPlayer())) {
            return;
        }
        Calendar calendar = Calendar.getInstance();
        int currentDay = calendar.get(Calendar.DAY_OF_MONTH);
        if (calendar.get(Calendar.MONTH) != Calendar.DECEMBER || currentDay > 24 + missedDays) {
            return;
        }
        Runnable run = () -> {
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
            getServer().getScheduler().runTaskLater(this, run, notificationDelay * 20L);
        }
    }

    private boolean hasRetrieved(HumanEntity player, int day) {
        return retrievedDays.containsEntry(player.getUniqueId(), day);
    }
}
