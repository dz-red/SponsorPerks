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
import org.bukkit.event.player.PlayerJoinEvent;
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
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * SponsorPerks — косметика для группы sponsor (LuckPerms).
 *   /spons — GUI: свечение / питомцы / партиклы
 *   /glow &<цвет>|rainbow|stop, /pet <вид>|stop, /fx <эффект>|stop — быстрый доступ
 */
public class SponsorPerks extends JavaPlugin implements Listener, CommandExecutor {

    private static final int MAX_PETS = 5;

    private final Map<UUID, String> glowTeams = new HashMap<>();
    private final Map<UUID, List<UUID>> pets = new HashMap<>();
    private final Set<UUID> rainbowGlow = new HashSet<>();
    private final Map<UUID, String> activeFx = new HashMap<>();
    private int fxPhase = 0;
    private NamespacedKey petKey, petKindKey, kGlow, kFx, kPets, kAcc;
    private boolean tab = false;

    // ---- аксессуары (display-энтити на теле) ----
    private final Map<UUID, String> activeAcc = new HashMap<>();       // владелец -> ключ аксессуара
    private final Map<UUID, Double> accScale = new HashMap<>();        // владелец -> множитель размера
    private final Map<UUID, List<UUID>> accDisplays = new HashMap<>(); // владелец -> дисплеи (1-2 шт)
    private static final double ACC_MIN = 0.2, ACC_MAX = 3.0, ACC_STEP = 0.2;

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
    private static final ChatColor[] RAINBOW = {
        ChatColor.RED, ChatColor.GOLD, ChatColor.YELLOW, ChatColor.GREEN, ChatColor.AQUA, ChatColor.BLUE, ChatColor.LIGHT_PURPLE
    };

    // ---- питомцы ----
    private record PetDef(String key, EntityType type, Material egg, String name, boolean baby, boolean catRandom) {}
    private final List<PetDef> roster = new ArrayList<>();
    private static final int[] PET_SLOTS = {19,20,21,22,23,24,25, 28,29,30,31,32,33,34};

