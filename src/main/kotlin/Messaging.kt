package linkore

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

fun Player.sendDeserialized(statement: String) {
    this.sendDeserialized(Component.text(statement))
}

fun Player.sendDeserialized(statement: Component) {
    val component = Component.text("", NamedTextColor.GRAY)
        .append(Component.text("[", NamedTextColor.DARK_GRAY))
        .append(Component.text("LinkORE", NamedTextColor.GRAY))
        .append(Component.text("] ", NamedTextColor.DARK_GRAY))
        .append(statement)
    this.sendMessage(component)
}
