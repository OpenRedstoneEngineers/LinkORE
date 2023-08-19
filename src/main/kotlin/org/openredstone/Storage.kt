package org.openredstone

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object Users : Table("linkore_user") {
    val uuid = varchar("user_uuid", 36).index()
    val ign = varchar("user_ign", 16)
    val discord_id = long("user_discord_id").nullable().index()
    val primaryGroup = varchar("user_primary_group", 32)
}

data class UnlinkedUser(val name: String, val uuid: UUID, val primaryGroup: String)
data class User(val name: String, val uuid: UUID, val primaryGroup: String, val discordId: Long)

class Storage(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    driver: String = "com.mysql.cj.jdbc.Driver"
) {
    private val db = Database.connect(
        "jdbc:mysql://${host}:${port}/${database}",
        driver = driver,
        user = user,
        password = password
    )
    init {
        transaction(this.db) {
            SchemaUtils.create(Users)
        }
    }

    fun linkUser(uuid: UUID, discordId: Long) = transaction(db) {
        Users.update({ Users.uuid eq uuid.toString()}) {
            it[discord_id] = discordId
        }
    }

    fun unlinkUser(discordId: Long) = transaction(db) {
        Users.deleteWhere { discord_id eq discordId }
    }

    fun updatePrimaryGroup(uuid: UUID, primaryGroup: String) = transaction(db) {
        Users.update({ Users.uuid eq uuid.toString()}) {
            it[this.primaryGroup] = primaryGroup
        }
    }

    fun insertUnlinkedUser(unlinkedUser: UnlinkedUser) = transaction(db) {
        if (Users.select { Users.uuid eq unlinkedUser.uuid.toString() }.count() == 0L) {
            Users.insert {
                it[uuid] = unlinkedUser.uuid.toString()
                it[ign] = unlinkedUser.name
                it[primaryGroup] = unlinkedUser.primaryGroup
            }
        } else {
            Users.update({ Users.uuid eq unlinkedUser.uuid.toString()}) {
                it[ign] = unlinkedUser.name
                it[primaryGroup] = unlinkedUser.primaryGroup
            }
        }
    }

    fun getUser(discordId: Long) : User? = transaction(db) {
        Users.select {
            Users.discord_id eq discordId
        }.firstOrNull()?.let {
            User(
                name = it[Users.ign],
                uuid = UUID.fromString(it[Users.uuid]),
                primaryGroup = it[Users.primaryGroup],
                discordId = it[Users.discord_id]!!
            )
        }
    }

    fun getUser(uuid: UUID) : User? = transaction(db) {
        Users.select {
            Users.uuid eq uuid.toString() and Users.discord_id.isNotNull()
        }.firstOrNull()?.let {
            User(
                name = it[Users.ign],
                uuid = UUID.fromString(it[Users.uuid]),
                primaryGroup = it[Users.primaryGroup],
                discordId = it[Users.discord_id]!!
            )
        }
    }
}
