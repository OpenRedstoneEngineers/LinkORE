package org.openredstone.linkore.db

import com.squareup.sqldelight.Transacter
import com.squareup.sqldelight.db.SqlDriver
import org.openredstone.linkore.db.web.newInstance
import org.openredstone.linkore.db.web.schema
import orgopenredstonelinkoredb.User
import orgopenredstonelinkoredb.UserQueries

public interface Database : Transacter {
  public val userQueries: UserQueries

  public companion object {
    public val Schema: SqlDriver.Schema
      get() = Database::class.schema

    public operator fun invoke(driver: SqlDriver, UserAdapter: User.Adapter): Database =
        Database::class.newInstance(driver, UserAdapter)
  }
}
