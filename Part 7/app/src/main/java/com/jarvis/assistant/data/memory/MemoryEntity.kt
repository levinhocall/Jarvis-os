package com.jarvis.assistant.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,
    val key: String,
    val value: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val provenance: String = "EXPLICIT_USER_STATEMENT",
    val sensitivity: String = "PREFERENCE",
    val expiryTimeMs: Long? = null,
    val memoryType: String = TYPE_PERSONAL_FACT,
    val status: String = STATUS_ACTIVE,
    val importance: Float = 0.5f,
    val confidence: Float = 0.8f,
    val sourceConversationId: String? = null,
    val sourceMessageId: String? = null,
    val embeddingJson: String? = null,
    val embeddingModel: String? = null,
    val embeddingDimension: Int = 0,
    val pinned: Boolean = false
) {
    val isExpired: Boolean
        get() = expiryTimeMs != null && System.currentTimeMillis() > expiryTimeMs

    companion object {
        const val CATEGORY_PERSONAL = "Personal"
        const val CATEGORY_PREFERENCES = "Preferences"
        const val CATEGORY_CONTACTS = "Contacts"
        const val CATEGORY_ROUTINES = "Routines"
        const val CATEGORY_NOTES = "Notes"

        const val TYPE_PREFERENCE = "PREFERENCE"
        const val TYPE_PERSONAL_FACT = "PERSONAL_FACT"
        const val TYPE_PROJECT = "PROJECT"
        const val TYPE_DECISION = "DECISION"
        const val TYPE_ONGOING_TASK = "ONGOING_TASK"
        const val TYPE_EPISODIC_EVENT = "EPISODIC_EVENT"

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_SUPERSEDED = "SUPERSEDED"
        const val STATUS_DELETED = "DELETED"

        val ALL_CATEGORIES = listOf(
            CATEGORY_PERSONAL,
            CATEGORY_PREFERENCES,
            CATEGORY_CONTACTS,
            CATEGORY_ROUTINES,
            CATEGORY_NOTES
        )

        val ALL_TYPES = listOf(
            TYPE_PREFERENCE,
            TYPE_PERSONAL_FACT,
            TYPE_PROJECT,
            TYPE_DECISION,
            TYPE_ONGOING_TASK,
            TYPE_EPISODIC_EVENT
        )
    }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
