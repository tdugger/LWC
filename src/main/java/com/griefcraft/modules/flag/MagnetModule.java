/*
 * Copyright 2011 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */

package com.griefcraft.modules.flag;

import com.griefcraft.lwc.LWC;
import com.griefcraft.model.Flag;
import com.griefcraft.model.Protection;
import com.griefcraft.scripting.JavaModule;
import com.griefcraft.util.config.Configuration;
import com.narrowtux.showcase.Showcase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class MagnetModule extends JavaModule {

    private Configuration configuration = Configuration.load("magnet.yml");

    /**
     * The LWC object
     */
    private LWC lwc;

    /**
     * If this module is enabled
     */
    private boolean enabled = false;

    /**
     * The item blacklist
     */
    private List<Integer> itemBlacklist;

    /**
     * The radius around the container in which to suck up items
     */
    private int radius;

    /**
     * How many items to check each time
     */
    private int perSweep;

    /**
     * The current entity queue
     */
    private final Queue<Item> items = new LinkedList<Item>();

    // does all of the work
    // searches the worlds for items and magnet chests nearby
    private class MagnetTask implements Runnable {
        public void run() {
            final Server server = Bukkit.getServer();
            final LWC lwc = LWC.getInstance();

            // Do we need to requeue?
            if (items.size() == 0) {
                Future<Void> itemLoader = server.getScheduler().callSyncMethod(lwc.getPlugin(), new Callable<Void>() {

                    public Void call() {
                        for (World world : server.getWorlds()) {
                            for (Entity entity : world.getEntities()) {
                                if (!(entity instanceof Item)) {
                                    continue;
                                }
                                
                                Item item = (Item) entity;
                                
                                // native stack handle
                                net.minecraft.server.ItemStack stackHandle = ((net.minecraft.server.EntityItem) ((CraftItem) item).getHandle()).itemStack;
                                
                                // check if it is in the blacklist
                                if (itemBlacklist.contains(stackHandle.id)) {
                                    continue;
                                }
                                
                                // check if the item is valid
                                if (stackHandle.count <= 0) {
                                    continue;
                                }
                                
                                // has the item been living long enough?
                                if (item.getPickupDelay() > item.getTicksLived()) {
                                    continue; // a player wouldn't have had a chance to pick it up yet
                                }
                                
                                // Check for usable blocks
                                if (scanForInventoryBlock(item.getLocation(), radius) == null) {
                                    continue;
                                }
                                
                                items.offer(item);
                            }
                        }

                        return null;
                    }

                });
                
                // load the items
                try {
                    itemLoader.get();
                } catch (Exception e) { }
            }

            // Throttle amount of items polled
            int count = 0;
            Item item;

            int i = 1;
            while ((item = items.poll()) != null) {
                final World world = item.getWorld();

                if (item.isDead()) {
                    continue;
                }

                if (isShowcaseItem(item)) {
                    // it's being used by the Showcase plugin ... ignore it
                    continue;
                }

                // create the future task that will grab the inventory blocks from the world
                final Item finalItem = item;
                Future<Block> inventoryBlockCallable = server.getScheduler().callSyncMethod(lwc.getPlugin(), new Callable<Block>() {

                    public Block call() {
                        return scanForInventoryBlock(finalItem.getLocation(), radius);
                    }

                });


                Block testBlock = null;
                try {
                    testBlock = inventoryBlockCallable.get();
                } catch (Exception e) { }

                final Block block = testBlock;

                if (block != null) {

                    Runnable runnable = new Runnable() {
                        public void run() {
                            Protection protection = lwc.findProtection(block);

                            if (protection == null) {
                                return;
                            }

                            if (!protection.hasFlag(Flag.Type.MAGNET)) {
                                return;
                            }

                            ItemStack itemStack = finalItem.getItemStack();

                            // Remove the items and suck them up :3
                            Map<Integer, ItemStack> remaining = lwc.depositItems(block, itemStack);

                            // we cancelled the item drop for some reason
                            if (remaining == null) {
                                return;
                            }

                            if (remaining.size() == 1) {
                                ItemStack other = remaining.values().iterator().next();

                                if (itemStack.getTypeId() == other.getTypeId() && itemStack.getAmount() == other.getAmount()) {
                                    return;
                                }
                            }

                            // remove the item on the ground
                            finalItem.remove();

                            // if we have a remainder, we need to drop them
                            if (remaining.size() > 0) {
                                for (ItemStack stack : remaining.values()) {
                                    world.dropItemNaturally(finalItem.getLocation(), stack);
                                }
                            }
                        }
                    };

                    server.getScheduler().scheduleSyncDelayedTask(lwc.getPlugin(), runnable);
                }

                // Time to throttle?
                if (count > perSweep) {
                    break;
                }

                count++;
            }
        }
    }

    /**
     * Check for the Showcase plugin and if it exists we also want to make sure the block doesn't have a showcase
     * on it.
     *
     * @param item
     * @return
     */
    private boolean isShowcaseItem(Item item) {
        if (item == null) {
            return false;
        }

        // check for the showcase plugin
        boolean hasShowcase = Bukkit.getServer().getPluginManager().getPlugin("Showcase") != null;

        if (hasShowcase) {
            return Showcase.instance.getItemByDrop(item) != null;
        }

        return false;
    }

    @Override
    public void load(LWC lwc) {
        this.lwc = lwc;
        enabled = configuration.getBoolean("magnet.enabled", false);
        itemBlacklist = new ArrayList<Integer>();
        radius = configuration.getInt("magnet.radius", 3);
        perSweep = configuration.getInt("magnet.perSweep", 20);

        if (!enabled) {
            return;
        }

        // get the item blacklist
        List<String> temp = configuration.getStringList("magnet.blacklist", new ArrayList<String>());

        for (String item : temp) {
            Material material = Material.matchMaterial(item);

            if (material != null) {
                itemBlacklist.add(material.getId());
            }
        }

        // register our search thread schedule
        MagnetTask searchThread = new MagnetTask();
        lwc.getPlugin().getServer().getScheduler().scheduleAsyncRepeatingTask(lwc.getPlugin(), searchThread, 50, 50);
    }

    /**
     * Scan for one inventory block around the given block inside the given radius
     *
     * @param location
     * @param radius
     * @return
     */
    private Block scanForInventoryBlock(Location location, int radius) {
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();
        World world = location.getWorld();

        // native handle
        net.minecraft.server.WorldServer worldHandle = ((CraftWorld) world).getHandle();

        List<net.minecraft.server.TileEntity> entities = (List<net.minecraft.server.TileEntity>) worldHandle.getTileEntities(baseX - radius,  baseY - radius, baseZ - radius, baseX + radius, baseY + radius, baseZ + radius);

        for (net.minecraft.server.TileEntity entity : entities) {
            Block block = world.getBlockAt(entity.x, entity.y, entity.z);

            try {
                if (block.getState() instanceof InventoryHolder) {
                    return block;
                }
            } catch (NullPointerException e) {
                LWC lwc = LWC.getInstance();
                lwc.log("Possibly invalid block found at [" + entity.x + ", " + entity.y + ", " + entity.z + "]!");
            }
        }

        return null;
    }

}
