package org.openredstone.linkore.web
import com.squareup.sqldelight.ColumnAdapter
import com.squareup.sqldelight.db.SqlDriver
import org.openredstone.linkore.db.Database
import orgopenredstonelinkoredb.User
import java.util.*

private fun <A : Any, B> adapter(to: (A) -> B, from: (B) -> A): ColumnAdapter<A, B> = object : ColumnAdapter<A, B> {
    override fun decode(databaseValue: B): A = from(databaseValue)
    override fun encode(value: A): B = to(value)
}

private val uuidAdapter = adapter(UUID::toString, UUID::fromString)

fun createDb(driver: SqlDriver): Database {
    // TODO figure this out (if exists / migrations / whatever)
    Database.Schema.create(driver)
    return Database(driver, UserAdapter = User.Adapter(uuidAdapter = uuidAdapter))
}
