// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File

/**
 * Saved lists, read entry by entry (`JSONFile.loadList` and `JSONFileRescue` on iOS, build 112; Publit#207).
 * Trips, tickets and saved places used to be all or nothing: one entry the app cannot read (written by a newer
 * version, or damaged) and the store started empty, and its next change wrote that over the saved data. Now
 * such an entry is left out, the others stay, and the data as it was is kept next to the list ("….unreadable").
 */
object SavedLists {
    /** `skipped` entries could not be read; `damaged`: the text is not a list at all. */
    data class Read<T>(val items: List<T>, val skipped: Int = 0, val damaged: Boolean = false) {
        val complete get() = skipped == 0 && !damaged
    }

    fun <T : Any> decode(json: Json, element: KSerializer<T>, text: String): Read<T> {
        runCatching { json.decodeFromString(ListSerializer(element), text) }.getOrNull()?.let { return Read(it) }
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return Read(emptyList(), damaged = true)
        val items = array.mapNotNull { entry -> runCatching { json.decodeFromJsonElement(element, entry) }.getOrNull() }
        return Read(items, skipped = array.size - items.size)
    }

    /** The list in `file`; empty when there is none. Whatever could not be read stays in `copyFile(file)`. */
    fun <T : Any> load(json: Json, element: KSerializer<T>, file: File): List<T> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull()
        val read = text?.let { decode(json, element, it) } ?: Read(emptyList(), damaged = true)
        if (!read.complete) keepCopy(file)
        return read.items
    }

    fun copyFile(file: File) = File(file.path + ".unreadable")

    /** One copy per file, the newest. */
    fun keepCopy(file: File) {
        runCatching { if (file.exists()) file.copyTo(copyFile(file), overwrite = true) }
    }
}
