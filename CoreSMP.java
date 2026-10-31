package dev.eventserver.coresmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * CoreSMP - every player can be given a "Core" (an item with Curse of Vanishing).
 *  - Core out of the inventory for longer than the grace time -> random curse (potion effects). Clears when the core returns.
 *  - Core destroyed (burned, exploded, voided, despawned, died with it) -> permanent HARDCORE.
 *  - Nametag colours: green = normal, red = cursed, black = hardcore (tab list, chat, above head).
 */
public final class CoreSMP extends JavaPlugin implements Listener, TabExecutor {

    private enum State { NORMAL, CURSED, HARDCORE }

    private static final class Data {
        UUID coreId;
        Material material;
        State state = State.NORMAL;
        List<String> curse = new ArrayList<>();
        boolean dead;
    }

    /** Inventory types the core may not be put into (they would consume it or hide it from the owner). */
    private static final Set<String> BLOCKED_TYPES = Set.of(
            "ENDER_CHEST", "FURNACE", "BLAST_FURNACE", "SMOKER", "BREWING", "WORKBENCH", "ANVIL",
            "GRINDSTONE", "SMITHING", "STONECUTTER", "LOOM", "CARTOGRAPHY", "BEACON", "CRAFTER",
            "MERCHANT", "COMPOSTER", "DISPENSER", "DROPPER", "LECTERN");

    private final Map<UUID, Data> data = new HashMap<>();
    private final Map<UUID, Long> missingSince = new HashMap<>();
    private final Map<UUID, ItemStack> pendingCore = new HashMap<>();
    private final List<Material> pool = new ArrayList<>();
    private final Random rng = new SecureRandom();

    private NamespacedKey keyId;
    private NamespacedKey keyOwner;
    private NamespacedKey keySpecial;
    private NamespacedKey keyTarget;
    private NamespacedKey keyTargetName;
    private File dataFile;

