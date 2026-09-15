package io.github.octarect.autonomousnpc;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

final class NpcState {
    final UUID id;
    final String name;
    Location home;
    Location location;
    boolean paused;
    boolean aggressive;
    boolean initialized;
    double health = 20;
    int food = 20;
    UUID retaliateAgainst;
    Location deathLocation;
    String goal = "exploring";
    ItemStack[] inventory = new ItemStack[0];

    NpcState(UUID id, String name, Location home) {
        this.id = id;
        this.name = name;
        this.home = home;
        this.location = home.clone();
    }
}
