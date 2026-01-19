package de.jan.bettervillagers;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public class BetterVillagers extends JavaPlugin implements Listener {
    private static final int MAIN_MENU_SIZE = 27;
    private static final int SEARCH_MENU_SIZE = 54;
    private static final int RESET_EMERALDS = 64;
    private static final int RESET_LEVELS = 2;

    private final Map<UUID, Villager> activeVillagers = new HashMap<>();
    private final Map<UUID, VillagerSearchData> searchingPlayers = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }

        event.setCancelled(true);
        activeVillagers.put(player.getUniqueId(), villager);
        player.getScheduler().execute(this, () -> openMainMenu(player), null);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof MenuHolder menuHolder)) {
            return;
        }

        event.setCancelled(true);
        Villager villager = activeVillagers.get(player.getUniqueId());
        if (villager == null) {
            return;
        }

        int slot = event.getRawSlot();
        if (menuHolder instanceof MainMenuHolder) {
            handleMainMenuClick(player, villager, slot);
        } else if (menuHolder instanceof TradesMenuHolder tradesMenuHolder) {
            handleTradesMenuClick(player, villager, slot, tradesMenuHolder);
        } else if (menuHolder instanceof SearchMenuHolder searchMenuHolder) {
            handleSearchMenuClick(player, villager, slot, searchMenuHolder);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        searchingPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        VillagerSearchData searchData = searchingPlayers.get(player.getUniqueId());
        if (searchData == null) {
            return;
        }

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        String normalized = message.toLowerCase(Locale.ROOT).trim();
        searchingPlayers.remove(player.getUniqueId());

        if (normalized.equals("cancel")) {
            player.getScheduler().execute(this, () -> player.sendMessage(message("Suche abgebrochen!", NamedTextColor.RED)), null);
            return;
        }

        player.getScheduler().execute(this, () -> openSearchMenu(player, searchData.villager(), Optional.of(normalized)), null);
    }

    private void handleMainMenuClick(Player player, Villager villager, int slot) {
        if (slot == 11) {
            player.getScheduler().execute(this, () -> openTradesMenu(player, villager), null);
        } else if (slot == 15) {
            player.getScheduler().execute(this, () -> openSearchMenu(player, villager, Optional.empty()), null);
        } else if (slot == 22) {
            player.closeInventory();
        }
    }

    private void handleTradesMenuClick(Player player, Villager villager, int slot, TradesMenuHolder holder) {
        int size = holder.size();
        if (slot == size - 9) {
            player.getScheduler().execute(this, () -> openSearchMenu(player, villager, Optional.empty()), null);
        } else if (slot == size - 5) {
            attemptTradeReset(player, villager);
        } else if (slot == size - 1) {
            player.getScheduler().execute(this, () -> openMainMenu(player), null);
        }
    }

    private void handleSearchMenuClick(Player player, Villager villager, int slot, SearchMenuHolder holder) {
        if (slot == 4) {
            player.closeInventory();
            searchingPlayers.put(player.getUniqueId(), new VillagerSearchData(villager, holder.filter()));
            player.sendMessage(message("Gib den Suchbegriff im Chat ein (oder 'cancel' zum Abbrechen)", NamedTextColor.YELLOW));
        } else if (slot == 49) {
            player.getScheduler().execute(this, () -> openSearchMenu(player, villager, Optional.empty()), null);
        } else if (slot == 53) {
            player.getScheduler().execute(this, () -> openTradesMenu(player, villager), null);
        }
    }

    private void attemptTradeReset(Player player, Villager villager) {
        player.getScheduler().execute(this, () -> {
            if (!hasEmeralds(player, RESET_EMERALDS)) {
                player.sendMessage(message("Du brauchst 64 Emeralds!", NamedTextColor.RED));
                return;
            }
            if (player.getLevel() < RESET_LEVELS) {
                player.sendMessage(message("Du brauchst mindestens Level 2!", NamedTextColor.RED));
                return;
            }

            removeEmeralds(player, RESET_EMERALDS);
            player.setLevel(player.getLevel() - RESET_LEVELS);

            villager.getScheduler().execute(this, () -> {
                villager.setVillagerExperience(0);
                villager.resetOffers();
                player.getScheduler().execute(this, () -> player.sendMessage(message("Trades zurückgesetzt! (-64 Emeralds, -2 Level)", NamedTextColor.GREEN)), null);
            }, null);
        }, null);
    }

    private void openMainMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(new MainMenuHolder(), MAIN_MENU_SIZE, message("Villager Menü", NamedTextColor.DARK_PURPLE));
        inventory.setItem(11, menuItem(Material.EMERALD, "Trades anpassen", NamedTextColor.GREEN,
            List.of("Kostet: 64 Emeralds + 2 Level", "Klicke um die Trades anzupassen")));
        inventory.setItem(15, menuItem(Material.COMPASS, "Trades durchsuchen", NamedTextColor.AQUA,
            List.of("Suche nach bestimmten Items", "Klicke um zu suchen")));
        inventory.setItem(22, menuItem(Material.BARRIER, "Schließen", NamedTextColor.RED, List.of()));
        player.openInventory(inventory);
    }

    private void openTradesMenu(Player player, Villager villager) {
        List<MerchantRecipe> recipes = villager.getRecipes();
        int totalSlots = Math.min(54, ((recipes.size() + 8) / 9 + 1) * 9);
        Inventory inventory = Bukkit.createInventory(new TradesMenuHolder(totalSlots), totalSlots,
            message("Villager Trades", NamedTextColor.GOLD));

        int index = 0;
        for (int i = 0; i < recipes.size() && index < totalSlots - 9; i++) {
            MerchantRecipe recipe = recipes.get(i);
            inventory.setItem(index++, tradeItem(recipe, i + 1));
        }

        inventory.setItem(totalSlots - 9, menuItem(Material.COMPASS, "Suchen", NamedTextColor.AQUA,
            List.of("Klicke um nach Trades zu suchen")));
        ItemStack resetItem = menuItem(Material.EMERALD_BLOCK, "Trades zurücksetzen", NamedTextColor.RED,
            List.of("Kostet: 64 Emeralds + 2 Level", "Setzt alle Trades zurück!"));
        resetItem.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.LUCK, 1);
        inventory.setItem(totalSlots - 5, resetItem);
        inventory.setItem(totalSlots - 1, menuItem(Material.ARROW, "Zurück", NamedTextColor.GRAY, List.of()));
        player.openInventory(inventory);
    }

    private void openSearchMenu(Player player, Villager villager, Optional<String> filter) {
        List<MerchantRecipe> recipes = villager.getRecipes();
        List<MerchantRecipe> filtered = filter
            .map(text -> recipes.stream().filter(recipe -> tradeMatches(recipe, text)).toList())
            .orElse(recipes);

        String title = filter.map(text -> "Trade Suche (" + filtered.size() + " Gefunden)")
            .orElse("Trade Suche");
        Inventory inventory = Bukkit.createInventory(new SearchMenuHolder(filter), SEARCH_MENU_SIZE,
            message(title, NamedTextColor.AQUA));

        inventory.setItem(4, menuItem(Material.NAME_TAG, "Suchbegriff eingeben", NamedTextColor.YELLOW,
            List.of("Schreibe im Chat den Material-Namen", "z.B. 'diamond' oder 'emerald'")));

        int index = 0;
        for (int i = 0; i < filtered.size(); i++) {
            while (index == 4 || index == 49 || index == 53) {
                index++;
            }
            if (index >= SEARCH_MENU_SIZE) {
                break;
            }
            inventory.setItem(index++, tradeItem(filtered.get(i), i + 1));
        }

        inventory.setItem(49, menuItem(Material.BOOK, "Alle Trades anzeigen", NamedTextColor.GREEN, List.of()));
        inventory.setItem(53, menuItem(Material.ARROW, "Zurück", NamedTextColor.GRAY, List.of()));
        player.openInventory(inventory);
    }

    private ItemStack tradeItem(MerchantRecipe recipe, int index) {
        ItemStack display = recipe.getResult().clone();
        ItemMeta meta = display.getItemMeta();
        meta.displayName(message("Trade #" + index, NamedTextColor.GOLD));

        List<Component> lore = new ArrayList<>();
        List<ItemStack> ingredients = recipe.getIngredients();
        for (int i = 0; i < ingredients.size(); i++) {
            ItemStack ingredient = ingredients.get(i);
            lore.add(componentLine("Input " + (i + 1) + ": " + ingredient.getAmount() + " " + formatMaterial(ingredient.getType()),
                NamedTextColor.YELLOW));
        }
        ItemStack result = recipe.getResult();
        lore.add(componentLine("Ergebnis: " + result.getAmount() + " " + formatMaterial(result.getType()), NamedTextColor.GREEN));
        lore.add(componentLine("Verwendungen: " + recipe.getUses() + "/" + recipe.getMaxUses(), NamedTextColor.GRAY));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private boolean tradeMatches(MerchantRecipe recipe, String filter) {
        String normalized = filter.toLowerCase(Locale.ROOT);
        if (recipe.getResult().getType().name().toLowerCase(Locale.ROOT).contains(normalized)) {
            return true;
        }
        return recipe.getIngredients().stream()
            .anyMatch(item -> item.getType().name().toLowerCase(Locale.ROOT).contains(normalized));
    }

    private ItemStack menuItem(Material material, String name, NamedTextColor color, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message(name, color));
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(componentLine(line, NamedTextColor.GRAY));
            }
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private Component message(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private Component componentLine(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private String formatMaterial(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private boolean hasEmeralds(Player player, int amount) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.EMERALD) {
                count += item.getAmount();
                if (count >= amount) {
                    return true;
                }
            }
        }
        return false;
    }

    private void removeEmeralds(Player player, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != Material.EMERALD) {
                continue;
            }
            int remove = Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - remove);
            remaining -= remove;
            if (item.getAmount() <= 0) {
                contents[i] = null;
            }
            if (remaining <= 0) {
                break;
            }
        }
        player.getInventory().setContents(contents);
    }

    private sealed interface MenuHolder extends InventoryHolder permits MainMenuHolder, TradesMenuHolder, SearchMenuHolder {
        @Override
        default Inventory getInventory() {
            return null;
        }
    }

    private static final class MainMenuHolder implements MenuHolder {
    }

    private static final class TradesMenuHolder implements MenuHolder {
        private final int size;

        private TradesMenuHolder(int size) {
            this.size = size;
        }

        private int size() {
            return size;
        }
    }

    private static final class SearchMenuHolder implements MenuHolder {
        private final Optional<String> filter;

        private SearchMenuHolder(Optional<String> filter) {
            this.filter = filter;
        }

        private Optional<String> filter() {
            return filter;
        }
    }

    private record VillagerSearchData(Villager villager, Optional<String> filter) {
    }
}
