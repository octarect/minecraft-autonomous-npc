package io.github.octarect.autonomousnpc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class AutonomousNpcPlugin extends JavaPlugin implements Listener {
    private static final List<String> DEFAULT_NAMES = List.of("Aster", "Birch", "Cinder");
    private final Map<UUID, NpcState> states = new HashMap<>();
    private final Map<UUID, ServerPlayer> players = new HashMap<>();
    private final Map<UUID, List<org.bukkit.Chunk>> tickets = new HashMap<>();
    private final Map<String, Integer> ticketReferences = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("autonomousnpc").setExecutor(this);
        loadStates();
        Bukkit.getScheduler().runTask(this, this::spawnLoaded);
        Bukkit.getScheduler().runTaskTimer(this, this::think, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::saveStates, 600L, 600L);
    }

    @Override
    public void onDisable() {
        saveStates();
        new ArrayList<>(states.keySet()).forEach(this::despawn);
    }

    private void loadStates() {
        World world = Bukkit.getWorld("world");
        if (world == null) return;
        var section = getConfig().getConfigurationSection("npcs");
        if (section == null) {
            Map<UUID, NpcState> defaults = new HashMap<>();
            for (String name : DEFAULT_NAMES) {
                UUID id = UUID.randomUUID();
                defaults.put(id, new NpcState(id, name, world.getSpawnLocation()));
            }
            states.clear();
            states.putAll(defaults);
            return;
        }
        for (String key : section.getKeys(false)) {
            UUID id = UUID.fromString(key);
            String name = section.getString(key + ".name", "NPC");
            Location home = location(key, "home");
            Location current = location(key, "location");
            if (home == null || current == null) continue;
            NpcState state = new NpcState(id, name, home);
            state.location = current;
            state.paused = section.getBoolean(key + ".paused");
            state.aggressive = section.getBoolean(key + ".aggressive");
            state.initialized = section.getBoolean(key + ".initialized");
            state.health = section.getDouble(key + ".health", 20);
            state.food = section.getInt(key + ".food", 20);
            String target = section.getString(key + ".retaliate-against");
            if (target != null) state.retaliateAgainst = UUID.fromString(target);
            state.goal = section.getString(key + ".goal", "exploring");
            List<?> storedInventory = section.getList(key + ".inventory", List.of());
            state.inventory = storedInventory.stream().filter(ItemStack.class::isInstance).map(ItemStack.class::cast).toArray(ItemStack[]::new);
            states.put(id, state);
        }
    }

    private Location location(String key, String prefix) {
        String path = "npcs." + key + "." + prefix + ".";
        World world = Bukkit.getWorld(getConfig().getString(path + "world", "world"));
        if (world == null) return null;
        return new Location(world, getConfig().getDouble(path + "x", world.getSpawnLocation().getX()), getConfig().getDouble(path + "y", world.getSpawnLocation().getY()), getConfig().getDouble(path + "z", world.getSpawnLocation().getZ()));
    }

    private void spawnLoaded() {
        states.keySet().forEach(this::spawn);
    }

    private void spawn(UUID id) {
        if (players.containsKey(id)) return;
        NpcState state = states.get(id);
        if (state == null || state.location.getWorld() == null) return;
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) state.location.getWorld()).getHandle();
        GameProfile profile = new GameProfile(id, state.name);
        String texture = getConfig().getString("skin.texture", "");
        String signature = getConfig().getString("skin.signature", "");
        if (!texture.isEmpty() && !signature.isEmpty()) profile.properties().put("textures", new Property("textures", texture, signature));
        ServerPlayer npc = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        npc.setPos(state.location.getX(), state.location.getY(), state.location.getZ());
        level.addNewPlayer(npc);
        Player player = (Player) npc.getBukkitEntity();
        if (state.initialized) player.getInventory().setContents(state.inventory);
        else {
            player.getInventory().addItem(new ItemStack(Material.BREAD, 16), new ItemStack(Material.WOODEN_SWORD), new ItemStack(Material.WOODEN_AXE), new ItemStack(Material.TORCH, 32));
            state.initialized = true;
        }
        player.setHealth(Math.max(1, Math.min(player.getMaxHealth(), state.health)));
        player.setFoodLevel(state.food);
        players.put(id, npc);
        holdTickets(id, player.getLocation());
    }

    private void despawn(UUID id) {
        releaseTickets(id);
        ServerPlayer npc = players.remove(id);
        if (npc != null) npc.discard();
    }

    private void think() {
        for (var entry : players.entrySet()) {
            NpcState state = states.get(entry.getKey());
            ServerPlayer npc = entry.getValue();
            Player player = (Player) npc.getBukkitEntity();
            if (state == null || state.paused || player.isDead()) continue;
            Location here = player.getLocation();
            holdTickets(state.id, here);
            if (player.getFoodLevel() < 14 && player.getInventory().containsAtLeast(new ItemStack(Material.BREAD), 1)) {
                state.goal = "eating";
                player.getInventory().removeItem(new ItemStack(Material.BREAD, 1));
                player.setFoodLevel(Math.min(20, player.getFoodLevel() + 5));
            }
            Monster danger = nearest(player, Monster.class, 12);
            if (danger != null) {
                state.goal = "fleeing";
                move(npc, here.toVector().subtract(danger.getLocation().toVector()).normalize().multiply(0.25));
            } else if (here.distanceSquared(state.home) > Math.pow(getConfig().getDouble("activity-radius", 64), 2) || here.getWorld().getTime() > 13000) {
                state.goal = "returning-home";
                moveToward(npc, here, state.home);
            }
            else {
                Entity target = state.aggressive ? nearest(player, Player.class, 3) : Bukkit.getPlayer(state.retaliateAgainst);
                if (target != null && target.getLocation().getWorld() == here.getWorld() && target.getLocation().distanceSquared(here) <= 9) {
                    state.goal = "attacking";
                    player.attack(target);
                } else {
                    state.goal = "exploring";
                    move(npc, new org.bukkit.util.Vector((Math.random() - .5) * .18, 0, (Math.random() - .5) * .18));
                }
            }
        }
    }

    private <T extends Entity> T nearest(Player player, Class<T> type, double range) {
        return player.getNearbyEntities(range, range, range).stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private void moveToward(ServerPlayer npc, Location from, Location to) {
        move(npc, to.toVector().subtract(from.toVector()).normalize().multiply(0.18));
    }

    private void move(ServerPlayer npc, org.bukkit.util.Vector vector) {
        npc.move(MoverType.SELF, new Vec3(vector.getX(), vector.getY(), vector.getZ()));
    }

    private void holdTickets(UUID id, Location location) {
        releaseTickets(id);
        List<org.bukkit.Chunk> held = new ArrayList<>();
        World world = location.getWorld();
        int centerX = location.getBlockX() >> 4;
        int centerZ = location.getBlockZ() >> 4;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            org.bukkit.Chunk chunk = world.getChunkAt(centerX + x, centerZ + z);
            String key = ticketKey(chunk);
            if (ticketReferences.merge(key, 1, Integer::sum) == 1) chunk.addPluginChunkTicket(this);
            held.add(chunk);
        }
        tickets.put(id, held);
    }

    private void releaseTickets(UUID id) {
        List<org.bukkit.Chunk> held = tickets.remove(id);
        if (held != null) held.forEach(chunk -> {
            String key = ticketKey(chunk);
            if (ticketReferences.merge(key, -1, Integer::sum) == 0) {
                ticketReferences.remove(key);
                chunk.removePluginChunkTicket(this);
            }
        });
    }

    private String ticketKey(org.bukkit.Chunk chunk) {
        return chunk.getWorld().getUID() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        NpcState state = states.get(player.getUniqueId());
        if (state == null || state.aggressive || !(event.getDamager() instanceof Player attacker)) return;
        state.retaliateAgainst = attacker.getUniqueId();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        NpcState state = states.get(player.getUniqueId());
        if (state == null) return;
        Bukkit.getScheduler().runTask(this, () -> {
            state.deathLocation = player.getLocation();
            state.inventory = new ItemStack[0];
            state.location = state.home.clone();
            String cause = player.getLastDamageCause() == null ? "unknown" : player.getLastDamageCause().getCause().name();
            getLogger().info(state.name + " died to " + cause + " at " + state.deathLocation + " with " + event.getDrops().size() + " dropped item stacks");
            despawn(state.id);
            state.aggressive = Math.random() < .10;
            state.retaliateAgainst = null;
            Bukkit.getScheduler().runTaskLater(this, () -> spawn(state.id), 40L);
            saveStates();
        });
    }

    private void saveStates() {
        getConfig().set("npcs", null);
        for (NpcState state : states.values()) {
            String key = "npcs." + state.id;
            Player player = players.containsKey(state.id) ? (Player) players.get(state.id).getBukkitEntity() : null;
            Location location = player == null ? state.location : player.getLocation();
            getConfig().set(key + ".name", state.name);
            saveLocation(key + ".home", state.home);
            saveLocation(key + ".location", location);
            getConfig().set(key + ".paused", state.paused);
            getConfig().set(key + ".aggressive", state.aggressive);
            getConfig().set(key + ".initialized", state.initialized);
            getConfig().set(key + ".health", player == null ? state.health : player.getHealth());
            getConfig().set(key + ".food", player == null ? state.food : player.getFoodLevel());
            getConfig().set(key + ".retaliate-against", state.retaliateAgainst == null ? null : state.retaliateAgainst.toString());
            getConfig().set(key + ".goal", state.goal);
            getConfig().set(key + ".inventory", player == null ? state.inventory : player.getInventory().getContents());
        }
        saveConfig();
    }

    private void saveLocation(String path, Location location) {
        getConfig().set(path + ".world", location.getWorld().getName());
        getConfig().set(path + ".x", location.getX());
        getConfig().set(path + ".y", location.getY());
        getConfig().set(path + ".z", location.getZ());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equals("list")) {
            states.values().forEach(state -> sender.sendMessage(state.name + ": " + (players.containsKey(state.id) ? "active" : "inactive") + (state.paused ? " (paused)" : "")));
            return true;
        }
        if (args.length < 2) return false;
        NpcState state = states.values().stream().filter(value -> value.name.equalsIgnoreCase(args[1])).findFirst().orElse(null);
        if (state == null) { sender.sendMessage("Unknown NPC: " + args[1]); return true; }
        switch (args[0]) {
            case "pause" -> { state.paused = true; releaseTickets(state.id); }
            case "resume" -> { state.paused = false; spawn(state.id); }
            case "spawn" -> spawn(state.id);
            case "remove" -> { despawn(state.id); states.remove(state.id); }
            case "debug" -> sender.sendMessage(state.name + " id=" + state.id + " aggressive=" + state.aggressive + " tickets=" + tickets.getOrDefault(state.id, List.of()).size());
            default -> { return false; }
        }
        saveStates();
        return true;
    }
}
