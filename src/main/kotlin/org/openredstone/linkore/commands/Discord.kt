package org.openredstone.linkore.commands

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
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.openredstone.linkore.*

@CommandAlias("discord")
@CommandPermission("linkore.discord")
class Discord(
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
        val unlinkedUser = UnlinkedUser(player.username, player.uniqueId)
        val token = tokens.createFor(unlinkedUser)
        player.sendDeserialized(
            Component.text("You can join the DiscOREd by clicking ")
                .append(
                    Component.text("[HERE]", NamedTextColor.WHITE)
                        .hoverEvent(HoverEvent.showText(Component.text("Go to DiscOREd")))
                        .clickEvent(ClickEvent.openUrl("https://openredstone.org/discord"))
                )
        )
        player.sendDeserialized("To access all of the channels in the DiscOREd, you'll need to link your account.")
        player.sendDeserialized(
            Component.text("[", NamedTextColor.WHITE)
                .append(Component.text("(•_•)", TextColor.fromHexString("#5865f2")))
                .append(Component.text("]", NamedTextColor.WHITE))
                .append(Component.text(" In ", NamedTextColor.GRAY))
                .append(Component.text("Discord", TextColor.fromHexString("#5865f2")))
                .append(Component.text(", run ", NamedTextColor.GRAY))
                .append(Component.text("/link ", NamedTextColor.WHITE))
                .append(
                    Component.text(token, NamedTextColor.WHITE)
                        .hoverEvent(HoverEvent.showText(Component.text("Click to copy")))
                        .clickEvent(ClickEvent.copyToClipboard(token))
                )
                .append(Component.text(" to link your account.", NamedTextColor.GRAY))
        )
        player.sendDeserialized(Component.text("Your link code is secret. Do not share it with anyone.")
            .decorate(TextDecoration.ITALIC, TextDecoration.BOLD))
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
