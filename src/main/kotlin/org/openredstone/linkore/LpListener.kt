package org.openredstone.linkore

import com.velocitypowered.api.scheduler.ScheduledTask
import com.velocitypowered.api.scheduler.Scheduler
import net.luckperms.api.LuckPerms
import net.luckperms.api.event.user.UserDataRecalculateEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

fun startLuckPermsListener(
    database: Storage,
    discordBot: DiscordBot,
    plugin: LinkORE,
    scheduler: Scheduler,
    lpApi: LuckPerms
) {
    val userJobs = ConcurrentHashMap<UUID, ScheduledTask>()
    val waitMs = 500L
    fun onUserUpdate(event: UserDataRecalculateEvent) {
        // This will not work when modifying uncached users, but when the user rejoins, this will be fired anyway.
        val uuid = event.user.uniqueId
        val linkedUser = database.getUser(event.user.uniqueId) ?: return
        val task = scheduler
            .buildTask(plugin) { _ ->
                plugin.proxy.getPlayer(event.user.uniqueId).ifPresent {
                    val username = it.username
                    if (linkedUser.name != username) {
                        linkedUser.name = username
                        database.linkUser(linkedUser)
                    }
                }
                handleExceptions { discordBot.syncUser(linkedUser, event.user.primaryGroup).join() }
                userJobs.remove(uuid)
            }
            .delay(waitMs, TimeUnit.MILLISECONDS)
            .schedule()
        userJobs[uuid]?.cancel()
        userJobs[uuid] = task
    }
    lpApi.eventBus.subscribe(plugin, UserDataRecalculateEvent::class.java, ::onUserUpdate)
}
