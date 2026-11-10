package com.jarvis.assistant.automation.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.contacts.ContactInfo
import com.jarvis.assistant.automation.contacts.ContactResolutionResult
import com.jarvis.assistant.automation.contacts.ContactLookup
import com.jarvis.assistant.automation.contacts.ContactChoiceResolver
import com.jarvis.assistant.util.ActivityLauncherHelper
import java.net.URLEncoder

data class PendingWhatsAppDraft(
    val contactName: String,
    val phoneNumber: String?,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

class WhatsAppAssistant(
    private val context: Context,
    private val contactResolver: ContactLookup
) {
    companion object {
        private const val TAG = "WhatsAppAssistant"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
    }

    private var pendingDraft: PendingWhatsAppDraft? = null
    private var disambiguationCandidates: List<ContactInfo>? = null
    private var pendingMessageForChoice: String? = null
    private var pendingRecipientForMessage: String? = null

    fun hasPendingDraft(): Boolean = pendingDraft != null

    fun isAwaitingDisambiguation(): Boolean = !disambiguationCandidates.isNullOrEmpty()

    fun createDraft(targetContact: String, messageText: String): String {
        val cleanContact = targetContact.trim().ifBlank { pendingRecipientForMessage.orEmpty() }
        val cleanMessage = messageText.trim()
        // A new/updated request cannot inherit a send-ready draft from an older recipient.
        pendingDraft = null
        disambiguationCandidates = null
        pendingMessageForChoice = null

        if (cleanContact.isBlank()) {
            return "Who would you like to message on WhatsApp, sir?"
        }
        if (cleanMessage.isBlank()) {
            pendingRecipientForMessage = cleanContact
            return "What message would you like to send to $cleanContact?"
        }
        pendingRecipientForMessage = null

        val resolution = contactResolver.resolveContact(cleanContact)
        when (resolution) {
            is ContactResolutionResult.SingleMatch -> {
                disambiguationCandidates = null
                val contact = resolution.contact
                pendingDraft = PendingWhatsAppDraft(
                    contactName = contact.name,
                    phoneNumber = contact.phoneNumber,
                    message = cleanMessage
                )
                return "Draft ready for ${contact.name}: \"$cleanMessage\". Should I send it?"
            }
            is ContactResolutionResult.MultipleMatches -> {
                disambiguationCandidates = resolution.candidates
                pendingMessageForChoice = cleanMessage
                val names = resolution.candidates.take(3).map { it.name }.joinToString(" and ")
                return "Which $cleanContact? I found $names."
            }
            is ContactResolutionResult.NoMatch -> {
                // If contact was entered as a raw phone number or not found in contacts
                disambiguationCandidates = null
                val isPhoneNumber = cleanContact.replace(Regex("[^0-9+]"), "").length >= 7
                val phone = if (isPhoneNumber) cleanContact.replace(Regex("[^0-9+]"), "") else null
                pendingDraft = PendingWhatsAppDraft(
                    contactName = cleanContact,
                    phoneNumber = phone,
                    message = cleanMessage
                )
                return "Draft ready for $cleanContact: \"$cleanMessage\". Should I send it?"
            }
            is ContactResolutionResult.PermissionDenied -> {
                disambiguationCandidates = null
                pendingDraft = PendingWhatsAppDraft(
                    contactName = cleanContact,
                    phoneNumber = null,
                    message = cleanMessage
                )
                return "Draft ready for $cleanContact. Note: Contacts permission is needed for automatic phone resolution. Should I send it?"
            }
        }
    }

    fun handleDisambiguationChoice(userChoice: String, pendingMessage: String = ""): String {
        val candidates = disambiguationCandidates ?: return "No contact disambiguation in progress."
        val chosen = ContactChoiceResolver.choose(candidates, userChoice)
            ?: return "I couldn't identify a unique contact. Say their full name or the numbered choice; nothing was sent."
        // Call/delete ambiguity must not turn into an unrelated message draft.
        val originalMessage = pendingMessageForChoice
            ?: return "Repeat the call or delete request with the exact contact. No message draft was created."
        val msg = pendingMessage.trim().ifBlank { originalMessage }
        if (msg.isBlank()) return "What message should I write for ${chosen.name}?"
        disambiguationCandidates = null
        pendingMessageForChoice = null
        pendingDraft = PendingWhatsAppDraft(chosen.name, chosen.phoneNumber, msg)
        return "Draft ready for ${chosen.name}: \"$msg\". Should I send it?"
    }

    fun sendMessageDirectly(targetContact: String, messageText: String): String {
        val draftResult = createDraft(targetContact, messageText)
        return if (hasPendingDraft() && !isAwaitingDisambiguation()) {
            confirmAndSend()
        } else {
            draftResult
        }
    }

    fun confirmAndSend(): String {
        if (isAwaitingDisambiguation()) return "Choose the exact contact first. Nothing was sent."
        val draft = pendingDraft ?: return "There is no draft waiting to be sent."
        val message = draft.message
        val phone = draft.phoneNumber

        return try {
            val encodedMessage = URLEncoder.encode(message, "UTF-8")
            val hasExplicitCountryCode = phone?.trim()?.startsWith("+") == true
            val rawDigits = phone?.replace(Regex("[^0-9]"), "") ?: ""
            val cleanPhone = when {
                hasExplicitCountryCode -> rawDigits
                rawDigits.length == 10 -> "91$rawDigits"
                rawDigits.length == 11 && rawDigits.startsWith("0") -> "91${rawDigits.substring(1)}"
                else -> rawDigits
            }

            val targetPkg = if (isPackageInstalled(WHATSAPP_PACKAGE)) {
                WHATSAPP_PACKAGE
            } else if (isPackageInstalled("com.whatsapp.w4b")) {
                "com.whatsapp.w4b"
            } else {
                WHATSAPP_PACKAGE
            }

            val intent = if (cleanPhone.isNotBlank()) {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMessage")).apply {
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            val isA11yActive = JarvisAccessibilityService.isRunning()
            val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (!launched) {
                return "Could not open WhatsApp. The draft for ${draft.contactName} is still saved and no message was sent."
            }
            if (isA11yActive) {
                // Queue automation only after Android accepted the WhatsApp launch.
                JarvisAccessibilityService.triggerWhatsAppSend(message)
            }

            val recipient = draft.contactName
            pendingDraft = null
            if (isA11yActive) {
                "Opened WhatsApp and queued the send action for $recipient. Delivery is not yet verified."
            } else {
                "Opened WhatsApp with your message for $recipient. Tap Send to send it; no message has been sent yet."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WhatsApp message: ${e.message}", e)
            pendingDraft = null
            "Failed to open WhatsApp. Please ensure WhatsApp is installed."
        }
    }

    private fun isPackageInstalled(pkgName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkgName, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    fun cancelDraft(): String {
        val name = pendingDraft?.contactName ?: pendingRecipientForMessage
        val hadChoice = isAwaitingDisambiguation()
        pendingDraft = null
        pendingRecipientForMessage = null
        pendingMessageForChoice = null
        disambiguationCandidates = null
        return if (name != null) {
            "Draft cancelled for $name. I did not send it."
        } else if (hadChoice) {
            "Pending contact choice cancelled. Nothing was sent."
        } else {
            "No active draft to cancel."
        }
    }

    fun makeCall(targetContact: String, isVideo: Boolean): String {
        pendingDraft = null
        pendingMessageForChoice = null
        pendingRecipientForMessage = null
        disambiguationCandidates = null
        val cleanContact = targetContact.trim()
        if (cleanContact.isBlank()) {
            return "Who would you like to call on WhatsApp?"
        }

        val resolution = contactResolver.resolveContact(cleanContact)
        val contact = when (resolution) {
            is ContactResolutionResult.SingleMatch -> resolution.contact
            is ContactResolutionResult.MultipleMatches -> {
                disambiguationCandidates = resolution.candidates
                val names = resolution.candidates.take(3).map { it.name }.joinToString(" and ")
                return "Which $cleanContact? I found $names."
            }
            else -> null
        }

        val callType = if (isVideo) "video call" else "voice call"

        if (contact == null || contact.phoneNumber.isBlank()) {
            return "I couldn't find a phone number for $cleanContact to initiate a WhatsApp $callType."
        }

        val rawDigits = contact.phoneNumber.replace(Regex("[^0-9]"), "")
        val cleanPhone = when {
            rawDigits.length == 10 -> "91$rawDigits"
            rawDigits.length == 11 && rawDigits.startsWith("0") -> "91${rawDigits.substring(1)}"
            else -> rawDigits
        }

        return try {
            val mimeType = if (isVideo) {
                "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
            } else {
                "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
            }

            val callDataUri = getWhatsAppCallDataUri(contact.id, mimeType)
            val intent = if (callDataUri != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(callDataUri, mimeType)
                    setPackage(WHATSAPP_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone")).apply {
                    setPackage(WHATSAPP_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (!launched) {
                "Could not open WhatsApp for the $callType. The call was not started."
            } else {
                if (callDataUri == null && JarvisAccessibilityService.instance != null) {
                    // Queue the UI action only after WhatsApp launch has been accepted.
                    JarvisAccessibilityService.triggerWhatsAppCall(isVideo)
                }
                if (callDataUri != null) {
                "Opened WhatsApp's $callType action for ${contact.name}; connection is not confirmed."
            } else if (JarvisAccessibilityService.instance != null) {
                "Opened WhatsApp and queued the $callType action for ${contact.name}; connection is not confirmed."
                } else {
                    "Opened the WhatsApp chat for ${contact.name}, but the $callType was not started because Accessibility is unavailable."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WhatsApp call: ${e.message}", e)
            "Could not initiate WhatsApp call."
        }
    }

    /**
     * Deletes the most recent message in a WhatsApp chat.
     * - If [targetContact] is given, opens that contact's chat first (via the WhatsApp
     *   deep link, same as sending a message) then arms the accessibility service to
     *   long-press the last bubble and pick the delete option.
     * - If [targetContact] is blank/null, it operates on whichever WhatsApp chat is
     *   already open on screen (e.g. right after sending a message and saying
     *   "delete that message").
     */
    fun deleteLastMessage(targetContact: String?, forEveryone: Boolean): String {
        pendingDraft = null
        pendingMessageForChoice = null
        pendingRecipientForMessage = null
        disambiguationCandidates = null
        val cleanContact = targetContact?.trim().orEmpty()
        val callType = if (forEveryone) "for everyone" else "for me"

        if (cleanContact.isBlank()) {
            return try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                if (JarvisAccessibilityService.instance == null) {
                    return "JARVIS Accessibility is not connected; no WhatsApp message was deleted."
                }
                if (launchIntent == null || !ActivityLauncherHelper.startActivitySafely(context, launchIntent)) {
                    return "Could not open WhatsApp; no message was deleted."
                }
                JarvisAccessibilityService.triggerWhatsAppDelete(forEveryone)
                "Opened the current WhatsApp chat and queued a delete action $callType. Deletion is not yet verified."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete WhatsApp message: ${e.message}", e)
                "Could not open WhatsApp to delete the message."
            }
        }

        val resolution = contactResolver.resolveContact(cleanContact)
        val phoneCandidate = when (resolution) {
            is ContactResolutionResult.SingleMatch -> resolution.contact.phoneNumber
            is ContactResolutionResult.MultipleMatches -> {
                disambiguationCandidates = resolution.candidates
                val names = resolution.candidates.take(3).map { it.name }.joinToString(" and ")
                return "Which $cleanContact? I found $names."
            }
            else -> null
        }

        val rawDigits = (phoneCandidate ?: cleanContact).replace(Regex("[^0-9]"), "")
        if (rawDigits.isBlank()) {
            return "I couldn't find a phone number for $cleanContact to open that chat."
        }
        val cleanPhone = when {
            rawDigits.length == 10 -> "91$rawDigits"
            rawDigits.length == 11 && rawDigits.startsWith("0") -> "91${rawDigits.substring(1)}"
            else -> rawDigits
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone")).apply {
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (!launched) {
                "Could not open the chat with $cleanContact; no message was deleted."
            } else if (JarvisAccessibilityService.instance == null) {
                "Opened the chat with $cleanContact, but JARVIS Accessibility is not connected; no delete action was queued."
            } else {
                JarvisAccessibilityService.triggerWhatsAppDelete(forEveryone)
                "Opened the chat with $cleanContact and queued a delete action $callType. Deletion is not yet verified."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open chat to delete WhatsApp message: ${e.message}", e)
            "Could not open the chat with $cleanContact to delete the message."
        }
    }

    private fun getWhatsAppCallDataUri(contactId: String, mimeType: String): Uri? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, mimeType),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val idIdx = it.getColumnIndex(ContactsContract.Data._ID)
                    if (idIdx >= 0) {
                        val rowId = it.getLong(idIdx)
                        return Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, rowId.toString())
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
