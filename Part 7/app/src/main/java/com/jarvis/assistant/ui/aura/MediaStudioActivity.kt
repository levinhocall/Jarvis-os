package com.jarvis.assistant.ui.aura

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.ui.directives.DirectiveActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class MediaStudioActivity : DirectiveActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); heading("Gemini Image Studio")
        label("Your prompt is sent to Gemini's image model and may use paid quota. Successful images are saved to Pictures/Jarvis. WhatsApp sharing lets you choose the contact and confirm Send.")
        val prompt=input("Describe the image…")
        val status=label("Ready. Enable Gemini Images in Aura to generate.")
        action("Generate and save to gallery") { button ->
            button.isEnabled=false
            lifecycleScope.launch {
                try { val uri=AuraCloudClient.image(applicationContext,prompt.text.toString()); remember(applicationContext,uri); status.text="Image saved to Pictures/Jarvis." }
                catch(cancelled: CancellationException){throw cancelled}
                catch(_: Exception){status.text="Image generation failed. Check Gemini access and Aura's image toggle. No image was reported saved."}
                finally {button.isEnabled=true}
            }
        }
        action("View last generated image") {
            last(applicationContext)?.let { uri -> runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { notice("No image viewer is available.") } } ?: notice("Generate an image first.")
        }
        action("Share last image to WhatsApp") { if (last(this) == null) notice("Generate an image first.") else share(this) }
    }
    companion object {
        fun remember(context: android.content.Context, uri: Uri) { context.getSharedPreferences("aura_media",0).edit().putString("last_generated",uri.toString()).apply() }
        fun last(context: android.content.Context): Uri? = context.getSharedPreferences("aura_media",0).getString("last_generated",null)?.let(Uri::parse)
        fun share(activity: android.app.Activity): Boolean {
            val uri=last(activity) ?: return false
            return runCatching {
                activity.startActivity(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM,uri)
                    .setPackage("com.whatsapp").apply { clipData = android.content.ClipData.newRawUri("Generated image",uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                true
            }.getOrElse { android.widget.Toast.makeText(activity,"WhatsApp sharing unavailable. The image remains in your gallery.",android.widget.Toast.LENGTH_LONG).show(); false }
        }
    }
}
