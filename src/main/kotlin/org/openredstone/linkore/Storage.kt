package org.openredstone.linkore

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object Users : Table("linkore_user") {
    val uuid = varchar("user_uuid", 36).index(isUnique = true)
    val ign = varchar("user_ign", 16)
    val discord_id = long("user_discord_id").index(isUnique = true)
}

data class UnlinkedUser(val name: String, val uuid: UUID)
data class User(var name: String, val uuid: UUID, val discordId: Long)

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

    fun linkUser(user: User): Unit = transaction(db) {
        Users.upsert {
            it[uuid] = user.uuid.toString()
            it[ign] = user.name
            it[discord_id] = user.discordId
        }
    }

    fun unlinkUser(discordId: Long): Unit = transaction(db) {
        Users.deleteWhere { discord_id eq discordId }
    }

    fun getUser(discordId: Long): User? = doGetUser { Users.discord_id eq discordId }
    fun getUser(uuid: UUID): User? = doGetUser { Users.uuid eq uuid.toString() }

    private fun doGetUser(query: SqlExpressionBuilder.() -> Op<Boolean>) = transaction(db) {
        Users.select(query).firstOrNull()?.let(::rowToUser)
    }
    private fun rowToUser(row: ResultRow) = User(
        name = row[Users.ign],
        uuid = UUID.fromString(row[Users.uuid]),
        discordId = row[Users.discord_id]
    )
}
