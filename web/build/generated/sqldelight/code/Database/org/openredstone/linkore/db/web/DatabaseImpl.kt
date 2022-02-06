package org.openredstone.linkore.db.web

import com.squareup.sqldelight.Query
import com.squareup.sqldelight.TransacterImpl
import com.squareup.sqldelight.`internal`.copyOnWriteList
import com.squareup.sqldelight.db.SqlCursor
import com.squareup.sqldelight.db.SqlDriver
import java.util.UUID
import kotlin.Any
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Unit
import kotlin.collections.MutableList
import kotlin.jvm.JvmField
import kotlin.reflect.KClass
import org.openredstone.linkore.db.Database
import orgopenredstonelinkoredb.User
import orgopenredstonelinkoredb.UserQueries

internal val KClass<Database>.schema: SqlDriver.Schema
  get() = DatabaseImpl.Schema

internal fun KClass<Database>.newInstance(driver: SqlDriver, UserAdapter: User.Adapter): Database =
    DatabaseImpl(driver, UserAdapter)

private class DatabaseImpl(
  driver: SqlDriver,
  internal val UserAdapter: User.Adapter
) : TransacterImpl(driver), Database {
  public override val userQueries: UserQueriesImpl = UserQueriesImpl(this, driver)

  public object Schema : SqlDriver.Schema {
    public override val version: Int
      get() = 1

    public override fun create(driver: SqlDriver): Unit {
      driver.execute(null, """
          |CREATE TABLE User(
          |  uuid TEXT NOT NULL PRIMARY KEY,
          |  name TEXT NOT NULL,
          |  discordId INTEGER NOT NULL UNIQUE
          |)
          """.trimMargin(), 0)
    }

    public override fun migrate(
      driver: SqlDriver,
      oldVersion: Int,
      newVersion: Int
    ): Unit {
    }
  }
}

private class UserQueriesImpl(
  private val database: DatabaseImpl,
  private val driver: SqlDriver
) : TransacterImpl(driver), UserQueries {
  internal val userByMinecraftUuid: MutableList<Query<*>> = copyOnWriteList()

  internal val userByDiscordId: MutableList<Query<*>> = copyOnWriteList()

  public override fun <T : Any> userByMinecraftUuid(uuid: UUID, mapper: (
    uuid: UUID,
    name: String,
    discordId: Long
  ) -> T): Query<T> = UserByMinecraftUuidQuery(uuid) { cursor ->
    mapper(
      database.UserAdapter.uuidAdapter.decode(cursor.getString(0)!!),
      cursor.getString(1)!!,
      cursor.getLong(2)!!
    )
  }

  public override fun userByMinecraftUuid(uuid: UUID): Query<User> = userByMinecraftUuid(uuid) {
      uuid_, name, discordId ->
    User(
      uuid_,
      name,
      discordId
    )
  }

  public override fun <T : Any> userByDiscordId(discordId: Long, mapper: (
    uuid: UUID,
    name: String,
    discordId: Long
  ) -> T): Query<T> = UserByDiscordIdQuery(discordId) { cursor ->
    mapper(
      database.UserAdapter.uuidAdapter.decode(cursor.getString(0)!!),
      cursor.getString(1)!!,
      cursor.getLong(2)!!
    )
  }

  public override fun userByDiscordId(discordId: Long): Query<User> = userByDiscordId(discordId) {
      uuid, name, discordId_ ->
    User(
      uuid,
      name,
      discordId_
    )
  }

  public override fun createUser(User: User): Unit {
    driver.execute(-316848618, """INSERT INTO User VALUES (?, ?, ?)""", 3) {
      bindString(1, database.UserAdapter.uuidAdapter.encode(User.uuid))
      bindString(2, User.name)
      bindLong(3, User.discordId)
    }
    notifyQueries(-316848618, {database.userQueries.userByMinecraftUuid +
        database.userQueries.userByDiscordId})
  }

  public override fun updateUserName(name: String, uuid: UUID): Unit {
    driver.execute(-280644466, """UPDATE User SET name = ? WHERE uuid = ?""", 2) {
      bindString(1, name)
      bindString(2, database.UserAdapter.uuidAdapter.encode(uuid))
    }
    notifyQueries(-280644466, {database.userQueries.userByMinecraftUuid +
        database.userQueries.userByDiscordId})
  }

  public override fun deleteUser(uuid: UUID): Unit {
    driver.execute(78527301, """DELETE FROM User WHERE uuid = ?""", 1) {
      bindString(1, database.UserAdapter.uuidAdapter.encode(uuid))
    }
    notifyQueries(78527301, {database.userQueries.userByMinecraftUuid +
        database.userQueries.userByDiscordId})
  }

  private inner class UserByMinecraftUuidQuery<out T : Any>(
    @JvmField
    public val uuid: UUID,
    mapper: (SqlCursor) -> T
  ) : Query<T>(userByMinecraftUuid, mapper) {
    public override fun execute(): SqlCursor = driver.executeQuery(1347439191,
        """SELECT uuid, name, discordId FROM User WHERE uuid = ?""", 1) {
      bindString(1, database.UserAdapter.uuidAdapter.encode(uuid))
    }

    public override fun toString(): String = "user.sq:userByMinecraftUuid"
  }

  private inner class UserByDiscordIdQuery<out T : Any>(
    @JvmField
    public val discordId: Long,
    mapper: (SqlCursor) -> T
  ) : Query<T>(userByDiscordId, mapper) {
    public override fun execute(): SqlCursor = driver.executeQuery(-152358346,
        """SELECT * FROM User WHERE discordId = ?""", 1) {
      bindLong(1, discordId)
    }

    public override fun toString(): String = "user.sq:userByDiscordId"
  }
}
