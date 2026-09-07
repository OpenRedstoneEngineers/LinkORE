package org.openredstone.linkore

import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Member
import dev.kord.core.entity.application.GuildChatInputCommand
import dev.kord.core.entity.interaction.GuildChatInputCommandInteraction
import dev.kord.core.event.guild.MemberJoinEvent
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.user
import dev.kord.rest.request.KtorRequestException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.toSet
import net.luckperms.api.LuckPerms
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.CompletionException

fun String.discordEscape() = this.replace("""_""", "\\_")

inline fun <T> handleExceptions(action: () -> T): T? {
    return try {
        action()
    } catch (exception: KtorRequestException) {
        println(exception)
        null
    } catch (exception: CompletionException) {
        println(exception)
        null
    }
}

suspend fun GuildChatInputCommandInteraction.basicResponse(message: String) {
    respondEphemeral { content = message }
}

class DiscordBot(
    token: String,
    serverId: Long,
    track: String,
    private val luckPerms: LuckPerms,
    private val logger: Logger,
    private val database: Storage,
    private val tokens: Tokens
) {
    // Group 1 is the "Discord" alias, group 2 is the IGN
    private val nicknameRegex = Regex("""(.+?)\[(\w{3,16})\]""")
    private val possibleGroups = luckPerms.trackManager.getTrack(track)!!.groups.map {
        luckPerms.groupManager.getGroup(it)?.displayName?.lowercase() ?: it
    }.toSet()
    lateinit var guild: Guild
    private lateinit var discordApi: Kord
    private lateinit var authSlashCommand: GuildChatInputCommand
    private lateinit var discordSlashCommand: GuildChatInputCommand
    private lateinit var forceSyncSlashCommand: GuildChatInputCommand
    private lateinit var unlinkSlashCommand: GuildChatInputCommand
    private lateinit var whoisSlashCommand: GuildChatInputCommand

    init {
        runAsync {
            discordApi = Kord(token)
            launch {
                discordApi.login {
                    @OptIn(PrivilegedIntent::class)
                    intents += Intent.MessageContent
                }
            }
            guild = discordApi.getGuild(Snowflake(serverId))
            authSlashCommand = createAuthSlashCommand()
            discordSlashCommand = createDiscordSlashCommand()
            forceSyncSlashCommand = createForceSyncSlashCommand()
            unlinkSlashCommand = createUnlinkSlashCommand()
            whoisSlashCommand = createWhoisSlashCommand()
            discordApi.on<GuildChatInputCommandInteractionCreateEvent> {
                if (interaction.guild.id == guild.id) {
                    responseHandler(interaction)
                }
            }
            discordApi.on<MemberJoinEvent> {
                onJoin(this)
            }
        }
    }

    private suspend fun updateRoles() = guild.roles.toList().associateBy { it.name.lowercase() }

    suspend fun clearDiscordUser(discordId: Long) =
        clearDiscordUser(guild.getMember(Snowflake(discordId)))

    private suspend fun clearDiscordUser(discordUser: Member) {
        // The discord roles this user is part of
        val discRoles = discordUser.roles.toList()
        // The user's roles we actually care about
        val currentRoles = discRoles.filter { it.name.lowercase(Locale.getDefault()) in possibleGroups }
        currentRoles.forEach {
            discordUser.removeRole(it.id)
        }
        handleExceptions { discordUser.edit { nickname = null } }
    }

    suspend fun syncUser(user: User, primaryGroup: String = luckPerms.userManager.loadUser(user.uuid).join().primaryGroup) {
        val discordUser = guild.getMember(Snowflake(user.discordId))
        handleExceptions { syncRoles(discordUser, primaryGroup) }
        handleExceptions { syncName(user, discordUser) }
    }

    private suspend fun syncName(user: User, discordUser: Member) {
        val discNickname = discordUser.nickname
        if (discNickname == null) {
            // No nickname present, setting it
            discordUser.edit { nickname = user.name }
            return
        }
        if (discNickname == user.name || discNickname.endsWith(" [${user.name}]")) {
            // Nickname already is set on Discord
            return
        }
        val matchResult = nicknameRegex.find(discNickname)
        val newName = if (matchResult == null) {
            // Nickname is set, but could not match, setting again
            user.name
        } else {
            // Found existing alias, updating IGN using alias
            val existingAlias = matchResult.groupValues[1].trim()
            "$existingAlias [${user.name}]"
        }
        discordUser.edit {
            nickname = newName
        }
    }

    private suspend fun syncRoles(discordUser: Member, primaryGroupName: String) {
        val roles = updateRoles()
        if (!roles.keys.containsAll(possibleGroups)) {
            logger.error("Not all tracked groups appear in Discord. Aborting sync.")
            return
        }
        // The discord Roles this user is part of
        val discRoles = discordUser.roles.toSet().map { it.name.lowercase() }
        // The Roles we actually care about
        val currentRoles = discRoles.intersect(possibleGroups)
        val primaryGroup = luckPerms.groupManager.getGroup(primaryGroupName)!!.let {
            it.displayName ?: it.name
        }.lowercase()
        // The Roles they are in on Discord that they need to be removed from
        val rolesToRemove = currentRoles - primaryGroup
        rolesToRemove.forEach {
            discordUser.removeRole(roles.getValue(it).id)
        }
        if (primaryGroup !in currentRoles) {
            // Add the role corresponding to the user's primary group to the user
            discordUser.addRole(roles.getValue(primaryGroup).id)
        }
    }

    private suspend fun onJoin(event: MemberJoinEvent) {
        if (event.guild.id != guild) return
        val linkedUser = database.getUser(event.member.id.value.toLong()) ?: return
        syncUser(linkedUser)
    }

    private suspend fun responseHandler(interaction: GuildChatInputCommandInteraction) {
        when (interaction.command.rootId) {
            authSlashCommand.id -> doAuthCommand(interaction)
            discordSlashCommand.id -> doDiscordCommand(interaction)
            unlinkSlashCommand.id -> doUnlinkCommand(interaction)
            forceSyncSlashCommand.id -> doForceSyncCommand(interaction)
            whoisSlashCommand.id -> doWhoisCommand(interaction)
        }
    }

    private suspend fun doAuthCommand(interaction: GuildChatInputCommandInteraction) {
        val user = interaction.user
        val existingUser = database.getUser(user.id.value.toLong())
        if (existingUser != null) {
            interaction.basicResponse("You are already linked to ${existingUser.name.discordEscape()} (`${existingUser.uuid}`)")
            return
        }
        val token = interaction.command.strings["code"]!!
        val unlinkedUser = tokens.tryConsume(token) ?: run {
            interaction.basicResponse("Invalid code provided! I do not recognize the token `$token`.")
            return
        }
        val linkedUser = unlinkedUser.linkTo(user.id.value.toLong())
        database.linkUser(linkedUser)
        syncUser(linkedUser)
        interaction.basicResponse("You are now linked to **${linkedUser.name.discordEscape()}** (`${linkedUser.uuid}`)!")
    }

    private suspend fun doDiscordCommand(interaction: GuildChatInputCommandInteraction) {
        interaction.basicResponse("This command needs to be ran ingame. Join `mc.openredstone.org` in Minecraft Java edition.")
    }

    private suspend fun doUnlinkCommand(interaction: GuildChatInputCommandInteraction) {
        database.getUser(interaction.user.id.value.toLong()) ?: run {
            interaction.basicResponse("You are not linked to any Minecraft account!")
            return
        }
        logger.info("Performing unlink for ${interaction.user.effectiveName} (${interaction.user.id})")
        database.unlinkUser(interaction.user.id.value.toLong())
        clearDiscordUser(interaction.user)
        interaction.basicResponse("You are now unlinked. Run `/discord` ingame to link again.")
    }

    private suspend fun doForceSyncCommand(interaction: GuildChatInputCommandInteraction) {
        val user = database.getUser(interaction.user.id.value.toLong()) ?: run {
            interaction.basicResponse("You are not linked to any Minecraft account!")
            return
        }
        logger.info("Performing force-sync for ${interaction.user.effectiveName} (${interaction.user.id})")
        clearDiscordUser(interaction.user)
        syncUser(user)
        interaction.basicResponse("Your Discord has been synced based on your linked user.")
    }

    private suspend fun doWhoisCommand(interaction: GuildChatInputCommandInteraction) {
        val argument = interaction.command.users["user"] ?: run {
            interaction.basicResponse("The user argument is required for this command.")
            return
        }
        val linkedUser = database.getUser(argument.id.value.toLong()) ?: run {
            interaction.basicResponse("That user is not linked.")
            return
        }
        logger.info("Performing whois for ${interaction.user.effectiveName} (${interaction.user.id})")
        syncUser(linkedUser)
        interaction.basicResponse("User <@${argument.id}> is linked to ${linkedUser.name.discordEscape()} (`${linkedUser.uuid}`)")
    }

    private suspend fun createDiscordSlashCommand(): GuildChatInputCommand =
        discordApi.createGuildChatInputCommand(
            guild.id,
            "discord",
            "This needs to be ran ingame."
        )

    private suspend fun createAuthSlashCommand(): GuildChatInputCommand =
        discordApi.createGuildChatInputCommand(
            guild.id,
            "link",
            "Link your Discord account with your Minecraft account!"
        ) {
            string("code", "The code provided from ingame.") { required = true }
        }

    private suspend fun createForceSyncSlashCommand(): GuildChatInputCommand =
        discordApi.createGuildChatInputCommand(
            guild.id,
            "force-sync",
            "Force a sync of your roles and display name with your ingame account."
        )

    private suspend fun createUnlinkSlashCommand(): GuildChatInputCommand =
        discordApi.createGuildChatInputCommand(
            guild.id,
            "unlink",
            "Unlink this Discord account from your Minecraft account!"
        )

    private suspend fun createWhoisSlashCommand(): GuildChatInputCommand =
        discordApi.createGuildChatInputCommand(
            guild.id,
            "whois",
            "Look up the linking information associated with this user. It also syncs the user!"
        ) {
            user("user", "The user to look up") { required = true }
            defaultMemberPermissions = Permissions(Permission.BanMembers)
        }
}
