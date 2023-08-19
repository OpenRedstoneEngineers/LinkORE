package org.openredstone.linkore

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
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
    private val track: String,
    private val luckPerms: LuckPerms,
    private val logger: Logger,
    private val database: Storage,
    private val tokens: Tokens
) {
    // Group 1 is the "Discord" alias, group 2 is the IGN
    private val nicknameRegex = Regex("""(.+?)\[(\w{3,16})\]""")
    private val authSlashCommand: SlashCommand
    private val unlinkSlashCommand: SlashCommand
    private val api = DiscordApiBuilder()
        .setToken(token)
        .setAllIntents()
        .login().join()
    private val server = api.getServerById(serverId).toNullable()
        ?: throw Exception("Cannot find Discord server with id $serverId")

    init {
        with(api) {
            updateActivity(playingMessage)
            authSlashCommand = createAuthSlashCommand()
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
        // TODO: 8/6/2023 Lots of dupe with syncRoles()
        val roles = updateRoles()
        // The groups in the track we care about
        val possibleGroups = luckPerms.trackManager.getTrack(track)!!.groups
        // The discord Roles this user is part of
        val discRoles = server.getRoles(discordUser)
        // The Roles we actually care about
        val currentRoles = discRoles.filter { it.name in possibleGroups }.map { it.name }
        // The LP Groups of the track this user is in intersected with their Roles on Discord
        val joinedRoles = possibleGroups.toSet().intersect(currentRoles.toSet())
        joinedRoles.forEach {
            server.removeRoleFromUser(discordUser, roles.getValue(it))
        }
        server.resetNickname(discordUser)
    }

    fun syncUser(user: User) {
        // This Discord User
        val discordUser = api.getUserById(user.discordId).join()
        syncRoles(user, discordUser)
        syncName(user, discordUser)
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

    private fun syncRoles(user: User, discordUser: JavacordUser) {
        // TODO: 8/6/2023 Lots of dupe with unlinkUser()
        val roles = updateRoles()
        // The groups in the track we care about
        // TODO: passed as an argument?
        val possibleGroups = luckPerms.trackManager.getTrack(track)!!.groups.toSet()
        val primaryGroup = luckPerms.userManager.loadUser(user.uuid).join().primaryGroup
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
        if (primaryGroup !in possibleGroups) {
            // This user's primary group isn't being tracked, so no need to attempt to add them
            return
        }
        // Add the role corresponding to the user's primary group to the user
        server.addRoleToUser(discordUser, roles.getValue(primaryGroup))
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
            unlinkSlashCommand.id -> {
                doUnlinkCommand(interaction)
            }
        }
    }

    private fun doAuthCommand(interaction: SlashCommandInteraction) {
        val existingUser = database.getUser(interaction.user.id)
        if (existingUser != null) {
            interaction.basicResponse("You are already linked to ${existingUser.name} (`${existingUser.uuid}`)")
            return
        }
        val token = interaction.arguments.first().stringValue.get()
        val unlinkedUser = tokens.tryConsume(token) ?: run {
            interaction.basicResponse("Invalid code provided! I do not recognize the token `$token`.")
            return
        }
        val linkedUser = unlinkedUser.linkTo(interaction.user.id)
        database.linkUser(linkedUser)
        syncUser(linkedUser)
        interaction.basicResponse("You are now linked to **${linkedUser.name}** (`${linkedUser.uuid}`)!")
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
            "auth", "Link your Discord account with your Minecraft account!",
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

    private fun createUnlinkSlashCommand(): SlashCommand = SlashCommand
        .with("unlink", "Unlink this Discord account from your Minecraft account!")
        .createForServer(server)
        .join()
}