    private Team teamNormal, teamCursed, teamHard;
    private NamedTextColor cNormal, cCursed, cHard;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyId = new NamespacedKey(this, "core_id");
        keyOwner = new NamespacedKey(this, "core_owner");
        keySpecial = new NamespacedKey(this, "special_item");
        keyTarget = new NamespacedKey(this, "revive_target");
        keyTargetName = new NamespacedKey(this, "revive_name");
        dataFile = new File(getDataFolder(), "players.yml");
        loadData();
        buildPool();
        setupTeams();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("core");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        PluginCommand statusCmd = getCommand("status");
        if (statusCmd != null) {
            statusCmd.setExecutor(this);
            statusCmd.setTabCompleter(this);
        }
        PluginCommand specialCmd = getCommand("special");
        if (specialCmd != null) {
            specialCmd.setExecutor(this);
            specialCmd.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 5L);
        for (Player p : Bukkit.getOnlinePlayers()) refreshTag(p);
        getLogger().info("CoreSMP enabled. Item pool: " + pool.size() + " items.");
    }

    @Override
    public void onDisable() {
        cleanupStructures();
        save();
    }

    // ------------------------------------------------------------------ config / data

    private void buildPool() {
        Set<String> black = new HashSet<>();
        for (String s : getConfig().getStringList("blacklist")) black.add(s.toUpperCase(Locale.ROOT));
        pool.clear();
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
            if (black.contains(m.name())) continue;
            pool.add(m);
        }
    }

    private Material randomMaterial() {
        return pool.get(rng.nextInt(pool.size()));
    }

    private NamedTextColor colorOf(String key, NamedTextColor def) {
        String s = getConfig().getString("colors." + key, "");
        NamedTextColor c = NamedTextColor.NAMES.value(s.toLowerCase(Locale.ROOT));
        return c != null ? c : def;
    }

    private void loadData() {
        data.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                Data d = new Data();
                String cid = sec.getString(k + ".core-id");
                d.coreId = cid == null ? null : UUID.fromString(cid);
                String mat = sec.getString(k + ".material");
                d.material = mat == null ? null : Material.matchMaterial(mat);
                d.state = State.valueOf(sec.getString(k + ".state", "NORMAL"));
                d.curse = new ArrayList<>(sec.getStringList(k + ".curse"));
                if (d.curse.size() > 1) d.curse = new ArrayList<>(d.curse.subList(0, 1));
                d.dead = sec.getBoolean(k + ".dead");
                data.put(UUID.fromString(k), d);
            } catch (Exception ex) {
                getLogger().warning("Skipping bad entry in players.yml: " + k);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Data> en : data.entrySet()) {
            String b = "players." + en.getKey();
            Data d = en.getValue();
            y.set(b + ".core-id", d.coreId == null ? null : d.coreId.toString());
            y.set(b + ".material", d.material == null ? null : d.material.name());
            y.set(b + ".state", d.state.name());
            y.set(b + ".curse", d.curse);
            y.set(b + ".dead", d.dead);
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save players.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ nametags

    private void setupTeams() {
        cNormal = colorOf("normal", NamedTextColor.GREEN);
        cCursed = colorOf("cursed", NamedTextColor.RED);
        cHard = colorOf("hardcore", NamedTextColor.BLACK);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        teamNormal = team(sb, "core_normal", cNormal);
        teamCursed = team(sb, "core_cursed", cCursed);
        teamHard = team(sb, "core_hardcore", cHard);
    }

    private Team team(Scoreboard sb, String name, NamedTextColor c) {
        Team t = sb.getTeam(name);
        if (t == null) t = sb.registerNewTeam(name);
        t.color(c);
        return t;
    }

    private State stateOf(UUID u) {
        Data d = data.get(u);
        return d == null ? State.NORMAL : d.state;
    }

    /** Black, jolting hearts: infinite hidden Wither + Regeneration (their damage/healing is cancelled below). */
    private void applyHardcoreLook(Player p) {
        if (!getConfig().getBoolean("hardcore-black-hearts", true)) return;
        PotionEffect w = p.getPotionEffect(PotionEffectType.WITHER);
        if (w == null || !w.isInfinite()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, PotionEffect.INFINITE_DURATION, 0,
                    false, false, false));
        }
        PotionEffect r = p.getPotionEffect(PotionEffectType.REGENERATION);
        if (r == null || !r.isInfinite()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, PotionEffect.INFINITE_DURATION, 0,
                    false, false, false));
        }
    }

    private void removeHardcoreLook(Player p) {
        for (PotionEffectType t : new PotionEffectType[]{PotionEffectType.WITHER, PotionEffectType.REGENERATION}) {
            PotionEffect cur = p.getPotionEffect(t);
            if (cur != null && cur.isInfinite() && !cur.hasIcon() && !cur.hasParticles()) {
                p.removePotionEffect(t);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWitherDamage(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.WITHER && e.getEntity() instanceof Player p
                && stateOf(p.getUniqueId()) == State.HARDCORE) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEffectRegen(EntityRegainHealthEvent e) {
        if (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.MAGIC_REGEN && e.getEntity() instanceof Player p
                && stateOf(p.getUniqueId()) == State.HARDCORE) {
            e.setCancelled(true);
        }
    }

    private void refreshTag(Player p) {
        Data data0 = data.get(p.getUniqueId());
        if (data0 == null && !getConfig().getBoolean("color-untouched-players", false)) {
            // never touched by the plugin, or cleared: completely default name
            teamNormal.removeEntry(p.getName());
            teamCursed.removeEntry(p.getName());
            teamHard.removeEntry(p.getName());
            p.displayName(Component.text(p.getName()));
            p.playerListName(null);
            removeHardcoreLook(p);
            return;
        }
        State st = stateOf(p.getUniqueId());
        Team t;
        NamedTextColor col;
        if (st == State.CURSED) {
            t = teamCursed;
            col = cCursed;
        } else if (st == State.HARDCORE) {
            t = teamHard;
            col = cHard;
        } else {
            t = teamNormal;
            col = cNormal;
        }
        t.addEntry(p.getName());
        Component name = Component.text(p.getName(), col);
        p.displayName(name);
        p.playerListName(name);
        if (st == State.HARDCORE) applyHardcoreLook(p);
        else removeHardcoreLook(p);
    }

    // ------------------------------------------------------------------ core item helpers

    private String idOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    private String ownerOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING);
    }

    private boolean isCore(ItemStack it) {
        return idOf(it) != null;
    }

    private boolean isOwnCore(ItemStack it, Data d) {
        String id = idOf(it);
        return id != null && d.coreId != null && id.equals(d.coreId.toString());
    }

    private ItemStack findOwnCore(Player p, Data d) {
        if (isOwnCore(p.getItemOnCursor(), d)) return p.getItemOnCursor();
        for (ItemStack it : p.getInventory().getContents()) {
            if (isOwnCore(it, d)) return it;
        }
        return null;
    }

    private boolean hasCore(Player p, Data d) {
        return findOwnCore(p, d) != null;
    }

    private ItemStack buildCore(Player p, Data d) {
        ItemStack it = new ItemStack(d.material);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Core", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        m.addEnchant(Enchantment.BINDING_CURSE, 1, true);
        m.setUnbreakable(true);
        // hide every tooltip line except the enchantment (Curse of Binding stays visible)
        for (ItemFlag flag : ItemFlag.values()) {
            if (flag != ItemFlag.HIDE_ENCHANTS) m.addItemFlags(flag);
        }
        try {
            m.setMaxStackSize(1);
        } catch (Throwable ignored) {
            // some item types may refuse; the core is still unique through its tags
        }
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(keyId, PersistentDataType.STRING, d.coreId.toString());
        pdc.set(keyOwner, PersistentDataType.STRING, p.getUniqueId().toString());
        it.setItemMeta(m);
        it.setAmount(1);
        return it;
    }

    private void giveStack(Player p, ItemStack stack) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(stack);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItem(p.getLocation(), rest);
        }
    }

    /** Removes every core item that belongs to this player (inventory, cursor, ender chest). */
    private void removeCores(Player p) {
        String me = p.getUniqueId().toString();
        ItemStack[] c = p.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < c.length; i++) {
            if (me.equals(ownerOf(c[i]))) {
                c[i] = null;
                changed = true;
            }
        }
        if (changed) p.getInventory().setContents(c);
        if (me.equals(ownerOf(p.getItemOnCursor()))) p.setItemOnCursor(null);
        ItemStack[] e = p.getEnderChest().getContents();
        boolean ech = false;
        for (int i = 0; i < e.length; i++) {
            if (me.equals(ownerOf(e[i]))) {
                e[i] = null;
                ech = true;
            }
        }
        if (ech) p.getEnderChest().setContents(e);
    }

    // ------------------------------------------------------------------ curses

    private static PotionEffectType effectType(String n) {
        return switch (n.toLowerCase(Locale.ROOT)) {
            case "weakness" -> PotionEffectType.WEAKNESS;
            case "blindness" -> PotionEffectType.BLINDNESS;
            case "slowness" -> PotionEffectType.SLOWNESS;
            case "mining_fatigue" -> PotionEffectType.MINING_FATIGUE;
            case "nausea" -> PotionEffectType.NAUSEA;
            case "hunger" -> PotionEffectType.HUNGER;
            case "poison" -> PotionEffectType.POISON;
            case "darkness" -> PotionEffectType.DARKNESS;
            case "unluck" -> PotionEffectType.UNLUCK;
            default -> null;
        };
    }

    /** Rolls exactly ONE curse effect. Blindness is rarer by "curse.blindness-penalty" percentage points. */
    private List<String> rollCurse() {
        List<String> names = new ArrayList<>();
        for (String s : getConfig().getStringList("curse.effects")) {
            String k = s.toLowerCase(Locale.ROOT);
            if (effectType(k) != null && !names.contains(k)) names.add(k);
        }
        if (names.isEmpty()) names.add("weakness");
        int n = names.size();
        int bi = names.indexOf("blindness");
        boolean penalise = bi >= 0 && n > 1;
        double equal = 1.0 / n;
        double pBlind = penalise
                ? Math.max(0.0, equal - getConfig().getDouble("curse.blindness-penalty", 5.0) / 100.0)
                : equal;
        double r = rng.nextDouble();
        double acc = 0;
        int pick = n - 1;
        for (int i = 0; i < n; i++) {
            if (penalise) acc += (i == bi) ? pBlind : (1.0 - pBlind) / (n - 1);
            else acc += equal;
            if (r < acc) {
                pick = i;
                break;
            }
        }
        int maxAmp = Math.max(0, getConfig().getInt("curse.max-amplifier", 1));
        List<String> out = new ArrayList<>();
        out.add(names.get(pick) + ":" + rng.nextInt(maxAmp + 1));
        return out;
    }

    private void ensureEffects(Player p, Data d) {
        for (String s : d.curse) {
            String[] parts = s.split(":");
            PotionEffectType t = effectType(parts[0]);
            if (t == null) continue;
            int amp = 0;
            if (parts.length > 1) {
                try {
                    amp = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    amp = 0;
                }
            }
            PotionEffect cur = p.getPotionEffect(t);
            if (cur == null || cur.getAmplifier() < amp || !cur.isInfinite()) {
                p.addPotionEffect(new PotionEffect(t, PotionEffect.INFINITE_DURATION, amp, false, false, true));
            }
        }
    }

    private void removeCurse(Player p, Data d) {
        for (String s : d.curse) {
            PotionEffectType t = effectType(s.split(":")[0]);
            if (t == null) continue;
            PotionEffect cur = p.getPotionEffect(t);
            if (cur != null && cur.isInfinite()) p.removePotionEffect(t);
        }
    }

    private String curseText(Data d) {
        if (d.curse.isEmpty()) return "none rolled yet";
        List<String> parts = new ArrayList<>();
        for (String s : d.curse) {
            String[] x = s.split(":");
            int lvl = 1;
            try {
                if (x.length > 1) lvl = Integer.parseInt(x[1]) + 1;
            } catch (NumberFormatException ignored) {
                lvl = 1;
            }
            parts.add(x[0] + " " + lvl);
        }
        return String.join(", ", parts);
    }

    private void applyCurse(Player p, Data d) {
        if (d.curse.isEmpty()) d.curse = rollCurse();
        d.state = State.CURSED;
        missingSince.remove(p.getUniqueId());
        save();
        ensureEffects(p, d);
        refreshTag(p);
        curseSound(p);
    }

    private void coreDestroyed(UUID owner, String why) {
        Data d = data.get(owner);
        if (d == null || d.state == State.HARDCORE) return;
        Player p = Bukkit.getPlayer(owner);
        if (p != null) removeCurse(p, d);
        d.state = State.HARDCORE;
        d.coreId = null;
        d.curse = new ArrayList<>();
        missingSince.remove(owner);
        save();
        if (p != null) refreshTag(p);
        getLogger().info(Bukkit.getOfflinePlayer(owner).getName() + "'s Core was destroyed (" + why + ")");
        playDestroySound(owner);
    }

    /** Item-breaking sound, played to the player when they get cursed. */
    private void curseSound(Player p) {
        p.playSound(p.getLocation(), "entity.item.break", 1.0f, 1.0f);
    }

    private void playDestroySound(UUID owner) {
        String mode = getConfig().getString("core-destroyed-sound", "ALL").toUpperCase(Locale.ROOT);
        if (mode.equals("NONE")) return;
        if (mode.equals("OWNER")) {
            Player p = Bukkit.getPlayer(owner);
            if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f);
            return;
        }
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.playSound(o.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f);
        }
    }


    // ------------------------------------------------------------------ main check loop

    private void tick() {
        long now = System.currentTimeMillis();
        long grace = getConfig().getLong("grace-seconds", 5L) * 1000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            Data d = data.get(u);
            if (d == null || p.isDead()) continue;
            if (d.state == State.HARDCORE) {
                applyHardcoreLook(p);
                continue;
            }
            if (d.coreId == null) {
                // cursed with no core at all: keep the curse effects on them
                if (d.state == State.CURSED) ensureEffects(p, d);
                continue;
            }

            if (hasCore(p, d)) {
                missingSince.remove(u);
                if (d.state == State.CURSED) {
                    removeCurse(p, d);
                    d.state = State.NORMAL;
                    save();
                    refreshTag(p);                }
            } else if (d.state == State.NORMAL) {
                long since = missingSince.computeIfAbsent(u, k -> now);
                if (now - since >= grace) applyCurse(p, d);
            }
            if (d.state == State.CURSED) ensureEffects(p, d);
        }
    }

    // ------------------------------------------------------------------ giving / clearing

    private void assignCore(Player p, Material mat, boolean rerollCurse) {
        UUID u = p.getUniqueId();
        Data d = data.get(u);
        boolean fresh = d == null;
        if (fresh) {
            d = new Data();
            data.put(u, d);
        }
        removeCores(p);
        d.coreId = UUID.randomUUID();
        d.material = mat;
        if (fresh || rerollCurse || d.curse.isEmpty()) {
            if (d.state == State.CURSED) removeCurse(p, d);
            d.curse = rollCurse();
        }
        if (d.state == State.CURSED) {
            removeCurse(p, d);
            d.state = State.NORMAL;
        }
        missingSince.remove(u);
        save();
        refreshTag(p);
        giveStack(p, buildCore(p, d));
        p.sendMessage(Component.text("You received your Core.", NamedTextColor.GREEN));
    }

    /**
     * Fully randomizes a player: new random core item, new random curse, and a random state:
     * NORMAL = core in inventory, CURSED = no core and cursed, HARDCORE = no core and hardcore.
     */
    private State randomizeAll(Player p) {
        UUID u = p.getUniqueId();
        Data d = data.get(u);
        if (d == null) {
            d = new Data();
            data.put(u, d);
        }
        // wipe whatever they had before
        removeCurse(p, d);
        removeCores(p);
        if (d.dead && p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
        d.dead = false;
        missingSince.remove(u);
        pendingCore.remove(u);

        d.material = randomMaterial();
        d.curse = rollCurse();
        d.coreId = UUID.randomUUID();
        d.state = State.NORMAL;

        int wn = Math.max(0, getConfig().getInt("randomize.normal", 1));
        int wc = Math.max(0, getConfig().getInt("randomize.cursed", 1));
        int wh = Math.max(0, getConfig().getInt("randomize.hardcore", 1));
        if (wn + wc + wh <= 0) wn = 1;
        int r = rng.nextInt(wn + wc + wh);
        State out = r < wn ? State.NORMAL : (r < wn + wc ? State.CURSED : State.HARDCORE);

        if (out == State.NORMAL) {
            giveStack(p, buildCore(p, d));
            p.sendMessage(Component.text("You received your Core. Keep it safe.", NamedTextColor.GREEN));
        } else if (out == State.CURSED) {
            d.state = State.CURSED;
            d.coreId = null;
            ensureEffects(p, d);
            curseSound(p);
            p.sendMessage(Component.text("You have no Core and you are cursed!", NamedTextColor.RED));
        } else {
            d.state = State.HARDCORE;
            d.coreId = null;
            d.curse = new ArrayList<>();
            p.sendMessage(Component.text("Your Core is destroyed. You are HARDCORE.", NamedTextColor.DARK_RED));
        }
        save();
        refreshTag(p);
        return out;
    }

    private State parseStatus(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "normal" -> State.NORMAL;
            case "cursed", "curse" -> State.CURSED;
            case "hrdcr", "hardcore" -> State.HARDCORE;
            default -> null;
        };
    }

    /** Sets a custom status: NORMAL = has a core, CURSED = no core + curse, HARDCORE = no core + hardcore. */
    private void setStatus(Player p, State st) {
        UUID u = p.getUniqueId();
        Data d = data.get(u);
        if (d == null) {
            d = new Data();
            data.put(u, d);
        }
        removeCurse(p, d);
        if (d.dead && p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
        d.dead = false;
        missingSince.remove(u);
        pendingCore.remove(u);

        if (st == State.NORMAL) {
            d.state = State.NORMAL;
            if (d.coreId == null || !hasCore(p, d)) {
                removeCores(p);
                if (d.material == null) d.material = randomMaterial();
                d.coreId = UUID.randomUUID();
                giveStack(p, buildCore(p, d));
            }
            if (d.curse.isEmpty()) d.curse = rollCurse();
            p.sendMessage(Component.text("Your status is now NORMAL.", NamedTextColor.GREEN));
        } else if (st == State.CURSED) {
            removeCores(p);
            d.coreId = null;
            if (d.curse.isEmpty()) d.curse = rollCurse();
            d.state = State.CURSED;
            ensureEffects(p, d);
            curseSound(p);
            p.sendMessage(Component.text("Your status is now CURSED.", NamedTextColor.RED));
        } else {
            removeCores(p);
            d.coreId = null;
            d.curse = new ArrayList<>();
            d.state = State.HARDCORE;
            p.sendMessage(Component.text("Your status is now HARDCORE.", NamedTextColor.DARK_RED));
        }
        save();
        refreshTag(p);
    }

    private boolean statusCommand(CommandSender s, String[] a) {
        if (a.length < 2) {
            msg(s, "Usage: /status <normal|cursed|hrdcr> <players>", NamedTextColor.RED);
            return true;
        }
        State st = parseStatus(a[0]);
        if (st == null) {
            msg(s, "Unknown status '" + a[0] + "'. Use normal, cursed or hrdcr.", NamedTextColor.RED);
            return true;
        }
        int n = 0;
        for (Player p : targets(s, a[1])) {
            setStatus(p, st);
            n++;
        }
        if (n > 0) {
            String name = st == State.HARDCORE ? "hrdcr" : st.name().toLowerCase(Locale.ROOT);
            msg(s, "Set " + n + " player(s) to " + name + ".", NamedTextColor.GREEN);
        }
        return true;
    }

    private void clearAll(Player p) {
        UUID u = p.getUniqueId();
        Data d = data.remove(u);
        if (d != null) {
            removeCurse(p, d);
            if (d.dead && p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
        }
        removeCores(p);
        missingSince.remove(u);
        pendingCore.remove(u);
        save();
        refreshTag(p);
    }

    // ------------------------------------------------------------------ commands

    private void msg(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text, c));
    }

    private void usage(CommandSender s) {
        msg(s, "/core <players>                  give a random core", NamedTextColor.YELLOW);
        msg(s, "/core set <item> <players>       give a specific core", NamedTextColor.YELLOW);
        msg(s, "/core reroll <players>           randomize core item AND state", NamedTextColor.YELLOW);
        msg(s, "/core clear <players>            remove core, curse and hardcore", NamedTextColor.YELLOW);
        msg(s, "/core status [players]           show core info", NamedTextColor.YELLOW);
        msg(s, "/core reload                     reload config", NamedTextColor.YELLOW);
        msg(s, "/status <normal|cursed|hrdcr> <players>   set a custom status", NamedTextColor.YELLOW);
        msg(s, "/special give revivetotem <playername> [receiver]   special items", NamedTextColor.YELLOW);
        msg(s, "/special give corebreaker [players]                 the Core Voider rod", NamedTextColor.YELLOW);
        msg(s, "/special give gamblersfallacy [players]             the Gambler's Fallacy mace", NamedTextColor.YELLOW);
    }

    private List<Player> targets(CommandSender s, String sel) {
        List<Player> out = new ArrayList<>();
        if (sel.startsWith("@")) {
            try {
                for (Entity e : Bukkit.selectEntities(s, sel)) {
                    if (e instanceof Player p) out.add(p);
                }
            } catch (IllegalArgumentException ex) {
                msg(s, "Invalid selector: " + sel, NamedTextColor.RED);
                return out;
            }
        } else {
            Player p = Bukkit.getPlayerExact(sel);
            if (p != null) out.add(p);
        }
        if (out.isEmpty()) msg(s, "No online players matched '" + sel + "'.", NamedTextColor.RED);
        return out;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("status")) return statusCommand(s, a);
        if (c.getName().equalsIgnoreCase("special")) return specialCommand(s, a);
        if (a.length == 0) {
            usage(s);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            reloadConfig();
            buildPool();
            setupTeams();
            for (Player p : Bukkit.getOnlinePlayers()) refreshTag(p);
            msg(s, "CoreSMP config reloaded.", NamedTextColor.GREEN);
            return true;
        }

        if (sub.equals("set")) {
            if (a.length < 3) {
                msg(s, "Usage: /core set <item> <players>", NamedTextColor.RED);
                return true;
            }
            Material mat = Material.matchMaterial(a[1]);
            if (mat == null || !mat.isItem() || mat.isAir()) {
                msg(s, "Unknown item: " + a[1], NamedTextColor.RED);
                return true;
            }
            int n = 0;
            for (Player p : targets(s, a[2])) {
                assignCore(p, mat, false);
                n++;
            }
            if (n > 0) msg(s, "Set core to " + mat.name().toLowerCase(Locale.ROOT) + " for " + n + " player(s).", NamedTextColor.GREEN);
            return true;
        }

        if (sub.equals("randomize") || sub.equals("reroll")) {
            if (a.length < 2) {
                msg(s, "Usage: /core reroll <players>", NamedTextColor.RED);
                return true;
            }
            int[] count = new int[3];
            int n = 0;
            for (Player p : targets(s, a[1])) {
                State st = randomizeAll(p);
                count[st.ordinal()]++;
                n++;
            }
            if (n > 0) {
                msg(s, "Randomized " + n + " player(s): " + count[0] + " normal, " + count[1]
                        + " cursed, " + count[2] + " hardcore.", NamedTextColor.GREEN);
            }
            return true;
        }

        if (sub.equals("clear")) {
            if (a.length < 2) {
                msg(s, "Usage: /core clear <players>", NamedTextColor.RED);
                return true;
            }
            int n = 0;
            for (Player p : targets(s, a[1])) {
                clearAll(p);
                n++;
            }
            if (n > 0) msg(s, "Cleared core, curse and hardcore for " + n + " player(s).", NamedTextColor.GREEN);
            return true;
        }

        if (sub.equals("status")) {
            List<Player> list;
            if (a.length >= 2) list = targets(s, a[1]);
            else if (s instanceof Player self) list = List.of(self);
            else {
                msg(s, "Usage: /core status <players>", NamedTextColor.RED);
                return true;
            }
            for (Player p : list) {
                Data d = data.get(p.getUniqueId());
                if (d == null) {
                    msg(s, p.getName() + ": no core.", NamedTextColor.GRAY);
                    continue;
                }
                String item = d.material == null ? "none" : d.material.name().toLowerCase(Locale.ROOT);
                msg(s, p.getName() + ": " + d.state + ", core item: " + item + ", curse: " + curseText(d)
                        + (d.dead ? ", out of the game" : ""), NamedTextColor.AQUA);
            }
            return true;
        }

        // anything else is treated as a player selector -> give a random core
        int given = 0;
        for (Player p : targets(s, a[0])) {
            Data d = data.get(p.getUniqueId());
            if (d != null && d.coreId != null) {
                msg(s, p.getName() + " already has a core (use /core randomize to reroll).", NamedTextColor.GRAY);
                continue;
            }
            assignCore(p, randomMaterial(), true);
            given++;
        }
        if (given > 0) msg(s, "Gave a random core to " + given + " player(s).", NamedTextColor.GREEN);
        return true;
    }

    private List<String> selectors() {
        List<String> l = new ArrayList<>(List.of("@a", "@p", "@r", "@s"));
        for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
        return l;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (c.getName().equalsIgnoreCase("special")) return specialTab(a);
        if (c.getName().equalsIgnoreCase("status")) {
            if (a.length == 1) out.addAll(List.of("normal", "cursed", "hrdcr"));
            else if (a.length == 2) out.addAll(selectors());
        } else if (a.length == 1) {
            out.addAll(List.of("set", "reroll", "randomize", "clear", "status", "reload"));
            out.addAll(selectors());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("set")) {
            for (Material m : pool) out.add(m.name().toLowerCase(Locale.ROOT));
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("reload") && !a[0].equalsIgnoreCase("set")) {
            out.addAll(selectors());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("set")) {
            out.addAll(selectors());
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ events: joining / dying

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        missingSince.remove(p.getUniqueId());
        refreshTag(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        missingSince.remove(p.getUniqueId());
        // non-op players are fully reset (same as /core clear) when they log off,
        // but not when the whole server is shutting down/restarting
        if (!p.isOp() && getConfig().getBoolean("clear-on-logoff", true) && !Bukkit.isStopping()) {
            clearAll(p);
        }
    }

    private void kickHardcore(Player p) {
        if (p.isOnline()) {
            p.kick(Component.text("You died in HARDCORE mode. You can rejoin.", NamedTextColor.DARK_RED));
        }
    }


    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Data d = data.get(p.getUniqueId());
        if (d == null) return;

        if (d.state == State.HARDCORE) {
            // lightning on the exact spot where they died, a moment after the death
            final org.bukkit.Location deathSpot = p.getLocation().clone();
            long boltDelay = Math.max(0L, getConfig().getLong("hardcore-lightning-delay-ticks", 10L));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (getConfig().getBoolean("hardcore-lightning-real", false)) {
                    deathSpot.getWorld().strikeLightning(deathSpot);
                } else {
                    deathSpot.getWorld().strikeLightningEffect(deathSpot);
                }
            }, boltDelay);
            long delay = Math.max(1L, getConfig().getLong("hardcore-kick-delay-ticks", 20L));
            Bukkit.getScheduler().runTaskLater(this, () -> kickHardcore(p), delay);
            return;
        }
        if (d.coreId == null || e.getKeepInventory()) return;
        ItemStack found = findOwnCore(p, d);
        if (found == null) return;

        String policy = getConfig().getString("death-with-core", "DROP").toUpperCase(Locale.ROOT);
        if (policy.equals("DROP")) return; // the core drops on the ground like any other item

        e.getDrops().removeIf(this::isCore);
        if (policy.equals("KEEP")) {
            pendingCore.put(p.getUniqueId(), found.clone());
        } else if (!policy.equals("NOTHING")) {
            coreDestroyed(p.getUniqueId(), "it vanished when they died");
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        missingSince.remove(u);
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            ItemStack back = pendingCore.remove(u);
            if (back != null) giveStack(p, back);
        });
    }

    // ------------------------------------------------------------------ events: destruction

    @EventHandler
    public void onItemRemoved(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof Item item)) return;
        ItemStack st = item.getItemStack();
        String owner = ownerOf(st);
        String id = idOf(st);
        if (owner == null || id == null) return;

        String cause = e.getCause().name();
        String why;
        switch (cause) {
            case "DEATH" -> why = "burned, blown up or otherwise killed";
            case "EXPLODE" -> why = "exploded";
            case "OUT_OF_WORLD" -> why = "lost in the void";
            case "DESPAWN" -> why = "despawned";
            default -> {
                return;
            }
        }
        UUID u;
        try {
            u = UUID.fromString(owner);
        } catch (IllegalArgumentException ex) {
            return;
        }
        Data d = data.get(u);
        if (d == null || d.coreId == null || !id.equals(d.coreId.toString())) return;
        coreDestroyed(u, why);
    }

    // ------------------------------------------------------------------ events: keep the core safe

    private boolean blocked(Inventory inv) {
        return BLOCKED_TYPES.contains(inv.getType().name());
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!blocked(top)) return;
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;
        boolean clickedTop = clicked.equals(top);
        ClickType ct = e.getClick();
        boolean bad = false;
        if (ct == ClickType.SHIFT_LEFT || ct == ClickType.SHIFT_RIGHT) {
            bad = !clickedTop && isCore(e.getCurrentItem());
        } else if (ct == ClickType.NUMBER_KEY) {
            if (clickedTop) bad = isCore(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()));
        } else if (ct == ClickType.SWAP_OFFHAND) {
            if (clickedTop) bad = isCore(e.getWhoClicked().getInventory().getItemInOffHand());
        } else if (clickedTop) {
            bad = isCore(e.getCursor());
        }
        if (bad) {
            e.setCancelled(true);
            e.getWhoClicked().sendMessage(Component.text("Your Core can't go in there.", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!blocked(top) || !isCore(e.getOldCursor())) return;
        int size = top.getSize();
        for (int slot : e.getRawSlots()) {
            if (slot < size) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (isCore(e.getItem()) && blocked(e.getDestination())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent e) {
        if (isCore(e.getFuel())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (isCore(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isCore(e.getItemInHand())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (isCore(e.getItem())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isCore(e.getItem())) return;
        e.setUseItemInHand(Event.Result.DENY);
        if (e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.COMPOSTER) {
            e.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    // ================================================================== special items

    private record Pos(UUID world, int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private static final class Ritual {
        final UUID target;
        final UUID activator;
        final World world;
        final Map<Pos, Material> blocks = new HashMap<>();
        int cx, cz, baseY;
        boolean crumbling;

        Ritual(UUID target, UUID activator, World world) {
            this.target = target;
            this.activator = activator;
            this.world = world;
        }
    }

    private final Map<Pos, Ritual> structure = new HashMap<>();
    private final Set<UUID> noFall = new HashSet<>();

    private String specialOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keySpecial, PersistentDataType.STRING);
    }

    private ItemStack buildReviveTotem(UUID target, String targetName) {
        ItemStack it = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Revive Totem", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        m.setEnchantmentGlintOverride(true);
        try {
            m.setMaxStackSize(1);
        } catch (Throwable ignored) {
            // totems are already unstackable
        }
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(keySpecial, PersistentDataType.STRING, "revivetotem");
        pdc.set(keyTarget, PersistentDataType.STRING, target.toString());
        pdc.set(keyTargetName, PersistentDataType.STRING, targetName);
        it.setItemMeta(m);
        return it;
    }

    // ---- /special give revivetotem <playername> [receiver]

    private boolean specialCommand(CommandSender s, String[] a) {
        String usage = "Usage: /special give revivetotem <playername> [receiver]";
        if (a.length < 2 || !a[0].equalsIgnoreCase("give")) {
            msg(s, usage, NamedTextColor.RED);
            return true;
        }
        if (a[1].equalsIgnoreCase("corebreaker")) {
            // /special give corebreaker [receiver]  -> the Core Voider fishing rod
            List<Player> rec;
            if (a.length >= 3) {
                rec = targets(s, a[2]);
            } else if (s instanceof Player self) {
                rec = List.of(self);
            } else {
                msg(s, "From the console, name a receiver: /special give corebreaker <playername>",
                        NamedTextColor.RED);
                return true;
            }
            for (Player r : rec) giveStack(r, buildCoreBreaker());
            if (!rec.isEmpty()) msg(s, "Gave a Core Voider to " + rec.size() + " player(s).", NamedTextColor.GREEN);
            return true;
        }
        String norm = a[1].toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (norm.equals("gamblersfallacy") || norm.equals("gamblersfalacy")) {
            // /special give gamblersfallacy [receiver]  -> the Gambler's Fallacy mace
            List<Player> rec;
            if (a.length >= 3) {
                rec = targets(s, a[2]);
            } else if (s instanceof Player self) {
                rec = List.of(self);
            } else {
                msg(s, "From the console, name a receiver: /special give gamblersfallacy <playername>",
                        NamedTextColor.RED);
                return true;
            }
            for (Player r : rec) giveStack(r, buildGamblersFallacy());
            if (!rec.isEmpty()) msg(s, "Gave a Gambler's Fallacy to " + rec.size() + " player(s).", NamedTextColor.GREEN);
            return true;
        }
        if (!a[1].equalsIgnoreCase("revivetotem")) {
            msg(s, "Unknown special item '" + a[1] + "'. Available: revivetotem, corebreaker, gamblersfallacy",
                    NamedTextColor.RED);
            return true;
        }
        if (a.length < 3) {
            msg(s, usage, NamedTextColor.RED);
            return true;
        }
        UUID id;
        String name;
        Player online = Bukkit.getPlayerExact(a[2]);
        if (online != null) {
            id = online.getUniqueId();
            name = online.getName();
        } else {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(a[2]);
            if (off == null) {
                msg(s, "'" + a[2] + "' has never joined this server.", NamedTextColor.RED);
                return true;
            }
            id = off.getUniqueId();
            name = off.getName() != null ? off.getName() : a[2];
        }
        List<Player> receivers;
        if (a.length >= 4) {
            receivers = targets(s, a[3]);
        } else if (s instanceof Player self) {
            receivers = List.of(self);
        } else {
            msg(s, "From the console, name a receiver: /special give revivetotem <playername> <receiver>",
                    NamedTextColor.RED);
            return true;
        }
        for (Player r : receivers) giveStack(r, buildReviveTotem(id, name));
        if (!receivers.isEmpty()) msg(s, "Gave a Revive Totem for " + name + ".", NamedTextColor.GREEN);
        return true;
    }

    private List<String> specialTab(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("give");
        } else if (a.length == 2) {
            out.addAll(List.of("revivetotem", "corebreaker", "gamblersfallacy"));
        } else if (a.length == 3 && (a[1].equalsIgnoreCase("corebreaker")
                || a[1].toLowerCase(Locale.ROOT).startsWith("gamblers"))) {
            out.addAll(selectors());
        } else if (a.length == 3) {
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null) out.add(op.getName());
            }
        } else if (a.length == 4) {
            out.addAll(selectors());
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ---- using the totem

    @EventHandler
    public void onTotemUse(PlayerInteractEvent e) {
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (!"revivetotem".equals(specialOf(it))) return;
        e.setCancelled(true);

        Player user = e.getPlayer();
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        String tid = pdc.get(keyTarget, PersistentDataType.STRING);
        String tname = pdc.get(keyTargetName, PersistentDataType.STRING);
        UUID target;
        try {
            target = UUID.fromString(tid);
        } catch (Exception ex) {
            return;
        }
        Player tp = Bukkit.getPlayer(target);
        if (tp == null || !tp.isOnline() || tp.isDead()) {
            user.sendMessage(Component.text(tname + " is not online right now.", NamedTextColor.RED));
            return;
        }
        ItemStack copy = it.clone();
        if (e.getHand() == EquipmentSlot.OFF_HAND) user.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        else user.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        startRevive(user, tp, copy);
    }

    private int findGround(World w, int x, int z, int startY) {
        int top = Math.min(w.getMaxHeight() - 8, startY + 2);
        int bottom = Math.max(w.getMinHeight(), startY - 20);
        for (int y = top; y >= bottom; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.isPassable() || b.isLiquid() || Tag.LEAVES.isTagged(b.getType())) continue;
            if (!w.getBlockAt(x, y + 1, z).isPassable()) continue;
            return y;
        }
        return startY - 1;
    }

    private void startRevive(Player user, Player target, ItemStack totem) {
        World w = user.getWorld();
        Location loc = user.getLocation();
        double yaw = Math.toRadians(loc.getYaw());
        Ritual r = new Ritual(target.getUniqueId(), user.getUniqueId(), w);
        r.cx = (int) Math.floor(loc.getX() - Math.sin(yaw) * 5.0);
        r.cz = (int) Math.floor(loc.getZ() + Math.cos(yaw) * 5.0);
        r.baseY = findGround(w, r.cx, r.cz, loc.getBlockY()) + 1;

        for (int layer = 1; layer <= 4; layer++) {
            final int k = layer;
            Bukkit.getScheduler().runTaskLater(this, () -> placeLayer(r, k), 20L * k);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> placeBeacon(r, totem), 100L);
    }

    private boolean setStructure(Ritual r, Block b, Material mat) {
        Material cur = b.getType();
        if (cur == Material.BEDROCK || cur == Material.BARRIER) return false;
        b.setType(mat, false);
        Pos p = Pos.of(b);
        r.blocks.put(p, mat);
        structure.put(p, r);
        return true;
    }

    /** Layer 1 = 9x9, 2 = 7x7, 3 = 5x5, 4 = 3x3 (a full netherite beacon base). */
    private void placeLayer(Ritual r, int layer) {
        if (r.crumbling) return;
        int h = 5 - layer;
        int y = r.baseY + layer - 1;
        List<Block> top = new ArrayList<>();
        for (int dx = -h; dx <= h; dx++) {
            for (int dz = -h; dz <= h; dz++) {
                Block b = r.world.getBlockAt(r.cx + dx, y, r.cz + dz);
                if (setStructure(r, b, Material.NETHERITE_BLOCK)) top.add(b);
                if (layer == 1) {
                    // support columns, so the pyramid never floats over a slope or hole
                    for (int d = 1; d <= 10; d++) {
                        Block below = r.world.getBlockAt(r.cx + dx, y - d, r.cz + dz);
                        if (!below.isPassable() && !below.isLiquid()) break;
                        setStructure(r, below, Material.NETHERITE_BLOCK);
                    }
                }
            }
        }
        BlockData sand = Material.SOUL_SAND.createBlockData();
        for (Block b : top) {
            Location c = b.getLocation().add(0.5, 1.05, 0.5);
            r.world.spawnParticle(Particle.SOUL, c, 2, 0.25, 0.1, 0.25, 0.02);
            r.world.spawnParticle(Particle.BLOCK, c, 3, 0.3, 0.1, 0.3, 0.0, sand);
        }
        Location center = new Location(r.world, r.cx + 0.5, y + 1.0, r.cz + 0.5);
        r.world.playSound(center, "block.netherite_block.place", SoundCategory.BLOCKS, 2.0f, 0.6f);
    }

    private void placeBeacon(Ritual r, ItemStack totem) {
        if (r.crumbling) return;
        Player target = Bukkit.getPlayer(r.target);
        if (target == null || !target.isOnline() || target.isDead()) {
            // the player left before the ritual finished: give the totem back and tear it down
            Player user = Bukkit.getPlayer(r.activator);
            if (user != null) {
                giveStack(user, totem);
                user.sendMessage(Component.text("The player left, your Revive Totem was returned.",
                        NamedTextColor.RED));
            }
            startCrumble(r);
            return;
        }
        Block b = r.world.getBlockAt(r.cx, r.baseY + 4, r.cz);
        setStructure(r, b, Material.BEACON);
        Location center = b.getLocation().add(0.5, 0.5, 0.5);
        r.world.spawnParticle(Particle.SOUL, center.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
        r.world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 4.0f, 1.0f);

        // the revival itself
        if (getConfig().getBoolean("revive-sets-normal", true)) setStatus(target, State.NORMAL);
        if (target.getGameMode() == GameMode.SPECTATOR) target.setGameMode(GameMode.SURVIVAL);
        target.setHealth(target.getMaxHealth());
        target.setFoodLevel(20);
        target.setFireTicks(0);

        int tpY = Math.min(r.world.getMaxHeight() - 2, b.getY() + 100);
        Location dest = new Location(r.world, r.cx + 0.5, tpY, r.cz + 0.5,
                target.getLocation().getYaw(), target.getLocation().getPitch());
        noFall.add(target.getUniqueId());
        target.setFallDistance(0f);
        target.teleport(dest);
        target.setVelocity(new Vector(0, 0, 0));
        startWatch(r);
    }

    /** Waits until the revived player is back on solid ground off the structure, then crumbles it. */
    private void startWatch(Ritual r) {
        final long timeout = Math.max(10L, getConfig().getLong("revive-timeout-seconds", 300L)) * 20L;
        new BukkitRunnable() {
            long age = 0;

            @Override
            public void run() {
                age += 2;
                if (r.crumbling) {
                    cancel();
                    return;
                }
                Player p = Bukkit.getPlayer(r.target);
                if (p == null || !p.isOnline() || age >= timeout) {
                    noFall.remove(r.target);
                    startCrumble(r);
                    cancel();
                    return;
                }
                if (age < 10 || !p.isOnGround()) return;
                noFall.remove(r.target); // landed, fall protection is over
                Block under = p.getLocation().clone().subtract(0, 0.2, 0).getBlock();
                if (structure.get(Pos.of(under)) != r) {
                    startCrumble(r);
                    cancel();
                }
            }
        }.runTaskTimer(this, 2L, 2L);
    }

    private void startCrumble(Ritual r) {
        if (r.crumbling) return;
        r.crumbling = true;
        List<Pos> list = new ArrayList<>(r.blocks.keySet());
        Collections.shuffle(list, rng);
        int steps = Math.max(1, getConfig().getInt("crumble-seconds", 15) * 5); // one step per 4 ticks
        int per = Math.max(1, (int) Math.ceil(list.size() / (double) steps));
        new BukkitRunnable() {
            int idx = 0;

            @Override
            public void run() {
                for (int i = 0; i < per && idx < list.size(); i++, idx++) {
                    removeStructureBlock(r, list.get(idx));
                }
                if (idx >= list.size()) cancel();
            }
        }.runTaskTimer(this, 0L, 4L);
    }

    private void removeStructureBlock(Ritual r, Pos p) {
        Material expected = r.blocks.remove(p);
        structure.remove(p);
        Block b = r.world.getBlockAt(p.x(), p.y(), p.z());
        if (expected == null || b.getType() != expected) return; // someone changed it, leave it alone
        r.world.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, 0.0,
                b.getBlockData());
        if (rng.nextInt(6) == 0) {
            r.world.playSound(b.getLocation(), "block.netherite_block.break", SoundCategory.BLOCKS, 1.0f, 0.8f);
        }
        b.setType(Material.AIR, false);
    }

    private void cleanupStructures() {
        for (Map.Entry<Pos, Ritual> en : new ArrayList<>(structure.entrySet())) {
            Ritual r = en.getValue();
            Pos p = en.getKey();
            Material expected = r.blocks.get(p);
            Block b = r.world.getBlockAt(p.x(), p.y(), p.z());
            if (expected != null && b.getType() == expected) b.setType(Material.AIR, false);
        }
        structure.clear();
    }

    // ---- protection while the ritual is running

    @EventHandler(ignoreCancelled = true)
    public void onRevivedFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p
                && noFall.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onStructureBreak(BlockBreakEvent e) {
        if (!structure.isEmpty() && structure.containsKey(Pos.of(e.getBlock())) && !e.getPlayer().isOp()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onStructureExplode(EntityExplodeEvent e) {
        if (!structure.isEmpty()) e.blockList().removeIf(b -> structure.containsKey(Pos.of(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onStructureBlockExplode(BlockExplodeEvent e) {
        if (!structure.isEmpty()) e.blockList().removeIf(b -> structure.containsKey(Pos.of(b)));
    }

    /** The Revive Totem must never trigger the vanilla "cheat death" effect. */
    @EventHandler
    public void onTotemResurrect(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        if (specialOf(main) == null && specialOf(off) == null) return;
        boolean realTotem = (main.getType() == Material.TOTEM_OF_UNDYING && specialOf(main) == null)
                || (off.getType() == Material.TOTEM_OF_UNDYING && specialOf(off) == null);
        if (!realTotem) e.setCancelled(true);
    }

    // ================================================================== Core Voider (corebreaker)

    private final Map<UUID, Long> breakerCooldown = new HashMap<>();

    private ItemStack buildCoreBreaker() {
        ItemStack it = new ItemStack(Material.FISHING_ROD);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Core Voider", NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        m.setUnbreakable(true);
        m.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        m.getPersistentDataContainer().set(keySpecial, PersistentDataType.STRING, "corebreaker");
        it.setItemMeta(m);
        return it;
    }

    /** Reeling the rod back in (not casting it) while looking at a player destroys that player's core. */
    @EventHandler
    public void onCoreVoiderReel(PlayerFishEvent e) {
        String st = e.getState().name();
        if (!(st.equals("REEL_IN") || st.equals("IN_GROUND") || st.equals("FAILED_ATTEMPT")
                || st.equals("CAUGHT_ENTITY") || st.equals("CAUGHT_FISH"))) return;
        Player user = e.getPlayer();
        if (!"corebreaker".equals(specialOf(user.getInventory().getItemInMainHand()))
                && !"corebreaker".equals(specialOf(user.getInventory().getItemInOffHand()))) return;

        long cd = getConfig().getLong("corebreaker-cooldown-seconds", 0L) * 1000L;
        long now = System.currentTimeMillis();
        Long last = breakerCooldown.get(user.getUniqueId());
        if (cd > 0 && last != null && now - last < cd) return;

        Player victim = lookedAtPlayer(user);
        if (victim == null) return;
        Data d = data.get(victim.getUniqueId());
        if (d == null || d.coreId == null || d.state == State.HARDCORE) return; // nothing to destroy

        breakerCooldown.put(user.getUniqueId(), now);
        removeCores(victim);                                  // gone even from inventory / ender chest
        coreDestroyed(victim.getUniqueId(), "Core Voider");   // hardcore + black hearts + beacon sound
    }

    /** The player the user is aiming at, with practically unlimited reach. */
    private Player lookedAtPlayer(Player user) {
        Location eye = user.getEyeLocation();
        Vector origin = eye.toVector();
        Vector dir = eye.getDirection().normalize();
        double range = Math.max(10.0, getConfig().getDouble("corebreaker-range", 5000.0));
        boolean walls = getConfig().getBoolean("corebreaker-through-walls", false);
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : user.getWorld().getPlayers()) {
            if (p.equals(user) || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = eye.distance(p.getLocation());
            // a little aiming tolerance that grows with distance, so far-away players are still hittable
            BoundingBox box = p.getBoundingBox().clone().expand(Math.min(1.5, 0.15 + d * 0.01));
            RayTraceResult rr = box.rayTrace(origin, dir, range);
            if (rr == null) continue;
            double hit = rr.getHitPosition().distance(origin);
            if (hit >= bestDist) continue;
            if (!walls && blockedBetween(user.getWorld(), origin, dir, hit)) continue;
            bestDist = hit;
            best = p;
        }
        return best;
    }

    /** True if a solid block is between the user and the target (unloaded chunks are skipped, never loaded). */
    private boolean blockedBetween(World w, Vector origin, Vector dir, double dist) {
        for (double t = 0.6; t < dist - 0.6; t += 0.4) {
            int x = (int) Math.floor(origin.getX() + dir.getX() * t);
            int y = (int) Math.floor(origin.getY() + dir.getY() * t);
            int z = (int) Math.floor(origin.getZ() + dir.getZ() * t);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (w.getBlockAt(x, y, z).getType().isOccluding()) return true;
        }
        return false;
    }

    // ================================================================== Gambler's Fallacy (mace)

    private ItemStack buildGamblersFallacy() {
        ItemStack it = new ItemStack(Material.MACE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Gambler's Fallacy", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        m.getPersistentDataContainer().set(keySpecial, PersistentDataType.STRING, "gamblersfallacy");
        it.setItemMeta(m);
        return it;
    }

    /** Every hit on a CURSED player rerolls their curse and applies the new one instantly. */
    @EventHandler(ignoreCancelled = true)
    public void onGamblersFallacyHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof Player victim)) return;
        if (!"gamblersfallacy".equals(specialOf(attacker.getInventory().getItemInMainHand()))) return;
        Data d = data.get(victim.getUniqueId());
        if (d == null || d.state != State.CURSED) return; // only works on cursed players
        removeCurse(victim, d);
        d.curse = rollCurse();
        save();
        ensureEffects(victim, d);
    }
}
