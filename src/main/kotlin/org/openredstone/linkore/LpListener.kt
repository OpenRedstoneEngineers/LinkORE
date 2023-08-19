package org.openredstone.linkore

import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import net.luckperms.api.LuckPerms
import net.luckperms.api.event.user.UserDataRecalculateEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

fun startLuckPermsListener(
    database: Storage,
    discordBot: DiscordBot,
    plugin: Any,
    lpApi: LuckPerms
) {
    @OptIn(DelicateCoroutinesApi::class)
    val scope = CoroutineScope(newSingleThreadContext("luckPermsListenerContext"))
    val userJobs = ConcurrentHashMap<UUID, Job>()
    val waitMs = 500L
    fun onUserUpdate(event: UserDataRecalculateEvent) = scope.launch {
        // This will not work when modifying uncached users, but when the user rejoins, this will be fired anyway.
        val uuid = event.user.uniqueId
        userJobs[uuid]?.cancel()
        userJobs[uuid] = scope.launch waitAndSync@{
            delay(waitMs)
            val linkedUser = database.getUser(event.user.uniqueId) ?: return@waitAndSync
            discordBot.syncUser(linkedUser)
            userJobs.remove(uuid)
        }
    }
    lpApi.eventBus.subscribe(plugin, UserDataRecalculateEvent::class.java, ::onUserUpdate)
}
