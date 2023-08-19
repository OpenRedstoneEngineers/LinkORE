package linkore

import co.aikar.commands.VelocityCommandManager
import com.google.inject.Inject
import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.yaml
import com.uchuhimo.konf.source.yaml.toYaml
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import commands.Discord
import commands.Linkore
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.slf4j.Logger
import java.io.File
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

fun UnlinkedUser.linkTo(discordId: Long): User = User(
    uuid = uuid,
    name = name,
    primaryGroup = primaryGroup,
    discordId = discordId,
)

class Tokens {
    private data class Token(val user: UnlinkedUser, val createdAt: Instant)
    private val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val tokens = ConcurrentHashMap<String, Token>()
    private val secureRandom = SecureRandom()
    private val length: Int = 6
    private val lifespan: Duration = Duration.ofMinutes(30)

    fun createFor(user: UnlinkedUser): String =
        generateToken().also { tokens[it] = Token(user, createdAt = Instant.now()) }

    fun tryConsume(token: String): UnlinkedUser? = tokens.remove(token)?.takeIf { it.isValid() }?.user

    private fun generateToken(): String {
        return (0 until length).map {
            chars[secureRandom.nextInt(chars.length)]
        }.joinToString("")
    }

    private fun Token.isValid(): Boolean = (createdAt + lifespan) > Instant.now()
}

private const val VERSION = "1.0"

@Plugin(
    id = "linkore",
    name = "LinkORE",
    version = VERSION,
    url = "https://openredstone.org",
    description = "A linking system for Ingame to Discord",
    authors = ["Nickster258", "PaukkuPalikka"],
    dependencies = [Dependency(id = "luckperms")]
)
class LinkORE @Inject constructor(val proxy: ProxyServer, val logger: Logger, @DataDirectory dataFolder: Path) {
    lateinit var luckPerms: LuckPerms
    lateinit var config: Config
    lateinit var discordBot: DiscordBot
    lateinit var database: Storage
    lateinit var lpListener: LpListener
    private val dataFolder = dataFolder.toFile()
    val tokens = Tokens()

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        config = loadConfig()
        luckPerms = LuckPermsProvider.get()
        database = Storage(
            config[LinkoreSpec.database.host],
            config[LinkoreSpec.database.port],
            config[LinkoreSpec.database.database],
            config[LinkoreSpec.database.username],
            config[LinkoreSpec.database.password]
        )
        discordBot = DiscordBot(
            this,
            config[LinkoreSpec.discord.botToken],
            config[LinkoreSpec.discord.serverId],
            config[LinkoreSpec.discord.playingMessage],
            config[LinkoreSpec.discord.track]
        )
        lpListener = LpListener(this, luckPerms)
        VelocityCommandManager(proxy, this).apply {
            registerCommand(Linkore(this@LinkORE))
            registerCommand(Discord(this@LinkORE))
        }
        logger.info("Loaded LinkORE!!!")
    }

    private fun loadConfig(reloaded: Boolean = false): Config {
        if (!dataFolder.exists()) {
            logger.info("No resource directory found, creating directory")
            dataFolder.mkdir()
        }
        val configFile = File(dataFolder, "config.yml")
        val loadedConfig = if (!configFile.exists()) {
            logger.info("No config file found, generating from default config.yml")
            configFile.createNewFile()
            Config { addSpec(LinkoreSpec) }
        } else {
            Config { addSpec(LinkoreSpec) }.from.yaml.watchFile(configFile)
        }
        loadedConfig.toYaml.toFile(configFile)
        logger.info("${if (reloaded) "Rel" else "L"}oaded config.yml")
        return loadedConfig
    }

    fun getVersion(): String {
        return VERSION
    }
}
