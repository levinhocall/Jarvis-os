package com.jarvis.assistant.ui.controls

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.MicrophoneOwnership
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.FragmentControlsBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.util.VisualMotionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ControlsFragment : Fragment() {
    private var binding: FragmentControlsBinding? = null
    private val app get() = requireActivity().application as JarvisApp
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleTorch() else notice("Camera permission is needed for the flashlight.")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        FragmentControlsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = binding ?: return
        ThemeManager.applyDynamicBackground(b.root)
        b.btnRefreshControls.setOnClickListener { refreshSystemStatus() }
        b.btnControlTorch.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) toggleTorch()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        b.btnControlNetwork.setOnClickListener { openSettings(if(Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS) }
        b.btnControlBluetooth.setOnClickListener { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        b.btnControlSound.setOnClickListener { openSettings(Settings.ACTION_SOUND_SETTINGS) }
        b.btnControlDisplay.setOnClickListener { openSettings(Settings.ACTION_DISPLAY_SETTINGS) }
        b.btnControlWake.setOnClickListener { startActivity(Intent(requireContext(), com.jarvis.assistant.ui.directives.WakeWordActivity::class.java)) }
        b.btnControlAccess.setOnClickListener { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        b.btnControlPermissions.setOnClickListener { startActivity(Intent(requireContext(), PermissionActivity::class.java)) }
        b.btnControlScreen.setOnClickListener { (activity as? MainActivity)?.toggleScreenShare() }
        b.btnControlCoding.setOnClickListener { (activity as? MainActivity)?.selectTab(MainActivity.TAB_CODING) }
        b.btnControlAllTools.setOnClickListener { startActivity(Intent(requireContext(), com.jarvis.assistant.ui.home.FeatureHubActivity::class.java)) }
        b.switchReducedMotion.isChecked = VisualMotionSettings.preference(requireContext())
        b.switchReducedMotion.setOnCheckedChangeListener { _, enabled -> VisualMotionSettings.setPreference(requireContext(), enabled) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { JarvisConversationService.connectionStatus.collect { binding?.tvControlVoice?.text = "Voice connection: ${it.lowercase().replace('_', ' ')}" } }
                launch { MicrophoneOwnership.owner.collect { binding?.tvControlMic?.text = "Microphone: ${it?.name?.lowercase()?.replace('_', ' ') ?: "idle"}" } }
                launch { AccessibilityHealthModel.state.collect { renderAccessibility() } }
                launch { app.memoryRepository.getAllMemoriesFlow().collect { binding?.tvControlMemory?.text = "${it.size} saved memories · stored on this phone" } }
            }
        }
    }

    override fun onResume() { super.onResume(); refreshSystemStatus() }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if(!hidden && binding != null) refreshSystemStatus() }

    private fun refreshSystemStatus() {
        val context = context ?: return
        AccessibilityHealthModel.refreshFromSystem(context)
        renderAccessibility()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val battery = context.getSystemService(BatteryManager::class.java)
            val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            val free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()
            val status = "${level?.let { "$it% battery" } ?: "Battery unavailable"} · " +
                (free?.let { android.text.format.Formatter.formatFileSize(context, it) + " free" } ?: "Storage unavailable")
            withContext(Dispatchers.Main) { binding?.tvControlDevice?.text = status }
        }
    }

    private fun renderAccessibility() {
        val context = context ?: return
        val connected = JarvisAccessibilityService.isRunning() && AccessibilityHealthModel.isOperational()
        binding?.tvControlAccessibility?.text = "Accessibility: " + when {
            connected -> "connected"
            AccessibilityHealthModel.isServiceEnabledInSettings(context) -> "enabled in Settings, waiting for Android connection"
            else -> "off · enable for app automation"
        }
    }

    private fun toggleTorch() {
        val manager = app.phoneControlManager
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { manager.setFlashlight(!manager.isTorchOn) }
            notice(result)
        }
    }
    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) } catch (_: Exception) { notice("This Settings panel is unavailable on this device.") }
    }
    private fun notice(message: String) { context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() } }
    override fun onDestroyView() { binding = null; super.onDestroyView() }
}
