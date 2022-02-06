package orgopenredstonelinkoredb

import com.squareup.sqldelight.ColumnAdapter
import java.util.UUID
import kotlin.Long
import kotlin.String

public data class User(
  public val uuid: UUID,
  public val name: String,
  public val discordId: Long
) {
  public override fun toString(): String = """
  |User [
  |  uuid: $uuid
  |  name: $name
  |  discordId: $discordId
  |]
  """.trimMargin()

  public class Adapter(
    public val uuidAdapter: ColumnAdapter<UUID, String>
  )
}
