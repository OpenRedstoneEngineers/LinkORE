package org.openredstone

import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import net.luckperms.api.LuckPerms
import net.luckperms.api.event.user.UserDataRecalculateEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class LpListener(
    private val linkore: LinkORE,
    lpApi: LuckPerms
) {
    @OptIn(DelicateCoroutinesApi::class)
    private val scope = CoroutineScope(newSingleThreadContext("kontext"))
    private val userJobs = ConcurrentHashMap<UUID, Job>()
    private val waitMs = 500L

    init {
        lpApi.eventBus.subscribe(linkore, UserDataRecalculateEvent::class.java, this::onUserUpdate)
    }

    private fun onUserUpdate(event: UserDataRecalculateEvent) = scope.launch {
        // This will not work when modifying uncached users, but when the user rejoins, this will be fired anyway.
        val uuid = event.user.uniqueId
        userJobs[uuid]?.cancel()
        userJobs[uuid] = scope.launch {
            delay(waitMs)
            linkore.database.updatePrimaryGroup(uuid, event.user.primaryGroup)
            val linkedUser = linkore.database.getUser(event.user.uniqueId) ?: return@launch
            linkore.discordBot.syncUser(linkedUser)
            userJobs.remove(uuid)
        }
    }
}
