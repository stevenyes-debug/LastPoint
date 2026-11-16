package com.lastpoint;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LastPointMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("lastpoint");
    private static LastPointStorage storage;
    private static final Map<UUID, Long> TELEPORT_COOLDOWNS = new HashMap<>();

    @Override
    public void onInitialize() {
        storage = LastPointStorage.load();

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.player != null) {
                storage.record(handler.player.getUUID(), handler.player);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> storage.shutdown());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("lastpoint")
                    .requires(LastPointMod::isOp)
                    .executes(context -> info(context.getSource()))
                    .then(Commands.literal("setdefault").executes(context -> setDefault(context.getSource())))
                    .then(Commands.literal("info").executes(context -> info(context.getSource()))));
            dispatcher.register(Commands.literal("setlastdefault")
                    .requires(LastPointMod::isOp)
                    .executes(context -> setDefault(context.getSource())));
        });

        UseBlockCallback.EVENT.register(LastPointMod::onUseBlock);
        PlayerBlockBreakEvents.BEFORE.register(LastPointMod::onBlockBreak);
        LOGGER.info("[LastPoint] Ready. Put [last] on the first line of a sign.");
    }

    private static int setDefault(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }
        storage.setDefault(player);
        storage.saveNow();
        source.sendSuccess(() -> Component.literal("[LastPoint] 默认位置已设置为 " + formatLocation(player)), false);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        source.sendSuccess(() -> Component.literal("[LastPoint] 已保存玩家位置数量: " + storage.playerCount()), false);
        LastPointStorage.StoredLocation defaultLocation = storage.getDefault();
        if (defaultLocation == null) {
            source.sendSuccess(() -> Component.literal("[LastPoint] 默认位置: 未设置"), false);
        } else {
            source.sendSuccess(() -> Component.literal("[LastPoint] 默认位置: " + formatStored(defaultLocation)), false);
        }
        if (player != null) {
            LastPointStorage.StoredLocation own = storage.get(player.getUUID());
            if (own == null) {
                source.sendSuccess(() -> Component.literal("[LastPoint] 你还没有上次位置记录，点击 [last] 会使用默认位置"), false);
            } else {
                source.sendSuccess(() -> Component.literal("[LastPoint] 你的上次位置: " + formatStored(own)), false);
            }
        }
        return 1;
    }

    private static boolean isOp(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        return isOp(player);
    }

    private static boolean isOp(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server != null && server.getPlayerList().isOp(player.nameAndId());
    }

    private static String formatLocation(ServerPlayer player) {
        return player.level().dimension().identifier() + " " + String.format("%.2f %.2f %.2f", player.getX(), player.getY(), player.getZ());
    }

    private static String formatStored(LastPointStorage.StoredLocation location) {
        return location.world + " " + String.format("%.2f %.2f %.2f", location.x, location.y, location.z);
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            BlockPos pos = hitResult.getBlockPos();
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof SignBlockEntity sign) {
                String firstLine = firstLine(sign, serverPlayer);
                if (isLastSign(firstLine)) {
                    if (serverPlayer.isShiftKeyDown() && isOp(serverPlayer)) {
                        return InteractionResult.PASS;
                    }
                    teleportToSavedLocation(serverPlayer);
                    return InteractionResult.SUCCESS;
                }
            }
        }
        return InteractionResult.PASS;
    }

    private static boolean onBlockBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        boolean protectedSign = false;
        if (state.getBlock() instanceof SignBlock && blockEntity instanceof SignBlockEntity sign) {
            protectedSign = signHasLastLine(sign);
        }
        if (protectedSign == false && isSupportOfLastSign(level, pos)) {
            protectedSign = true;
        }
        if (protectedSign == false) {
            return true;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (serverPlayer.isShiftKeyDown() && isOp(serverPlayer)) {
                return true;
            }
            send(serverPlayer, "§c[LastPoint] 这个 [last] 告示牌已被保护，无法破坏");
        }
        return false;
    }

    private static String firstLine(SignBlockEntity sign, ServerPlayer player) {
        SignText text = sign.isFacingFrontText(player) ? sign.getFrontText() : sign.getBackText();
        return text.getMessage(0, false).getString().trim();
    }

    private static boolean signHasLastLine(SignBlockEntity sign) {
        String front = sign.getFrontText().getMessage(0, false).getString().trim();
        String back = sign.getBackText().getMessage(0, false).getString().trim();
        return isLastSign(front) || isLastSign(back);
    }

    private static boolean isLastSign(String line) {
        String configured = storage.signText().trim();
        return line.equalsIgnoreCase(configured)
                || line.equalsIgnoreCase("[last]")
                || line.equalsIgnoreCase("last");
    }

    private static boolean isSupportOfLastSign(Level level, BlockPos brokenPos) {
        for (Direction direction : Direction.values()) {
            BlockPos signPos = brokenPos.relative(direction);
            if (isProtectedLastSign(level, signPos, brokenPos)) {
                return true;
            }
        }
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos signPos = brokenPos.below(dy);
            if (isProtectedLastSign(level, signPos, brokenPos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProtectedLastSign(Level level, BlockPos signPos, BlockPos brokenPos) {
        BlockState state = level.getBlockState(signPos);
        if (state.getBlock() instanceof SignBlock) {
            BlockEntity blockEntity = level.getBlockEntity(signPos);
            if (blockEntity instanceof SignBlockEntity sign && signHasLastLine(sign)) {
                return isSupportForSign(signPos, state, brokenPos);
            }
        }
        return false;
    }

    private static boolean isSupportForSign(BlockPos signPos, BlockState signState, BlockPos brokenPos) {
        if (signState.getBlock() instanceof StandingSignBlock) {
            return brokenPos.equals(signPos.below());
        }
        if (signState.getBlock() instanceof WallSignBlock) {
            Direction facing = signState.getValue(WallSignBlock.FACING);
            return brokenPos.equals(signPos.relative(facing.getOpposite()));
        }
        if (signState.getBlock() instanceof CeilingHangingSignBlock) {
            int dy = brokenPos.getY() - signPos.getY();
            return dy > 0 && dy <= 8 && brokenPos.getX() == signPos.getX() && brokenPos.getZ() == signPos.getZ();
        }
        if (signState.getBlock() instanceof WallHangingSignBlock) {
            Direction facing = signState.getValue(WallHangingSignBlock.FACING);
            if (brokenPos.equals(signPos.relative(facing.getOpposite()))) {
                return true;
            }
            int dy = brokenPos.getY() - signPos.getY();
            return dy > 0 && dy <= 8 && brokenPos.getX() == signPos.getX() && brokenPos.getZ() == signPos.getZ();
        }
        return false;
    }

    private static void teleportToSavedLocation(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long lastTeleport = TELEPORT_COOLDOWNS.get(player.getUUID());
        if (lastTeleport != null && now - lastTeleport < 1000L) {
            return;
        }
        TELEPORT_COOLDOWNS.put(player.getUUID(), now);

        LastPointStorage.StoredLocation location = storage.get(player.getUUID());
        boolean usingDefault = false;
        if (location == null) {
            location = storage.getDefault();
            usingDefault = true;
        }
        if (location == null) {
            send(player, "§c[LastPoint] 没有上次位置记录，默认位置也还没有设置");
            return;
        }

        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        Identifier worldId = Identifier.tryParse(location.world);
        if (worldId == null) {
            send(player, "§c[LastPoint] 保存的世界名称无效");
            return;
        }
        ResourceKey<Level> worldKey = ResourceKey.create(Registries.DIMENSION, worldId);
        ServerLevel targetLevel = server.getLevel(worldKey);
        if (targetLevel == null) {
            send(player, "§c[LastPoint] 目标世界不存在: " + location.world);
            return;
        }

        player.teleportTo(targetLevel, location.x, location.y, location.z, Set.of(), location.yaw, location.pitch, true);
        if (usingDefault) {
            send(player, "§e[LastPoint] 没有上次位置，已传送到默认位置");
        } else {
            send(player, "§a[LastPoint] 已返回上次位置");
        }
    }

    private static void send(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), false);
    }
}
