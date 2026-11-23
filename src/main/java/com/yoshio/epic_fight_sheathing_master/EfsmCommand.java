/*
 * Copyright (C) 2026 Yoshio255
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.yoshio.epic_fight_sheathing_master;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

public class EfsmCommand {

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("efsm")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("reload")
                    .executes(EfsmCommand::reloadAllowlist))
                .then(Commands.literal("tune")
                    .then(Commands.argument("weaponKey", StringArgumentType.word())
                        .then(Commands.literal("print")
                            .executes(EfsmCommand::printOffset))
                        .then(Commands.literal("reset")
                            .executes(EfsmCommand::resetOffset))
                        .then(Commands.argument("field", StringArgumentType.word())
                            .then(Commands.argument("value", FloatArgumentType.floatArg())
                                .executes(EfsmCommand::tuneOffset)))
                    )
                )
                .then(Commands.literal("hitbox")
                    .executes(EfsmCommand::toggleHitboxDebug)
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .then(Commands.literal("size")
                            .then(Commands.argument("axis", StringArgumentType.word())
                                .then(Commands.argument("value", FloatArgumentType.floatArg())
                                    .executes(EfsmCommand::setHitboxSize))))
                        .then(Commands.literal("offset")
                            .then(Commands.argument("axis", StringArgumentType.word())
                                .then(Commands.argument("value", FloatArgumentType.floatArg())
                                    .executes(EfsmCommand::setHitboxOffset))))
                        .then(Commands.literal("step")
                            .then(Commands.argument("axis", StringArgumentType.word())
                                .then(Commands.argument("value", FloatArgumentType.floatArg())
                                    .executes(EfsmCommand::setHitboxStep))))
                        .then(Commands.literal("count")
                            .then(Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(EfsmCommand::setHitboxCount)))
                        .then(Commands.literal("print")
                            .executes(EfsmCommand::printHitboxParams))
                        .then(Commands.literal("reset")
                            .executes(EfsmCommand::resetHitboxParams))))
        );
    }

    // Re-reads config/epic_fight_sheathing_master-allowlist.json (SheathAllowlist).
    // Never fails: an invalid file just falls back to the built-in default list.
    private static int reloadAllowlist(CommandContext<CommandSourceStack> ctx) {
        final String result = SheathAllowlist.reload();
        ctx.getSource().sendSuccess(() -> Component.literal("[efsm] allowlist reloaded: " + result), false);
        return 1;
    }

    // Toggles the sheath-collision debug box overlay (SheathCollisionDebugRenderer).
    private static int toggleHitboxDebug(CommandContext<CommandSourceStack> ctx) {
        SheathCollisionSystem.toggleDebug();
        boolean enabled = SheathCollisionSystem.isDebugEnabled();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[efsm] Sheath collision hitbox debug: " + (enabled ? "ON" : "OFF")), false);
        return 1;
    }

    private static SheathHitboxProfile getHitboxProfile(CommandContext<CommandSourceStack> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        SheathHitboxProfile profile = SheathHitboxProfile.REGISTRY.get(tag);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[efsm] Unknown hitbox tag '" + tag + "'. Valid: " + String.join(", ", SheathHitboxProfile.REGISTRY.keySet())));
        }
        return profile;
    }

    private static int setHitboxSize(CommandContext<CommandSourceStack> ctx) {
        SheathHitboxProfile profile = getHitboxProfile(ctx);
        if (profile == null) return 0;
        String axis = StringArgumentType.getString(ctx, "axis").toLowerCase();
        float value = FloatArgumentType.getFloat(ctx, "value");
        if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z")) {
            ctx.getSource().sendFailure(Component.literal("[efsm] Unknown axis '" + axis + "'. Valid: x  y  z"));
            return 0;
        }
        profile.setHalfSize(axis, value);
        final String msg = "[efsm] hitbox " + StringArgumentType.getString(ctx, "tag") + " halfSize." + axis + " = " + value + "  (" + profile + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int setHitboxOffset(CommandContext<CommandSourceStack> ctx) {
        SheathHitboxProfile profile = getHitboxProfile(ctx);
        if (profile == null) return 0;
        String axis = StringArgumentType.getString(ctx, "axis").toLowerCase();
        float value = FloatArgumentType.getFloat(ctx, "value");
        if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z")) {
            ctx.getSource().sendFailure(Component.literal("[efsm] Unknown axis '" + axis + "'. Valid: x  y  z"));
            return 0;
        }
        profile.setOffset(axis, value);
        final String msg = "[efsm] hitbox " + StringArgumentType.getString(ctx, "tag") + " offset." + axis + " = " + value + "  (" + profile + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int setHitboxStep(CommandContext<CommandSourceStack> ctx) {
        SheathHitboxProfile profile = getHitboxProfile(ctx);
        if (profile == null) return 0;
        String axis = StringArgumentType.getString(ctx, "axis").toLowerCase();
        float value = FloatArgumentType.getFloat(ctx, "value");
        if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z")) {
            ctx.getSource().sendFailure(Component.literal("[efsm] Unknown axis '" + axis + "'. Valid: x  y  z"));
            return 0;
        }
        profile.setStep(axis, value);
        final String msg = "[efsm] hitbox " + StringArgumentType.getString(ctx, "tag") + " step." + axis + " = " + value + "  (" + profile + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int setHitboxCount(CommandContext<CommandSourceStack> ctx) {
        SheathHitboxProfile profile = getHitboxProfile(ctx);
        if (profile == null) return 0;
        int value = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value");
        profile.setCount(value);
        final String msg = "[efsm] hitbox " + StringArgumentType.getString(ctx, "tag") + " count = " + value + "  (" + profile + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int printHitboxParams(CommandContext<CommandSourceStack> ctx) {
        SheathHitboxProfile profile = getHitboxProfile(ctx);
        if (profile == null) return 0;
        final String msg = "[efsm] hitbox " + StringArgumentType.getString(ctx, "tag") + " " + profile;
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int resetHitboxParams(CommandContext<CommandSourceStack> ctx) {
        SheathHitboxProfile profile = getHitboxProfile(ctx);
        if (profile == null) return 0;
        profile.reset();
        final String msg = "[efsm] hitbox " + StringArgumentType.getString(ctx, "tag") + " params reset.  " + profile;
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int printOffset(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "weaponKey");
        TuneableOffset off = TuneableOffset.REGISTRY.get(key);
        if (off == null) {
            ctx.getSource().sendFailure(Component.literal("[efsm] Unknown weapon key: " + key));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[efsm] " + key + ": " + off), false);
        return 1;
    }

    private static int resetOffset(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "weaponKey");
        TuneableOffset off = TuneableOffset.REGISTRY.get(key);
        if (off == null) {
            ctx.getSource().sendFailure(Component.literal("[efsm] Unknown weapon key: " + key));
            return 0;
        }
        off.reset();
        ctx.getSource().sendSuccess(() -> Component.literal("[efsm] " + key + " reset to defaults.  " + off), false);
        return 1;
    }

    private static int tuneOffset(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "weaponKey");
        String field = StringArgumentType.getString(ctx, "field").toLowerCase();
        float value = FloatArgumentType.getFloat(ctx, "value");

        TuneableOffset off = TuneableOffset.REGISTRY.get(key);
        if (off == null) {
            ctx.getSource().sendFailure(Component.literal("[efsm] Unknown weapon key: " + key));
            return 0;
        }

        switch (field) {
            case "tx"    -> off.tx    = value;
            case "ty"    -> off.ty    = value;
            case "tz"    -> off.tz    = value;
            case "rotx"  -> off.rotX  = value;
            case "roty"  -> off.rotY  = value;
            case "rotz"  -> off.rotZ  = value;
            case "scale" -> off.scale = value;
            default -> {
                ctx.getSource().sendFailure(Component.literal(
                        "[efsm] Unknown field '" + field + "'. Valid: tx  ty  tz  rotx  roty  rotz  scale"));
                return 0;
            }
        }

        final String msg = "[efsm] " + key + "." + field + " = " + value + "  (" + off + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