    // ---- партиклы ----
    private record FxDef(String key, String style, Particle particle, Material icon, String name) {}
    private final List<FxDef> fxRoster = new ArrayList<>();
    private static final int[] FX_SLOTS = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};

    // ---- аксессуары: составная модель из частей; mode = точка крепления + анимация ----
    // AccPart: смещение в системе игрока (fwd=вперёд, right=вправо, up=вверх, блоки),
    //          неравномерный масштаб (sx,sy,sz) и собственный поворот (rx,ry,rz, градусы).
    private record AccPart(boolean item, Material mat,
                           float fwd, float right, float up,
                           float sx, float sy, float sz,
                           float rx, float ry, float rz) {}
    private record AccDef(String key, Material icon, String name, String mode, List<AccPart> parts) {}
    private static AccPart blk(Material m, float fwd, float right, float up, float sx, float sy, float sz, float rx, float ry, float rz) {
        return new AccPart(false, m, fwd, right, up, sx, sy, sz, rx, ry, rz);
    }
    private static AccPart itm(Material m, float fwd, float right, float up, float s, float rx, float ry, float rz) {
        return new AccPart(true, m, fwd, right, up, s, s, s, rx, ry, rz);
    }
    // Кольцо из n блоков радиусом r на высоте up; каждый чётный — «зубец» (выше и тоньше).
    private static List<AccPart> ringOf(Material m, int n, float r, float up, float s, float pointUp, float pointH) {
        List<AccPart> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            float f = (float) (Math.cos(a) * r), rt = (float) (Math.sin(a) * r);
            boolean point = (i % 2 == 0);
            l.add(blk(m, f, rt, point ? up + pointUp : up, s, point ? pointH : s, s, 0, (float) Math.toDegrees(-a), 0));
        }
        return l;
    }
    private final List<AccDef> accRoster = new ArrayList<>();
    private static final int[] ACC_SLOTS = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};

    private static final int SLOT_MAIN_GLOW = 10, SLOT_MAIN_FX = 12, SLOT_MAIN_PETS = 14, SLOT_MAIN_ACC = 16;
    private static final int SLOT_GLOW_OFF = 48, SLOT_PETS_CLEAR = 48, SLOT_FX_OFF = 48, SLOT_BACK = 49;
    private static final int SLOT_GLOW_RAINBOW = 31;
    private static final int SLOT_ACC_OFF = 48, SLOT_ACC_SIZE_DOWN = 47, SLOT_ACC_SIZE_UP = 51, SLOT_ACC_INFO = 4;

    @Override
    public void onEnable() {
        petKey = new NamespacedKey(this, "owner");
        petKindKey = new NamespacedKey(this, "petkind");
        kGlow = new NamespacedKey(this, "save_glow");
        kFx = new NamespacedKey(this, "save_fx");
        kPets = new NamespacedKey(this, "save_pets");
        kAcc = new NamespacedKey(this, "save_acc");
        tab = getServer().getPluginManager().isPluginEnabled("TAB");
        buildRoster();
        buildFx();
        buildAcc();
        getCommand("glow").setExecutor(this);
        getCommand("pet").setExecutor(this);
        getCommand("spons").setExecutor(this);
        getCommand("fx").setExecutor(this);
        getCommand("acc").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        sweepStrayPets();
        startFollowTask();
        startCosmeticTask();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("sponsor.menu")) Bukkit.getScheduler().runTaskLater(this, () -> restoreCosmetics(p), 20L);
        getLogger().info("SponsorPerks on. TAB: " + (tab ? "yes" : "no") + ", питомцев: " + roster.size() + ", партиклов: " + fxRoster.size());
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) clearGlowLive(p);
        for (List<UUID> list : pets.values())
            for (UUID id : list) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        pets.clear();
        for (List<UUID> list : accDisplays.values())
            for (UUID id : list) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        accDisplays.clear();
        activeAcc.clear();
        accScale.clear();
        activeFx.clear();
        rainbowGlow.clear();
    }

    private void buildRoster() {
        addDef("bee",    "BEE",         "BEE_SPAWN_EGG",         "Пчела",                false, false);
        addDef("fox",    "FOX",         "FOX_SPAWN_EGG",         "Лиса",                 false, false);
        addDef("bat",    "BAT",         "BAT_SPAWN_EGG",         "Летучая мышь",         false, false);
        addDef("zombie", "ZOMBIE",      "ZOMBIE_SPAWN_EGG",      "Зомбёныш",             true,  false);
        addDef("ocelot", "OCELOT",      "OCELOT_SPAWN_EGG",      "Мини-оцелот",          true,  false);
        addDef("cat",    "CAT",         "CAT_SPAWN_EGG",         "Мини-кошка",           true,  true);
        addDef("ghast",  "HAPPY_GHAST", "HAPPY_GHAST_SPAWN_EGG", "Счастливый гастёныш",  true,  false);
        addDef("parrot", "PARROT",      "PARROT_SPAWN_EGG",      "Попугай",              false, false);
        addDef("allay",  "ALLAY",       "ALLAY_SPAWN_EGG",       "Аллай",                false, false);
        addDef("axolotl","AXOLOTL",     "AXOLOTL_SPAWN_EGG",     "Аксолотль",            true,  false);
        addDef("wolf",   "WOLF",        "WOLF_SPAWN_EGG",        "Волчонок",             true,  false);
        addDef("rabbit", "RABBIT",      "RABBIT_SPAWN_EGG",      "Кролик",               false, false);
        addDef("frog",   "FROG",        "FROG_SPAWN_EGG",        "Лягушка",              false, false);
    }
    private void addDef(String key, String type, String egg, String name, boolean baby, boolean catRandom) {
        EntityType et;
        try { et = EntityType.valueOf(type); }
        catch (Throwable t) { getLogger().info("Питомец '" + key + "' недоступен — пропуск."); return; }
        Material m;
        try { m = Material.valueOf(egg); } catch (Throwable t) { m = Material.EGG; }
        roster.add(new PetDef(key, et, m, name, baby, catRandom));
    }
    private PetDef defByKey(String key) { for (PetDef d : roster) if (d.key().equalsIgnoreCase(key)) return d; return null; }

    private void buildFx() {
        addFx("flame_trail",  "TRAIL", "FLAME",            "BLAZE_POWDER",    "Огненный шлейф");
        addFx("heart_trail",  "TRAIL", "HEART",            "POPPY",           "Сердечки");
        addFx("portal_trail", "TRAIL", "PORTAL",           "ENDER_PEARL",     "Портальный шлейф");
        addFx("soul_trail",   "TRAIL", "SOUL_FIRE_FLAME",  "SOUL_LANTERN",    "Шлейф душ");
        addFx("cherry_trail", "TRAIL", "CHERRY_LEAVES",    "PINK_PETALS",     "Вишнёвый шлейф");
        addFx("snow_trail",   "TRAIL", "SNOWFLAKE",        "SNOWBALL",        "Снежный шлейф");
        addFx("totem_trail",  "TRAIL", "TOTEM_OF_UNDYING", "TOTEM_OF_UNDYING","Конфетти");
        addFx("spark_trail",  "TRAIL", "ELECTRIC_SPARK",   "GLOWSTONE_DUST",  "Искры");
        addFx("ender_aura",   "AURA",  "PORTAL",           "CHORUS_FRUIT",    "Эндер-аура");
        addFx("flame_aura",   "AURA",  "FLAME",            "FIRE_CHARGE",     "Пламенная аура");
        addFx("enchant_aura", "AURA",  "ENCHANT",          "ENCHANTED_BOOK",  "Чарующая аура");
        addFx("halo_fire",    "HALO",  "FLAME",            "GOLDEN_HELMET",   "Нимб огня");
        addFx("halo_soul",    "HALO",  "SOUL_FIRE_FLAME",  "SOUL_TORCH",      "Нимб душ");
        addFx("halo_end",     "HALO",  "END_ROD",          "END_ROD",         "Нимб эндера");
        addFx("wings_angel",  "WINGS", "END_ROD",          "FEATHER",         "Крылья ангела");
        addFx("wings_dragon", "WINGS", "FLAME",            "FIRE_CHARGE",     "Крылья дракона");
        addFx("wings_soul",   "WINGS", "SOUL_FIRE_FLAME",  "SOUL_LANTERN",    "Крылья душ");
        addFx("wings_end",    "WINGS", "WITCH",            "DRAGON_HEAD",     "Крылья Края");
        addFx("wings_fairy",  "WINGS", "CHERRY_LEAVES",    "PINK_PETALS",     "Крылья феи");
        addFx("wings_storm",  "WINGS", "ELECTRIC_SPARK",   "GLOWSTONE_DUST",  "Крылья бури");
    }
    private void addFx(String key, String style, String particle, String icon, String name) {
        Particle pt;
        try { pt = Particle.valueOf(particle); }
        catch (Throwable t) { getLogger().info("Партикл '" + particle + "' недоступен — пропуск " + key); return; }
        Material m; try { m = Material.valueOf(icon); } catch (Throwable t) { m = Material.FIREWORK_ROCKET; }
        fxRoster.add(new FxDef(key, style, pt, m, name));
    }
    private FxDef fxByKey(String key) { for (FxDef f : fxRoster) if (f.key().equalsIgnoreCase(key)) return f; return null; }

    private void buildAcc() {
        // Рюкзак: приплюснутая коробка на спине.
        accRoster.add(new AccDef("backpack", Material.PURPLE_SHULKER_BOX, "Рюкзак выживальщика", "BACK", List.of(
            blk(Material.PURPLE_SHULKER_BOX, -0.30f, 0f, 1.15f, 0.62f, 0.72f, 0.42f, 0, 0, 0)
        )));
        // Скрещенные мечи: два итем-меча крест-накрест на спине.
        accRoster.add(new AccDef("swords", Material.NETHERITE_SWORD, "Скрещенные мечи", "CROSSED", List.of(
            itm(Material.NETHERITE_SWORD, -0.34f, 0f, 1.15f, 1.35f, 0, 180, 20),
            itm(Material.NETHERITE_SWORD, -0.34f, 0f, 1.15f, 1.35f, 0, 0, -20)
        )));
        // Топор за спиной наискось.
        accRoster.add(new AccDef("axe", Material.NETHERITE_AXE, "Топор за спиной", "BACK", List.of(
            itm(Material.NETHERITE_AXE, -0.32f, 0f, 1.15f, 1.25f, 0, 180, 45)
        )));
        // Каменные крылья: два веера по 2 наклонных «пера» из кальцита.
        accRoster.add(new AccDef("wings", Material.AMETHYST_CLUSTER, "Каменные крылья", "WINGS", List.of(
            blk(Material.CALCITE, -0.22f, -0.30f, 1.35f, 0.70f, 0.10f, 0.45f, 0,  25,  55),
            blk(Material.CALCITE, -0.32f, -0.55f, 1.18f, 0.60f, 0.09f, 0.40f, 0,  40,  62),
            blk(Material.CALCITE, -0.22f,  0.30f, 1.35f, 0.70f, 0.10f, 0.45f, 0, -25, -55),
            blk(Material.CALCITE, -0.32f,  0.55f, 1.18f, 0.60f, 0.09f, 0.40f, 0, -40, -62)
        )));
        // Штырь: ствол + головка + два шара у основания, на уровне паха.
        accRoster.add(new AccDef("dick", Material.RED_TERRACOTTA, "Терракотовый штырь", "LEG", List.of(
            blk(Material.RED_TERRACOTTA,  0.42f,  0f,    0.55f, 0.26f, 0.26f, 0.72f, 12, 0, 0), // ствол вперёд
            blk(Material.PINK_TERRACOTTA, 0.80f,  0f,    0.47f, 0.32f, 0.32f, 0.26f, 0,  0, 0), // головка
            blk(Material.RED_TERRACOTTA,  0.28f, -0.13f, 0.40f, 0.22f, 0.24f, 0.22f, 0,  0, 0), // яйца
            blk(Material.RED_TERRACOTTA,  0.28f,  0.13f, 0.40f, 0.22f, 0.24f, 0.22f, 0,  0, 0)
        )));
        // Шарик: светящийся шар парит выше пояса + цепочка-верёвка.
        accRoster.add(new AccDef("balloon", Material.SHROOMLIGHT, "Шарик на верёвочке", "BALLOON", List.of(
            blk(Material.SHROOMLIGHT, 0f, 0f, 0.55f, 0.50f, 0.60f, 0.50f, 0, 0, 0),
            blk(Material.CHAIN,       0f, 0f, 0.02f, 0.12f, 1.10f, 0.12f, 0, 0, 0)
        )));
        // Хвост: цепочка из 4 сегментов дугой вниз-назад, белый кончик.
        accRoster.add(new AccDef("tail", Material.ORANGE_WOOL, "Хвост", "TAIL", List.of(
            blk(Material.ORANGE_WOOL, -0.28f, 0f, 0.85f, 0.30f, 0.30f, 0.30f, 0, 0, 0),
            blk(Material.ORANGE_WOOL, -0.48f, 0f, 0.70f, 0.26f, 0.26f, 0.26f, 0, 0, 0),
            blk(Material.ORANGE_WOOL, -0.66f, 0f, 0.52f, 0.22f, 0.22f, 0.22f, 0, 0, 0),
            blk(Material.WHITE_WOOL,  -0.80f, 0f, 0.34f, 0.18f, 0.18f, 0.18f, 0, 0, 0)
        )));
        // Спутник: корпус + антенна, вращается по орбите.
        accRoster.add(new AccDef("satellite", Material.END_ROD, "Орбитальный спутник", "ORBIT", List.of(
            blk(Material.IRON_BLOCK, 0f, 0f, 0f,    0.28f, 0.28f, 0.28f, 0, 0, 0),
            blk(Material.END_ROD,    0f, 0f, 0.28f, 0.10f, 0.10f, 0.50f, 0, 0, 0)
        )));
        // Дух: душевный фонарь + факелок, парит и скользит сзади.
        accRoster.add(new AccDef("spirit", Material.SOUL_LANTERN, "Дух-спутник", "FOLLOW", List.of(
            blk(Material.SOUL_LANTERN, 0f, 0f,  0.12f, 0.50f, 0.50f, 0.50f, 0, 0, 0),
            blk(Material.SOUL_TORCH,   0f, 0f, -0.05f, 0.20f, 0.40f, 0.20f, 0, 0, 0)
        )));
        // 🐷 Пятак + уши: розовый пятак на лице + два уха сверху.
        accRoster.add(new AccDef("pig", Material.PINK_WOOL, "Свинья: пятак и уши", "FACE", List.of(
            blk(Material.PINK_TERRACOTTA, 0.26f,  0f,    1.55f, 0.30f, 0.24f, 0.12f, 0, 0, 0),  // пятак
            blk(Material.PINK_WOOL,       0.02f, -0.15f, 1.92f, 0.15f, 0.20f, 0.07f, 0, 0,  22), // левое ухо
            blk(Material.PINK_WOOL,       0.02f,  0.15f, 1.92f, 0.15f, 0.20f, 0.07f, 0, 0, -22)  // правое ухо
        )));
        // 👑 Корона: кольцо из золотых блоков с зубцами над головой.
        accRoster.add(new AccDef("crown", Material.GOLD_BLOCK, "Корона", "HEAD",
            ringOf(Material.GOLD_BLOCK, 8, 0.20f, 1.93f, 0.12f, 0.12f, 0.26f)));
        // 🎩 Цилиндр: поля + тулья + лента.
        accRoster.add(new AccDef("hat", Material.BLACK_CONCRETE, "Цилиндр", "HEAD", List.of(
            blk(Material.BLACK_CONCRETE, 0f, 0f, 1.90f, 0.55f, 0.06f, 0.55f, 0, 0, 0), // поля
            blk(Material.RED_CONCRETE,   0f, 0f, 1.97f, 0.40f, 0.09f, 0.40f, 0, 0, 0), // лента
            blk(Material.BLACK_CONCRETE, 0f, 0f, 2.14f, 0.36f, 0.44f, 0.36f, 0, 0, 0)  // тулья
        )));
        // 😈 Рога демона: два изогнутых рога (основание + кончик) на голове.
        accRoster.add(new AccDef("horns", Material.BLACKSTONE, "Рога демона", "HEAD", List.of(
            blk(Material.BLACKSTONE, 0.02f, -0.13f, 1.92f, 0.13f, 0.17f, 0.13f, -15, 0,  26), // лев. основание
            blk(Material.BLACKSTONE, -0.03f, -0.21f, 2.10f, 0.08f, 0.15f, 0.08f, -28, 0,  42), // лев. кончик
            blk(Material.BLACKSTONE, 0.02f,  0.13f, 1.92f, 0.13f, 0.17f, 0.13f, -15, 0, -26), // прав. основание
            blk(Material.BLACKSTONE, -0.03f,  0.21f, 2.10f, 0.08f, 0.15f, 0.08f, -28, 0, -42)  // прав. кончик
        )));
        // 💰 Мешок золота: мешок на правом бедре + золото сверху.
        accRoster.add(new AccDef("moneybag", Material.GOLD_INGOT, "Мешок золота", "BELT", List.of(
            blk(Material.BROWN_WOOL, -0.05f, 0.34f, 0.85f, 0.28f, 0.32f, 0.24f, 0, 0, 0), // мешок
            blk(Material.GOLD_BLOCK, -0.05f, 0.34f, 1.03f, 0.20f, 0.12f, 0.18f, 0, 0, 0)  // золото торчит
        )));
        // 🚀 Реактивный ранец: два баллона + сопла + пламя (светится).
        accRoster.add(new AccDef("jetpack", Material.FURNACE, "Реактивный ранец", "BACK", List.of(
            blk(Material.NETHERITE_BLOCK, -0.28f, -0.16f, 1.18f, 0.20f, 0.52f, 0.20f, 0, 0, 0), // баллон L
            blk(Material.NETHERITE_BLOCK, -0.28f,  0.16f, 1.18f, 0.20f, 0.52f, 0.20f, 0, 0, 0), // баллон R
            blk(Material.IRON_BLOCK,      -0.28f, -0.16f, 0.84f, 0.13f, 0.13f, 0.13f, 0, 0, 0), // сопло L
            blk(Material.IRON_BLOCK,      -0.28f,  0.16f, 0.84f, 0.13f, 0.13f, 0.13f, 0, 0, 0), // сопло R
            blk(Material.MAGMA_BLOCK,     -0.28f, -0.16f, 0.72f, 0.10f, 0.12f, 0.10f, 0, 0, 0), // пламя L
            blk(Material.MAGMA_BLOCK,     -0.28f,  0.16f, 0.72f, 0.10f, 0.12f, 0.10f, 0, 0, 0)  // пламя R
        )));
        // 🧊 Ледяные шипы: три растянутых шипа по хребту (основание + кончик), с наклоном назад.
        accRoster.add(new AccDef("icespikes", Material.PACKED_ICE, "Ледяные шипы", "BACK", List.of(
            blk(Material.PACKED_ICE, -0.24f, 0f, 0.95f, 0.16f, 0.42f, 0.16f, -20, 0, 0),
            blk(Material.BLUE_ICE,   -0.30f, 0f, 1.24f, 0.10f, 0.30f, 0.10f, -22, 0, 0),
            blk(Material.PACKED_ICE, -0.30f, 0f, 1.30f, 0.17f, 0.48f, 0.17f, -26, 0, 0),
            blk(Material.BLUE_ICE,   -0.37f, 0f, 1.62f, 0.10f, 0.34f, 0.10f, -28, 0, 0),
            blk(Material.PACKED_ICE, -0.35f, 0f, 1.66f, 0.15f, 0.42f, 0.15f, -30, 0, 0),
            blk(Material.BLUE_ICE,   -0.42f, 0f, 1.96f, 0.09f, 0.30f, 0.09f, -32, 0, 0)
        )));
    }
    private AccDef accByKey(String key) { for (AccDef d : accRoster) if (d.key().equalsIgnoreCase(key)) return d; return null; }

    // ===================== команды =====================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage("Только для игроков."); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "spons" -> {
                // Открытие настоящего GUI — только внутренним вызовом из /menu (/spons open)
                if (args.length > 0 && args[0].equalsIgnoreCase("open")) {
                    if (!p.hasPermission("sponsor.menu")) { p.sendMessage("§cЭто привилегия §5Спонсора§c."); return true; }
                    openMain(p);
                    return true;
                }
                // Обычный /spons — подсказка о переезде + открыть хаб
                p.sendMessage("§dСпонсорское меню переехало в §f/menu §7→ §d«Косметика спонсора»§7.");
                p.performCommand("menu");
                return true;
            }
            case "glow" -> { return doGlow(p, args); }
            case "pet"  -> { return doPet(p, args); }
            case "fx"   -> { return doFx(p, args); }
            case "acc"  -> { return doAcc(p, args); }
        }
        return true;
    }

    // ---------------------- /glow ----------------------
    private boolean doGlow(Player p, String[] args) {
        if (!p.hasPermission("sponsor.glow")) { p.sendMessage("§cЭто привилегия §5Спонсора§c."); return true; }
        if (args.length == 0) { p.sendMessage("§dИспользование: §f/glow &<цвет> §7| §f/glow rainbow §7| §f/glow stop"); return true; }
        if (args[0].equalsIgnoreCase("stop")) { clearGlow(p); p.sendMessage("§7Свечение выключено."); return true; }
        if (args[0].equalsIgnoreCase("rainbow")) { startRainbow(p); p.sendMessage("§dСвечение: §5радуга"); return true; }
        char code = Character.toLowerCase(args[0].charAt(args[0].length() - 1));
        ChatColor cc = ChatColor.getByChar(code);
        if (cc == null || !cc.isColor()) { p.sendMessage("§cЦвет кодом, напр. §f/glow &e§c."); return true; }
        setGlow(p, cc);
        p.sendMessage("§dСвечение включено: " + cc + cc.name().toLowerCase());
        return true;
    }

    private void setGlow(Player p, ChatColor cc) {
        rainbowGlow.remove(p.getUniqueId());
        pauseTab(p);
        applyGlowColor(p, cc);
        p.setGlowing(true);
        p.getPersistentDataContainer().set(kGlow, PersistentDataType.STRING, cc.name());
    }
    private void applyGlowColor(Player p, ChatColor cc) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String tn = "spglow_" + cc.name();
        Team t = sb.getTeam(tn);
        if (t == null) t = sb.registerNewTeam(tn);
        t.setColor(cc);
        String prev = glowTeams.get(p.getUniqueId());
        if (prev != null && !prev.equals(tn)) { Team pt = sb.getTeam(prev); if (pt != null) pt.removeEntry(p.getName()); }
        t.addEntry(p.getName());
        glowTeams.put(p.getUniqueId(), tn);
    }
    private void startRainbow(Player p) {
        pauseTab(p);
        rainbowGlow.add(p.getUniqueId());
        p.setGlowing(true);
        p.getPersistentDataContainer().set(kGlow, PersistentDataType.STRING, "rainbow");
    }

    private void clearGlowLive(Player p) {
        rainbowGlow.remove(p.getUniqueId());
        p.setGlowing(false);
        String tn = glowTeams.remove(p.getUniqueId());
        if (tn != null) { Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(tn); if (t != null) t.removeEntry(p.getName()); }
        resumeTab(p);
    }
    private void clearGlow(Player p) { clearGlowLive(p); p.getPersistentDataContainer().set(kGlow, PersistentDataType.STRING, ""); }

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

    // ---------------------- /fx ----------------------
    private boolean doFx(Player p, String[] args) {
        if (!p.hasPermission("sponsor.fx")) { p.sendMessage("§cЭто привилегия §5Спонсора§c."); return true; }
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder("§dПартиклы: §f");
            for (FxDef f : fxRoster) sb.append(f.key()).append(" ");
            p.sendMessage(sb.toString().trim() + " §7| §f/fx stop");
            return true;
        }
        if (args[0].equalsIgnoreCase("stop")) { activeFx.remove(p.getUniqueId()); p.getPersistentDataContainer().set(kFx, PersistentDataType.STRING, ""); p.sendMessage("§7Партиклы выключены."); return true; }
        FxDef f = fxByKey(args[0]);
        if (f == null) { p.sendMessage("§cНет такого эффекта."); return true; }
        activeFx.put(p.getUniqueId(), f.key());
        p.getPersistentDataContainer().set(kFx, PersistentDataType.STRING, f.key());
        p.sendMessage("§dЭффект включён: §f" + f.name());
        return true;
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

    private boolean addPet(Player p, PetDef d) { return addPet(p, d, true); }
    private boolean addPet(Player p, PetDef d, boolean announce) {
        UUID u = p.getUniqueId();
        List<UUID> list = pets.computeIfAbsent(u, k -> new ArrayList<>());
        if (list.size() >= MAX_PETS) { if (announce) p.sendMessage("§cМаксимум §f" + MAX_PETS + " §cпитомцев всего (любых видов)."); return false; }
        Location loc = p.getLocation().add((Math.random() - 0.5) * 1.6, 0.4, (Math.random() - 0.5) * 1.6);
        Entity e = p.getWorld().spawnEntity(loc, d.type());
        if (d.baby() && e instanceof Ageable a) a.setBaby();
        if (e instanceof Zombie z) z.setShouldBurnInDay(false);
        if (d.catRandom() && e instanceof Cat c) {
            try { Cat.Type[] v = Cat.Type.values(); c.setCatType(v[(int)(Math.random() * v.length)]); } catch (Throwable ignored) {}
        }
        if (e instanceof Parrot pa) {
            try { Parrot.Variant[] v = Parrot.Variant.values(); pa.setVariant(v[(int)(Math.random() * v.length)]); } catch (Throwable ignored) {}
        }
        if (e instanceof LivingEntity le) {
            le.setCanPickupItems(false);
            le.setInvulnerable(true);
            le.setSilent(true);
            le.setCollidable(false);
            le.setRemoveWhenFarAway(false);
            le.setPersistent(false);
        }
        e.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, u.toString());
        e.getPersistentDataContainer().set(petKindKey, PersistentDataType.STRING, d.key());
        e.setCustomNameVisible(false);
        list.add(e.getUniqueId());
        if (announce) p.sendMessage("§dПитомец призван §7(" + list.size() + "/" + MAX_PETS + " всего)§d.");
        persistPets(p);
        return true;
    }

    private void removeOnePet(Player p, EntityType type) {
        List<UUID> list = pets.get(p.getUniqueId());
        if (list == null) return;
        for (int i = list.size() - 1; i >= 0; i--) {
            Entity e = Bukkit.getEntity(list.get(i));
            if (e == null) { list.remove(i); continue; }
            if (e.getType() == type) { e.remove(); list.remove(i); persistPets(p); p.sendMessage("§7Питомец убран §d(" + list.size() + "/" + MAX_PETS + ")§7."); return; }
        }
    }

    private int countOfType(UUID owner, EntityType type) {
        List<UUID> list = pets.get(owner);
        if (list == null) return 0;
        int c = 0;
        for (UUID id : list) { Entity e = Bukkit.getEntity(id); if (e != null && e.getType() == type) c++; }
        return c;
    }
    private int totalPets(UUID owner) { List<UUID> list = pets.get(owner); return list == null ? 0 : list.size(); }

    private void despawnPets(Player p) {
        List<UUID> list = pets.remove(p.getUniqueId());
        if (list != null) for (UUID id : list) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
    }
    private void clearPets(Player p) { despawnPets(p); p.getPersistentDataContainer().set(kPets, PersistentDataType.STRING, ""); }
    private void persistPets(Player p) {
        List<UUID> list = pets.get(p.getUniqueId());
        StringBuilder sb = new StringBuilder();
        if (list != null) for (UUID id : list) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) { String k = e.getPersistentDataContainer().get(petKindKey, PersistentDataType.STRING); if (k != null) { if (sb.length() > 0) sb.append(","); sb.append(k); } }
        }
        p.getPersistentDataContainer().set(kPets, PersistentDataType.STRING, sb.toString());
    }
    private void restoreCosmetics(Player p) {
        if (!p.isOnline()) return;
        var pdc = p.getPersistentDataContainer();
        String g = pdc.getOrDefault(kGlow, PersistentDataType.STRING, "");
        if (g.equals("rainbow")) startRainbow(p);
        else if (!g.isEmpty()) { try { setGlow(p, ChatColor.valueOf(g)); } catch (Throwable ignored) {} }
        String fx = pdc.getOrDefault(kFx, PersistentDataType.STRING, "");
        if (!fx.isEmpty() && fxByKey(fx) != null) activeFx.put(p.getUniqueId(), fx);
        String pets = pdc.getOrDefault(kPets, PersistentDataType.STRING, "");
        if (!pets.isEmpty()) for (String k : pets.split(",")) { if (k.isEmpty()) continue; PetDef d = defByKey(k); if (d != null) addPet(p, d, false); }
        restoreAcc(p);
    }

    private boolean isPet(Entity e) { return e != null && e.getPersistentDataContainer().has(petKey, PersistentDataType.STRING); }

    private void sweepStrayPets() {
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntities())
                if (isPet(e)) e.remove();
    }

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
                        if (pe instanceof Mob m) m.setTarget(null);
                        double dist = Objects.equals(pe.getWorld(), base.getWorld()) ? pe.getLocation().distance(base) : 999;
                        if (dist > 10) {
                            pe.teleport(base.clone().add((Math.random() - 0.5) * 2.5, 0, (Math.random() - 0.5) * 2.5));
                        } else if (dist > 4 && pe instanceof Mob m) {
                            m.getPathfinder().moveTo(base, 1.2);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 20L, 10L);
    }

    // плавные партиклы + радуга
    private void startCosmeticTask() {
        new BukkitRunnable() {
            @Override public void run() {
                fxPhase++;
                if (fxPhase % 4 == 0 && !rainbowGlow.isEmpty()) {
                    ChatColor rc = RAINBOW[(fxPhase / 4) % RAINBOW.length];
                    for (UUID id : new ArrayList<>(rainbowGlow)) {
                        Player p = Bukkit.getPlayer(id);
                        if (p == null || !p.isOnline()) { rainbowGlow.remove(id); continue; }
                        applyGlowColor(p, rc);
                        p.setGlowing(true);
                    }
                }
                for (Map.Entry<UUID, String> en : new ArrayList<>(activeFx.entrySet())) {
                    Player p = Bukkit.getPlayer(en.getKey());
                    if (p == null || !p.isOnline()) { activeFx.remove(en.getKey()); continue; }
                    FxDef f = fxByKey(en.getValue());
                    if (f != null) renderFx(p, f);
                }
                updateAccessories();
            }
        }.runTaskTimer(this, 20L, 2L);
    }

    private void renderFx(Player p, FxDef f) {
        World w = p.getWorld();
        Location loc = p.getLocation();
        try { switch (f.style()) {
            case "TRAIL" -> w.spawnParticle(f.particle(), loc.clone().add(0, 0.25, 0), 3, 0.18, 0.12, 0.18, 0.0);
            case "AURA"  -> w.spawnParticle(f.particle(), loc.clone().add(0, 1.0, 0), 6, 0.4, 0.7, 0.4, 0.02);
            case "WINGS" -> renderWings(p, f.particle());
            case "HALO"  -> {
                double r = 0.32, y = 2.35;
                int n = 12;
                double bph = (fxPhase % 60) / 60.0 * Math.PI * 2;
                for (int i = 0; i < n; i++) {
                    double a = bph + Math.PI * 2 * i / n;
                    w.spawnParticle(f.particle(), loc.clone().add(Math.cos(a) * r, y, Math.sin(a) * r), 1, 0, 0, 0, 0.0);
                }
            }
        } } catch (Throwable ignored) {}
    }

    private void renderWings(Player p, Particle particle) {
        World w = p.getWorld();
        Location loc = p.getLocation();
        Vector dir = loc.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector back = dir.clone().multiply(-1);
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        Vector up = new Vector(0, 1, 0);
        double flap = Math.sin(fxPhase * 0.10) * 0.24;
        for (int s = -1; s <= 1; s += 2) {
            for (int fth = 1; fth <= 5; fth++) {
                double ang = Math.toRadians(18 + fth * 12) + flap;
                double len = 0.5 + fth * 0.28;
                for (double d = 0.2; d <= len; d += 0.14) {
                    double h = s * (Math.cos(ang) * d + 0.18);
                    double v = 1.15 + Math.sin(ang) * d;
                    Vector off = right.clone().multiply(h).add(up.clone().multiply(v)).add(back.clone().multiply(0.28));
                    w.spawnParticle(particle, loc.clone().add(off), 1, 0, 0, 0, 0.0);
                }
            }
        }
    }

    // ===================== аксессуары (display-энтити) =====================
    private boolean doAcc(Player p, String[] args) {
        if (!p.hasPermission("sponsor.acc")) { p.sendMessage("§cЭто привилегия §5Спонсора§c."); return true; }
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder("§dАксессуары: §f");
            for (AccDef d : accRoster) sb.append(d.key()).append(" ");
            p.sendMessage(sb.toString().trim() + " §7| §f/acc size <0.2-3.0> §7| §f/acc off");
            return true;
        }
        if (args[0].equalsIgnoreCase("off")) { clearAccessory(p); p.sendMessage("§7Аксессуар снят."); return true; }
        if (args[0].equalsIgnoreCase("size")) {
            if (args.length < 2) { p.sendMessage("§dТекущий размер: §f" + accScale.getOrDefault(p.getUniqueId(), 1.0)); return true; }
            try {
                double v = Math.max(ACC_MIN, Math.min(ACC_MAX, Double.parseDouble(args[1].replace(',', '.'))));
                setAccScale(p, v); p.sendMessage("§dРазмер: §f" + v);
            } catch (Throwable t) { p.sendMessage("§cЧисло, напр. §f/acc size 1.5"); }
            return true;
        }
        AccDef d = accByKey(args[0]);
        if (d == null) { p.sendMessage("§cНет такого аксессуара."); return true; }
        setAccessory(p, d);
        p.sendMessage("§dАксессуар надет: §f" + d.name());
        return true;
    }

    private void setAccessory(Player p, AccDef d) {
        clearAccessory(p);
        UUID u = p.getUniqueId();
        activeAcc.put(u, d.key());
        accScale.putIfAbsent(u, 1.0);
        List<UUID> ds = new ArrayList<>();
        for (AccPart part : d.parts()) {          // по дисплею на каждую часть модели
            Display disp = spawnAccDisplay(p, part);
            if (disp != null) ds.add(disp.getUniqueId());
        }
        accDisplays.put(u, ds);
        p.getPersistentDataContainer().set(kAcc, PersistentDataType.STRING, d.key() + ":" + accScale.get(u));
    }

    private Display spawnAccDisplay(Player p, AccPart part) {
        Location loc = p.getLocation();
        Display disp;
        if (part.item()) {
            ItemDisplay id = p.getWorld().spawn(loc, ItemDisplay.class);
            id.setItemStack(new ItemStack(part.mat()));
            disp = id;
        } else {
            BlockDisplay bd = p.getWorld().spawn(loc, BlockDisplay.class);
            bd.setBlock(part.mat().createBlockData());
            disp = bd;
        }
        disp.setPersistent(false);
        disp.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, p.getUniqueId().toString()); // метка для sweep
        try { disp.setBrightness(new Display.Brightness(15, 15)); } catch (Throwable ignored) {}
        try { p.addPassenger(disp); } catch (Throwable ignored) {}      // ПАССАЖИР: едет на игроке → без шлейфа под Speed
        disp.setRotation(0f, 0f);           // world-align РАЗОВО (translation в мир-осях, не дёргаем каждый тик)
        return disp;
    }

    private void clearAccessory(Player p) {
        UUID u = p.getUniqueId();
        activeAcc.remove(u);
        List<UUID> ds = accDisplays.remove(u);
        if (ds != null) for (UUID id : ds) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        p.getPersistentDataContainer().set(kAcc, PersistentDataType.STRING, "");
    }

    private void setAccScale(Player p, double v) {
        accScale.put(p.getUniqueId(), v);
        String key = activeAcc.get(p.getUniqueId());
        if (key != null) p.getPersistentDataContainer().set(kAcc, PersistentDataType.STRING, key + ":" + v);
    }

    private void restoreAcc(Player p) {
        String s = p.getPersistentDataContainer().getOrDefault(kAcc, PersistentDataType.STRING, "");
        if (s.isEmpty()) return;
        String[] parts = s.split(":");
        AccDef d = accByKey(parts[0]);
        if (d == null) return;
        if (parts.length > 1) try { accScale.put(p.getUniqueId(), Double.parseDouble(parts[1])); } catch (Throwable ignored) {}
        setAccessory(p, d);
    }

    // позиционирование дисплеев каждый тик (из cosmeticTask)
    private void updateAccessories() {
        for (UUID u : new ArrayList<>(accDisplays.keySet())) {
            Player p = Bukkit.getPlayer(u);
            List<UUID> ds = accDisplays.get(u);
            if (p == null || !p.isOnline() || ds == null || ds.isEmpty()) {
                if (ds != null) for (UUID id : ds) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
                accDisplays.remove(u); activeAcc.remove(u); continue;
            }
            AccDef d = accByKey(activeAcc.get(u));
            if (d == null) continue;
            positionAccessory(p, d, ds, accScale.getOrDefault(u, 1.0));
        }
    }

    private void positionAccessory(Player p, AccDef d, List<UUID> ds, double sc) {
        float yaw = p.getLocation().getYaw();
        double rad = Math.toRadians(yaw);
        Vector fwd = new Vector(-Math.sin(rad), 0, Math.cos(rad));       // куда смотрит игрок
        Vector right = new Vector(fwd.getZ(), 0, -fwd.getX());           // вправо от игрока
        double phase = fxPhase * 0.12;

        // Якорь всей модели (мировое смещение) + флаги анимации режима.
        Vector anchor = new Vector(0, 0, 0);
        boolean faceYaw = true;
        double bob = 0; boolean orbit = false;
        switch (d.mode()) {
            case "BALLOON" -> { anchor = right.clone().multiply(0.32).add(new Vector(0, 0.9, 0)); bob = Math.sin(phase) * 0.18; }
            case "ORBIT"   -> { faceYaw = false; orbit = true; }
            case "FOLLOW"  -> { faceYaw = false; anchor = fwd.clone().multiply(-1.2).add(new Vector(0, 1.4, 0)); bob = Math.sin(phase * 0.7) * 0.12; }
            default        -> {}
        }
        Vector orbOff = orbit ? new Vector(Math.cos(phase) * 1.3, 1.0 + Math.sin(phase * 2) * 0.15, Math.sin(phase) * 1.3) : null;

        // === ПАССАЖИРСТВО: тюн-знаки (подгоняются вживую) ===
        final float MOUNT_UP = 1.8f;   // высота точки посадки пассажира на игроке (замерено: дисплей на +1.8 от ног)
        final float NECK_UP  = 1.5f;   // точка шеи — вокруг неё наклоняется головной убор по питчу
        boolean headMode = d.mode().equals("HEAD") || d.mode().equals("FACE");   // следят за башкой
        float pitchRad = headMode ? (float) Math.toRadians(p.getLocation().getPitch()) : 0f;
        Quaternionf pitchQ = (headMode && pitchRad != 0f)
                ? new Quaternionf().rotateAxis(pitchRad, (float) right.getX(), (float) right.getY(), (float) right.getZ())
                : null;

        List<AccPart> parts = d.parts();
        // Центроид раскладки = точка крепления модели к телу; разлёт частей масштабируем на sc,
        // иначе блоки пухнут, а расстояния между ними стоят на месте → наезжают друг на друга.
        float cf = 0, cr = 0, cu = 0, minF = Float.MAX_VALUE;
        for (AccPart pt : parts) { cf += pt.fwd(); cr += pt.right(); cu += pt.up(); minF = Math.min(minF, pt.fwd()); }
        int n = Math.max(1, parts.size());
        cf /= n; cr /= n; cu /= n;
        // LEG торчит вперёд: масштаб пиним к ближнему концу (minF), чтобы модель росла
        // ТОЛЬКО вперёд от паха, а не назад сквозь тело. Остальные растут от центроида.
        float originF = d.mode().equals("LEG") ? minF : cf;

        for (int i = 0; i < ds.size() && i < parts.size(); i++) {
            Entity e = Bukkit.getEntity(ds.get(i));
            if (!(e instanceof Display disp)) continue;
            AccPart part = parts.get(i);

            // Смещение части: якорь по оси (не масштабируется) + разлёт от него × sc.
            float pf = originF + (float) ((part.fwd()   - originF) * sc);
            float pr = cr + (float) ((part.right() - cr) * sc);
            float pu = cu + (float) ((part.up()    - cu) * sc);
            Vector off = fwd.clone().multiply(pf)
                    .add(right.clone().multiply(pr))
                    .add(new Vector(0, pu, 0))
                    .add(anchor);
            if (bob != 0) off.add(new Vector(0, bob, 0));
            if (orbOff != null) off.add(orbOff);
            if (d.mode().equals("TAIL")) {                              // живая раскачка хвоста
                double sway = Math.sin(phase * 1.6 + i * 0.7) * 0.05 * (i + 1);
                off.add(right.clone().multiply(sway));
            }
            if (pitchQ != null) {                                       // наклон убора вокруг шеи по питчу макушки
                Vector3f op = new Vector3f((float) off.getX(), (float) (off.getY() - NECK_UP), (float) off.getZ());
                pitchQ.transform(op);
                off = new Vector(op.x, op.y + NECK_UP, op.z);
            }

            // Поворот: сначала лицом по игроку, затем собственный поворот части.
            Quaternionf rot = new Quaternionf();
            if (faceYaw) rot.rotateY((float) Math.toRadians(-yaw));
            if (part.ry() != 0) rot.rotateY((float) Math.toRadians(part.ry()));
            if (part.rx() != 0) rot.rotateX((float) Math.toRadians(part.rx()));
            if (part.rz() != 0) rot.rotateZ((float) Math.toRadians(part.rz()));

            float sx = (float) (part.sx() * sc), sy = (float) (part.sy() * sc), sz = (float) (part.sz() * sc);
            Vector3f center = new Vector3f(0, 0, 0);                     // итемы уже центрированы
            if (!(disp instanceof ItemDisplay)) {
                center = new Vector3f(-sx / 2f, -sy / 2f, -sz / 2f);    // центрируем блок вокруг точки крепления
                rot.transform(center);
            }
            // ПАССАЖИР: позиция берётся с маунта на игроке, смещение кладём в translation
            // (мир-оси, минус высота посадки). Телепорта нет → под Speed II шлейфа нет.
            Vector3f trans = new Vector3f((float) off.getX(), (float) (off.getY() - MOUNT_UP), (float) off.getZ());
            trans.add(center);
            disp.setTransformation(new Transformation(trans, rot, new Vector3f(sx, sy, sz), new Quaternionf()));
        }
    }

    // ===================== GUI =====================
    private enum Page { MAIN, GLOW, PETS, FX, ACC }
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
        inv.setItem(SLOT_MAIN_FX,   item(Material.FIREWORK_ROCKET, "Партиклы", NamedTextColor.GOLD, 1, "Шлейфы, ауры, нимбы"));
        inv.setItem(SLOT_MAIN_PETS, item(Material.BONE, "Питомцы", NamedTextColor.AQUA, 1, "Призвать компаньонов"));
        inv.setItem(SLOT_MAIN_ACC,  item(Material.ARMOR_STAND, "Аксессуары", NamedTextColor.GREEN, 1, "Модельки на теле: рюкзак, крылья, хвост…"));
        p.openInventory(inv);
    }

    private void openGlow(Player p) {
        Gui h = new Gui(Page.GLOW);
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Спонсор · Свечение").color(NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        fillBg(inv);
        for (int i = 0; i < COLORS.length; i++)
            inv.setItem(COLOR_SLOTS[i], item(WOOLS[i], CNAMES[i], ntc(COLORS[i]), 1, "ЛКМ — включить свечение"));
        inv.setItem(SLOT_GLOW_RAINBOW, item(Material.NETHER_STAR, "Радуга", NamedTextColor.LIGHT_PURPLE, 1, "Переливающееся свечение"));
        inv.setItem(SLOT_GLOW_OFF, item(Material.BARRIER, "Выключить свечение", NamedTextColor.RED, 1, "ЛКМ — снять свечение"));
        inv.setItem(SLOT_BACK, item(Material.ARROW, "← Меню", NamedTextColor.GRAY, 1, "В главное меню"));
        p.openInventory(inv);
    }

    private String styleName(String s) { return switch (s) { case "TRAIL" -> "шлейф"; case "AURA" -> "аура"; case "HALO" -> "нимб"; case "WINGS" -> "крылья"; default -> s; }; }

    private void openFx(Player p) {
        Gui h = new Gui(Page.FX);
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Спонсор · Партиклы").color(NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        fillBg(inv);
        for (int i = 0; i < fxRoster.size() && i < FX_SLOTS.length; i++) {
            FxDef f = fxRoster.get(i);
            inv.setItem(FX_SLOTS[i], item(f.icon(), f.name(), NamedTextColor.GOLD, 1, "ЛКМ — включить", "Тип: " + styleName(f.style())));
        }
        inv.setItem(SLOT_FX_OFF, item(Material.BARRIER, "Выключить партиклы", NamedTextColor.RED, 1, "ЛКМ — снять эффект"));
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

    private void openAcc(Player p) {
        Gui h = new Gui(Page.ACC);
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Спонсор · Аксессуары").color(NamedTextColor.DARK_PURPLE));
        h.inv = inv;
        fillBg(inv);
        String cur = activeAcc.get(p.getUniqueId());
        for (int i = 0; i < accRoster.size() && i < ACC_SLOTS.length; i++) {
            AccDef d = accRoster.get(i);
            boolean on = d.key().equals(cur);
            inv.setItem(ACC_SLOTS[i], item(d.icon(), (on ? "§a✔ " : "") + d.name(), on ? NamedTextColor.GREEN : NamedTextColor.AQUA, 1, "ЛКМ — надеть"));
        }
        double sc = accScale.getOrDefault(p.getUniqueId(), 1.0);
        inv.setItem(SLOT_ACC_INFO, item(Material.NAME_TAG, "Размер: " + String.format("%.1f", sc), NamedTextColor.LIGHT_PURPLE, 1, "Меняй кнопками −/+ снизу"));
        inv.setItem(SLOT_ACC_SIZE_DOWN, item(Material.RED_CONCRETE, "− Меньше", NamedTextColor.RED, 1, "Уменьшить размер"));
        inv.setItem(SLOT_ACC_SIZE_UP, item(Material.LIME_CONCRETE, "+ Больше", NamedTextColor.GREEN, 1, "Увеличить размер"));
        inv.setItem(SLOT_ACC_OFF, item(Material.BARRIER, "Снять аксессуар", NamedTextColor.RED, 1, "ЛКМ — снять"));
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
            else if (slot == SLOT_MAIN_FX) openFx(p);
            else if (slot == SLOT_MAIN_PETS) openPets(p);
            else if (slot == SLOT_MAIN_ACC) openAcc(p);
            return;
        }
        if (gui.page == Page.GLOW) {
            if (slot == SLOT_GLOW_OFF) { clearGlow(p); p.sendMessage("§7Свечение выключено."); return; }
            if (slot == SLOT_GLOW_RAINBOW) { startRainbow(p); p.sendMessage("§dСвечение: §5радуга"); return; }
            if (slot == SLOT_BACK) { openMain(p); return; }
            for (int i = 0; i < COLOR_SLOTS.length; i++)
                if (COLOR_SLOTS[i] == slot) { setGlow(p, COLORS[i]); p.sendMessage("§dСвечение: " + COLORS[i] + CNAMES[i]); return; }
        } else if (gui.page == Page.FX) {
            if (slot == SLOT_FX_OFF) { activeFx.remove(p.getUniqueId()); p.getPersistentDataContainer().set(kFx, PersistentDataType.STRING, ""); p.sendMessage("§7Партиклы выключены."); return; }
            if (slot == SLOT_BACK) { openMain(p); return; }
            for (int i = 0; i < fxRoster.size() && i < FX_SLOTS.length; i++)
                if (FX_SLOTS[i] == slot) { FxDef f = fxRoster.get(i); activeFx.put(p.getUniqueId(), f.key()); p.getPersistentDataContainer().set(kFx, PersistentDataType.STRING, f.key()); p.sendMessage("§dЭффект: §f" + f.name()); return; }
        } else if (gui.page == Page.PETS) {
            if (slot == SLOT_PETS_CLEAR) { clearPets(p); openPets(p); return; }
            if (slot == SLOT_BACK) { openMain(p); return; }
            for (int i = 0; i < roster.size() && i < PET_SLOTS.length; i++)
                if (PET_SLOTS[i] == slot) {
                    if (e.isRightClick()) removeOnePet(p, roster.get(i).type());
                    else addPet(p, roster.get(i));
                    openPets(p); return;
                }
        } else if (gui.page == Page.ACC) {
            if (slot == SLOT_BACK) { openMain(p); return; }
            if (slot == SLOT_ACC_OFF) { clearAccessory(p); openAcc(p); return; }
            if (slot == SLOT_ACC_SIZE_DOWN || slot == SLOT_ACC_SIZE_UP) {
                double sc = accScale.getOrDefault(p.getUniqueId(), 1.0) + (slot == SLOT_ACC_SIZE_UP ? ACC_STEP : -ACC_STEP);
                sc = Math.max(ACC_MIN, Math.min(ACC_MAX, Math.round(sc * 10) / 10.0));
                setAccScale(p, sc); openAcc(p); return;
            }
            for (int i = 0; i < accRoster.size() && i < ACC_SLOTS.length; i++)
                if (ACC_SLOTS[i] == slot) { setAccessory(p, accRoster.get(i)); openAcc(p); return; }
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
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPermission("sponsor.menu")) return;
        Bukkit.getScheduler().runTaskLater(this, () -> restoreCosmetics(p), 20L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        despawnPets(e.getPlayer()); clearGlowLive(e.getPlayer());
        UUID u = e.getPlayer().getUniqueId();
        activeFx.remove(u);
        List<UUID> ds = accDisplays.remove(u);   // снимаем дисплеи, но PDC оставляем — восстановим на заходе
        if (ds != null) for (UUID id : ds) { Entity ent = Bukkit.getEntity(id); if (ent != null) ent.remove(); }
        activeAcc.remove(u); accScale.remove(u);
    }
}
