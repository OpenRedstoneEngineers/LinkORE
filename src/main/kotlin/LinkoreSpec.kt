package linkore

import com.uchuhimo.konf.ConfigSpec

object LinkoreSpec : ConfigSpec("") {
    object discord : ConfigSpec() {
        val serverId by optional(1234L)
        val botToken by optional("nouNetwork")
        val playingMessage by optional("on the ORE \uD83D\uDE0E")
        val track by optional("trackName")
    }
    object database : ConfigSpec() {
        val username by optional("linkoretest")
        val password by optional("linkoretest")
        val database by optional("linkoretest_1")
        val host by optional("localhost")
        val port by optional(3306)
    }
}
