package com.jarvis.assistant.automation.command

import android.util.Log
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import com.jarvis.assistant.automation.youtube.YouTubeAutomationManager
import com.jarvis.assistant.data.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandExecutor(
    private val phoneManager: PhoneControlManager,
    private val youtubeManager: YouTubeAutomationManager,
    private val whatsappAssistant: WhatsAppAssistant,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    suspend fun execute(command: JarvisCommand): String = withContext(Dispatchers.Main) {
        // Log only the command type; command data may contain PINs, messages, or other private input.
        Log.i(TAG, "Executing command type: ${command::class.simpleName ?: "Unknown"}")
        when (command) {
            // YouTube
            is JarvisCommand.PlayYouTube -> {
                val resolvedSong = memoryManager.resolveSongQuery(command.query)
                memoryManager.trackSongPlay(resolvedSong)
                youtubeManager.playVideo(resolvedSong)
            }
            is JarvisCommand.SearchYouTube -> youtubeManager.searchYouTube(command.query)
            is JarvisCommand.PauseMedia -> youtubeManager.pausePlayback()
            is JarvisCommand.ResumeMedia -> youtubeManager.resumePlayback()
            is JarvisCommand.NextMedia -> youtubeManager.nextMedia()
            is JarvisCommand.PreviousMedia -> youtubeManager.previousMedia()
            is JarvisCommand.SubscribeChannel -> youtubeManager.subscribeToChannel()
            is JarvisCommand.ToggleFullscreen -> youtubeManager.toggleFullscreen()

            // Phone Controls
            is JarvisCommand.Flash -> phoneManager.setFlashlight(command.enabled)
            is JarvisCommand.Brightness -> {
                if (command.isRelative) {
                    phoneManager.adjustBrightness(command.delta > 0)
                } else {
                    phoneManager.setBrightnessPercent(command.level)
                }
            }
            is JarvisCommand.Volume -> {
                if (command.isRelative) {
                    phoneManager.adjustVolume(command.delta > 0)
                } else {
                    phoneManager.setVolumePercent(command.level)
                }
            }
            is JarvisCommand.OpenApp -> phoneManager.openApp(command.packageName)
            is JarvisCommand.OpenWebUrls -> phoneManager.openMultipleUrls(command.urls)
            is JarvisCommand.PhoneCall -> phoneManager.makePhoneCall(command.contactOrNumber)
            is JarvisCommand.SearchWeb -> phoneManager.searchWeb(command.query)

            // WhatsApp
            is JarvisCommand.WhatsAppMessage -> whatsappAssistant.sendMessageDirectly(command.contact, command.message)
            is JarvisCommand.WhatsAppCall -> whatsappAssistant.makeCall(command.contact, command.video)
            is JarvisCommand.WhatsAppDeleteMessage -> whatsappAssistant.deleteLastMessage(command.contact, command.forEveryone)
            is JarvisCommand.ConfirmAction -> {
                if (command.confirmed) {
                    whatsappAssistant.confirmAndSend()
                } else {
                    whatsappAssistant.cancelDraft()
                }
            }
            is JarvisCommand.DisambiguationChoice -> whatsappAssistant.handleDisambiguationChoice(command.choice)

            // Memory
            is JarvisCommand.Remember -> memoryManager.rememberFact(command.text)
            is JarvisCommand.Forget -> memoryManager.forgetFact(command.query)
            is JarvisCommand.WhatDoYouRemember -> memoryManager.getWhatDoYouRemember()
            is JarvisCommand.RecallBirthday -> memoryManager.getBirthdayResponse()
            is JarvisCommand.RecallFavoriteSong -> memoryManager.getFavoriteSongResponse()
            is JarvisCommand.RecallRoutine -> memoryManager.getRoutineResponse()
            is JarvisCommand.GoToBackground -> "Going to background."

            // Navigation & Gestures
            is JarvisCommand.ScrollScreen -> {
                val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                if (a11y == null) {
                    "Cannot scroll: Accessibility Service is not active. Please enable JARVIS in Settings."
                } else {
                    val scrolled = a11y.performSmartScroll(command.direction, command.distance)
                    if (scrolled) "Scrolled ${command.direction}." else "Unable to scroll."
                }
            }
            is JarvisCommand.NavigateBack -> {
                val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                if (a11y == null) {
                    "Cannot go back: Accessibility Service is not active."
                } else {
                    val backed = a11y.performSystemGesture("back")
                    if (backed) "Going back." else "Unable to go back."
                }
            }
            is JarvisCommand.NavigateHome -> {
                val a11yHomed = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                    ?.performSystemGesture("home") ?: false
                if (a11yHomed) {
                    "Returning home."
                } else {
                    // Standard system Intent fallback (works unconditionally across all Android versions)
                    try {
                        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                            addCategory(android.content.Intent.CATEGORY_HOME)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        phoneManager.context.startActivity(homeIntent)
                        "Returning home."
                    } catch (e: Exception) {
                        "Unable to return home."
                    }
                }
            }
            is JarvisCommand.StopOrPause -> {
                com.jarvis.assistant.automation.agent.AgentTaskEngine.requestCancellation()
                "Stopped."
            }
            is JarvisCommand.ContextualSearch -> {
                val ctx = phoneManager.context
                val query = command.query
                val platform = command.platform?.lowercase() ?: "google"
                if (platform == "flipkart") {
                    val clicked = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                        ?.clickNodeBySemanticMatch("Flipkart") ?: false
                    if (!clicked) {
                        try {
                            val url = "https://www.flipkart.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error opening Flipkart search: ${e.message}")
                        }
                    }
                    "Searching $query on Flipkart."
                } else {
                    try {
                        val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening Google search: ${e.message}")
                    }
                    "Searching $query on Google."
                }
            }
            is JarvisCommand.ClickElementByText -> {
                val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                if (a11y == null) {
                    "Cannot click elements: Accessibility Service is not active. Please enable JARVIS in Settings."
                } else {
                    val clicked = a11y.clickNodeBySemanticMatch(command.targetText)
                    if (clicked) "Clicked ${command.targetText}." else "Could not find ${command.targetText} on screen."
                }
            }
            is JarvisCommand.ExecuteProtocol -> {
                com.jarvis.assistant.automation.protocol.ProtocolManager.executeProtocol(
                    command.rawText,
                    phoneManager.context,
                    phoneManager
                ).spokenAnnouncement
            }
            is JarvisCommand.ProactiveStatusReport -> {
                com.jarvis.assistant.automation.briefing.ProactiveBriefingManager.generateBriefing(phoneManager.context)
            }
            is JarvisCommand.ReadUnreadWhatsApp -> {
                com.jarvis.assistant.automation.notification.JarvisNotificationListenerService.getUnreadWhatsAppSummary()
            }
            is JarvisCommand.SendSilentWhatsAppReply -> {
                val sent = com.jarvis.assistant.automation.notification.JarvisNotificationListenerService.sendSilentReply(
                    command.contact,
                    command.message,
                    phoneManager.context
                )
                if (sent) "WhatsApp reply sent to ${command.contact}." else "Could not send silent reply to ${command.contact}."
            }
            is JarvisCommand.ScanScreenTactical -> {
                com.jarvis.assistant.vision.TacticalScannerManager.scanCurrentScreen(phoneManager.context).summary
            }
            is JarvisCommand.ShareToPC -> {
                com.jarvis.assistant.automation.bridge.CrossDeviceBridge.shareCurrentContent(phoneManager.context, command.targetDevice).message
            }
            is JarvisCommand.RecallFact -> {
                com.jarvis.assistant.data.memory.RecallCoreManager.queryRecall(phoneManager.context, command.query)
            }
            is JarvisCommand.CameraVisionScan -> {
                com.jarvis.assistant.vision.CameraVisionAnalyzer.analyzeSnapshot(phoneManager.context, command.prompt).summary
            }
            is JarvisCommand.SecurityAuthorize -> {
                val auth = com.jarvis.assistant.security.SecurityGuardManager.verifyPassphrase(command.passphrase, phoneManager.context)
                if (auth) {
                    "Voice authorization accepted. Elevated security access granted for 60 seconds, Nazib Sir."
                } else {
                    "Security authorization failed. Access denied, Sir."
                }
            }
            is JarvisCommand.QueryExpenseLedger -> {
                com.jarvis.assistant.automation.ledger.ExpenseLedgerTracker.getTodayLedgerSummary(phoneManager.context)
            }
            is JarvisCommand.CallScreeningAction -> {
                val handled = com.jarvis.assistant.automation.phone.CallScreeningReceiver.rejectCallAndSendMessage(
                    phoneManager.context,
                    command.message
                )
                if (handled) "Call rejected and auto-reply message sent, Sir." else "Call silenced, Sir."
            }
        }
    }
}
