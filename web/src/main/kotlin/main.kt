package org.openredstone.linkore.web

import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.jackson.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.javacord.api.DiscordApi
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.event.message.MessageCreateEvent
import org.openredstone.koreutils.messaging.api.Request
import org.openredstone.linkore.api.*
import orgopenredstonelinkoredb.User
import orgopenredstonelinkoredb.UserQueries
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture.allOf
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.jvm.jvmName

data class UnlinkedUser(val name: String, val uuid: UUID)

fun UnlinkedUser.linkTo(discordId: Long): User = User(
    uuid =  uuid,
    name = name,
    discordId = discordId,
)

/**
 * - Thread safe (internally synchronized)
 * - Trivially reloadable by creating a new instance
 * */
class Tokens(private val config: Config) {
    data class Config(
        val length: Int = 8,
        val lifespan: Duration = Duration.ofMinutes(1),
    )
    private data class Token(val user: UnlinkedUser, val createdAt: Instant)
    private val tokens = ConcurrentHashMap<String, Token>()
    // SecureRandom is thread-safe
    private val secureRandom = SecureRandom()

    fun createFor(user: UnlinkedUser): String =
        generateToken().also { tokens[it] = Token(user, createdAt = Instant.now()) }

    fun tryConsume(token: String): UnlinkedUser? = tokens.remove(token)?.takeIf { it.isValid() }?.user

    private fun generateToken(): String {
        val rawToken = ByteArray(config.length)
        secureRandom.nextBytes(rawToken)
        return rawToken.joinToString { it.toString(16) }
    }

    private fun Token.isValid(): Boolean = createdAt + config.lifespan < Instant.now()
}

class Linking(private val tokens: Tokens, private val db: UserQueries) {
    fun RequestLinking.handle(): LinkingRequestResponse {
        // breh
        if (db.userByMinecraftUuid(minecraftUUID).executeAsOneOrNull() != null) {
            return AlreadyLinked
        }
        val token = tokens.createFor(UnlinkedUser(minecraftName, minecraftUUID))
        return Success("make a link with $token")
    }

    fun finishLinking(discordId: Long, token: String): String {
        if (db.userByDiscordId(discordId).executeAsOneOrNull() != null) {
            return "You are already linked to Discord."
        }
        val unlinked = tokens.tryConsume(token) ?: return "Invalid token."
        val user = unlinked.linkTo(discordId)
        db.createUser(user)

//        val primaryGroup = luckPerms.getUserManager().getUser(user.uuid).getPrimaryGroup()
//        discordOperations.setTrackedDiscordGroup(
//            user.discordId,
//            luckPerms.getGroupManager().getGroup(primaryGroup).getDisplayName()
//        )
//        discordOperations.setNickname(discordId, user.name)

        return "You are now linked."
    }
}

class DiscordConfig(
    val token: String,
    val activity: String = "Linking yORE DiscOREd and MinecOREft accounts",
    val commandPrefix: String = "!",
)

fun main() {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    val db = createDb(driver)
    val linking = Linking(Tokens(Tokens.Config()), db.userQueries)
    val discordConfig = DiscordConfig(token = "blablo")
    val discordBot = DiscordApiBuilder().setToken(discordConfig.token).login().join()
    discordBot.updateActivity(discordConfig.activity)

    val discordDispatcher = Dispatchers.Default
    fun messageCreated(event: MessageCreateEvent): String {
        val rawMessage = event.messageContent
        val splat = rawMessage.split(" ")
        if (splat.firstOrNull() != "${discordConfig.commandPrefix}auth") {
            return "Invalid command."
        }
        val token = splat.getOrNull(1) ?: return "This command requires exactly one argument."
        return linking.finishLinking(event.messageAuthor.id, token)
    }

    // auth command
    discordBot.addMessageCreateListener { event ->
        CoroutineScope(discordDispatcher).launch {
            val response = messageCreated(event)
            val message = event.channel.sendMessage(response).await()
            delay(5_000)
            // so like apparently java cord does not like this because you can get conection eror and then boom
            allOf(event.message.delete(), message.delete()).await()
        }
    }

    embeddedServer(Netty, port = 8080) {
        install(DataConversion)
        install(ContentNegotiation) {
            jackson()
        }
        routing {
            with(linking) {
                request { request: RequestLinking -> request.handle() }
            }
        }
    }.start(wait = true)
}

private inline fun <reified Req : Request<Resp>, reified Resp : Any> Routing.request(crossinline handle: suspend (Req) -> Resp) {
    post("/${Req::class.jvmName}") {
        call.respond(handle(call.receive()))
    }
}
