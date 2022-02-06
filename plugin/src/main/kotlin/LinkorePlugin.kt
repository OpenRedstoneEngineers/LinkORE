// This is very experiment. Fear

import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.features.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.platform.bungeecord.BungeeAudiences
import net.kyori.adventure.text.minimessage.MiniMessage
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Plugin
import org.openredstone.koreutils.bungee.dispatcher
import org.openredstone.koreutils.messaging.impl.MessagingScope
import org.openredstone.linkore.api.*
import java.lang.Exception
import kotlin.reflect.KClass

interface SenderAware {
    fun <T : CommandSender> sender(sender: KClass<T>): T
    class Impl(private val commandSender: CommandSender) : SenderAware {
        @Suppress("UNCHECKED_CAST")
        override fun <T : CommandSender> sender(sender: KClass<T>): T =
            (commandSender.takeIf(sender::isInstance) ?: throw Exception("Can only execute as a ${sender.simpleName}")) as T
    }
}

inline fun <reified T : CommandSender> SenderAware.sender(): T = sender(T::class)

interface PlayerAudience {
    fun ProxiedPlayer.asAudience(): Audience
    class Impl(private val audiences: BungeeAudiences) : PlayerAudience {
        override fun ProxiedPlayer.asAudience(): Audience = audiences.player(this)
    }
}

interface HasConfig {
    val config: Config
    class Impl(override val config: Config) : HasConfig
}

class CommandScope(
    http: HttpClient, url: String,
    private val sa: SenderAware,
    private val pa: PlayerAudience,
    private val hc: HasConfig,
) : MessagingScope(http, url), SenderAware by sa, PlayerAudience by pa, HasConfig by hc


suspend fun <R> R.discordCommand() where R : MessagingScope, R : SenderAware, R : PlayerAudience, R : HasConfig {
    val player = sender<ProxiedPlayer>()
    val response = RequestLinking(minecraftName = player.name, minecraftUUID = player.uniqueId)
        .call()
    val chat = when (response) {
        AlreadyLinked -> "You are already linked."
        is Success -> """
            To finish the linking process, please visit the following URL:
            <click:open_url:${response.link}>${response.link}</click>
            lol actually just paste <red>!auth ${response.link}</red> anywhere on our Discord
            """.trimIndent()
    }
    val mm = MiniMessage.get()
    player.asAudience().sendMessage(mm.parse(chat))
}

fun Plugin.command(name: String, permission: String, config: Config, execute: suspend CommandScope.() -> Unit) {
    val audiences = BungeeAudiences.create(this)
    val http = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = JacksonSerializer()
        }
    }
    val cmd = object : Command(name, permission) {
        override fun execute(sender: CommandSender, args: Array<out String>) {
            CoroutineScope(dispatcher).launch {
                CommandScope(
                    http,
                    config.linkoreWebUrl,
                    SenderAware.Impl(sender),
                    PlayerAudience.Impl(audiences),
                    HasConfig.Impl(config),
                ).execute()
            }
        }
    }
    proxy.pluginManager.registerCommand(this, cmd)
}

data class Config(
    val linkoreWebUrl: String,
)

class LinkorePlugin : Plugin() {
    override fun onEnable() {
        val config = Config("ha")
        command("discord", "linkore.discord", config) {
            discordCommand()
        }
    }
}
