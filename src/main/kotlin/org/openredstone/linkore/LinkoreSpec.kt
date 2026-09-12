package org.openredstone.linkore

import com.uchuhimo.konf.ConfigSpec

object LinkoreSpec : ConfigSpec("") {
    object Discord : ConfigSpec() {
        val serverId by optional(1234L)
        val botToken by optional("nouNetwork")
        val track by optional("trackName")
    }
    object Database : ConfigSpec() {
        val username by optional("linkoretest")
        val password by optional("linkoretest")
        val database by optional("linkoretest_1")
        val host by optional("localhost")
        val port by optional(3306)
    }
}
