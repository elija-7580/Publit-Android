package io.github.elija7580.publit.tickets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** Passes and their original documents, on the device only (PassStore on iOS). */
class PassStore(dir: File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val ser = ListSerializer(TicketPass.serializer())
    private val file = File(dir, "passes.json")
    val documentDirectory = File(dir, "passes").apply { mkdirs() }
    private val _passes = MutableStateFlow(runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList()))
    val passes = _passes.asStateFlow()

    class TooLarge : Exception(io.github.elija7580.publit.core.L10n.t("This file is too large for a ticket."))

    fun pass(id: String) = _passes.value.firstOrNull { it.id == id }
    fun documentFile(p: TicketPass) = p.document?.let { File(documentDirectory, it.file) }
    fun logoFile(p: TicketPass) = p.logo?.let { File(documentDirectory, it.file) }

    fun grouped(at: Instant = Instant.now()) = PassGroup.grouped(_passes.value, at)
    /** Expired a day ago or longer, or moved there by hand. Newest first. */
    fun archived(at: Instant = Instant.now()) = _passes.value.filter { it.isArchived(at) }
        .sortedWith(compareByDescending<TicketPass> { it.archiveDate }.thenByDescending { it.createdAt })

    fun archive(id: String) = pass(id)?.let { update(it.copy(archivedAt = Instant.now(), keptOut = null)) }
    /** Back into the list; an expired ticket then stays there until archived again. */
    fun unarchive(id: String) = pass(id)?.let { p ->
        update(p.copy(archivedAt = null, keptOut = if (p.validity() is TicketPass.Validity.Expired) true else null))
    }

    /** Deletes every archived ticket with its original. Returns how many. */
    fun removeArchived(): Int {
        val gone = archived()
        gone.forEach { p -> documentFile(p)?.delete(); logoFile(p)?.delete() }
        val ids = gone.map { it.id }.toSet()
        set(_passes.value.filter { it.id !in ids })
        return gone.size
    }

    /**
     * What a trip offers: every ticket added for it (oldest first), then tickets valid at the trip's time:
     * passes, and dated tickets that belong to no trip.
     */
    fun forTrip(tripId: String?, at: Instant = Instant.now()): List<TicketPass> {
        val own = tripId?.let { id -> _passes.value.filter { it.tripId == id }.sortedBy { it.createdAt } }.orEmpty()
        val valid = _passes.value.filter { p ->
            p !in own && p.archivedAt == null && (p.category.isPass || p.tripId == null) && p.validity(at) is TicketPass.Validity.Valid
        }
        return own + valid
    }

    /** Saves a checked pass with its original. A document already saved (same file and page) is not added again. */
    fun add(pass: TicketPass, document: ByteArray? = null, kind: TicketPass.Document.Kind? = null, page: Int = 0, deduplicate: Boolean = false): TicketPass {
        var p = pass
        if (document != null && kind != null) {
            if (document.size > MAX_DOCUMENT_BYTES) throw TooLarge()
            val fp = TicketImportPolicy.fingerprint(document, page)
            if (deduplicate) _passes.value.firstOrNull { it.document?.fingerprint == fp }?.let { return it }
            val name = p.id + if (kind == TicketPass.Document.Kind.PDF) ".pdf" else ".img"
            File(documentDirectory, name).writeBytes(document)
            p = p.copy(document = TicketPass.Document(name, kind, page, fp))
        }
        set(_passes.value.filter { it.id != p.id } + p)
        return p
    }

    fun update(p: TicketPass) = set(_passes.value.map { if (it.id == p.id) p.copy(updatedAt = Instant.now()) else it })

    fun remove(id: String) {
        pass(id)?.let { p -> documentFile(p)?.delete(); logoFile(p)?.delete() }
        set(_passes.value.filter { it.id != id })
    }

    private fun set(list: List<TicketPass>) {
        _passes.value = list
        runCatching { val tmp = File(file.path + ".tmp"); tmp.writeText(json.encodeToString(ser, list)); tmp.renameTo(file) }
    }

    companion object { const val MAX_DOCUMENT_BYTES = 25 * 1024 * 1024 }
}
