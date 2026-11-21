package dev.chill.commonsense;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Op-only (level 2) commands to read and change what Common Sense remembers about a mob, so it can be tested without setup:
 * <pre>
 * /commonsense wary &lt;mobs&gt; get|clear|set &lt;seconds&gt;   herd wariness (set: wary of you)
 * /commonsense tie &lt;mobs&gt; get|clear|here                ground tie of a saddled mount
 * /commonsense home &lt;mobs&gt; get|clear                     a villager's remembered bed and home spot
 * </pre>
 */
public final class DebugCommands {

    private DebugCommands() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(CommonSense.MOD_ID).requires(s -> s.hasPermission(2))
                .then(Commands.literal("wary").then(Commands.argument("mobs", EntityArgument.entities())
                        .then(Commands.literal("get").executes(c -> each(c, DebugCommands::waryGet)))
                        .then(Commands.literal("clear").executes(c -> each(c, m -> {
                            CompanionState st = HerdBehaviour.existing(m);
                            if (st != null) {
                                st.waryOf = null;
                                st.waryUntil = 0;
                                st.fleeTarget = null;
                                st.fleeUntil = 0;
                            }
                            return "not wary";
                        })))
                        .then(Commands.literal("set").then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                .executes(c -> {
                                    Entity self = c.getSource().getEntityOrException();
                                    int seconds = IntegerArgumentType.getInteger(c, "seconds");
                                    return each(c, m -> {
                                        CompanionState st = Companions.state(m);
                                        st.waryOf = self.getUUID();
                                        st.waryUntil = m.level().getGameTime() + seconds * 20L;
                                        st.fleeUntil = 0;
                                        return "wary of " + self.getName().getString() + " for " + seconds + " s";
                                    });
                                })))))
                .then(Commands.literal("tie").then(Commands.argument("mobs", EntityArgument.entities())
                        .then(Commands.literal("get").executes(c -> each(c, m -> {
                            BlockPos t = Manners.tie(m);
                            return t == null ? "not tied" : "tied at " + t.toShortString();
                        })))
                        .then(Commands.literal("clear").executes(c -> each(c, m -> {
                            Manners.setTie(m, null);
                            return "untied";
                        })))
                        .then(Commands.literal("here").executes(c -> each(c, m -> {
                            if (!Manners.tieable(m)) {
                                return "not a saddled mount";
                            }
                            Manners.setTie(m, m.blockPosition());
                            return "tied at " + m.blockPosition().toShortString();
                        })))))
                .then(Commands.literal("home").then(Commands.argument("mobs", EntityArgument.entities())
                        .then(Commands.literal("get").executes(c -> each(c, m -> {
                            if (!m.hasData(CommonSense.VILLAGE)) {
                                return "remembers nothing";
                            }
                            VillageMemory mem = m.getData(CommonSense.VILLAGE);
                            return "bed " + (mem.bed == null ? "none" : mem.bed.pos().toShortString() + (mem.bedAbandoned ? " (abandoned)" : ""))
                                    + ", home " + (mem.anchor == null ? "none" : mem.anchor.pos().toShortString())
                                    + (m instanceof Villager && mem.lured ? ", following a lure" : "");
                        })))
                        .then(Commands.literal("clear").executes(c -> each(c, m -> {
                            m.removeData(CommonSense.VILLAGE);
                            return "forgot bed and home";
                        }))))));
    }

    private static String waryGet(Mob m) {
        CompanionState st = HerdBehaviour.existing(m);
        if (!HerdBehaviour.isWary(m) || st == null) {
            return "not wary";
        }
        long left = (st.waryUntil - m.level().getGameTime()) / 20;
        Entity who = m.level() instanceof net.minecraft.server.level.ServerLevel level ? level.getEntity(st.waryOf) : null;
        return "wary of " + (who == null ? st.waryOf.toString() : who.getName().getString()) + " for " + left + " more s";
    }

    /** Runs the action on every mob selected and reports one line each; non-mobs are skipped. */
    private static int each(CommandContext<CommandSourceStack> c, Function<Mob, String> action) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(c, "mobs");
        List<String> lines = new ArrayList<>();
        for (Entity e : targets) {
            if (e instanceof Mob m) {
                lines.add(m.getName().getString() + ": " + action.apply(m));
            }
        }
        if (lines.isEmpty()) {
            c.getSource().sendFailure(Component.literal("No mobs selected."));
            return 0;
        }
        for (String line : lines.size() > 10 ? lines.subList(0, 10) : lines) {
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        if (lines.size() > 10) {
            int more = lines.size() - 10;
            c.getSource().sendSuccess(() -> Component.literal("...and " + more + " more"), false);
        }
        return lines.size();
    }
}
