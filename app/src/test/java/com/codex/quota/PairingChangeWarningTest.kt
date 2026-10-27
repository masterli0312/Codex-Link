package com.codex.quota

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PairingChangeWarningTest {
    @Test fun bothLocalesExplainThatChangingTopicClearsEveryPairingAndDraft() {
        fun text(locale: String): String {
            val file = listOf(File("src/main/res/$locale/strings.xml"), File("app/src/main/res/$locale/strings.xml"))
                .first { it.isFile }
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            return (0 until strings.length).map { strings.item(it) }
                .first { it.attributes.getNamedItem("name").nodeValue == "computer_change_service_detail" }.textContent
        }
        val english = text("values")
        val chinese = text("values-zh-rCN")
        assertTrue(english.contains("all computer pairings") && english.contains("local drafts"))
        assertTrue(chinese.contains("所有电脑配对") && chinese.contains("本地草稿"))
    }
}
