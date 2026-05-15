package kr.toxicity.healthbar.manager

import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import kr.toxicity.healthbar.configuration.PluginConfiguration
import kr.toxicity.healthbar.api.manager.TextManager
import kr.toxicity.healthbar.api.text.HealthBarText
import kr.toxicity.healthbar.api.text.TextBitmap
import kr.toxicity.healthbar.pack.PackResource
import kr.toxicity.healthbar.text.HealthBarTextImpl
import kr.toxicity.healthbar.util.*
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object TextManagerImpl : TextManager, BetterHealthBerManager {

    private val frc = FontRenderContext(null, true, true)
    private const val SPLIT_SIZE = 16

    private lateinit var default: HealthBarTextImpl
    private val textMap = ConcurrentHashMap<String, HealthBarTextImpl>()

    override fun text(name: String): HealthBarText? = textMap[name]

    override fun start() {
        val charWidth = HashMap<Int, Int>()
        PLUGIN.getResource("width.txt")?.let {
            InputStreamReader(it, StandardCharsets.UTF_8).buffered().use { reader ->
                reader.readLines().forEach { line ->
                    val split = line.split(':')
                    if (split.size < 2) return@forEach
                    runCatching {
                        charWidth[split[0].toInt(16)] = split[1].toInt()
                    }
                }
            }
        }
        default = HealthBarTextImpl(
            "default",
            Collections.unmodifiableMap(charWidth),
            emptyList(),
            12
        )
    }

    override fun reload(resource: PackResource) {
        textMap.clear()
        textMap["default"] = default
        val fonts = resource.dataFolder.subFolder("fonts")
        val assets = resource.dataFolder.subFolder("assets")
        val defaultFontConfig = PluginConfiguration.FONT.create()
        loadDefaultFont(fonts, defaultFontConfig)
        resource.dataFolder.subFolder("texts").forEachAllYaml { file, s, configurationSection ->
            runWithHandleException("Unable to read this text: $s in ${file.path}") {
                val parse = when (configurationSection.getString("type")?.lowercase() ?: "ttf") {
                    "ttf" -> parseTTF(
                        file.path,
                        fonts,
                        configurationSection,
                        configurationSection.getBoolean("merge-default-bitmap", false)
                    )
                    "bitmap" -> parseBitmap(file.path, assets, configurationSection)
                    else -> throw RuntimeException("Unsupported text type: ${configurationSection.getString("type")}")
                }
                textMap.putSync("text", s) {
                    parse
                }
            }
        }
    }

    private class CharImage(
        val char: Int,
        val image: BufferedImage
    ): Comparable<CharImage> {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CharImage

            return char == other.char
        }

        override fun hashCode(): Int {
            return char.hashCode()
        }

        override fun compareTo(other: CharImage): Int {
            return char.compareTo(other.char)
        }
    }

    private fun parseFont(path: String, font: Font): HealthBarTextImpl {
        val imageMap = TreeMap<Int, MutableSet<CharImage>>()
        val charWidth = HashMap<Int, Int>()

        fun register(char: Int, image: BufferedImage) {
            synchronized(imageMap) {
                imageMap.computeIfAbsent(image.width) {
                    TreeSet()
                }.add(CharImage(char, image))
            }
            synchronized(charWidth) {
                charWidth[char] = image.width
            }
        }

        val height = (font.size.toDouble() * 1.4).roundToInt()

        (0..0x10FFFF).filter {
            font.canDisplay(it)
        }.forEachAsync {
            BufferedImage(font.size, height, BufferedImage.TYPE_INT_ARGB).apply {
                createGraphics().run {
                    fill(font.createGlyphVector(frc, it.parseChar()).getOutline(0F, font.size.toFloat()))
                    dispose()
                }
            }.removeEmptyWidth()?.let { image ->
                register(it, image.image)
            }
        }

        val bitMapList = ArrayList<TextBitmap>()
        imageMap.forEach {
            fun save(image: List<CharImage>) {
                val array = JsonArray()
                val sb = StringBuilder()
                val target = BufferedImage(it.key * image.size.coerceAtMost(SPLIT_SIZE), height * (((image.size - 1) / SPLIT_SIZE).coerceAtLeast(0) + 1), BufferedImage.TYPE_INT_ARGB)
                target.createGraphics().run {
                    image.forEachIndexed { index, charImage ->
                        drawImage(charImage.image, it.key * (index % SPLIT_SIZE), height * (index / SPLIT_SIZE), null)
                        sb.appendCodePoint(charImage.char)
                        if ((index + 1) % SPLIT_SIZE == 0) {
                            array.add(JsonPrimitive(sb.toString()))
                            sb.setLength(0)
                        }
                    }
                    dispose()
                }
                if (sb.isNotEmpty()) {
                    array.add(JsonPrimitive(sb.toString()))
                    sb.setLength(0)
                }
                synchronized(bitMapList) {
                    bitMapList.add(TextBitmap(target, array, 0))
                }
            }
            it.value.toList().split(SPLIT_SIZE * SPLIT_SIZE).forEachAsync { list ->
                if (list.size % SPLIT_SIZE == 0 || list.size < SPLIT_SIZE) {
                    save(list)
                } else {
                    val sub = list.split(SPLIT_SIZE)
                    save(sub.subList(0, sub.lastIndex).flatten())
                    save(sub.last())
                }
            }
        }
        return HealthBarTextImpl(
            path,
            Collections.unmodifiableMap(charWidth),
            Collections.unmodifiableList(bitMapList),
            height
        )
    }

    private fun parseTTF(
        path: String,
        fonts: File,
        section: org.bukkit.configuration.ConfigurationSection,
        mergeDefaultBitmap: Boolean
    ): HealthBarTextImpl {
        val file = File(fonts, section.getString("file")
            .ifNull { "Unable to find 'file' configuration." }
            .replace('/', File.separatorChar))
            .apply {
                if (!exists()) throw RuntimeException("Unable to find this font: $path")
            }
        val font = runCatching {
            Font.createFont(Font.TRUETYPE_FONT, file)
        }.getOrElse {
            throw RuntimeException("Unable to load this font: ${file.path}", it)
        }.deriveFont(section.getInt("scale", 16).coerceAtLeast(1).toFloat())
        val parsed = parseFont(path, font)
        if (!mergeDefaultBitmap || path == "default") return parsed

        val mergedWidth = HashMap<Int, Int>(parsed.chatWidth())
        default.chatWidth().forEach { (codepoint, width) ->
            mergedWidth.putIfAbsent(codepoint, width)
        }

        return HealthBarTextImpl(
            path,
            Collections.unmodifiableMap(mergedWidth),
            Collections.unmodifiableList(ArrayList<TextBitmap>().apply {
                addAll(parsed.bitmap())
                addAll(default.bitmap())
            }),
            parsed.height()
        )
    }

    private fun parseBitmap(path: String, assets: File, section: org.bukkit.configuration.ConfigurationSection): HealthBarTextImpl {
        val charsSection = section.getConfigurationSection("chars")
            .ifNull { "Unable to find 'chars' configuration." }
        val charWidth = HashMap<Int, Int>()
        val bitmaps = ArrayList<TextBitmap>()
        var maxHeight = 0
        charsSection.getKeys(false).forEach { key ->
            val charConfig = charsSection.getConfigurationSection(key)
                .ifNull { "Invalid char config: $key" }
            val codepointRows = charConfig.getStringList("codepoints").ifEmpty {
                throw RuntimeException("Codepoints value not set: $key")
            }
            val relativeFile = charConfig.getString("file")
                .ifNull { "File value not set: $key" }
                .replace('/', File.separatorChar)
            val image = File(assets, relativeFile)
                .apply { if (!exists()) throw RuntimeException("Unable to find this asset file: $relativeFile") }
                .toImage()
            if (image.height % codepointRows.size != 0) {
                throw RuntimeException("Image height ${image.height} cannot be divided by ${codepointRows.size}: $relativeFile")
            }
            val rowCodepoints = codepointRows.map { row ->
                row.codePoints().toArray().apply {
                    if (isEmpty()) throw RuntimeException("Codepoint is empty: $key")
                    if (image.width % size != 0) throw RuntimeException("Image width ${image.width} cannot be divided by $size: $relativeFile")
                }
            }
            val ascent = charConfig.getInt("ascent", 0)
            val distinctWidth = rowCodepoints.map { it.size }.distinct()
            if (distinctWidth.size != 1) throw RuntimeException("Codepoint length mismatch in bitmap: $key")
            val glyphColumns = distinctWidth.first()
            val cellWidth = image.width / glyphColumns
            val cellHeight = image.height / codepointRows.size
            maxHeight = maxOf(maxHeight, cellHeight)
            rowCodepoints.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { columnIndex, codepoint ->
                    val glyph = image.getSubimage(columnIndex * cellWidth, rowIndex * cellHeight, cellWidth, cellHeight)
                    charWidth[codepoint] = glyph.removeEmptyWidth()?.let { it.xOffset + it.image.width } ?: 0
                }
            }
            val array = JsonArray().apply {
                codepointRows.forEach { add(JsonPrimitive(it)) }
            }
            bitmaps += TextBitmap(image, array, ascent)
        }
        return HealthBarTextImpl(
            path,
            Collections.unmodifiableMap(charWidth),
            Collections.unmodifiableList(bitmaps),
            maxHeight
        )
    }

    private fun loadDefaultFont(fonts: File, config: org.bukkit.configuration.file.YamlConfiguration) {
        val fileName = config.getString("default-font-name") ?: return
        val file = File(fonts, fileName.replace('/', File.separatorChar))
        if (!file.exists()) return
        val scale = config.getInt("scale", 16).coerceAtLeast(1)
        val font = runCatching {
            Font.createFont(Font.TRUETYPE_FONT, file)
        }.getOrElse {
            throw RuntimeException("Unable to load default font: ${file.path}", it)
        }.deriveFont(scale.toFloat())
        default = parseFont("default", font)
    }
}