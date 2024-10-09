package com.github.inkm3.signtp.Events;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import com.github.inkm3.signtp.CustomEvents.ServerDataUpdateEvent;
import com.github.inkm3.signtp.FileEdit.ServerData;
import com.github.inkm3.signtp.FileEdit.SignData;
import com.github.inkm3.signtp.Main;
import com.github.inkm3.signtp.SignData.SerializeTextData;
import com.github.inkm3.signtp.System.SignTP;
import com.github.inkm3.signtp.Util.Message;
import com.github.inkm3.signtp.Util.SignUtil;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.SignBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.WallHangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.data.type.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.craftbukkit.block.CraftBlockEntityState;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.IOException;
import java.net.Socket;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

public class SignEvents {

    private static final Plugin plugin = Main.getPlugin(Main.class);


    public static void load() {
        PluginManager manager = plugin.getServer().getPluginManager();

        manager.registerEvents(new SignInteract(), plugin);
        manager.registerEvents(new SignSetting(), plugin);
        manager.registerEvents(new SignUpdate(), plugin);
        manager.registerEvents(new SignCopy(), plugin);
        manager.registerEvents(new BreakEvent(), plugin);
    }

    static class SignInteract implements Listener {

        private static final HashMap<Player, ConnectingData> ConnectionServer = new HashMap<>();

        record ConnectingData(SignData.deserializeSignData data, Side side) {

        }

        @EventHandler
        private void PlayerOpenSignEvent(PlayerOpenSignEvent event) {
            org.bukkit.block.Sign sign = event.getSign();
            Player player = event.getPlayer();
            Side side = event.getSide();

            if (!SignUtil.isSignTP(sign)) return;
            SerializeTextData data = SignUtil.getSignData(sign);
            event.setCancelled(true);

            if (player.hasPermission("signtp.edit") && player.isSneaking()) {
                String[] rawText = Side.FRONT.equals(side) ? data.front : data.back;
                SignUtil.sendSignRawText(player, sign.getLocation(), side, rawText);
                SignUtil.sendOpenSignEditor(player, sign.getLocation(), side);
            }
        }

        @EventHandler
        private void PlayerQuitEvent(PlayerQuitEvent event) {
            Player player = event.getPlayer();

            if (!ConnectionServer.containsKey(player)) return;

            ConnectionServer.get(player).data().removePlayer(player);
            ConnectionServer.remove(player);
        }

        @EventHandler
        private void PlayerInteractEvent(PlayerInteractEvent event) {
            if (event.getClickedBlock() == null) return;
            if (!event.getAction().isRightClick()) return;

            Player player = event.getPlayer();
            Block block = event.getClickedBlock();
            Location loc = block.getLocation();
            ItemStack item = event.getItem();

            if (!(block.getState() instanceof org.bukkit.block.Sign sign)) return;

            Side side = sign.getInteractableSideFor(player);
            SignData.deserializeSignData data = SignData.get(loc);

            if (data == null) {
                if (SignUtil.isSignTP(sign)) {
                    SignUtil.removeSignTP(sign);
                    sign.getBlock().breakNaturally(true, false);
                }
                return;
            }
            else if (!SignUtil.isSignTP(sign)) {
                SignUtil.setSignData(sign);
            }

            BlockData blockData = data.getData();
            String[] frontData = data.getFront();
            String[] backData = data.getBack();

            if (item != null) {
                if (!player.isSneaking()) {
                    if (player.hasPermission("signtp.edit")) {
                        if (item.getType().createBlockData().createBlockState() instanceof org.bukkit.block.Sign itemData)  {
                            sign.setBlockData(setSignData(blockData, itemData.getBlockData()));
                            sign.update(true, false);
                            data.setData(sign.getBlockData());
                            return;
                        }
                    }
                }
                else {
                    return;
                }
            }
            else if (player.isSneaking() && player.hasPermission("signtp.edit")) {
                return;
            }

            String[] line = switch (side) {
                case FRONT -> {
                    if ("[SignTP]".equalsIgnoreCase(frontData[0])) yield frontData;
                    else {
                        side = Side.BACK;
                        yield backData;
                    }
                }

                case BACK -> {
                    if ("[SignTP]".equalsIgnoreCase(backData[0])) yield backData;
                    else {
                        side = Side.FRONT;
                        yield frontData;
                    }
                }

                default -> new String[]{ "", "", "", "" } ;
            };

            String server = line[1];
            String title = line[2].isBlank() ? server : line[2];

            ServerData connect = ServerData.get(server);
            ServerData connected = ServerData.getServer();

            ConnectingData connectingData = ConnectionServer.get(player);

            String connectedName = connected != null ? connected.name() : "";
            String connectingName = connectingData != null ? connectingData.side().equals(Side.FRONT) ? connectingData.data().getFront()[1] : connectingData.data().getBack()[1] : "";

            String pattern = ((Supplier<String>) () -> {
                if (connect == null) return "Unknown";
                else if (!player.hasPermission("signtp.connect")) return "Authority";
                else if (connect.equals(connected)) return "Connected";
                else if ("OFFLINE".equals(connect.status())) return "Offline";
                else if (ConnectionServer.containsKey(player)) return "Connecting";
                else {
                    String ip = connect.ip();
                    int port = connect.port();
                    Socket s;

                    try {
                        s = new Socket(ip, port);
                        s.close();

                        return "";
                    } catch (IOException ignore) {
                        connect.status("OFFLINE");
                        return "Error";
                    }
                }
            }).get();

            if (pattern.isEmpty()) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();

                out.writeUTF("Connect");
                out.writeUTF(server);
                player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());

