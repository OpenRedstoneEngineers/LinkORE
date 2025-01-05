package org.openredstone.linkore

import net.luckperms.api.LuckPerms
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.entity.message.MessageFlag
import org.javacord.api.entity.permission.PermissionType
import org.javacord.api.event.interaction.SlashCommandCreateEvent
import org.javacord.api.event.server.member.ServerMemberJoinEvent
import org.javacord.api.exception.MissingPermissionsException
import org.javacord.api.interaction.SlashCommand
import org.javacord.api.interaction.SlashCommandInteraction
import org.javacord.api.interaction.SlashCommandOption
import org.javacord.api.interaction.SlashCommandOptionType
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.javacord.api.entity.user.User as JavacordUser

internal fun <T> Optional<T>.toNullable(): T? = orElse(null)

fun String.discordEscape() = this.replace("""_""", "\\_")

fun <T> handleExceptions(action: () -> T): T? {
    return try {
        action()
    } catch (exception: MissingPermissionsException) {
        println(exception)
        null
    } catch (exception: CompletionException) {
        println(exception)
        null
    }
}

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
    private val forceSyncSlashCommand: SlashCommand
    private val unlinkSlashCommand: SlashCommand
    private val whoisSlashCommand: SlashCommand
    private val api = DiscordApiBuilder()
        .setToken(token)
        .setAllIntents()
        .login().join()
    private val server = api.getServerById(serverId).toNullable()
        ?: throw Exception("Cannot find Discord server with id $serverId")
    private val logChannel = server.getTextChannelById(logChannelId).toNullable()
    private val possibleGroups = luckPerms.trackManager.getTrack(track)!!.groups.map {
        luckPerms.groupManager.getGroup(it)?.displayName?.lowercase() ?: it
    }.toSet()

    init {
        with(api) {
            updateActivity(playingMessage)
            authSlashCommand = createAuthSlashCommand()
            discordSlashCommand = createDiscordSlashCommand()
            forceSyncSlashCommand = createForceSyncSlashCommand()
            unlinkSlashCommand = createUnlinkSlashCommand()
            whoisSlashCommand = createWhoisSlashCommand()
            addSlashCommandCreateListener(::responseListener)
            addServerMemberJoinListener(::onJoinListener)
        }
    }

    private fun updateRoles() = server.roles.associate { it.name.lowercase() to it }

    fun clearDiscordUser(discordId: Long) : CompletableFuture<Void> =
        clearDiscordUser(api.getUserById(discordId).join())

    private fun clearDiscordUser(discordUser: JavacordUser) : CompletableFuture<Void> {
        // The discord roles this user is part of
        val discRoles = server.getRoles(discordUser)
        // The user's roles we actually care about
        val currentRoles = discRoles.filter { it.name.lowercase(Locale.getDefault()) in possibleGroups }
        val futures = mutableListOf<CompletableFuture<Void>>()
        currentRoles.forEach {
            futures.add(server.removeRoleFromUser(discordUser, it))
        }
        futures.add(server.resetNickname(discordUser))
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    fun syncUser(user: User, primaryGroup: String = luckPerms.userManager.loadUser(user.uuid).join().primaryGroup) : CompletableFuture<Void> {
        // This Discord User
        val discordUser = api.getUserById(user.discordId).join()
        val futures = listOfNotNull(
            syncRoles(discordUser, primaryGroup),
            syncName(user, discordUser)
        )
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    private fun sendLogMessage(message: String) {
        logChannel?.sendMessage(message)
        //    ?: throw Exception("Invalid public log channel $logChannelId")
    }

    private fun syncName(user: User, discordUser: JavacordUser) : CompletableFuture<Void>? {
        val nickname = server.getNickname(discordUser).toNullable()
        if (nickname == null) {
            // No nickname present, setting it
            server.updateNickname(discordUser, user.name)
            return null
        }
        if (nickname == user.name || nickname.endsWith(" [${user.name}]")) {
            // Nickname already is set on Discord
            return null
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
        return server.updateNickname(discordUser, newName)
    }

    private fun syncRoles(discordUser: JavacordUser, primaryGroupName: String) : CompletableFuture<Void>? {
        val roles = updateRoles()
        if (!roles.keys.containsAll(possibleGroups)) {
            logger.error("Not all tracked groups appear in Discord. Aborting sync.")
            return null
        }
        // The discord Roles this user is part of
        val discRoles = server.getRoles(discordUser).map { it.name.lowercase() }.toSet()
        // The Roles we actually care about
        val currentRoles = discRoles.intersect(possibleGroups)
        val primaryGroup = luckPerms.groupManager.getGroup(primaryGroupName)!!.let {
            it.displayName ?: it.name
        }.lowercase()
        // The Roles they are in on Discord that they need to be removed from
        val rolesToRemove = currentRoles - primaryGroup
        val futures = mutableListOf<CompletableFuture<Void>>()
        rolesToRemove.forEach {
            futures.add(server.removeRoleFromUser(discordUser, roles.getValue(it)))
        }
        val removedMessage = "Removed `${discordUser.getDisplayName(server)}` from ${rolesToRemove.joinToString("`, `", "`", "`")}"
        if (primaryGroup !in possibleGroups) {
            // This user's primary group isn't being tracked, so no need to attempt to add them
            if (rolesToRemove.isNotEmpty()) {
                sendLogMessage(removedMessage)
            }
            return CompletableFuture.allOf(*futures.toTypedArray())
        }
        if (primaryGroup !in currentRoles) {
            // Add the role corresponding to the user's primary group to the user
            futures.add(server.addRoleToUser(discordUser, roles.getValue(primaryGroup)))
            if (rolesToRemove.isNotEmpty()) {
                sendLogMessage("$removedMessage\n Adding `${discordUser.getDisplayName(server)}` to `${primaryGroup}`")
            } else {
                sendLogMessage("Adding `${discordUser.getDisplayName(server)}` to `${primaryGroup}`")
            }
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    private fun onJoinListener(event: ServerMemberJoinEvent) {
        if (event.server.id != server.id) return
        val linkedUser = database.getUser(event.user.id) ?: return
        syncUser(linkedUser)
    }

    private fun responseListener(event: SlashCommandCreateEvent) {
        val interaction = event.slashCommandInteraction
        when (interaction.commandId) {
            authSlashCommand.id -> doAuthCommand(interaction)
            discordSlashCommand.id -> doDiscordCommand(interaction)
            unlinkSlashCommand.id -> doUnlinkCommand(interaction)
            forceSyncSlashCommand.id -> doForceSyncCommand(interaction)
            whoisSlashCommand.id -> doWhoisCommand(interaction)
        }
    }

    private fun doAuthCommand(interaction: SlashCommandInteraction) {
        val userId = interaction.user.id
        val existingUser = database.getUser(userId)
        if (existingUser != null) {
            interaction.basicResponse("You are already linked to ${existingUser.name.discordEscape()} (`${existingUser.uuid}`)")
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
        syncUser(linkedUser).join()
        interaction.basicResponse("You are now linked to **${linkedUser.name.discordEscape()}** (`${linkedUser.uuid}`)!")
    }

    private fun doDiscordCommand(interaction: SlashCommandInteraction) {
        interaction.basicResponse("This command needs to be ran ingame. Join `mc.openredstone.org` in Minecraft Java edition.")
    }

    private fun doUnlinkCommand(interaction: SlashCommandInteraction) {
        database.getUser(interaction.user.id) ?: run {
            interaction.basicResponse("You are not linked to any Minecraft account!")
            return
        }
        logger.info("Performing unlink for ${interaction.user.name} (${interaction.user.id})")
        database.unlinkUser(interaction.user.id)
        handleExceptions { clearDiscordUser(interaction.user).join() }
        interaction.basicResponse("You are now unlinked. Run `/discord` ingame to link again.")
    }

    private fun doForceSyncCommand(interaction: SlashCommandInteraction) {
        val user = database.getUser(interaction.user.id) ?: run {
            interaction.basicResponse("You are not linked to any Minecraft account!")
            return
        }
        logger.info("Performing force-sync for ${interaction.user.name} (${interaction.user.id})")
        handleExceptions { clearDiscordUser(interaction.user).join() }
        handleExceptions { syncUser(user).join() }
        interaction.basicResponse("Your Discord has been synced based on your linked user.")
    }

    private fun doWhoisCommand(interaction: SlashCommandInteraction) {
        val argument = interaction.arguments.firstOrNull() ?: run {
            interaction.basicResponse("The user argument is required for this command.")
            return
        }
        val target = argument.userValue.toNullable() ?: run {
            interaction.basicResponse("Invalid or no user passed!")
            return
        }
        val linkedUser = database.getUser(target.id) ?: run {
            interaction.basicResponse("That user is not linked.")
            return
        }
        logger.info("Performing whois for ${interaction.user.name} (${interaction.user.id})")
        handleExceptions { syncUser(linkedUser).join() }
        interaction.basicResponse("User <@${target.id}> is linked to ${linkedUser.name.discordEscape()} (`${linkedUser.uuid}`)")
    }

    private fun createDiscordSlashCommand(): SlashCommand = SlashCommand
        .with("discord", "This needs to be ran ingame.")
        .createForServer(server)
        .join()

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

    private fun createForceSyncSlashCommand(): SlashCommand = SlashCommand
        .with("force-sync", "Force a sync of your roles and display name with your ingame account.")
        .createForServer(server)
        .join()

    private fun createUnlinkSlashCommand(): SlashCommand = SlashCommand
        .with("unlink", "Unlink this Discord account from your Minecraft account!")
        .createForServer(server)
        .join()

    private fun createWhoisSlashCommand(): SlashCommand = SlashCommand
        .with("whois", "Look up the linking information associated with this user. It also syncs the user!")
        .addOption(SlashCommandOption.create(SlashCommandOptionType.USER, "user", "The user to look up"))
        .setDefaultEnabledForPermissions(PermissionType.BAN_MEMBERS)
        .createForServer(server)
        .join()
}
