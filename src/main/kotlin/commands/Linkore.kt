package commands

import linkore.LinkORE
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CatchUnknown
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Default
import co.aikar.commands.annotation.Single
import co.aikar.commands.annotation.Subcommand
import com.velocitypowered.api.proxy.Player
import linkore.sendDeserialized
import java.util.*

@CommandAlias("linkore")
@CommandPermission("linkore.manage")
class Linkore(private val linkORE: LinkORE) : BaseCommand() {
    @Default
    @CatchUnknown
    @Subcommand("version")
    fun version(player: Player) {
        player.sendDeserialized("Version ${linkORE.getVersion()}")
    }

    @Subcommand("unlink")
    @CommandPermission("linkore.manage.unlink")
    inner class Unlink : BaseCommand() {
        @Subcommand("discord")
        fun discord(player: Player, @Single discordIdArg: String) {
            val discordId = discordIdArg.toLongOrNull() ?: kotlin.run {
                player.sendDeserialized("Invalid ID provided")
                return
            }
            val linkedUser = linkORE.database.getUser(discordId) ?: kotlin.run {
                player.sendDeserialized("User by ID $discordId is not linked")
                return
            }
            player.sendDeserialized("Going to unlink $discordId")
            linkORE.discordBot.unlinkUser(discordId)
            linkORE.database.unlinkUser(linkedUser.discordId)
        }

        @Subcommand("uuid")
        fun uuid(player: Player, uuid: String) {
            val parsedUuid = try {
                UUID.fromString(uuid)
            } catch (e: IllegalArgumentException) {
                player.sendDeserialized("Invalid UUID provided: $uuid")
                return
            }
            val linkedUser = linkORE.database.getUser(parsedUuid) ?: kotlin.run {
                player.sendDeserialized("User by UUID $parsedUuid is not linked")
                return
            }
            player.sendDeserialized("Going to unlink $parsedUuid")
            linkORE.discordBot.unlinkUser(linkedUser.discordId)
            linkORE.database.unlinkUser(linkedUser.discordId)
        }
    }
}
