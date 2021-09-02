package wraith.waystones.util;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import wraith.waystones.Waystones;
import wraith.waystones.block.WaystoneBlock;
import wraith.waystones.block.WaystoneBlockEntity;
import wraith.waystones.interfaces.PlayerEntityMixinAccess;
import wraith.waystones.interfaces.WaystoneValue;
import wraith.waystones.mixin.MinecraftServerAccessor;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WaystoneStorage {
    private final PersistentState state;

    private final ConcurrentHashMap<String, WaystoneValue> WAYSTONES = new ConcurrentHashMap<>();
    private final MinecraftServer server;

    private static final String ID = "fw_" + Waystones.MOD_ID;
    private final CompatibilityLayer compat;

    public WaystoneStorage(MinecraftServer server) {
        CompatibilityLayer compatLoading = new CompatibilityLayer(this, server);
        this.server = server;
        File worldDirectory = ((MinecraftServerAccessor) server).getSession().getWorldDirectory(server.getOverworld().getRegistryKey());
        File file = new File(worldDirectory, "data/waystones:waystones.dat");
        if (file.exists()) {
            file.renameTo(new File(worldDirectory, "data/" + ID));
        }

        var pState = new PersistentState(){
            @Override
            public NbtCompound writeNbt(NbtCompound tag) {
                return toTag(tag);
            }
        };
        state = this.server.getWorld(ServerWorld.OVERWORLD).getPersistentStateManager().getOrCreate(
                nbtCompound -> {
                    fromTag(nbtCompound);
                    return pState;
                },
                () -> pState, ID);

        if (!compatLoading.loadCompatibility()) {
            compatLoading = null;
        }
        compat = compatLoading;

        loadOrSaveWaystones(false);
    }

    public void fromTag(NbtCompound tag) {
        if (server == null || tag == null || !tag.contains("waystones")) {
            return;
        }
        WAYSTONES.clear();

        Set<String> globals = new HashSet<>();
        for(NbtElement element : tag.getList("global_waystones", NbtElement.STRING_TYPE)) {
            globals.add(element.asString());
        }

        NbtList waystones = tag.getList("waystones", 10);

        for (int i = 0; i < waystones.size(); ++i) {
            NbtCompound waystoneTag = waystones.getCompound(i);
            if (!waystoneTag.contains("hash") || !waystoneTag.contains("dimension") || !waystoneTag.contains("position")) {
                continue;
            }
            String name = waystoneTag.getString("name");
            String hash = waystoneTag.getString("hash");
            String dimension = waystoneTag.getString("dimension");
            int[] coordinates = waystoneTag.getIntArray("position");
            BlockPos pos = new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
            WAYSTONES.put(hash, new Lazy(name, pos, hash, dimension, globals.contains(hash)));
        }
    }

    final class Lazy implements WaystoneValue {
        /**
         * unresolved name
         */
        final String name;
        final BlockPos pos;
        final String hash, dimension;
        final boolean isGlobal;
        WaystoneBlockEntity entity;
        World world;

        Lazy(String name, BlockPos pos, String hash, String dimension, boolean global) {
            this.name = name;
            this.pos = pos;
            this.hash = hash;
            this.dimension = dimension;
            this.isGlobal = global;
        }

        @Override
        public WaystoneBlockEntity getEntity() {
            if(this.entity == null) {
                for(ServerWorld world : server.getWorlds()) {
                    if(WaystoneBlock.getDimensionName(world).equals(dimension)) {
                        WaystoneBlockEntity entity = WaystoneBlock.getEntity(world, pos);
                        if(entity != null) {
                            WAYSTONES.put(hash, entity); // should allow this instance to be GCed
                            this.entity = entity;
                            this.world = world;
                        }
                        break;
                    }
                }
            }
            return this.entity;
        }

        @Override
        public String getWaystoneName() {
            return name;
        }

        @Override
        public BlockPos way_getPos() {
            return pos;
        }

        @Override
        public String getWorldName() {
            return this.dimension;
        }

        @Override
        public boolean isGlobal() {
            return this.isGlobal;
        }
    }

    public NbtCompound toTag(NbtCompound tag) {
        if (tag == null) {
            tag = new NbtCompound();
        }
        NbtList waystones = new NbtList();
        for (Map.Entry<String, WaystoneValue> waystone : WAYSTONES.entrySet()) {
            String hash = waystone.getKey();
            WaystoneValue entity = waystone.getValue();

            NbtCompound waystoneTag = new NbtCompound();
            waystoneTag.putString("hash", hash);
            waystoneTag.putString("name", entity.getWaystoneName());
            BlockPos pos = entity.way_getPos();
            waystoneTag.putIntArray("position", Arrays.asList(pos.getX(), pos.getY(), pos.getZ()));
            waystoneTag.putString("dimension", entity.getWorldName());

            waystones.add(waystoneTag);
        }
        tag.put("waystones", waystones);
        NbtList globals = new NbtList();
        ArrayList<String> globalWaystones = getGlobals();
        for (String globalWaystone : globalWaystones) {
            globals.add(NbtString.of(globalWaystone));
        }
        tag.put("global_waystones", globals);
        return tag;
    }

    public boolean hasWaystone(WaystoneBlockEntity waystone) {
        return WAYSTONES.containsValue(waystone);
    }

    public void addWaystone(WaystoneBlockEntity waystone) {
        WAYSTONES.put(waystone.getHash(), waystone);
        loadOrSaveWaystones(true);
    }

    public void addWaystones(HashSet<WaystoneBlockEntity> waystones) {
        for (WaystoneBlockEntity waystone : waystones) {
            WAYSTONES.put(waystone.getHash(), waystone);
        }
        loadOrSaveWaystones(true);
    }

    public void loadOrSaveWaystones(boolean save) {
        if (server == null) {
            return;
        }
        ServerWorld world = server.getWorld(ServerWorld.OVERWORLD);

        if (save) {
            state.markDirty();
            sendToAllPlayers();
        }
        else {
            try {
                NbtCompound compoundTag = world.getPersistentStateManager().readNbt(ID, SharedConstants.getGameVersion().getWorldVersion());
                state.writeNbt(compoundTag.getCompound("data"));
            } catch (IOException ignored) {
            }
        }
        world.getPersistentStateManager().save();
    }

    public void sendToAllPlayers() {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            sendToPlayer(player);
        }
    }

    public void sendToPlayer(ServerPlayerEntity player) {
        PacketByteBuf data = new PacketByteBuf(Unpooled.buffer());
        data.writeNbt(toTag(new NbtCompound()));
        ServerPlayNetworking.send(player, Utils.ID("waystone_packet"), data);
    }

    public void removeWaystone(String hash) {
        WAYSTONES.remove(hash);
        forgetForAllPlayers(hash);
        loadOrSaveWaystones(true);
    }

    private void forgetForAllPlayers(String hash) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ((PlayerEntityMixinAccess)player).forgetWaystone(hash);
        }
    }

    public void removeWaystone(WaystoneBlockEntity waystone) {
        String hash = waystone.getHash();
        WAYSTONES.remove(hash);
        forgetForAllPlayers(hash);
        loadOrSaveWaystones(true);
    }

    public void renameWaystone(WaystoneBlockEntity waystone, String name) {
        waystone.setName(name);
        loadOrSaveWaystones(true);
    }

    public void renameWaystone(String hash, String name) {
        if (WAYSTONES.containsKey(hash)) {
            WAYSTONES.get(hash).getEntity().setName(name);
            loadOrSaveWaystones(true);
        }
    }

    public WaystoneBlockEntity getWaystone(String hash) {
        WaystoneValue value = WAYSTONES.getOrDefault(hash, null);
        return value != null ? value.getEntity() : null;
    }

    public boolean containsHash(String hash) {
        return WAYSTONES.containsKey(hash);
    }

    public ArrayList<String> getGlobals() {
        ArrayList<String> globals = new ArrayList<>();
        for (Map.Entry<String, WaystoneValue> waystone : WAYSTONES.entrySet()) {
            if (waystone.getValue().isGlobal()) {
                globals.add(waystone.getKey());
            }
        }
        return globals;
    }

    public void toggleGlobal(String hash) {
        WaystoneBlockEntity waystone = getWaystone(hash);
        if (waystone == null) {
            return;
        }
        waystone.toggleGlobal();
        sendToAllPlayers();
    }

    public void setOwner(String hash, PlayerEntity owner) {
        if (WAYSTONES.containsKey(hash)) {
            WAYSTONES.get(hash).getEntity().setOwner(owner);
        }
    }

    public HashSet<String> getAllHashes() {
        return new HashSet<>(WAYSTONES.keySet());
    }

    public int getCount() {
        return WAYSTONES.size();
    }

    public void sendCompatData(ServerPlayerEntity player) {
        if (this.compat != null) {
            this.compat.updatePlayerCompatibility(player);
        }
    }
}