package fun.wardensmp.sponsorperks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

/**
 * SponsorPerks — косметика для группы sponsor (LuckPerms).
 *   /spons — GUI: страница свечения + страница питомцев
 *   /glow &<цвет>|stop, /pet <вид>|stop — быстрый доступ
 * Питомцы: до 5 одного вида, плавно следуют за игроком (AI off + телепорт-лерп).
 */
public class SponsorPerks extends JavaPlugin implements Listener, CommandExecutor {

    private static final int MAX_PETS = 5;

    private final Map<UUID, String> glowTeams = new HashMap<>();
    private final Map<UUID, List<UUID>> pets = new HashMap<>();
    private NamespacedKey petKey;
    private boolean tab = false;

    // ---- цвета свечения ----
    private static final ChatColor[] COLORS = {
        ChatColor.BLACK, ChatColor.DARK_BLUE, ChatColor.DARK_GREEN, ChatColor.DARK_AQUA,
        ChatColor.DARK_RED, ChatColor.DARK_PURPLE, ChatColor.GOLD, ChatColor.GRAY,
        ChatColor.DARK_GRAY, ChatColor.BLUE, ChatColor.GREEN, ChatColor.AQUA,
        ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.YELLOW, ChatColor.WHITE
    };
    private static final Material[] WOOLS = {
        Material.BLACK_WOOL, Material.BLUE_WOOL, Material.GREEN_WOOL, Material.CYAN_WOOL,
        Material.RED_WOOL, Material.PURPLE_WOOL, Material.ORANGE_WOOL, Material.LIGHT_GRAY_WOOL,
        Material.GRAY_WOOL, Material.LIGHT_BLUE_WOOL, Material.LIME_WOOL, Material.CYAN_WOOL,
        Material.RED_WOOL, Material.MAGENTA_WOOL, Material.YELLOW_WOOL, Material.WHITE_WOOL
    };
    private static final String[] CNAMES = {
        "Чёрный","Тёмно-синий","Тёмно-зелёный","Тёмная бирюза","Тёмно-красный","Фиолетовый",
        "Золотой","Светло-серый","Тёмно-серый","Синий","Зелёный","Бирюзовый","Красный","Розовый","Жёлтый","Белый"
    };
    private static final int[] COLOR_SLOTS = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29};

    // ---- питомцы (вид собирается в onEnable: пропускаем недоступные на этой версии) ----
    private record PetDef(String key, EntityType type, Material egg, String name, boolean baby, boolean catRandom) {}
    private final List<PetDef> roster = new ArrayList<>();
    private static final int[] PET_SLOTS = {19,20,21,22,23,24,25};

    private static final int SLOT_MAIN_GLOW = 11, SLOT_MAIN_PETS = 15;   // главное меню
    private static final int SLOT_GLOW_OFF = 48, SLOT_PETS_CLEAR = 48, SLOT_BACK = 49;

    @Override
    public void onEnable() {
        petKey = new NamespacedKey(this, "owner");
        tab = getServer().getPluginManager().isPluginEnabled("TAB");
        buildRoster();
        getCommand("glow").setExecutor(this);
        getCommand("pet").setExecutor(this);
        getCommand("spons").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        sweepStrayPets();
        startFollowTask();
        getLogger().info("SponsorPerks on. TAB: " + (tab ? "yes" : "no") + ", питомцев в ростере: " + roster.size());
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) clearGlow(p);
        for (List<UUID> list : pets.values())
            for (UUID id : list) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        pets.clear();
    }

    private void buildRoster() {
        addDef("bee",    "BEE",         "BEE_SPAWN_EGG",         "Пчела",                false, false);
        addDef("fox",    "FOX",         "FOX_SPAWN_EGG",         "Лиса",                 false, false);
        addDef("bat",    "BAT",         "BAT_SPAWN_EGG",         "Летучая мышь",         false, false);
        addDef("zombie", "ZOMBIE",      "ZOMBIE_SPAWN_EGG",      "Зомбёныш",             true,  false);
        addDef("ocelot", "OCELOT",      "OCELOT_SPAWN_EGG",      "Мини-оцелот",          true,  false);
        addDef("cat",    "CAT",         "CAT_SPAWN_EGG",         "Мини-кошка",           true,  true);
        addDef("ghast",  "HAPPY_GHAST", "HAPPY_GHAST_SPAWN_EGG", "Счастливый гастёныш",  true,  false);
    }
    private void addDef(String key, String type, String egg, String name, boolean baby, boolean catRandom) {
        EntityType et;
        try { et = EntityType.valueOf(type); }
        catch (Throwable t) { getLogger().info("Питомец '" + key + "' недоступен на этой версии — пропуск."); return; }
        Material m;
        try { m = Material.valueOf(egg); } catch (Throwable t) { m = Material.EGG; }
        roster.add(new PetDef(key, et, m, name, baby, catRandom));
    }
    private PetDef defByKey(String key) {
        for (PetDef d : roster) if (d.key().equalsIgnoreCase(key)) return d;
        return null;
    }
    private PetDef defByType(EntityType et) {
        for (PetDef d : roster) if (d.type() == et) return d;
        return null;
    }

    // ===================== команды =====================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage("Только для игроков."); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "spons" -> {
                if (!p.hasPermission("sponsor.menu")) { p.sendMessage("§cЭто привилегия §5Спонсора§c."); return true; }
                openMain(p);
            }
            case "glow" -> { return doGlow(p, args); }
            case "pet"  -> { return doPet(p, args); }
        }
        return true;
    }

    // ---------------------- /glow ----------------------
    private boolean doGlow(Player p, String[] args) {
        if (!p.hasPermission("sponsor.glow")) { p.sendMessage("§cЭто привилегия §5Спонсора§c."); return true; }
        if (args.length == 0) { p.sendMessage("§dИспользование: §f/glow &<цвет> §7или §f/glow stop"); return true; }
        if (args[0].equalsIgnoreCase("stop")) { clearGlow(p); p.sendMessage("§7Свечение выключено."); return true; }
        char code = Character.toLowerCase(args[0].charAt(args[0].length() - 1));
        ChatColor cc = ChatColor.getByChar(code);
        if (cc == null || !cc.isColor()) { p.sendMessage("§cЦвет кодом, напр. §f/glow &e§c."); return true; }
        setGlow(p, cc);
        p.sendMessage("§dСвечение включено: " + cc + cc.name().toLowerCase());
        return true;
    }

    private void setGlow(Player p, ChatColor cc) {
        pauseTab(p);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String tn = "spglow_" + cc.name();
        Team t = sb.getTeam(tn);
        if (t == null) t = sb.registerNewTeam(tn);
        t.setColor(cc);
        String prev = glowTeams.get(p.getUniqueId());
        if (prev != null && !prev.equals(tn)) { Team pt = sb.getTeam(prev); if (pt != null) pt.removeEntry(p.getName()); }
        t.addEntry(p.getName());
        glowTeams.put(p.getUniqueId(), tn);
        p.setGlowing(true);
    }

    private void clearGlow(Player p) {
        p.setGlowing(false);
        String tn = glowTeams.remove(p.getUniqueId());
        if (tn != null) { Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(tn); if (t != null) t.removeEntry(p.getName()); }
        resumeTab(p);
    }

    private void pauseTab(Player p) {
        if (!tab) return;
        try {
            me.neznamy.tab.api.TabAPI api = me.neznamy.tab.api.TabAPI.getInstance();
            me.neznamy.tab.api.TabPlayer tp = api.getPlayer(p.getUniqueId());
            if (tp != null) api.getNameTagManager().pauseTeamHandling(tp);
        } catch (Throwable ignored) {}
    }
    private void resumeTab(Player p) {
        if (!tab) return;
        try {
            me.neznamy.tab.api.TabAPI api = me.neznamy.tab.api.TabAPI.getInstance();
            me.neznamy.tab.api.TabPlayer tp = api.getPlayer(p.getUniqueId());
            if (tp != null) api.getNameTagManager().resumeTeamHandling(tp);
        } catch (Throwable ignored) {}
    }

    // ---------------------- /pet ----------------------
    private boolean doPet(Player p, String[] args) {
        if (!p.hasPermission("sponsor.pet")) { p.sendMessage("§cЭто привилегия §5Спонсора§c."); return true; }
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder("§dПитомцы: §f");
            for (PetDef d : roster) sb.append(d.key()).append(" ");
            p.sendMessage(sb.toString().trim() + " §7| §f/pet stop");
            return true;
        }
        if (args[0].equalsIgnoreCase("stop")) { clearPets(p); p.sendMessage("§7Питомцы отпущены."); return true; }
        PetDef d = defByKey(args[0]);
        if (d == null) { p.sendMessage("§cНет такого питомца."); return true; }
        addPet(p, d);
        return true;
    }

    private boolean addPet(Player p, PetDef d) {
        UUID u = p.getUniqueId();
        List<UUID> list = pets.computeIfAbsent(u, k -> new ArrayList<>());
        if (list.size() >= MAX_PETS) { p.sendMessage("§cМаксимум §f" + MAX_PETS + " §cпитомцев всего (любых видов)."); return false; }
        Location loc = p.getLocation().add((Math.random() - 0.5) * 1.6, 0.4, (Math.random() - 0.5) * 1.6);
        Entity e = p.getWorld().spawnEntity(loc, d.type());
        if (d.baby() && e instanceof Ageable a) a.setBaby();
        if (e instanceof Zombie z) z.setShouldBurnInDay(false);
        if (d.catRandom() && e instanceof Cat c) {
            try { Cat.Type[] v = Cat.Type.values(); c.setCatType(v[(int)(Math.random() * v.length)]); } catch (Throwable ignored) {}
        }
        if (e instanceof LivingEntity le) {
            le.setCanPickupItems(false);   // не хватать вещи (фикс лис)
            le.setInvulnerable(true);
            le.setSilent(true);
            le.setCollidable(false);
            le.setRemoveWhenFarAway(false);
            le.setPersistent(false);
            // AI оставляем ВКЛЮЧЁННЫМ — мобы бегают/летают естественно
        }
        e.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, u.toString());
        e.setCustomNameVisible(false);
        list.add(e.getUniqueId());
        p.sendMessage("§dПитомец призван §7(" + list.size() + "/" + MAX_PETS + " всего)§d.");
        return true;
    }

    private void removeOnePet(Player p, EntityType type) {
        List<UUID> list = pets.get(p.getUniqueId());
        if (list == null) return;
        for (int i = list.size() - 1; i >= 0; i--) {
            Entity e = Bukkit.getEntity(list.get(i));
            if (e == null) { list.remove(i); continue; }
            if (e.getType() == type) { e.remove(); list.remove(i); p.sendMessage("§7Питомец убран §d(" + list.size() + "/" + MAX_PETS + ")§7."); return; }
        }
    }

    private int countOfType(UUID owner, EntityType type) {
        List<UUID> list = pets.get(owner);
        if (list == null) return 0;
        int c = 0;
        for (UUID id : list) { Entity e = Bukkit.getEntity(id); if (e != null && e.getType() == type) c++; }
        return c;
    }
    private int totalPets(UUID owner) {
        List<UUID> list = pets.get(owner);
        return list == null ? 0 : list.size();
    }

    private void clearPets(Player p) {
        List<UUID> list = pets.remove(p.getUniqueId());
        if (list != null) for (UUID id : list) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
    }

    private boolean isPet(Entity e) { return e != null && e.getPersistentDataContainer().has(petKey, PersistentDataType.STRING); }

    private void sweepStrayPets() {
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntities())
                if (isPet(e)) e.remove();
    }

    // плавный фоллоу: AI выключен, мы сами лерпим питомца к точке возле игрока
    private void startFollowTask() {
        new BukkitRunnable() {
            @Override public void run() {
                for (UUID owner : new ArrayList<>(pets.keySet())) {
                    Player p = Bukkit.getPlayer(owner);
                    List<UUID> list = pets.get(owner);
                    if (list == null) continue;
                    if (p == null || !p.isOnline()) {
                        for (UUID id : list) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
                        pets.remove(owner); continue;
                    }
                    list.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || e.isDead(); });
                    if (list.isEmpty()) { pets.remove(owner); continue; }
                    Location base = p.getLocation();
                    for (UUID id : list) {
                        Entity pe = Bukkit.getEntity(id);
                        if (pe == null) continue;
                        if (pe instanceof Mob m) m.setTarget(null);   // не агрятся ни на кого
                        double dist = Objects.equals(pe.getWorld(), base.getWorld()) ? pe.getLocation().distance(base) : 999;
                        if (dist > 10) {
                            // отбежал/отлетел далеко — телепорт к игроку (со сдвигом, чтоб не в стопку)
                            pe.teleport(base.clone().add((Math.random() - 0.5) * 2.5, 0, (Math.random() - 0.5) * 2.5));
                        } else if (dist > 4 && pe instanceof Mob m) {
                            m.getPathfinder().moveTo(base, 1.2);      // догоняет своими ногами/крыльями
                        }
                        // в радиусе 4 — гуляет сам, естественным AI
                    }
                }
            }
        }.runTaskTimer(this, 20L, 10L);
    }

    // ===================== GUI =====================
    private enum Page { MAIN, GLOW, PETS }
    private static final class Gui implements InventoryHolder {
        Page page; Inventory inv;
        Gui(Page p) { this.page = p; }
        @Override public Inventory getInventory() { return inv; }
    }

    private ItemStack item(Material m, String name, NamedTextColor color, int amount, String... lore) {
        ItemStack is = new ItemStack(m, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = is.getItemMeta();
        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Component.text(s).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        is.setItemMeta(meta);
        return is;
    }

    private NamedTextColor ntc(ChatColor c) {
        NamedTextColor n = NamedTextColor.NAMES.value(c.name().toLowerCase());
        return n != null ? n : NamedTextColor.WHITE;
    }

    private void fillBg(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY, 1);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private void openMain(Player p) {
        Gui h = new Gui(Page.MAIN);
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Спонсор · Меню").color(NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        fillBg(inv);
        inv.setItem(SLOT_MAIN_GLOW, item(Material.GLOWSTONE, "Свечение", NamedTextColor.LIGHT_PURPLE, 1, "Выбрать цвет обводки модели"));
        inv.setItem(SLOT_MAIN_PETS, item(Material.BONE, "Питомцы", NamedTextColor.AQUA, 1, "Призвать компаньонов"));
        p.openInventory(inv);
    }

    private void openGlow(Player p) {
        Gui h = new Gui(Page.GLOW);
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Спонсор · Свечение").color(NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        fillBg(inv);
        for (int i = 0; i < COLORS.length; i++)
            inv.setItem(COLOR_SLOTS[i], item(WOOLS[i], CNAMES[i], ntc(COLORS[i]), 1, "ЛКМ — включить свечение"));
        inv.setItem(SLOT_GLOW_OFF, item(Material.BARRIER, "Выключить свечение", NamedTextColor.RED, 1, "ЛКМ — снять свечение"));
        inv.setItem(SLOT_BACK, item(Material.ARROW, "← Меню", NamedTextColor.GRAY, 1, "В главное меню"));
        p.openInventory(inv);
    }

    private void openPets(Player p) {
        Gui h = new Gui(Page.PETS);
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Спонсор · Питомцы").color(NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        fillBg(inv);
        int total = totalPets(p.getUniqueId());
        for (int i = 0; i < roster.size() && i < PET_SLOTS.length; i++) {
            PetDef d = roster.get(i);
            int c = countOfType(p.getUniqueId(), d.type());
            inv.setItem(PET_SLOTS[i], item(d.egg(), d.name(), NamedTextColor.AQUA, Math.max(1, c),
                    "ЛКМ — призвать · ПКМ — убрать",
                    "Этого вида: " + c,
                    "Всего: " + total + "/" + MAX_PETS));
        }
        inv.setItem(4, item(Material.NAME_TAG, "Питомцев: " + total + "/" + MAX_PETS, NamedTextColor.LIGHT_PURPLE, 1, "Можно мешать любые виды"));
        inv.setItem(SLOT_PETS_CLEAR, item(Material.BARRIER, "Распустить всех", NamedTextColor.RED, 1, "ЛКМ — убрать питомцев"));
        inv.setItem(SLOT_BACK, item(Material.ARROW, "← Меню", NamedTextColor.GRAY, 1, "В главное меню"));
        p.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Gui gui)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (gui.page == Page.MAIN) {
            if (slot == SLOT_MAIN_GLOW) openGlow(p);
            else if (slot == SLOT_MAIN_PETS) openPets(p);
            return;
        }
        if (gui.page == Page.GLOW) {
            if (slot == SLOT_GLOW_OFF) { clearGlow(p); p.sendMessage("§7Свечение выключено."); return; }
            if (slot == SLOT_BACK) { openMain(p); return; }
            for (int i = 0; i < COLOR_SLOTS.length; i++)
                if (COLOR_SLOTS[i] == slot) { setGlow(p, COLORS[i]); p.sendMessage("§dСвечение: " + COLORS[i] + CNAMES[i]); return; }
        } else {
            if (slot == SLOT_PETS_CLEAR) { clearPets(p); openPets(p); return; }
            if (slot == SLOT_BACK) { openMain(p); return; }
            for (int i = 0; i < roster.size() && i < PET_SLOTS.length; i++)
                if (PET_SLOTS[i] == slot) {
                    if (e.isRightClick()) removeOnePet(p, roster.get(i).type());
                    else addPet(p, roster.get(i));
                    openPets(p); return;
                }
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    // ===================== защита =====================
    @EventHandler public void onTarget(EntityTargetEvent e) { if (isPet(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (isPet(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void onDamageBy(EntityDamageByEntityEvent e) { if (isPet(e.getDamager())) e.setCancelled(true); }
    @EventHandler public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) { if (isPet(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { clearPets(e.getPlayer()); clearGlow(e.getPlayer()); }
}