                SignUtil.sendSignText(player, sign.getLocation(), side, frontData);
                ConnectionServer.put(player, new ConnectingData(data, side));
                data.addPlayer(player, side);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    ConnectionServer.remove(player);
                    data.removePlayer(player);
                }, 10 * 20L);
            }
            else {
                Locale locale = player.locale();
                String msg = switch (pattern) {
                    case "Unknown" -> Message.getMessage(locale, Message.MessageType.ConnectionFailure_Unknown);
                    case "Authority" -> Message.getMessage(locale, Message.MessageType.ConnectionFailure_Authority);
                    case "Connected" -> Message.getMessage(locale, Message.MessageType.ConnectionFailure_Connected);
                    case "Offline" -> Message.getMessage(locale, Message.MessageType.ConnectionFailure_Offline);
                    case "Connecting" -> Message.getMessage(locale, Message.MessageType.ConnectionFailure_Connecting);
                    case "Error" -> Message.getMessage(locale, Message.MessageType.ConnectionFailure_Error);
                    default -> "";
                };

                if (!msg.isEmpty()) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(msg,
                            Placeholder.unparsed("server", server),
                            Placeholder.unparsed("connect", server),
                            Placeholder.unparsed("connecting", connectingName),
                            Placeholder.unparsed("connected", connectedName),
                            Placeholder.unparsed("title", title),
                            Placeholder.unparsed("player", player.getName())));
                }
            }
        }

        private BlockData setSignData(BlockData oldData, BlockData newData) {

            String oldType = SignBlock.getWoodType(((CraftBlockData) oldData).getState().getBlock()).name().toUpperCase();
            String newType = SignBlock.getWoodType(((CraftBlockData) newData).getState().getBlock()).name().toUpperCase();

            if (!oldType.equals(newType)) {
                switch (oldData) {
                    case WallHangingSign s -> {
                        WallHangingSign data = (WallHangingSign) Objects.requireNonNullElse(Material.getMaterial(newType + "_WALL_HANGING_SIGN"), oldData.getMaterial()).createBlockData();
                        data.setWaterlogged(s.isWaterlogged());
                        data.setFacing(s.getFacing());
                        return data;
                    }
                    case HangingSign s -> {
                        HangingSign data = (HangingSign) Objects.requireNonNullElse(Material.getMaterial(newType + "_HANGING_SIGN"), oldData.getMaterial()).createBlockData();
                        data.setWaterlogged(s.isWaterlogged());
                        data.setRotation(s.getRotation());
                        data.setAttached(s.isAttached());
                        return data;
                    }
                    case WallSign s -> {
                        WallSign data = (WallSign) Objects.requireNonNullElse(Material.getMaterial(newType + "_WALL_HANGING_SIGN"), oldData.getMaterial()).createBlockData();
                        data.setWaterlogged(s.isWaterlogged());
                        data.setFacing(s.getFacing());
                        return data;
                    }
                    case Sign s -> {
                        Sign data = (Sign) Objects.requireNonNullElse(Material.getMaterial(newType + "_SIGN"), oldData.getMaterial()).createBlockData();
                        data.setWaterlogged(s.isWaterlogged());
                        data.setRotation(s.getRotation());
                        return data;
                    }
                    default -> {
                        return oldData;
                    }
                }
            }
            else {
                if (oldData instanceof HangingSign data) {
                    data.setAttached(!data.isAttached());
                    return data;
                }
                return oldData;
            }
        }
    }

    static class SignUpdate implements Listener {

        @EventHandler
        private void ServerDataUpdateEvent(ServerDataUpdateEvent event) {
            ServerData data = event.getServeData();

            SignTP.update(data.name(), false);
        }
    }

    static class SignSetting implements Listener {

        @EventHandler
        private void BlockPlaceEvent(BlockPlaceEvent event) {
            Player player = event.getPlayer();
            Block block = event.getBlock();

            if (!(block.getState() instanceof org.bukkit.block.Sign sign)) return;

            SerializeTextData data =  SignUtil.getSignData(sign);

            if ("[SignTP]".equalsIgnoreCase(data.front[0]) || "[SignTP]".equalsIgnoreCase(data.back[0])) {
                if (player.hasPermission("signtp.edit")) {
                    SignData.create(sign, data.front, data.back, data.display);
                }
                else {
                    String rawMsg = Message.getMessage(player.locale(), Message.MessageType.Setting_AuthorityMissing);

                    player.sendMessage(MiniMessage.miniMessage().deserialize(rawMsg,
                            Placeholder.unparsed("server", ""),
                            Placeholder.unparsed("connect", ""),
                            Placeholder.unparsed("connecting", ""),
                            Placeholder.unparsed("connected", ""),
                            Placeholder.unparsed("title", ""),
                            Placeholder.unparsed("player", player.getName())));

                    event.setCancelled(true);
                }
            }
        }

        @EventHandler
        private void SignChangeEvent(SignChangeEvent event) {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            Location loc = block.getLocation();
            Side side = event.getSide();

            if (!(block.getState() instanceof org.bukkit.block.Sign sign)) return;

            List<String> lines = event.lines().stream().map(line -> ((TextComponent) line).content()).toList();
            SignData.deserializeSignData data = SignData.get(loc);
            boolean isSignTP = "[SignTP]".equalsIgnoreCase(lines.getFirst());

            if (!isSignTP && data == null) return;

            if (!player.hasPermission("signtp.edit")) {
                String rawMsg = Message.getMessage(player.locale(), Message.MessageType.Setting_AuthorityMissing);
                Component msg = MiniMessage.miniMessage().deserialize(rawMsg,
                        Placeholder.unparsed("server", ""),
                        Placeholder.unparsed("connect", ""),
                        Placeholder.unparsed("connecting", ""),
                        Placeholder.unparsed("connected", ""),
                        Placeholder.unparsed("title", ""),
                        Placeholder.unparsed("player", player.getName()));
                player.sendMessage(msg);
                event.setCancelled(true);
                return;
            }
            else if (lines.get(1).isBlank() && "[SignTP]".equalsIgnoreCase(lines.get(0))) {
                String rawMsg = Message.getMessage(player.locale(), Message.MessageType.Setting_ServerNameEmpty);
                Component msg = MiniMessage.miniMessage().deserialize(rawMsg,
                        Placeholder.unparsed("server", ""),
                        Placeholder.unparsed("connect", ""),
                        Placeholder.unparsed("connecting", ""),
                        Placeholder.unparsed("connected", ""),
                        Placeholder.unparsed("title", ""),
                        Placeholder.unparsed("player", player.getName()));
                player.sendMessage(msg);
                event.setCancelled(true);
                return;
            }

            if (!isSignTP) {
                if (switch (side) {
                    case FRONT -> !"[SignTP]".equalsIgnoreCase(data.getBack()[0]);
                    case BACK ->  !"[SignTP]".equalsIgnoreCase(data.getFront()[0]);
                } ) {
                    SignData.delete(loc);
                    return;
                }
            }

            String[] front =   new String[] { "", "", "", "" };
            String[] back =    new String[] { "", "", "", "" };
            String[] display = new String[] { "", "", "", "" };

            switch (side) {
                case FRONT -> front = lines.toArray(new String[4]);
                case BACK -> back = lines.toArray(new String[4]);
            }

            if (data != null) {
                switch (side) {
                    case FRONT -> data.setFront(front);
                    case BACK -> data.setBack(back);
                }
                SignUtil.setSignData(sign);
            }
            else {
                SignData.create(sign, front, back, display);
            }

            if (isSignTP) {
                event.setCancelled(true);
            }
        }
    }

    static class SignCopy implements Listener {

        @EventHandler
        private void InventoryCreativeEvent(InventoryCreativeEvent event) {
            if (!event.getClick().isCreativeAction()) return;

            if (event.getWhoClicked() instanceof Player player && !player.hasPermission("signtp.edit")) {
                ItemStack item = event.getCursor();

                if (item.getType().isBlock() && item.getType().createBlockData().createBlockState() instanceof org.bukkit.block.Sign sign) {
                    net.minecraft.world.item.ItemStack nmsItem = CraftItemStack.asNMSCopy(item);
                    nmsItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(SignUtil.removeSignTP(nmsItem.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(((CraftBlockEntityState<?>) sign).getSnapshotNBTWithoutComponents())).copyTag())));
                    item.setItemMeta(nmsItem.getBukkitStack().getItemMeta());
                }
            }
        }
    }

    static class BreakEvent implements Listener {

        @EventHandler
        private void BlockBreakEvent(BlockBreakEvent event) {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            Location loc = block.getLocation();

            if (!(block.getState() instanceof org.bukkit.block.Sign)) return;
            if (!SignData.isSignTP(loc)) return;

            if (player.hasPermission("signtp.edit") && !event.isCancelled()) {
                SignData.delete(loc);
            }
            else {
                event.setCancelled(true);
            }
        }

        @EventHandler
        private void BlockPistonRetractEvent(BlockPistonRetractEvent event) {
            if (event.getBlocks().stream().anyMatch(block -> SignData.isSignTP(block.getLocation()))) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        private void BlockPistonExtendEvent(BlockPistonExtendEvent event) {
            if (event.getBlocks().stream().anyMatch(block -> SignData.isSignTP(block.getLocation()))) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        private void BlockExplodeEvent(BlockExplodeEvent event) {
            event.blockList().removeIf(block -> SignData.isSignTP(block.getLocation()));
        }

        @EventHandler
        private void EntityExplodeEvent(EntityExplodeEvent event) {
            event.blockList().removeIf(block -> SignData.isSignTP(block.getLocation()));
        }

        @EventHandler
        private void BlockPhysicsEvent(BlockPhysicsEvent event) {
            Block block = event.getBlock();
            Location loc = block.getLocation();
            SignData.deserializeSignData data = SignData.get(loc);

            if (block.getState() instanceof org.bukkit.block.Sign sign && SignUtil.isSignTP(sign) && data != null) {
                event.setCancelled(true);

                if (switch (sign.getBlockData()) {
                    case HangingSign ignore -> !sign.getBlock().getRelative(BlockFace.UP).isSolid();
                    case WallSign s -> !sign.getBlock().getRelative(s.getFacing().getOppositeFace()).isSolid();
                    case Sign ignore -> !sign.getBlock().getRelative(BlockFace.DOWN).isSolid();
                    default -> false;
                } ) {
                    loc.getBlock().setType(Material.AIR, false);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (!(loc.getBlock().getState().equals(sign))) {
                            sign.update(true, false);
                            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                                data.getPlayer().forEach(connData -> {
                                    SignUtil.sendSignText(connData.player(), loc, connData.side(), Side.FRONT.equals(connData.side()) ? data.getFront() : data.getBack());
                                });
                            }, 1L);
                        }
                   }, 0L);
                }
            }
        }

        @EventHandler
        private void BlockDropItemEvent(BlockDropItemEvent event) {
            event.getItems().removeIf(item -> item != null && item.getItemStack().getType().isBlock() && item.getItemStack().getType().createBlockData().createBlockState() instanceof org.bukkit.block.Sign && SignData.isSignTP(item.getLocation()));
        }

    }

}
