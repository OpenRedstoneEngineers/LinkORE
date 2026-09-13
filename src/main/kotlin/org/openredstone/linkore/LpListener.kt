package org.openredstone.linkore

import com.velocitypowered.api.scheduler.Scheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.luckperms.api.LuckPerms
import net.luckperms.api.event.user.UserDataRecalculateEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

fun startLuckPermsListener(
    database: Storage,
    discordBot: DiscordBot,
    linkore: LinkORE,
    scheduler: Scheduler,
    lpApi: LuckPerms
) {
    val userJobs = ConcurrentHashMap<UUID, Job>()
    val waitMs = 500L
    fun onUserUpdate(event: UserDataRecalculateEvent) {
        // This will not work when modifying uncached users, but when the user rejoins, this will be fired anyway.
        val uuid = event.user.uniqueId
        val linkedUser = database.getUser(event.user.uniqueId) ?: return
        userJobs[uuid]?.cancel()
        userJobs[uuid] = linkore.scope.launch {
            // To debounce the event, as it can get triggered multiple times in quick succession
            delay(waitMs.milliseconds)
            linkore.proxy.getPlayer(event.user.uniqueId).ifPresent {
                val username = it.username
                if (linkedUser.name != username) {
                    linkedUser.name = username
                    database.linkUser(linkedUser)
                }
            }
            linkore.logger.info("Initiating LP sync of ${linkedUser.name} (${linkedUser.uuid})")
            discordBot.syncUser(linkedUser, event.user.primaryGroup)
            userJobs.remove(uuid)
        }
    }
    lpApi.eventBus.subscribe(linkore, UserDataRecalculateEvent::class.java, ::onUserUpdate)
}
