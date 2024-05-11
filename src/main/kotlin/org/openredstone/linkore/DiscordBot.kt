package org.openredstone.linkore

import net.luckperms.api.LuckPerms
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.entity.message.MessageFlag
import org.javacord.api.event.interaction.SlashCommandCreateEvent
import org.javacord.api.event.server.member.ServerMemberJoinEvent
import org.javacord.api.interaction.SlashCommand
import org.javacord.api.interaction.SlashCommandInteraction
import org.javacord.api.interaction.SlashCommandOption
import org.slf4j.Logger
import java.util.*
import org.javacord.api.entity.user.User as JavacordUser

internal fun <T> Optional<T>.toNullable(): T? = orElse(null)

fun SlashCommandInteraction.basicResponse(message: String) {
    createImmediateResponder().apply {
        setContent(message)
        setFlags(MessageFlag.EPHEMERAL)
        respond()
    }
}

class DiscordBot(
    token: String,
    serverId: Long,
    playingMessage: String,
    logChannelId: Long,
    private val track: String,
    private val luckPerms: LuckPerms,
    private val logger: Logger,
    private val database: Storage,
    private val tokens: Tokens
) {
    // Group 1 is the "Discord" alias, group 2 is the IGN
    private val nicknameRegex = Regex("""(.+?)\[(\w{3,16})\]""")
    private val authSlashCommand: SlashCommand
    private val discordSlashCommand: SlashCommand
    private val unlinkSlashCommand: SlashCommand
    private val api = DiscordApiBuilder()
        .setToken(token)
        .setAllIntents()
        .login().join()
    private val server = api.getServerById(serverId).toNullable()
        ?: throw Exception("Cannot find Discord server with id $serverId")
    private val logChannel = server.getTextChannelById(logChannelId).toNullable()
    private val possibleGroups = luckPerms.trackManager.getTrack(track)!!.groups.toSet()

    init {
        with(api) {
            updateActivity(playingMessage)
            authSlashCommand = createAuthSlashCommand()
            discordSlashCommand = createDiscordSlashCommand()
            unlinkSlashCommand = createUnlinkSlashCommand()
            addSlashCommandCreateListener(::responseListener)
            addServerMemberJoinListener(::onJoinListener)
        }
    }

    private fun updateRoles() = server.roles.associate { it.name.lowercase() to it }

    fun unlinkUser(discordId: Long) {
        api.getUserById(discordId).join()
    }

    private fun unlinkUser(discordUser: JavacordUser) {
        val roles = updateRoles()
        // The discord Roles this user is part of
        val discRoles = server.getRoles(discordUser)
        // The Roles we actually care about
        val currentRoles = discRoles.filter { it.name in possibleGroups }.map { it.name }
        // The LP Groups of the track this user is in intersected with their Roles on Discord
        val joinedRoles = possibleGroups.intersect(currentRoles.toSet())
        joinedRoles.forEach {
            server.removeRoleFromUser(discordUser, roles.getValue(it))
        }
        server.resetNickname(discordUser)
    }

    fun syncUser(user: User, primaryGroup: String = luckPerms.userManager.loadUser(user.uuid).join().primaryGroup) {
        // This Discord User
        val discordUser = api.getUserById(user.discordId).join()
        syncRoles(discordUser, primaryGroup)
        syncName(user, discordUser)
    }

    private fun sendLogMessage(message: String) {
        logChannel?.sendMessage(message)
        //    ?: throw Exception("Invalid public log channel $logChannelId")
    }

    private fun syncName(user: User, discordUser: JavacordUser) {
        val nickname = server.getNickname(discordUser).toNullable()
        if (nickname == null) {
            // No nickname present, setting it
            server.updateNickname(discordUser, user.name)
            return
        }
        if (nickname == user.name || nickname.endsWith(" [${user.name}]")) {
            // Nickname already is set on Discord
            return
        }
        val matchResult = nicknameRegex.find(nickname)
        val newName = if (matchResult == null) {
            // Nickname is set, but could not match, setting again
            user.name
        } else {
            // Found existing alias, updating IGN using alias
            val existingAlias = matchResult.groupValues[1].trim()
            "$existingAlias [${user.name}]"
        }
        server.updateNickname(discordUser, newName).join()
    }

    private fun syncRoles(discordUser: JavacordUser, primaryGroup: String) {
        val roles = updateRoles()
        if (!roles.keys.containsAll(possibleGroups)) {
            logger.error("Not all tracked groups appear in Discord. Aborting sync.")
            return
        }
        // The discord Roles this user is part of
        val discRoles = server.getRoles(discordUser).map { it.name }.toSet()
        // The Roles we actually care about
        val currentRoles = discRoles.intersect(possibleGroups)
        // The Roles they are in on Discord that they need to be removed from
        val rolesToRemove = currentRoles - primaryGroup
        rolesToRemove.forEach {
            server.removeRoleFromUser(discordUser, roles.getValue(it))
        }
        val removedMessage = "Removed `${discordUser.getDisplayName(server)}` from ${rolesToRemove.joinToString("`, `", "`", "`")}"
        if (primaryGroup !in possibleGroups) {
            // This user's primary group isn't being tracked, so no need to attempt to add them
            if (rolesToRemove.isNotEmpty()) {
                sendLogMessage(removedMessage)
            }
            return
        }
        if (primaryGroup !in currentRoles) {
            // Add the role corresponding to the user's primary group to the user
            server.addRoleToUser(discordUser, roles.getValue(primaryGroup))
            if (rolesToRemove.isNotEmpty()) {
                sendLogMessage("$removedMessage\n Adding `${discordUser.getDisplayName(server)}` to `${primaryGroup}`")
            } else {
                sendLogMessage("Adding `${discordUser.getDisplayName(server)}` to `${primaryGroup}`")
            }
        }
    }

    private fun onJoinListener(event: ServerMemberJoinEvent) {
        if (event.server.id != server.id) return
        val linkedUser = database.getUser(event.user.id) ?: return
        syncUser(linkedUser)
    }

    private fun responseListener(event: SlashCommandCreateEvent) {
        val interaction = event.slashCommandInteraction
        when (interaction.commandId) {
            authSlashCommand.id -> {
                doAuthCommand(interaction)
            }
            discordSlashCommand.id -> {
                doDiscordCommand(interaction)
            }
            unlinkSlashCommand.id -> {
                doUnlinkCommand(interaction)
            }
        }
    }

    private fun doAuthCommand(interaction: SlashCommandInteraction) {
        val userId = interaction.user.id
        val existingUser = database.getUser(userId)
        if (existingUser != null) {
            interaction.basicResponse("You are already linked to ${existingUser.name} (`${existingUser.uuid}`)")
            return
        }
        val token = interaction.arguments.first().stringValue.get()
        val unlinkedUser = tokens.tryConsume(token) ?: run {
            interaction.basicResponse("Invalid code provided! I do not recognize the token `$token`.")
            return
        }
        val linkedUser = unlinkedUser.linkTo(userId)
        database.linkUser(linkedUser)
        sendLogMessage("Linking user <@$userId>")
        syncUser(linkedUser)
        interaction.basicResponse("You are now linked to **${linkedUser.name}** (`${linkedUser.uuid}`)!")
    }

    private fun doDiscordCommand(interaction: SlashCommandInteraction) {
        interaction.basicResponse("This command needs to be ran ingame. Join `mc.openredstone.org` in Minecraft Java edition.")
    }

    private fun doUnlinkCommand(interaction: SlashCommandInteraction) {
        database.getUser(interaction.user.id) ?: run {
            interaction.basicResponse("You are not linked to any Minecraft account!")
            return
        }
        database.unlinkUser(interaction.user.id)
        unlinkUser(interaction.user)
        interaction.basicResponse("You are now unlinked. Run `/discord` ingame to link again.")
    }

    private fun createAuthSlashCommand(): SlashCommand = SlashCommand
        .with(
            "link", "Link your Discord account with your Minecraft account!",
            listOf(
                SlashCommandOption.createStringOption(
                    "code",
                    "The code provided from ingame",
                    true
                )
            )
        )
        .createForServer(server)
        .join()

    private fun createDiscordSlashCommand(): SlashCommand = SlashCommand
        .with("discord", "This needs to be ran ingame")
        .createForServer(server)
        .join()

    private fun createUnlinkSlashCommand(): SlashCommand = SlashCommand
        .with("unlink", "Unlink this Discord account from your Minecraft account!")
        .createForServer(server)
        .join()
}
