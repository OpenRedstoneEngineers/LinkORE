package linkore

import org.javacord.api.DiscordApi
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.entity.message.MessageFlag
import org.javacord.api.event.interaction.SlashCommandCreateEvent
import org.javacord.api.event.server.member.ServerMemberJoinEvent
import org.javacord.api.interaction.SlashCommand
import org.javacord.api.interaction.SlashCommandInteraction
import org.javacord.api.interaction.SlashCommandOption
import java.util.*

internal fun <T> Optional<T>.toNullable(): T? = this.orElse(null)

fun SlashCommandInteraction.basicResponse(message: String) {
    this.createImmediateResponder().apply {
        this.setContent(message)
        this.setFlags(MessageFlag.EPHEMERAL)
        this.respond()
    }
}

class DiscordBot(
    private val linkore: LinkORE,
    token: String,
    serverId: Long,
    playingMessage: String,
    private val track: String
) {
    // Group 1 is the "Discord" alias, group 2 is the IGN
    private val nicknameRegex = Regex("""(.+?)\[(\w{3,16})\]""")
    private lateinit var authSlashCommand: SlashCommand
    private lateinit var unlinkSlashCommand: SlashCommand
    private lateinit var roles: Map<String, Long>
    private var api: DiscordApi = DiscordApiBuilder()
        .setToken(token)
        .setAllIntents()
        .login().join()
    private var server = api.getServerById(serverId).get()

    init {
        api.updateActivity(playingMessage)
        api.getServerSlashCommands(server).join().forEach {
            if (it.name == "auth") {
                it.delete().get()
                createAuthSlashCommand()
            } else if (it.name == "unlink") {
                it.delete().get()
                createUnlinkSlashCommand()
            }
        }
        api.addSlashCommandCreateListener { addResponseListener(it) }
        api.addServerMemberJoinListener { addOnJoinListener(it) }
    }

    private fun updateRoles() {
        roles = api.roles.associate { it.name.lowercase() to it.id }
    }

    fun unlinkUser(discordId: Long) {
        unlinkUser(api.getUserById(discordId).get())
    }

    fun unlinkUser(discordUser: org.javacord.api.entity.user.User) {
        // TODO: 8/6/2023 Lots of dupe with syncRoles()
        updateRoles()
        // The groups in the track we care about
        val possibleGroups = linkore.luckPerms.trackManager.loadedTracks.first { it.name == track }.groups
        // The discord Roles this user is part of
        val discRoles = server.getRoles(discordUser)
        // The Roles we actually care about
        val currentRoles = discRoles.filter { it.name in possibleGroups }.map { it.name }
        // The LP Groups of the track this user is in intersected with their Roles on Discord
        val joinedRoles = possibleGroups.toSet().intersect(currentRoles.toSet())
        joinedRoles.forEach {
            removeRoleFromUser(roles[it]!!, discordUser)
        }
        server.resetNickname(discordUser)
    }

    fun syncUser(user: User) {
        // This Discord User
        val discordUser = api.getUserById(user.discordId).get()
        syncRoles(user, discordUser)
        syncName(user, discordUser)
    }

    private fun syncName(user: User, discordUser: org.javacord.api.entity.user.User) {
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
        if (matchResult == null) {
            // Nickname is set, but could not match, setting again
            server.updateNickname(discordUser, user.name)
        } else {
            // Found existing alias, updating IGN using alias
            val existingAlias = matchResult.groupValues[1].trim()
            val newName = "$existingAlias [${user.name}]"
            server.updateNickname(discordUser, newName)
        }
    }

    private fun syncRoles(
        user: User,
        discordUser: org.javacord.api.entity.user.User
    ) {
        // TODO: 8/6/2023 Lots of dupe with unlinkUser()
        updateRoles()
        // The groups in the track we care about
        val possibleGroups = linkore.luckPerms.trackManager.loadedTracks.first { it.name == track }.groups
        if (!roles.keys.containsAll(possibleGroups)) {
            linkore.logger.error("Not all tracked groups appear in Discord. Aborting sync.")
            return
        }
        // The discord Roles this user is part of
        val discRoles = server.getRoles(discordUser)
        // The Roles we actually care about
        val currentRoles = discRoles.filter { it.name in possibleGroups }.map { it.name }
        // The LP Groups of the track this user is in intersected with their Roles on Discord
        val joinedRoles = possibleGroups.toSet().intersect(currentRoles.toSet())
        // The Roles they are in on Discord that they need to be removed from
        val rolesToRemove = joinedRoles - setOf(user.primaryGroup)
        rolesToRemove.forEach {
            removeRoleFromUser(roles.getValue(it), discordUser)
        }
        // This user's primary group isn't being tracked, so no need to attempt to add them
        if (user.primaryGroup !in possibleGroups) {
            return
        }
        // The Roles they are not in on Discord that they need to be added to
        val rolesToAdd = setOf(user.primaryGroup) - joinedRoles
        rolesToAdd.forEach { // There should be only one group here
            addRoleToUser(roles.getValue(it), discordUser)
        }
    }

    private fun addRoleToUser(role: Long, user: org.javacord.api.entity.user.User) {
        server.getRoleById(role).ifPresent {
            server.addRoleToUser(user, it)
        }
    }

    private fun removeRoleFromUser(role: Long, user: org.javacord.api.entity.user.User) {
        server.getRoleById(role).ifPresent {
            server.removeRoleFromUser(user, it)
        }
    }

    private fun addOnJoinListener(event: ServerMemberJoinEvent) {
        if (event.server.id != server.id) return
        val linkedUser = linkore.database.getUser(event.user.id) ?: return
        syncUser(linkedUser)
    }

    private fun addResponseListener(event: SlashCommandCreateEvent) {
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
        val existingUser = linkore.database.getUser(interaction.user.id)
        if (existingUser != null) {
            interaction.basicResponse("You are already linked to ${existingUser.name} (`${existingUser.uuid}`)")
            return
        }
        val token = interaction.arguments.first().stringValue.get()
        val unlinkedUser = linkore.tokens.tryConsume(token) ?: kotlin.run {
            interaction.basicResponse("Invalid code provided! I do not recognize the token `$token`.")
            return
        }
        val linkedUser = unlinkedUser.linkTo(interaction.user.id)
        linkore.database.linkUser(linkedUser.uuid, linkedUser.discordId)
        syncUser(linkedUser)
        interaction.basicResponse("You are now linked to **${linkedUser.name}** (`${linkedUser.uuid}`)!")
    }

    private fun doUnlinkCommand(interaction: SlashCommandInteraction) {
        linkore.database.getUser(interaction.user.id) ?: run {
            interaction.basicResponse("You are not linked to any Minecraft account!")
            return
        }
        linkore.database.unlinkUser(interaction.user.id)
        unlinkUser(interaction.user)
        interaction.basicResponse("You are now unlinked. Run `/discord` ingame to link again.")
    }

    private fun createAuthSlashCommand() {
        authSlashCommand = SlashCommand
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
    }

    private fun createUnlinkSlashCommand() {
        unlinkSlashCommand = SlashCommand
            .with("unlink", "Unlink this Discord account from your Minecraft account!")
            .createForServer(server)
            .join()
    }
}
