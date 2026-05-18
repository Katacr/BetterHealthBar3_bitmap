package kr.toxicity.healthbar.manager

import kr.toxicity.healthbar.api.compatibility.MythicActiveMob
import kr.toxicity.healthbar.api.event.HealthBarCreateEvent
import kr.toxicity.healthbar.api.manager.PlaceholderManager
import kr.toxicity.healthbar.api.placeholder.PlaceholderContainer
import kr.toxicity.healthbar.pack.PackResource
import kr.toxicity.healthbar.util.*
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Player
import java.util.function.Function

object PlaceholderManagerImpl : PlaceholderManager, BetterHealthBerManager {

    private var customNameMap: Map<String, String> = emptyMap()
    private val plainSerializer = PlainTextComponentSerializer.plainText()

    override fun start() {
        PlaceholderContainer.NUMBER.run {
            addPlaceholder("health") { e: HealthBarCreateEvent ->
                e.entity.entity().health
            }
            addPlaceholder("max_health") { e: HealthBarCreateEvent ->
                e.entity.entity().getAttribute(ATTRIBUTE_MAX_HEALTH)!!.value
            }
            addPlaceholder("health_percentage") { e: HealthBarCreateEvent ->
                e.entity.entity().health / e.entity.entity().getAttribute(ATTRIBUTE_MAX_HEALTH)!!.value
            }
            addPlaceholder("absorption") { e: HealthBarCreateEvent ->
                e.entity.entity().absorptionAmount
            }
            addPlaceholder("armor") { e: HealthBarCreateEvent ->
                e.entity.entity().armor
            }
        }
        PlaceholderContainer.STRING.run {
            addPlaceholder("entity_type") { e: HealthBarCreateEvent ->
                e.entity.entity().type.toString().lowercase()
            }
            addPlaceholder("entity_name") { e: HealthBarCreateEvent ->
                val entity = e.entity.entity()
                entity.customName()?.let { plainSerializer.serialize(it) }
                    ?: customNameMap[entity.type.name]
                    ?: entity.name
            }
        }
        PlaceholderContainer.BOOL.run {
            addPlaceholder("has_potion_effect", placeholder(1) {
                Registry.EFFECT.get(NamespacedKey.minecraft(it[0]))?.let { type ->
                    Function { pair: HealthBarCreateEvent ->
                        pair.entity.entity().hasPotionEffect(type)
                    }
                } ?: run {
                    warn("Unable to find this potion effect: ${it[0]}")
                    Function { _: HealthBarCreateEvent ->
                        false
                    }
                }
            })
            addPlaceholder("is_player") { e: HealthBarCreateEvent ->
                e.entity.entity() is Player
            }
            addPlaceholder("is_mythic_mob") { e: HealthBarCreateEvent ->
                e.entity.mob() is MythicActiveMob
            }
            addPlaceholder("is_vanilla_mob") { e: HealthBarCreateEvent ->
                e.entity.mob() == null
            }
        }
    }

    override fun reload(resource: PackResource) {
        val file = java.io.File(DATA_FOLDER, "custom-name.yml")
        if (!file.exists()) {
            PLUGIN.saveResource("custom-name.yml", false)
        }
        val yaml = file.toYaml()
        val entitySection = yaml.getConfigurationSection("Entity")
        customNameMap = if (entitySection != null) {
            HashMap<String, String>().apply {
                entitySection.getKeys(false).forEach { key ->
                    entitySection.getString(key)?.let { value ->
                        put(key.uppercase(), value)
                    }
                }
            }
        } else {
            emptyMap()
        }
    }
}