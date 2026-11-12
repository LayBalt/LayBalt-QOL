package com.laybalt.features.impl.dungeon.solvers.puzzles

import com.laybalt.commands.CommandBuilder
import com.laybalt.commands.LbqHelp
import com.laybalt.config.ConfigManager
import com.laybalt.features.impl.dungeon.solvers.PuzzleSolvers
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils

/** `/lbwater` (also `/lbq water`): status, dumps, the recorder, re-planning and a full reset of the Water Board solver. */
object WaterBoardCommand: ICommandProvider {
    override fun CommandBuilder.command() {
        setName("lbwater")
        alsoUnderLbq("water")
        category(LbqHelp.Category.DUNGEONS)
        description("Water Board solver: /lbq water <status|dump|record on|off|replan|reset>")
        runs { status() }

        literal("status") {
            description("What the Water Board solver reads from the board right now")
            runs { status() }
        }

        literal("dump") {
            description("Saves the Water Board room (both lever planes, doors, water) to config/LayBaltQOL/waterboard")
            runs {
                val session = WaterBoardSolver.session ?: return@runs ChatUtils.modMessage("§cNo Water Board room in this dungeon yet.")
                val name = WaterBoardRecorder.dumpLayout(session, "command")
                    ?: return@runs ChatUtils.modMessage("§cThe Water Board is not loaded - walk closer and try again.")
                ChatUtils.modMessage("§aSaved §f$name §7in config/LayBaltQOL/waterboard")
            }
        }

        literal("record") {
            description("Records a trace of every Water Board room and its runs to config/LayBaltQOL/waterboard (on by default, local files only)")
            helpUsage("record <on|off>")
            runs { ChatUtils.modMessage("§7Water Board recorder is §f${if (PuzzleSolvers.waterRecorder.value) "on" else "off"}§7. Use §f/lbq water record on|off") }
            literal("on") {
                runs {
                    PuzzleSolvers.waterRecorder.value = true
                    ConfigManager.save()
                    ChatUtils.modMessage("§aWater Board recorder on §7- files go to config/LayBaltQOL/waterboard")
                }
            }
            literal("off") {
                runs {
                    PuzzleSolvers.waterRecorder.value = false
                    ConfigManager.save()
                    ChatUtils.modMessage("§7Water Board recorder off")
                }
            }
        }

        literal("replan") {
            description("Throws the current Water Board plan away and plans again from the board")
            runs {
                val session = WaterBoardSolver.session ?: return@runs ChatUtils.modMessage("§cNo Water Board room in this dungeon yet.")
                session.replan()
                ChatUtils.modMessage("§aWater Board: planning again from the board as it is.")
            }
        }

        literal("reset") {
            description("Forgets everything about the Water Board room and reads it again")
            runs {
                WaterBoardSolver.resetSession()
                ChatUtils.modMessage("§aWater Board solver reset.")
            }
        }
    }

    private fun status() {
        if (! PuzzleSolvers.water.value) return ChatUtils.modMessage("§cThe Water Board solver is off (Puzzle Solvers > Water Board Solver).")
        val session = WaterBoardSolver.session ?: return ChatUtils.modMessage("§7No Water Board room in this dungeon yet.")
        session.status().forEach(ChatUtils::modMessage)
    }
}
