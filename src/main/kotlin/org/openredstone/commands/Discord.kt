package org.openredstone.commands

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Default
import co.aikar.commands.annotation.Subcommand
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.luckperms.api.LuckPerms
import org.openredstone.*

@CommandAlias("discord")
@CommandPermission("linkore.discord")
class Discord(
    private val luckPerms: LuckPerms,
    private val database: Storage,
    private val discordBot: DiscordBot,
    private val tokens: Tokens
) : BaseCommand() {
    @Default
    @Subcommand("link")
    fun link(player: Player) {
        val existingUser = database.getUser(player.uniqueId)
        if (existingUser != null) {
            player.sendDeserialized("You are already linked!")
            return
        }
        val lpUser = luckPerms.userManager.getUser(player.uniqueId) ?: return
        val unlinkedUser = UnlinkedUser(player.username, player.uniqueId, lpUser.primaryGroup)
        println(unlinkedUser)
        val token = tokens.createFor(unlinkedUser)
        database.insertUnlinkedUser(unlinkedUser)
        player.sendDeserialized(
            Component.text("Your token is ")
                .append(
                    Component.text(token, NamedTextColor.WHITE)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to copy")))
                        .clickEvent(ClickEvent.copyToClipboard(token))
                )
                .append(Component.text(". Run "))
                .append(Component.text("/auth $token", NamedTextColor.WHITE))
                .append(Component.text(" on Discord to finish linking"))
        )
    }
    @Subcommand("unlink")
    fun unlink(player: Player) {
        val existingUser = database.getUser(player.uniqueId) ?: run {
            player.sendDeserialized("You are not currently linked.")
            return
        }
        discordBot.unlinkUser(existingUser.discordId)
        database.unlinkUser(existingUser.discordId)
        player.sendDeserialized("You should now be unlinked.")
    }
}
