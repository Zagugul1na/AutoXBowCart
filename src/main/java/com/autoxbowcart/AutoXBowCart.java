package com.autoxbowcart;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import java.util.HashSet;
import java.util.Set;

public class AutoXBowCart implements ClientModInitializer {

    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final Set<String> FRIENDS = new HashSet<>();

    private static double RANGE = 5.0;
    private static long BASE_DELAY_MS = 150;
    private static float SPEED_MULTIPLIER = 1.0f;
    private static int MAX_FAILS = 5;
    private static boolean DEBUG_MODE = false;
    private static boolean ONE_CLICK_MODE = false;
    private static boolean SAFE_MODE = false;

    private static KeyBinding toggleKey;
    private static KeyBinding oneClickKey;

    private static boolean enabled = false;
    private static long lastAttack = 0;
    private static int failCount = 0;
    private static String waitingForKey = null;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autoxbowcart.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));

        oneClickKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autoxbowcart.oneclick",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Смена биндов
            if (waitingForKey != null) {
                if (GLFW.glfwGetKey(client.getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
                    waitingForKey = null;
                    client.player.sendMessage(Text.literal("§cRebinding cancelled"), false);
                    MC.execute(() -> MC.setScreen(new AXCSettingsScreen()));
                    return;
                }

                for (int key = 0; key <= GLFW.GLFW_KEY_LAST; key++) {
                    if (GLFW.glfwGetKey(client.getWindow().getHandle(), key) == GLFW.GLFW_PRESS) {
                        InputUtil.Key newKey = InputUtil.Type.KEYSYM.createFromCode(key);

                        if (waitingForKey.equals("toggle")) {
                            toggleKey.setBoundKey(newKey);
                        } else if (waitingForKey.equals("oneclick")) {
                            oneClickKey.setBoundKey(newKey);
                        }

                        waitingForKey = null;
                        client.player.sendMessage(Text.literal("§aKey bound successfully"), false);
                        MC.execute(() -> MC.setScreen(new AXCSettingsScreen()));
                        break;
                    }
                }
            }

            // One-Click
            if (oneClickKey.wasPressed()) {
                if (!ONE_CLICK_MODE) {
                    sendMsg(client.player, "§cOne-Click Mode is OFF");
                } else {
                    BlockPos targetBlock = getBlockFromCrosshair(client.player);
                    if (targetBlock == null) {
                        sendMsg(client.player, "§cNo block in crosshair");
                    } else if (!isValidTarget(client.player, targetBlock)) {
                        sendMsg(client.player, "§cToo far or invalid block (max 5 blocks)");
                    } else {
                        ClientPlayerEntity p = client.player;
                        ClientWorld w = client.world;
                        new Thread(() -> executeSequence(p, w, targetBlock)).start();
                    }
                }
            }

            // Auto toggle
            if (toggleKey.wasPressed()) {
                enabled = !enabled;
                failCount = 0;
                sendMsg(client.player, enabled ? "§aENABLED" : "§cDISABLED");
            }

            // Auto mode
            if (!enabled) return;

            long cooldown = (long)(BASE_DELAY_MS / SPEED_MULTIPLIER);
            if (System.currentTimeMillis() - lastAttack < cooldown) return;

            if (failCount >= MAX_FAILS) {
                enabled = false;
                sendMsg(client.player, "§4Auto-disabled: " + MAX_FAILS + " failures");
                return;
            }

            PlayerEntity target = findNearestPlayer(client.player, client.world);
            if (target != null) {
                BlockPos railPos = calcRailPos(client.player, client.world, target);
                if (railPos != null) {
                    ClientPlayerEntity p = client.player;
                    ClientWorld w = client.world;
                    new Thread(() -> executeSequence(p, w, railPos)).start();
                }
            }
        });

        registerCommands();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("axc")
                    .then(ClientCommandManager.literal("settings")
                            .executes(ctx -> {
                                MC.execute(() -> MC.setScreen(new AXCSettingsScreen()));
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("toggle")
                            .executes(ctx -> {
                                enabled = !enabled;
                                failCount = 0;
                                sendMsg(MC.player, enabled ? "§aENABLED" : "§cDISABLED");
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("reset")
                            .executes(ctx -> {
                                resetAllSettings();
                                sendMsg(MC.player, "§eSettings reset");
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("friends")
                            .then(ClientCommandManager.literal("add")
                                    .then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                            .executes(ctx -> {
                                                String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
                                                FRIENDS.add(name.toLowerCase());
                                                sendMsg(MC.player, "§aFriend added: " + name);
                                                return 1;
                                            })
                                    )
                            )
                            .then(ClientCommandManager.literal("remove")
                                    .then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                                            .executes(ctx -> {
                                                String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
                                                FRIENDS.remove(name.toLowerCase());
                                                sendMsg(MC.player, "§cFriend removed: " + name);
                                                return 1;
                                            })
                                    )
                            )
                            .then(ClientCommandManager.literal("list")
                                    .executes(ctx -> {
                                        if (FRIENDS.isEmpty()) {
                                            sendMsg(MC.player, "§7No friends");
                                        } else {
                                            sendMsg(MC.player, "§aFriends: " + String.join(", ", FRIENDS));
                                        }
                                        return 1;
                                    })
                            )
                    )
            );
        });
    }

    /**
     * Проверка валидности цели для One-Click:
     * расстояние до 3 блоков, под блоком твёрдая опора, сам блок воздушный.
     */
    private boolean isValidTarget(ClientPlayerEntity p, BlockPos targetBlock) {
        if (MC.world == null) {
            sendMsg(p, "§cWorld is null");
            return false;
        }

        // Дистанция до 3 блоков
        Vec3d playerPos = Vec3d.ofCenter(p.getBlockPos());
        Vec3d targetPos = Vec3d.ofCenter(targetBlock);
        double dist = playerPos.distanceTo(targetPos);

        if (dist > 5.0) {
            sendMsg(p, "§cToo far: " + String.format("%.1f", dist) + " blocks (max 5.0)");
            return false;
        }

        // Под блоком твёрдая опора
        BlockPos below = targetBlock.down();
        if (!MC.world.getBlockState(below).isSolidBlock(MC.world, below)) {
            sendMsg(p, "§cNo solid block below target");
            return false;
        }

        // Сам блок воздушный
        if (!MC.world.getBlockState(targetBlock).isAir()) {
            sendMsg(p, "§cTarget block is not air");
            return false;
        }

        return true;
    }

    /**
     * Основная последовательность X-Bow Cart.
     * Выполняется в отдельном потоке, без задержек.
     */
    private void executeSequence(ClientPlayerEntity p, ClientWorld w, BlockPos railPos) {
        if (p == null || w == null || railPos == null) return;

        try {
            int crossbowSlot = findChargedCrossbow(p);
            if (crossbowSlot == -1) { sendMsg(p, "No charged crossbow"); return; }
            if (!hasRail(p)) { sendMsg(p, "No rails"); return; }
            if (!hasItem(p, Items.TNT_MINECART)) { sendMsg(p, "No TNT minecart"); return; }
            if (!hasItem(p, Items.FLINT_AND_STEEL)) { sendMsg(p, "No flint and steel"); return; }
            if (SAFE_MODE && !hasItem(p, Items.OAK_SLAB)) { sendMsg(p, "No oak slab"); return; }

            BlockPos firePos = calcFirePos(p, railPos);
            if (firePos == null) { sendMsg(p, "No space for fire"); return; }

            // 1. Рельсы
            MC.execute(() -> {
                swapToItemHotbar(p, Items.RAIL);
                if (!hasItem(p, Items.RAIL)) swapToItemHotbar(p, Items.POWERED_RAIL);
                rotateAndSendPacket(p, railPos);
                clickOnBlockBelow(p, railPos);
            });

            // 2. Вагонетка
            MC.execute(() -> {
                swapToItemHotbar(p, Items.TNT_MINECART);
                rotateAndSendPacket(p, railPos);
                clickOnBlock(p, railPos);
            });

            // 3. Огонь
            MC.execute(() -> {
                swapToItemHotbar(p, Items.FLINT_AND_STEEL);
                rotateAndSendPacket(p, firePos);
                clickOnBlockBelow(p, firePos);
            });

            // 4. Safe Mode
            if (SAFE_MODE) {
                BlockPos safePos = calcSafePos(p, firePos);
                if (safePos != null) {
                    MC.execute(() -> {
                        swapToItemHotbar(p, Items.OAK_SLAB);
                        rotateAndSendPacket(p, safePos);
                        clickOnBlockBelow(p, safePos);
                    });
                }
            }

            // 5. Выстрел
            MC.execute(() -> {
                p.getInventory().setSelectedSlot(crossbowSlot);
                if (MC.getNetworkHandler() != null) {
                    MC.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(crossbowSlot));
                }
                rotateAndSendPacket(p, railPos);
                MC.options.useKey.setPressed(true);
            });

            sleep(100);

            MC.execute(() -> {
                if (MC.getNetworkHandler() != null) {
                    MC.getNetworkHandler().sendPacket(
                            new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, p.getYaw(), p.getPitch())
                    );
                }
                MC.options.useKey.setPressed(false);
                p.swingHand(Hand.MAIN_HAND);
            });

            lastAttack = System.currentTimeMillis();
            failCount = 0;
            if (DEBUG_MODE) sendMsg(p, "BOOM!");
        } catch (Exception e) {
            failCount++;
            lastAttack = System.currentTimeMillis();
            if (DEBUG_MODE) sendMsg(p, "Error: " + e.getMessage());
        }
    }

    private void clickOnBlock(ClientPlayerEntity p, BlockPos pos) {
        ClientPlayerInteractionManager im = MC.interactionManager;
        if (im == null) return;
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(pos).add(0, 0.5, 0), Direction.UP, pos, false
        );
        im.interactBlock(p, Hand.MAIN_HAND, hit);
        p.swingHand(Hand.MAIN_HAND);
    }

    private void clickOnBlockBelow(ClientPlayerEntity p, BlockPos pos) {
        ClientPlayerInteractionManager im = MC.interactionManager;
        if (im == null) return;
        BlockPos below = pos.down();
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false
        );
        im.interactBlock(p, Hand.MAIN_HAND, hit);
        p.swingHand(Hand.MAIN_HAND);
    }

    private boolean swapToItemHotbar(ClientPlayerEntity p, Item item) {
        if (p == null || item == null) return false;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).getItem() == item) {
                p.getInventory().setSelectedSlot(i);
                if (MC.getNetworkHandler() != null) {
                    MC.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(i));
                }
                return true;
            }
        }
        return false;
    }

    private void rotateAndSendPacket(ClientPlayerEntity p, BlockPos pos) {
        if (p == null || pos == null) return;

        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d eye = p.getCameraPosVec(1.0f);
        Vec3d dir = target.subtract(eye).normalize();

        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double pitch = MathHelper.clamp(Math.toDegrees(-Math.asin(dir.y)), -90.0, 90.0);

        p.setYaw((float) yaw);
        p.setPitch((float) pitch);

        if (MC.getNetworkHandler() != null) {
            MC.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                    p.getX(), p.getY(), p.getZ(), (float) yaw, (float) pitch,
                    p.isOnGround(), p.horizontalCollision
            ));
        }
    }

    private BlockPos calcRailPos(ClientPlayerEntity p, ClientWorld w, PlayerEntity target) {
        Vec3d pPos = Vec3d.ofCenter(p.getBlockPos());
        Vec3d tPos = Vec3d.ofCenter(target.getBlockPos());
        Vec3d dir = tPos.subtract(pPos).normalize();

        BlockPos targetBlock = target.getBlockPos();
        BlockPos railBlock;

        if (Math.abs(dir.x) > Math.abs(dir.z)) {
            railBlock = targetBlock.offset(dir.x > 0 ? Direction.WEST : Direction.EAST);
        } else {
            railBlock = targetBlock.offset(dir.z > 0 ? Direction.NORTH : Direction.SOUTH);
        }

        if (!w.getBlockState(railBlock.down()).isSolidBlock(w, railBlock.down())) return null;
        if (!w.getBlockState(railBlock).isAir()) return null;

        return railBlock;
    }

    private BlockPos calcFirePos(ClientPlayerEntity p, BlockPos railPos) {
        Vec3d pPos = Vec3d.ofCenter(p.getBlockPos());
        Vec3d rPos = Vec3d.ofCenter(railPos);
        Vec3d dir = rPos.subtract(pPos).normalize();

        BlockPos fire;
        if (Math.abs(dir.x) > Math.abs(dir.z)) {
            fire = railPos.offset(dir.x > 0 ? Direction.WEST : Direction.EAST);
        } else {
            fire = railPos.offset(dir.z > 0 ? Direction.NORTH : Direction.SOUTH);
        }

        if (MC.world == null || !MC.world.getBlockState(fire).isAir()) return null;
        return fire;
    }

    private BlockPos calcSafePos(ClientPlayerEntity p, BlockPos firePos) {
        Vec3d pPos = Vec3d.ofCenter(p.getBlockPos());
        Vec3d fPos = Vec3d.ofCenter(firePos);
        Vec3d dir = pPos.subtract(fPos).normalize();

        BlockPos safe;
        if (Math.abs(dir.x) > Math.abs(dir.z)) {
            safe = firePos.offset(dir.x > 0 ? Direction.EAST : Direction.WEST);
        } else {
            safe = firePos.offset(dir.z > 0 ? Direction.SOUTH : Direction.NORTH);
        }

        safe = new BlockPos(safe.getX(), p.getBlockPos().getY(), safe.getZ());

        if (MC.world == null || !MC.world.getBlockState(safe).isAir()) return null;
        return safe;
    }

    private BlockPos getBlockFromCrosshair(ClientPlayerEntity p) {
        if (MC.crosshairTarget instanceof BlockHitResult hit) {
            return hit.getBlockPos().up();
        }
        return null;
    }

    private PlayerEntity findNearestPlayer(ClientPlayerEntity p, ClientWorld w) {
        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (PlayerEntity player : w.getPlayers()) {
            if (player != p && !player.isSpectator()) {
                if (FRIENDS.contains(player.getName().getString().toLowerCase())) continue;

                double d = p.squaredDistanceTo(player);
                if (d < RANGE * RANGE && d < bestDist) {
                    bestDist = d;
                    best = player;
                }
            }
        }
        return best;
    }

    private int findChargedCrossbow(ClientPlayerEntity p) {
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getStack(i).getItem() == Items.CROSSBOW &&
                    net.minecraft.item.CrossbowItem.isCharged(p.getInventory().getStack(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasItem(ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getStack(i).getItem() == item) return true;
        }
        return false;
    }

    private boolean hasRail(ClientPlayerEntity p) {
        return hasItem(p, Items.RAIL) || hasItem(p, Items.POWERED_RAIL);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void sendMsg(ClientPlayerEntity p, String msg) {
        if (p != null) {
            MutableText text = Text.literal("[AXC] ");
            text.styled(s -> s.withColor(0xFFAA00));
            text.append(Text.literal(msg));
            p.sendMessage(text, false);
        }
    }

    private static void resetAllSettings() {
        RANGE = 5.0;
        BASE_DELAY_MS = 150;
        SPEED_MULTIPLIER = 1.0f;
        MAX_FAILS = 5;
        DEBUG_MODE = false;
        ONE_CLICK_MODE = false;
        SAFE_MODE = false;
    }

    public static class AXCSettingsScreen extends Screen {
        public AXCSettingsScreen() {
            super(Text.literal("AutoXBowCart Settings"));
        }

        @Override
        protected void init() {
            int cx = this.width / 2;
            int y = 30;

            this.addDrawableChild(new SliderWidget(cx - 100, y, 200, 20,
                    Text.literal("Range: " + String.format("%.1f", RANGE)),
                    RANGE / 10.0) {
                @Override
                protected void updateMessage() {
                    RANGE = Math.round(this.value * 100) / 10.0;
                    this.setMessage(Text.literal("Range: " + String.format("%.1f", RANGE)));
                }
                @Override
                protected void applyValue() {}
            });
            y += 25;

            this.addDrawableChild(new SliderWidget(cx - 100, y, 200, 20,
                    Text.literal("Speed: " + String.format("%.1fx", SPEED_MULTIPLIER)),
                    (SPEED_MULTIPLIER - 1.0) / 19.0) {
                @Override
                protected void updateMessage() {
                    SPEED_MULTIPLIER = Math.round((1.0 + this.value * 19.0) * 10) / 10f;
                    this.setMessage(Text.literal("Speed: " + String.format("%.1fx", SPEED_MULTIPLIER)));
                }
                @Override
                protected void applyValue() {}
            });
            y += 25;

            this.addDrawableChild(new SliderWidget(cx - 100, y, 200, 20,
                    Text.literal("Max Fails: " + MAX_FAILS),
                    MAX_FAILS / 20.0) {
                @Override
                protected void updateMessage() {
                    MAX_FAILS = Math.max(1, (int)(this.value * 20));
                    this.setMessage(Text.literal("Max Fails: " + MAX_FAILS));
                }
                @Override
                protected void applyValue() {}
            });
            y += 35;

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("One-Click Mode: " + (ONE_CLICK_MODE ? "§aON" : "§cOFF")),
                    btn -> {
                        ONE_CLICK_MODE = !ONE_CLICK_MODE;
                        btn.setMessage(Text.literal("One-Click Mode: " + (ONE_CLICK_MODE ? "§aON" : "§cOFF")));
                    }
            ).dimensions(cx - 100, y, 200, 20).build());
            y += 25;

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Safe Mode: " + (SAFE_MODE ? "§aON" : "§cOFF")),
                    btn -> {
                        SAFE_MODE = !SAFE_MODE;
                        btn.setMessage(Text.literal("Safe Mode: " + (SAFE_MODE ? "§aON" : "§cOFF")));
                    }
            ).dimensions(cx - 100, y, 200, 20).build());
            y += 35;

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Toggle Key: " + toggleKey.getBoundKeyLocalizedText().getString()),
                    btn -> {
                        waitingForKey = "toggle";
                        if (MC.player != null) MC.player.sendMessage(Text.literal("§ePress new key..."), false);
                    }
            ).dimensions(cx - 100, y, 200, 20).build());
            y += 25;

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("One-Click Key: " + oneClickKey.getBoundKeyLocalizedText().getString()),
                    btn -> {
                        waitingForKey = "oneclick";
                        if (MC.player != null) MC.player.sendMessage(Text.literal("§ePress new key..."), false);
                    }
            ).dimensions(cx - 100, y, 200, 20).build());
            y += 35;

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Debug Mode: " + (DEBUG_MODE ? "§aON" : "§cOFF")),
                    btn -> {
                        DEBUG_MODE = !DEBUG_MODE;
                        btn.setMessage(Text.literal("Debug Mode: " + (DEBUG_MODE ? "§aON" : "§cOFF")));
                    }
            ).dimensions(cx - 100, y, 200, 20).build());
            y += 35;

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("§cReset Settings"),
                    btn -> {
                        resetAllSettings();
                        MC.setScreen(new AXCSettingsScreen());
                    }
            ).dimensions(cx - 100, y, 95, 20).build());

            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("§aDone"),
                    btn -> this.close()
            ).dimensions(cx + 5, y, 95, 20).build());
        }
    }
}