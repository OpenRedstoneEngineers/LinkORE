package org.openredstone.commands

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CatchUnknown
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Default
import co.aikar.commands.annotation.Single
import co.aikar.commands.annotation.Subcommand
import com.velocitypowered.api.proxy.Player
import org.openredstone.DiscordBot
import org.openredstone.Storage
import org.openredstone.sendDeserialized
import java.util.*

@CommandAlias("linkore")
@CommandPermission("linkore.manage")
class Linkore(
    private val version: String,
    private val database: Storage,
    private val discordBot: DiscordBot
    ) : BaseCommand() {
    @Default
    @CatchUnknown
    @Subcommand("version")
    fun version(player: Player) {
        player.sendDeserialized("Version $version")
    }

    @Subcommand("unlink")
    @CommandPermission("linkore.manage.unlink")
    fun unlink(player: Player, @Single arg: String) {
        val discordId = arg.toLongOrNull()
        val linkedUser = if (discordId == null) {
            val parsedUuid = try {
                UUID.fromString(arg)
            } catch (e: IllegalArgumentException) {
                player.sendDeserialized("Invalid UUID provided: $arg")
                return
            }
            database.getUser(parsedUuid) ?: run {
                player.sendDeserialized("User by UUID $parsedUuid is not linked")
                return
            }
        } else {
            database.getUser(discordId) ?: run {
                player.sendDeserialized("User by ID $discordId is not linked")
                return
            }
        }
        player.sendDeserialized("Going to unlink $discordId")
        discordBot.unlinkUser(linkedUser.discordId)
        database.unlinkUser(linkedUser.discordId)
    }
}
