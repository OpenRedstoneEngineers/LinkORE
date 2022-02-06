package org.openredstone.linkore.api
import org.openredstone.koreutils.messaging.api.Request
import java.util.*

// linking api operations:

// request linking (from proxy)
// - in: uuid, name
// - out: link with a token

// update things (from proxy or discord)

// queries (from anywhere)
// - user by uuid
// - user by discord id
// - user by name?

// admin operations (update or remove linkage)

sealed interface LinkingRequestResponse

data class Success(val link: String) : LinkingRequestResponse
object AlreadyLinked : LinkingRequestResponse

data class RequestLinking(val minecraftUUID: UUID, val minecraftName: String) : Request<LinkingRequestResponse>

data class LinkedUser(
    val minecraftUUID: UUID,
    val minecraftName: String,
    val discordId: Long,
)
