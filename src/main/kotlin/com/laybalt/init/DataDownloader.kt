package com.laybalt.init

import com.laybalt.LayBaltQOL
import com.laybalt.utils.GsonUtils
import org.slf4j.LoggerFactory

/**
 * Loads the JSON data files bundled inside the mod jar (`/data/<fileName>`).
 *
 * NoammAddons downloaded these files from its own server at startup; LayBalt QOL ships them
 * with the jar instead, so there is no network access, hash check or background update thread.
 */
object DataDownloader {
    private const val DATA_ROOT = "/data/"
    private val LOGGER = LoggerFactory.getLogger("${LayBaltQOL.CONFIG_DIR}-DataDownloader")

    /** Reads `/data/<fileName>` from the mod's classpath resources as text. */
    fun readData(fileName: String): String {
        val path = DATA_ROOT + fileName
        val stream = DataDownloader::class.java.getResourceAsStream(path)
            ?: throw IllegalStateException("Bundled data file not found on classpath: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Decodes the bundled `/data/<fileName>` JSON file into [T]. */
    inline fun <reified T: Any> loadJson(fileName: String): T = GsonUtils.decode<T>(readData(fileName))

    /** Whether `/data/<fileName>` is bundled in the jar. */
    fun exists(fileName: String) = DataDownloader::class.java.getResource(DATA_ROOT + fileName) != null

    init {
        LOGGER.debug("Using bundled data files from classpath {}", DATA_ROOT)
    }
}
