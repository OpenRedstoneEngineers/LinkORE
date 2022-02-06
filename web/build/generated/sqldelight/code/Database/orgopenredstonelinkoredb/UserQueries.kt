package orgopenredstonelinkoredb

import com.squareup.sqldelight.Query
import com.squareup.sqldelight.Transacter
import java.util.UUID
import kotlin.Any
import kotlin.Long
import kotlin.String
import kotlin.Unit

public interface UserQueries : Transacter {
  public fun <T : Any> userByMinecraftUuid(uuid: UUID, mapper: (
    uuid: UUID,
    name: String,
    discordId: Long
  ) -> T): Query<T>

  public fun userByMinecraftUuid(uuid: UUID): Query<User>

  public fun <T : Any> userByDiscordId(discordId: Long, mapper: (
    uuid: UUID,
    name: String,
    discordId: Long
  ) -> T): Query<T>

  public fun userByDiscordId(discordId: Long): Query<User>

  public fun createUser(User: User): Unit

  public fun updateUserName(name: String, uuid: UUID): Unit

  public fun deleteUser(uuid: UUID): Unit
}
