package com.codex.quota

import com.codex.quota.notifications.task.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ComposerCatalogTest {
    private fun skill(id: Int, name: String, plugin: Boolean = false) = RemoteSkill(id.toString(16).padStart(64, '0'), name, plugin = plugin)
    @Test fun commonListKeepsCurrentAndProviderDefaultWithoutRenamingModels() {
        val models = (1..8).map { RemoteModelOption("provider/$it", "Model $it", is_default = it == 7) }
        val common = ComposerCatalog.commonModels(models, "provider/8")
        assertEquals(listOf("provider/7", "provider/8", "provider/1", "provider/2", "provider/3", "provider/4"), common.map { it.model })
        assertEquals((1..8).map { "provider/$it" }, models.map { it.model })
        assertEquals(emptyList<RemoteModelOption>(), ComposerCatalog.commonModels(emptyList(), "missing"))
    }
    @Test fun defaultPluginsRemoveDuplicateFormatsAndInternalWorkflows() {
        val available = listOf(skill(1,"docx"), skill(2,"documents:documents",true), skill(3,"pdf"), skill(4,"pdf:pdf",true),
            skill(5,"xlsx"), skill(6,"spreadsheets:Spreadsheets",true), skill(7,"presentations:Presentations",true),
            skill(8,"pptx"), skill(9,"computer-use:computer-use",true), skill(10,"cloud-environment:cloud-environment-runtime",true),
            skill(11,"data-analytics:build-report",true), skill(12,"systematic-debugging"))
        assertEquals(listOf(available[1],available[3],available[5],available[6],available[8]), ComposerCatalog.skills(available))
        assertEquals(12, available.size)
        assertEquals(emptyList<RemoteSkill>(), ComposerCatalog.skills(listOf(available[9],available[10])))
    }
    @Test fun olderModelCatalogIsCompatibleAndDescriptionDoesNotChangeIdentity() {
        val model = Json.decodeFromString<RemoteModelOption>("""{"model":"provider/model","name":"A"}""")
        assertEquals("",model.description); assertFalse(model.is_default)
        assertEquals("provider/model", model.copy(description = "From provider", is_default = true).model)
    }
}
