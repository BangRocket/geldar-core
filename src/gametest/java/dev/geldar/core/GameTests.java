package dev.geldar.core;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import dev.geldar.core.blockowner.OwnerImport;
import dev.geldar.core.blockowner.OwnerSource.OwnedBlock;
import dev.geldar.core.blockowner.PlacementRecorder;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Game tests: the mod's handlers seen working in a running world, driven by players that have no client behind them.
 * Run with {@code gradlew runGameTestServer}. This class is a second entry point of the mod and is loaded by that run
 * only; it is not in the jar.
 *
 * <p>NATS is not there in a test, so the events the mod would publish are kept in {@link #EVENTS}, and each muffled
 * notice in {@link #MUFFLED}. Every test has an environment of its own, so the tests run one after another: two that
 * ran side by side would hear each other's players.
 */
@Mod(GeldarCore.ID)
public final class GameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, GeldarCore.ID);
    private static final Map<String, Integer> TESTS = new LinkedHashMap<>();
    static final InMemoryEventPublisher EVENTS = new InMemoryEventPublisher();
    static final List<String> MUFFLED = new CopyOnWriteArrayList<>();

    public GameTests(IEventBus modBus) {
        GeldarCore.publishers = (config, log) -> EVENTS;
        GeldarCore.hearingTap = (lifeId, direction, band) -> MUFFLED.add(lifeId);
        test("ghost_is_held_in_spectator", 100, GameTests::ghostIsHeldInSpectator);
        test("player_commands_are_blocked", 100, GameTests::playerCommandsAreBlocked);
        test("placed_blocks_have_an_owner", 100, GameTests::placedBlocksHaveAnOwner);
        test("agent_death_leaves_a_gravestone", 100, GameTests::agentDeathLeavesAGravestone);
        test("deaths_are_told_nearby_only", 100, GameTests::deathsAreToldNearbyOnly);
        test("chat_reaches_by_distance", 600, GameTests::chatReachesByDistance);
        test("backfill_records_old_gravestones", 200, GameTests::backfillRecordsOldGravestones);
        test("tilling_is_the_tillers", 100, GameTests::tillingIsTheTillers);
        test("unlogged_changes_keep_the_owner", 100, GameTests::unloggedChangesKeepTheOwner);
        test("pistons_take_ownership", 200, GameTests::pistonsTakeOwnership);
        test("explosions_take_ownership", 100, GameTests::explosionsTakeOwnership);
        test("bone_meal_growth_is_no_ones", 100, GameTests::boneMealGrowthIsNoOnes);
        test("coreprotect_history_is_imported", 100, GameTests::coreProtectHistoryIsImported);
        FUNCTIONS.register(modBus);
        modBus.addListener(RegisterGameTestsEvent.class, GameTests::register);
    }

    private static void test(String name, int maxTicks, Consumer<Scene> body) {
        TESTS.put(name, maxTicks);
        FUNCTIONS.register(name, () -> helper -> {
            Scene scene = new Scene(helper);
            scene.guarded(() -> body.accept(scene));
        });
    }

    private static void register(RegisterGameTestsEvent event) {
        TESTS.forEach((name, maxTicks) -> {
            Identifier id = Identifier.fromNamespaceAndPath(GeldarCore.ID, name);
            Holder<TestEnvironmentDefinition<?>> alone = event.registerEnvironment(id);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(alone, Identifier.withDefaultNamespace("empty"), maxTicks, 0, true)));
        });
    }

    // ---- the tests ----

    private static void ghostIsHeldInSpectator(Scene s) {
        Mock ghost = s.join("watcher_01", false, 0, 0);
        s.check(ghost.player().gameMode() == GameType.SPECTATOR, "a joiner without the live property is in spectator");
        s.check(!ghost.player().setGameMode(GameType.SURVIVAL), "a ghost's change of game mode is refused");
        s.check(ghost.player().gameMode() == GameType.SPECTATOR, "a ghost stays in spectator");

        Mock live = s.join("ag_live_01", true, 2, 0);
        s.check(live.player().gameMode() != GameType.SPECTATOR, "a joiner with the live property is not held");
        s.check(live.player().setGameMode(GameType.SURVIVAL), "a live player may change game mode");
        s.pass();
    }

    private static void playerCommandsAreBlocked(Scene s) {
        Mock typist = s.join("ag_cmd_01", true, 0, 0);
        BlockPos target = s.base.above(2);
        s.clearLater(target);
        String command = "setblock " + target.getX() + " " + target.getY() + " " + target.getZ() + " minecraft:gold_block";
        // Even with every permission, a command with a player behind it does nothing.
        s.server.getCommands().performPrefixedCommand(
                typist.player().createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS), command);
        s.check(s.level.getBlockState(target).isAir(), "a player's command is cancelled");
        s.server.getCommands().performPrefixedCommand(s.server.createCommandSourceStack(), command);
        s.check(s.level.getBlockState(target).is(Blocks.GOLD_BLOCK), "the console's command still runs");
        s.pass();
    }

    private static void placedBlocksHaveAnOwner(Scene s) {
        s.floor();
        Mock builder = s.join("ag_build_01", true, 0, 0);
        BlockPos at = s.base.offset(2, 0, 0);
        s.check(s.owner(at) == null, "a block nobody placed has no owner");

        s.place(builder, at);
        s.check(s.level.getBlockState(at).is(Blocks.STONE), "the player's block stands");
        OwnedBlock owned = s.owner(at);
        s.check(owned != null && "ag_build_01".equals(owned.owner()), "a placed block is owned by its placer: " + owned);
        s.check(owned != null && "stone".equals(owned.block()), "the record names the block: " + owned);

        s.check(builder.player().gameMode.destroyBlock(at), "the player breaks the block");
        s.check(s.owner(at) == null, "a broken block has no owner");

        // A block that goes without any player's event (an explosion, a piston) loses its record when it is read.
        s.place(builder, at);
        s.check(s.owner(at) != null, "the block placed again is owned again");
        s.level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        s.check(s.owner(at) == null, "a record whose block is gone is dropped");
        s.pass();
    }

    private static void agentDeathLeavesAGravestone(Scene s) {
        s.floor();
        int before = EVENTS.events().size();
        Mock agent = s.join("ag_grave_01", true, 0, 0);
        agent.player().setGameMode(GameType.SURVIVAL);
        agent.player().getInventory().add(new ItemStack(Items.OAK_LOG, 12));
        agent.player().hurtServer(s.level, s.level.damageSources().fall(), 4f);
        s.check(agent.player().isAlive(), "four points of fall damage do not kill");
        agent.player().hurtServer(s.level, s.level.damageSources().fall(), 1000f);
        s.check(agent.player().isDeadOrDying(), "the agent died");

        // The marker stands where the agent stood: stone bricks with a sign on top.
        s.check(s.level.getBlockState(s.base).is(Blocks.STONE_BRICKS), "stone bricks mark the death");
        s.check(s.level.getBlockState(s.base.above()).is(Blocks.OAK_SIGN), "a sign stands on the stone bricks");
        if (s.level.getBlockEntity(s.base.above()) instanceof SignBlockEntity sign) {
            s.check("Here lies".equals(line(sign, 0)), "sign line 1: " + line(sign, 0));
            s.check("ag_grave_01".equals(line(sign, 1)), "sign line 2: " + line(sign, 1));
            s.check(line(sign, 2).startsWith("Day "), "sign line 3: " + line(sign, 2));
            s.check("fell".equals(line(sign, 3)), "sign line 4: " + line(sign, 3));
        } else {
            s.check(false, "the sign holds its text");
        }
        OwnedBlock stone = s.owner(s.base);
        OwnedBlock sign = s.owner(s.base.above());
        s.check(stone != null && PlacementRecorder.SYSTEM_OWNER.equals(stone.owner()), "the stone is the system's: " + stone);
        s.check(sign != null && PlacementRecorder.SYSTEM_OWNER.equals(sign.owner()), "the sign is the system's: " + sign);

        // What went to NATS: the first blow as damage, then the death with its drops.
        JsonObject damage = s.event(before, "damage", "ag_grave_01");
        s.check(damage != null && "FALL".equals(damage.get("cause").getAsString()), "damage event: " + damage);
        s.check(damage != null && damage.get("amount").getAsDouble() == 4.0, "damage amount: " + damage);
        JsonObject death = s.event(before, "death", "ag_grave_01");
        s.check(death != null, "a death event went out");
        s.check("FALL".equals(death.get("cause").getAsString()), "death cause: " + death);
        s.check("overworld".equals(death.get("dimension").getAsString()), "death dimension: " + death);
        s.check(death.get("x").getAsInt() == s.base.getX() && death.get("y").getAsInt() == s.base.getY()
                && death.get("z").getAsInt() == s.base.getZ(), "death place: " + death);
        s.check(death.has("drops") && death.get("drops").getAsInt() == 12, "death drops: " + death);
        s.check(death.has("drop_items") && "oak_log".equals(death.getAsJsonArray("drop_items").get(0).getAsJsonObject()
                .get("item").getAsString()), "death drop items: " + death);
        s.check(death.has("respawn_x"), "death respawn point: " + death);

        // A player that is not an agent character gets no marker.
        Mock other = s.join("Steve_01", true, 3, 0);
        other.player().setGameMode(GameType.SURVIVAL);
        other.player().hurtServer(s.level, s.level.damageSources().fall(), 1000f);
        s.check(other.player().isDeadOrDying(), "the other player died");
        s.check(!s.level.getBlockState(s.base.offset(3, 0, 0)).is(Blocks.STONE_BRICKS), "no marker for a non-agent");
        s.check(s.event(before, "death", "Steve_01") != null, "its death is still exported");
        s.pass();
    }

    private static void deathsAreToldNearbyOnly(Scene s) {
        Mock victim = s.join("ag_fall_01", true, 0, 0);
        Mock near = s.join("ag_near_01", true, 5, 0);
        Mock far = s.join("ag_far_01", true, 60, 0);
        victim.player().setGameMode(GameType.SURVIVAL);
        near.forget();
        far.forget();
        victim.player().hurtServer(s.level, s.level.damageSources().fall(), 1000f);
        s.check(victim.player().isDeadOrDying(), "the victim died");
        s.check(near.systemLines().stream().anyMatch(line -> line.contains("ag_fall_01")),
                "a player within 32 blocks is told: " + near.systemLines());
        s.check(far.systemLines().stream().noneMatch(line -> line.contains("ag_fall_01")),
                "a player 60 blocks away is not told: " + far.systemLines());
        s.pass();
    }

    private static void chatReachesByDistance(Scene s) {
        Mock speaker = s.join("ag_talk_01", true, 0, 0);
        Mock near = s.join("ag_near_01", true, 5, 0);
        // Thirty blocks off is in earshot under open sky. A roof over the listener adds the sealed-room penalty, which
        // takes the voice past the clear limit of 32: the words go and a notice comes.
        Mock muffled = s.join("ag_muff_01", true, 30, 0);
        BlockPos roof = s.base.offset(30, 2, 0);
        s.level.setBlockAndUpdate(roof, Blocks.STONE.defaultBlockState());
        s.clearLater(roof);
        Mock open = s.join("ag_open_01", true, 0, 30);
        Mock far = s.join("ag_far_01", true, 36, 0);
        Mock ghost = s.join("watcher_01", false, 3, 0);
        // The judge reads the blocks between speaker and listener and never loads a chunk. A player with no client
        // loads none round itself either, so the chunks along the line are loaded here and waited for.
        s.whenLoaded(0, 30, 0, 30, () -> {
            for (Mock mock : s.mocks) {
                mock.forget();
            }
            MUFFLED.clear();
            Component sent = CommonHooks.getServerChatSubmittedDecorator()
                    .decorate(speaker.player(), Component.literal("hello there"));
            s.check(sent == null, "the game's own broadcast of the line is cancelled");
            s.check(speaker.chatsFrom(speaker) == 1, "the speaker keeps their line: " + speaker.chatsFrom(speaker));
            s.check(near.chatsFrom(speaker) == 1, "a listener 5 blocks away hears it: " + near.chatsFrom(speaker));
            s.check(ghost.chatsFrom(speaker) == 1, "a watcher 3 blocks away hears it: " + ghost.chatsFrom(speaker));
            s.check(open.chatsFrom(speaker) == 1, "a listener 30 blocks away in the open hears it: " + open.chatsFrom(speaker));
            s.check(muffled.chatsFrom(speaker) == 0, "a listener 30 blocks away under a roof gets no words");
            s.check(MUFFLED.equals(List.of("ag_muff_01")), "and gets a muffled notice instead: " + MUFFLED);
            s.check(far.chatsFrom(speaker) == 0, "a listener 36 blocks away gets nothing");

            for (Mock mock : s.mocks) {
                mock.forget();
            }
            Component booed = CommonHooks.getServerChatSubmittedDecorator()
                    .decorate(ghost.player(), Component.literal("boo"));
            s.check(booed == null, "a ghost's line is cancelled");
            for (Mock mock : s.mocks) {
                s.check(mock.chatsFrom(ghost) == 0, mock.name() + " does not hear a ghost");
            }
            s.pass();
        });
    }

    private static void backfillRecordsOldGravestones(Scene s) {
        s.floor();
        // A gravestone as one from before the record existed: the blocks and the sign text, with no owner.
        BlockPos stone = s.base.offset(-2, 0, 0);
        s.level.setBlockAndUpdate(stone, Blocks.STONE_BRICKS.defaultBlockState());
        s.level.setBlockAndUpdate(stone.above(), Blocks.OAK_SIGN.defaultBlockState());
        if (s.level.getBlockEntity(stone.above()) instanceof SignBlockEntity sign) {
            sign.updateText(text -> text.setMessage(0, Component.literal("Here lies"))
                    .setMessage(1, Component.literal("ag_old_01")), true);
        }
        s.check(s.owner(stone) == null && s.owner(stone.above()) == null, "the old gravestone has no owner yet");
        s.server.getCommands().performPrefixedCommand(s.server.createCommandSourceStack(), GraveBackfillCommand.NAME);
        // The owner lookup runs off the server thread and the recording comes back on it.
        s.poll(() -> s.owner(stone) != null && s.owner(stone.above()) != null, () -> {
            s.check(PlacementRecorder.SYSTEM_OWNER.equals(s.owner(stone).owner()), "the stone is the system's");
            s.check(PlacementRecorder.SYSTEM_OWNER.equals(s.owner(stone.above()).owner()), "the sign is the system's");
            s.pass();
        });
    }

    private static void tillingIsTheTillers(Scene s) {
        s.floor();
        Mock farmer = s.join("ag_till_01", true, 0, 0);
        BlockPos soil = s.base.offset(2, -1, 0);
        s.level.setBlockAndUpdate(soil, Blocks.DIRT.defaultBlockState());
        s.use(farmer, Items.WOODEN_HOE, soil, Direction.UP);
        s.check(s.level.getBlockState(soil).is(Blocks.FARMLAND), "the hoe tilled the dirt");
        OwnedBlock tilled = s.owner(soil);
        s.check(tilled != null && "ag_till_01".equals(tilled.owner()) && "farmland".equals(tilled.block()),
                "tilled farmland is the tiller's, as CoreProtect records it: " + tilled);
        // Farmland that decays is a change CoreProtect does not log: the record stands and names farmland, which is
        // what the Body's till looks for to treat the cell as no one's (Geldar's decision 0219).
        s.level.setBlockAndUpdate(soil, Blocks.DIRT.defaultBlockState());
        OwnedBlock decayed = s.owner(soil);
        s.check(decayed != null && "ag_till_01".equals(decayed.owner()) && "farmland".equals(decayed.block()),
                "decayed farmland keeps its record: " + decayed);
        s.pass();
    }

    private static void unloggedChangesKeepTheOwner(Scene s) {
        s.floor();
        Mock builder = s.join("ag_copper_01", true, 0, 0);
        BlockPos at = s.base.offset(2, 0, 0);
        s.use(builder, Items.COPPER_BLOCK, at.below(), Direction.UP);
        s.check(s.level.getBlockState(at).is(Blocks.COPPER_BLOCK), "the copper block stands");
        // Weathering is a change no event tells and CoreProtect does not log: the owner stands, naming what was placed.
        s.level.setBlockAndUpdate(at, Blocks.EXPOSED_COPPER.defaultBlockState());
        OwnedBlock weathered = s.owner(at);
        s.check(weathered != null && "ag_copper_01".equals(weathered.owner()) && "copper_block".equals(weathered.block()),
                "weathered copper keeps its record: " + weathered);
        // A cell left empty has lost its block, and the record goes with it.
        s.level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        s.check(s.owner(at) == null, "an emptied cell has no owner");
        s.level.setBlockAndUpdate(at, Blocks.COPPER_BLOCK.defaultBlockState());
        s.check(s.owner(at) == null, "a block set there afterwards by no player has no owner");
        s.pass();
    }

    private static void pistonsTakeOwnership(Scene s) {
        s.floor();
        Mock builder = s.join("ag_push_01", true, 0, 0);
        BlockPos piston = s.base.offset(-3, 0, 2);
        BlockPos first = piston.east();
        BlockPos second = piston.east(2);
        BlockPos third = piston.east(3);
        s.place(builder, first);
        s.place(builder, second);
        s.check(s.owner(first) != null && s.owner(second) != null, "both stones are the builder's");
        s.level.setBlockAndUpdate(piston, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
        s.level.setBlockAndUpdate(piston.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        s.poll(() -> s.level.getBlockState(third).is(Blocks.STONE), () -> {
            // CoreProtect logs a piston's move under #piston, so where the stones came from and went to have no owner,
            // though a stone stands again where the second one stood.
            s.check(s.level.getBlockState(second).is(Blocks.STONE), "the first stone stands where the second stood");
            s.check(s.owner(second) == null, "the cell a stone was pushed into has no owner: " + s.owner(second));
            s.check(s.owner(third) == null, "the cell at the end of the push has no owner: " + s.owner(third));
            s.check(s.owner(first) == null, "the cell the push started from has no owner: " + s.owner(first));
            s.pass();
        });
    }

    private static void explosionsTakeOwnership(Scene s) {
        s.floor();
        Mock builder = s.join("ag_boom_01", true, 0, 0);
        BlockPos at = s.base.offset(0, 0, -3);
        s.place(builder, at);
        s.check(s.owner(at) != null, "the stone is the builder's");
        s.level.explode(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 4.0F, Level.ExplosionInteraction.TNT);
        s.check(s.level.getBlockState(at).isAir(), "the explosion took the stone");
        // Something that is no player's fills the crater before the Body reads the record: the cell is still no one's.
        s.level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        s.check(s.owner(at) == null, "a cell an explosion emptied has no owner: " + s.owner(at));
        s.pass();
    }

    private static void boneMealGrowthIsNoOnes(Scene s) {
        s.floor();
        Mock gardener = s.join("ag_grow_01", true, 0, 0);
        BlockPos sapling = s.base.offset(-2, 0, -2);
        s.clearBox(sapling.offset(-6, 0, -6), sapling.offset(6, 16, 6));
        s.level.setBlockAndUpdate(sapling.below(), Blocks.DIRT.defaultBlockState());
        s.use(gardener, Items.OAK_SAPLING, sapling.below(), Direction.UP);
        s.check(s.level.getBlockState(sapling).is(Blocks.OAK_SAPLING), "the sapling is planted");
        for (int i = 0; i < 40 && s.level.getBlockState(sapling).is(Blocks.OAK_SAPLING); i++) {
            s.use(gardener, Items.BONE_MEAL, sapling, Direction.UP);
        }
        s.check(s.level.getBlockState(sapling).is(Blocks.OAK_LOG), "bone meal grew the sapling into a tree");
        // CoreProtect logs no growth with tree growth off: the planted cell keeps the planter's record, naming the
        // sapling, and the rest of the tree is no one's.
        OwnedBlock trunk = s.owner(sapling);
        s.check(trunk != null && "ag_grow_01".equals(trunk.owner()) && "oak_sapling".equals(trunk.block()),
                "the planted cell keeps its record: " + trunk);
        s.check(s.owner(sapling.above()) == null, "the tree bone meal grew is no one's: " + s.owner(sapling.above()));
        s.pass();
    }

    private static void coreProtectHistoryIsImported(Scene s) {
        s.floor();
        Mock builder = s.join("ag_here_01", true, 0, 0);
        // Set by no player on this server; the import says alice placed it on Paper.
        BlockPos standing = s.base.offset(1, 0, -1);
        // Named by the import, but its cell is empty here.
        BlockPos gone = s.base.offset(2, 0, -1);
        // Placed on this server after the move, so newer than anything the import knows.
        BlockPos newer = s.base.offset(3, 0, -1);
        s.level.setBlockAndUpdate(standing, Blocks.STONE.defaultBlockState());
        s.place(builder, newer);
        String world = Worlds.name(Compat.levelName(s.server), Compat.dimension(s.level));
        Path file = s.server.getServerDirectory().resolve("gametest-owners.csv");
        try {
            Files.writeString(file, OwnerImport.HEADER + "\n"
                    + exported(world, standing, "ag_alice_01") + exported(world, gone, "ag_alice_01")
                    + exported(world, newer, "ag_alice_01") + "nowhere,0,64,0,ag_alice_01,minecraft:stone\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        s.server.getCommands().performPrefixedCommand(s.server.createCommandSourceStack(),
                GeldarCore.IMPORT_OWNERS + " gametest-owners.csv");
        OwnedBlock imported = s.owner(standing);
        s.check(imported != null && "ag_alice_01".equals(imported.owner()) && "stone".equals(imported.block()),
                "the imported owner stands: " + imported);
        s.check(s.owner(gone) == null, "an imported owner of an empty cell is not laid in: " + s.owner(gone));
        OwnedBlock kept = s.owner(newer);
        s.check(kept != null && "ag_here_01".equals(kept.owner()), "a newer owner here is kept: " + kept);
        s.pass();
    }

    private static String exported(String world, BlockPos at, String user) {
        return world + "," + at.getX() + "," + at.getY() + "," + at.getZ() + "," + user + ",minecraft:stone\n";
    }

    private static String line(SignBlockEntity sign, int index) {
        return sign.getFrontText().getMessage(index, false).getString();
    }

    // ---- what a test stands on ----

    /** A player with no client: what the server sent it is kept in the channel. */
    record Mock(ServerPlayer player, EmbeddedChannel channel) {
        String name() {
            return Compat.name(player);
        }

        /**
         * Everything the server sent so far. The server holds back what it sends during a tick and lets it go when the
         * tick ends, for the connections it knows; this one it does not know, so it is let go here.
         */
        private java.util.Queue<Object> sent() {
            channel.flushOutbound();
            return channel.outboundMessages();
        }

        /** Forgets everything sent so far. */
        void forget() {
            sent().clear();
        }

        /** How many chat lines from {@code speaker} the server sent this player. */
        long chatsFrom(Mock speaker) {
            return sent().stream()
                    .filter(packet -> packet instanceof ClientboundPlayerChatPacket chat
                            && chat.sender().equals(speaker.player().getUUID()))
                    .count();
        }

        /** The server lines (not player chat) sent to this player. */
        List<String> systemLines() {
            List<String> lines = new ArrayList<>();
            for (Object packet : sent()) {
                if (packet instanceof ClientboundSystemChatPacket line) {
                    lines.add(line.content().getString());
                }
            }
            return lines;
        }
    }

    /** One test's corner of the world, and the players and blocks it must take away again. */
    static final class Scene {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        /** Where the first player stands: clear of the test's structure, with air all round. */
        final BlockPos base;
        final List<Mock> mocks = new ArrayList<>();
        private final List<BlockPos> toClear = new ArrayList<>();
        private final List<int[]> forced = new ArrayList<>();
        private boolean ended;

        Scene(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.base = helper.absolutePos(BlockPos.ZERO).above(6);
        }

        /** A player joins, standing {@code dx}, {@code dz} blocks from the base. {@code live} gives it the live property. */
        Mock join(String name, boolean live, int dx, int dz) {
            PropertyMap properties = live
                    ? new PropertyMap(ImmutableMultimap.of(
                            GhostListener.LIVE_PROPERTY, new Property(GhostListener.LIVE_PROPERTY, "1")))
                    : PropertyMap.EMPTY;
            GameProfile profile = new GameProfile(UUID.randomUUID(), name, properties);
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            // What a client says once it has the world: until then the server lets nothing hurt the player.
            player.connection.markClientLoaded();
            Mock mock = new Mock(player, channel);
            mocks.add(mock);
            player.teleportTo(base.getX() + dx + 0.5, base.getY(), base.getZ() + dz + 0.5);
            return mock;
        }

        /** A stone floor under the base, nine blocks square, for what needs ground. */
        void floor() {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos at = base.offset(dx, -1, dz);
                    level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
                    // The floor and whatever comes to stand on it.
                    for (int dy = 0; dy <= 3; dy++) {
                        toClear.add(at.above(dy));
                    }
                }
            }
        }

        void clearLater(BlockPos at) {
            toClear.add(at);
        }

        /** The player sets a stone block at {@code at}, as a click on the block below does. */
        void place(Mock mock, BlockPos at) {
            use(mock, Items.STONE, at.below(), Direction.UP);
        }

        /** The player uses {@code item} on one face of the block at {@code on}, as a right click does. */
        void use(Mock mock, Item item, BlockPos on, Direction face) {
            ItemStack stack = new ItemStack(item);
            mock.player().setItemInHand(InteractionHand.MAIN_HAND, stack);
            Vec3 at = Vec3.atCenterOf(on).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            stack.useOn(new UseOnContext(level, mock.player(), InteractionHand.MAIN_HAND, stack,
                    new BlockHitResult(at, face, on, false)));
        }

        /** Takes away everything in the box between two corners when the test ends. */
        void clearBox(BlockPos from, BlockPos to) {
            BlockPos.betweenClosed(from, to).forEach(at -> toClear.add(at.immutable()));
        }

        /** The owner record of one block as the Body's queries would read it, or null when it has none. */
        OwnedBlock owner(BlockPos at) {
            String world = Worlds.name(Compat.levelName(server), Compat.dimension(level));
            var owners = GeldarCore.blockOwners().source().ownersInChunk(world, at.getX() >> 4, at.getZ() >> 4);
            for (OwnedBlock block : owners.blocks()) {
                if (block.x() == at.getX() && block.y() == at.getY() && block.z() == at.getZ()) {
                    return block;
                }
            }
            return null;
        }

        /** The payload of the first event of this type about this player since {@code from}, or null. */
        JsonObject event(int from, String type, String player) {
            List<EventEnvelope> events = EVENTS.events();
            for (EventEnvelope event : events.subList(from, events.size())) {
                JsonObject payload = event.payload();
                if (event.subject().endsWith("." + type) && payload.has("player")
                        && player.equals(payload.get("player").getAsString())) {
                    return payload;
                }
            }
            return null;
        }

        void check(boolean condition, String what) {
            helper.assertTrue(condition, what);
        }

        /** Loads the chunks of the box from the base out to these block offsets, and runs {@code then} once they are in. */
        void whenLoaded(int fromDx, int toDx, int fromDz, int toDz, Runnable then) {
            List<int[]> wanted = new ArrayList<>();
            for (int x = (base.getX() + fromDx) >> 4; x <= (base.getX() + toDx) >> 4; x++) {
                for (int z = (base.getZ() + fromDz) >> 4; z <= (base.getZ() + toDz) >> 4; z++) {
                    wanted.add(new int[] {x, z});
                    if (level.setChunkForced(x, z, true)) {
                        forced.add(new int[] {x, z});
                    }
                }
            }
            poll(() -> wanted.stream().allMatch(chunk -> level.hasChunk(chunk[0], chunk[1])), then);
        }

        /** Runs {@code then} once {@code ready} holds, looking every five ticks; the test's time limit bounds the wait. */
        void poll(java.util.function.BooleanSupplier ready, Runnable then) {
            helper.runAfterDelay(5, () -> guarded(() -> {
                if (ready.getAsBoolean()) {
                    then.run();
                } else {
                    poll(ready, then);
                }
            }));
        }

        /** Runs a step of the test; when it fails, the players and blocks are taken away before the failure is told. */
        void guarded(Runnable step) {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                end();
                throw e;
            }
        }

        void pass() {
            end();
            helper.succeed();
        }

        private void end() {
            if (ended) {
                return;
            }
            ended = true;
            for (Mock mock : mocks) {
                server.getPlayerList().remove(mock.player());
            }
            for (BlockPos at : toClear) {
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            }
            for (int[] chunk : forced) {
                level.setChunkForced(chunk[0], chunk[1], false);
            }
        }
    }
}
