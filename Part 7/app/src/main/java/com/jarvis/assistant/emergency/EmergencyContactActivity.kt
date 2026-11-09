package com.jarvis.assistant.emergency

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.ui.directives.DirectiveActivity
import kotlinx.coroutines.launch

class EmergencyContactActivity:DirectiveActivity() {
    private lateinit var phone:android.widget.EditText
    private lateinit var output:android.widget.TextView
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {granted ->
        if(granted[Manifest.permission.CALL_PHONE]==true && (android.os.Build.VERSION.SDK_INT<33 || granted[Manifest.permission.POST_NOTIFICATIONS]==true)) enable() else output.text="Call and notification permissions are required for a visible, cancellable countdown. Monitoring remains off."
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);heading("Emergency Contact")
        label("Opt-in possible-fall assistance. Choose a trusted person's phone number. A detected free-fall, impact and stillness starts a 30-second countdown; cancel in the notification. If not cancelled, Android is asked to call that contact. This can miss accidents or trigger falsely; it is not a medical or crash-detection guarantee.")
        phone=input("Trusted contact phone number with country code",1).apply {inputType=android.text.InputType.TYPE_CLASS_PHONE}
        output=label(EmergencyMonitorService.status.value)
        lifecycleScope.launch {try {phone.setText(EmergencySettings.load(applicationContext).phone)} catch(_:Exception){output.text="Could not open encrypted emergency settings"}}
        action("Enable possible-fall monitoring") {
            if(!EmergencyMonitorService.validContact(phone.text.toString().trim())) {phone.error="Enter a trusted contact's number, not an emergency-services number";return@action}
            MaterialAlertDialogBuilder(this).setTitle("Allow automatic contact call?").setMessage("After a possible fall, a 30-second cancel window appears in a persistent notification. If not cancelled, JARVIS requests a call to your selected contact. False triggers and missed detection are possible. Monitoring uses battery.")
                .setPositiveButton("Enable") {_,_->permissions.launch(if(android.os.Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.CALL_PHONE,Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.CALL_PHONE))}.setNegativeButton("Cancel",null).show()
        }
        action("Start SOS countdown now") {
            lifecycleScope.launch {try {check(EmergencySettings.load(applicationContext).enabled);ContextCompat.startForegroundService(this@EmergencyContactActivity,Intent(this@EmergencyContactActivity,EmergencyMonitorService::class.java).setAction(EmergencyMonitorService.SOS))}catch(_:Exception){output.text="Enable the configured monitor before starting SOS"}}
        }
        action("Stop and disable monitoring") {lifecycleScope.launch {try {EmergencySettings.save(applicationContext,EmergencySettings.Config(phone.text.toString().trim(),false));stopService(Intent(this@EmergencyContactActivity,EmergencyMonitorService::class.java));output.text="Monitoring disabled"}catch(_:Exception){output.text="Could not save settings; monitoring stop requested";stopService(Intent(this@EmergencyContactActivity,EmergencyMonitorService::class.java))}}}
        lifecycleScope.launch {EmergencyMonitorService.status.collect {output.text=it}}
    }
    private fun enable() {lifecycleScope.launch {try {EmergencySettings.save(applicationContext,EmergencySettings.Config(phone.text.toString().trim(),true));ContextCompat.startForegroundService(this@EmergencyContactActivity,Intent(this@EmergencyContactActivity,EmergencyMonitorService::class.java))}catch(_:Exception){output.text="Android blocked monitoring or settings could not be saved. Retry from this screen."}}}
}
