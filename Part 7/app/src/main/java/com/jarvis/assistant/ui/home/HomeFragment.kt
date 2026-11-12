package com.jarvis.assistant.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.FragmentHomeBinding
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.util.LocationWeatherHelper
import com.jarvis.assistant.util.PermissionHelper
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private val NEON_BG: Int = Color.parseColor("#070510")

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var isPageLoaded = false
    private var pendingOrbState: String? = null
    private var avatarTaskWasCompleted = false
    private var pendingThemeKey: String? = null
    private var pendingCustomHex: String? = null
    private var lastAudioPushTime = 0L
    private var taskFallbackPanel: android.widget.LinearLayout? = null
    private var taskFallbackText: android.widget.TextView? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            fetchLiveWeather()
        } else {
            binding.tvWeatherCondition.text = "Location Permission Needed"
            binding.tvWeatherDetails.text = "Tap here to grant location for accurate weather"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val deck = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(binding.root, android.widget.LinearLayout.LayoutParams(-1,
                (resources.displayMetrics.heightPixels * 0.88f).toInt().coerceAtLeast((660 * resources.displayMetrics.density).toInt())))
            addView(com.jarvis.assistant.ui.directives.DirectiveGrid.create(requireContext()))
        }
        val scrollingDeck = androidx.core.widget.NestedScrollView(requireContext()).apply {
            isFillViewport = true
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(this)
            addView(deck)
            // Home fills exactly one screen; the directive grid sits below the fold instead of peeking under the dock.
            addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                val screenHeight = bottom - top
                v.post {
                    val b = _binding ?: return@post
                    val lp = b.root.layoutParams
                    if (screenHeight > 0 && lp.height != screenHeight) {
                        lp.height = screenHeight
                        b.root.layoutParams = lp
                    }
                }
            }
        }
        return com.jarvis.assistant.ui.wallpaper.InAppWallpaper(requireContext(), scrollingDeck, listOf(scrollingDeck, binding.root))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as? com.jarvis.assistant.ui.wallpaper.InAppWallpaper)?.let { viewLifecycleOwner.lifecycle.addObserver(it) }
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        createTaskFallbackPanel()

        // Top Navigation
        binding.btnTopChat.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_CHAT)
        }

        binding.btnTopSettings.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_SETTINGS)
        }

        binding.btnFeatureHub.setOnClickListener {
            startActivity(Intent(requireContext(), FeatureHubActivity::class.java))
        }

        binding.btnTopSettings.setOnLongClickListener {
            startActivity(Intent(requireContext(), FeatureHubActivity::class.java))
            true
        }

        binding.btnDismissLiveError.setOnClickListener {
            hideLiveErrorOverlay {
                viewModel.clearLiveError()
            }
        }

        setupOrbWebView()
        setupRealisticAvatar()
        setupMockupControls()

        binding.btnScreenShareQuick.setOnClickListener {
            val isSharing = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value
            if (isSharing) {
                (activity as? MainActivity)?.toggleScreenShare()
            } else {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Tactical Vision & Screen")
                    .setItems(arrayOf("Tactical Screen Scan (OCR/Prices)", "Live Screen Share with Jarvis")) { _, which ->
                        if (which == 0) {
                            lifecycleScope.launch {
                                val scan = com.jarvis.assistant.vision.TacticalScannerManager.scanCurrentScreen(requireContext())
                                com.google.android.material.snackbar.Snackbar.make(binding.root, scan.summary, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                            }
                        } else {
                            (activity as? MainActivity)?.toggleScreenShare()
                        }
                    }
                    .show()
            }
        }

        binding.btnSavedMemories.setOnClickListener {
            com.jarvis.assistant.ui.memory.RecallCoreBottomSheet.show(parentFragmentManager)
        }

        binding.btnConversationLanguageQuick.setOnClickListener {
            showLanguageDialog()
        }

        // Weather Card Click -> Refresh or Request Permission
        binding.cardWeather.setOnClickListener {
            if (!PermissionHelper.hasLocationPermission(requireContext())) {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                fetchLiveWeather()
            }
        }

        updateHomeHeaderAndVoiceStatus()
        observeViewModel()
        checkLocationAndFetchWeather()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOrbWebView() {
        binding.orbWebView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            val assets = androidx.webkit.WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(context)).build()
            webViewClient = object : androidx.webkit.WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse =
                    assets.shouldInterceptRequest(request.url) ?: android.webkit.WebResourceResponse(
                        "text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (_binding?.orbWebView !== view) return
                    isPageLoaded = true
                    syncOrbMotion()

                    // Sync Theme
                    val currentTheme = pendingThemeKey ?: preferences.appTheme
                    val customHex = pendingCustomHex ?: preferences.customThemeColor
                    setOrbTheme(currentTheme, customHex)

                    // Sync State
                    val state = pendingOrbState ?: when (viewModel.conversationState.value) {
                        ConversationState.LISTENING, ConversationState.INTERRUPTED, ConversationState.CONNECTED -> "listening"
                        ConversationState.SPEAKING -> "speaking"
                        ConversationState.THINKING, ConversationState.EXECUTING, ConversationState.RECOVERING, ConversationState.CONNECTING -> "thinking"
                        ConversationState.STANDBY, ConversationState.IDLE, ConversationState.WAITING_FOR_APPROVAL, ConversationState.FAILED, ConversationState.ERROR -> "idle"
                    }
                    setOrbState(state)
                }
            }

            // Single tap detection triggers mic/conversation without interrupting 3D rotation drag
            val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    (activity as? MainActivity)?.handleMicOrOrbClick()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    (activity as? MainActivity)?.handleHoldMicOrOrb()
                }
            })

            setOnTouchListener { v, event ->
                gestureDetector.onTouchEvent(event)
                v.onTouchEvent(event)
            }

            loadUrl("https://appassets.androidplatform.net/assets/orb.html?embedded=1")
        }
    }

    private fun syncOrbMotion() {
        val web = _binding?.orbWebView ?: return
        if (isPageLoaded) web.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.configure("+com.jarvis.assistant.ui.orb.OrbAppearance.configuration(web.context).toString()+");", null)
        if (isPageLoaded) web.evaluateJavascript(
            "window.JarvisOrb && window.JarvisOrb.setReducedMotion(${com.jarvis.assistant.util.VisualMotionSettings.isReduced(web.context)});", null)
    }

    private fun setupRealisticAvatar() {
        binding.realisticAvatar.hapticsEnabled = preferences.hapticFeedbackEnabled
        binding.realisticAvatar.setOnClickListener {
            (activity as? MainActivity)?.handleMicOrOrbClick()
        }
        binding.realisticAvatar.setOnLongClickListener {
            (activity as? MainActivity)?.handleHoldMicOrOrb()
            true
        }
        applyAvatarMode()
    }

    private fun setupMockupControls() {
        binding.waveLeft.mirrored = false
        binding.waveRight.mirrored = true
        binding.micRing.setOnClickListener { (activity as? MainActivity)?.handleMicOrOrbClick() }
        binding.micRing.setOnLongClickListener {
            (activity as? MainActivity)?.handleHoldMicOrOrb()
            true
        }
        binding.btnBarKeyboard.setOnClickListener { (activity as? MainActivity)?.selectTab(MainActivity.TAB_CHAT) }
        binding.btnBarVoice.setOnClickListener {
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.directives.VoiceModeActivity::class.java))
        }
        // Reuses the existing (now hidden) quick-action so screen-share logic stays in one place.
        binding.btnBarCamera.setOnClickListener { binding.btnScreenShareQuick.performClick() }
        binding.btnBarGrid.setOnClickListener {
            startActivity(Intent(requireContext(), FeatureHubActivity::class.java))
        }
    }

    private fun updateMicVisuals(status: HomeStatusState) {
        val mode = when {
            !status.isOn || status.isStandby -> MicRingView.Mode.OFF
            status.isMuted -> MicRingView.Mode.MUTED
            status.state == ConversationState.SPEAKING -> MicRingView.Mode.SPEAKING
            status.state == ConversationState.THINKING || status.state == ConversationState.EXECUTING ||
                status.state == ConversationState.CONNECTING || status.state == ConversationState.RECOVERING -> MicRingView.Mode.THINKING
            else -> MicRingView.Mode.LISTENING
        }
        binding.micRing.setMode(mode)
        val moving = mode == MicRingView.Mode.LISTENING || mode == MicRingView.Mode.SPEAKING
        binding.waveLeft.setActive(moving)
        binding.waveRight.setActive(moving)
    }

    /** Shows the realistic avatar or the existing Orb/Ghost stage, per Settings > Assistant Avatar. */
    private fun applyAvatarMode() {
        val b = _binding ?: return
        val girl = false // AI girl removed from Home; Orb/Ghost stage always shown
        b.realisticAvatar.hapticsEnabled = preferences.hapticFeedbackEnabled
        b.realisticAvatar.visibility = if (girl) View.VISIBLE else View.GONE
        b.orbHaloRing.visibility = if (girl) View.GONE else View.VISIBLE
        if (girl) {
            b.orbWebView.visibility = View.GONE
            b.orbWebView.onPause()
            b.orbWebView.pauseTimers()
        } else {
            b.orbWebView.visibility = View.VISIBLE
            b.orbWebView.onResume()
            b.orbWebView.resumeTimers()
        }
    }

    fun setOrbState(state: String) {
        val visual = com.jarvis.assistant.ui.orb.OrbVisualState.current(state)
        pendingOrbState = visual
        activity?.runOnUiThread { _binding?.realisticAvatar?.setStateKey(state) }
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            _binding?.orbWebView?.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setState('$visual');", null)
        }
    }

    fun setOrbTheme(themeKey: String, customHex: String?) {
        pendingThemeKey = themeKey
        pendingCustomHex = customHex
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            if (themeKey == "custom" && !customHex.isNullOrBlank()) {
                _binding?.orbWebView?.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setBackground('$customHex');", null)
            } else {
                val mapped = if (themeKey in setOf("system", "light", "dark")) com.jarvis.assistant.util.ThemeManager.resolveThemeKey(requireContext(), themeKey) else when (preferences.orbPreset) {
                    "aura" -> "dark"
                    "crimson" -> "red"
                    "midnight" -> "midnight"
                    else -> "light"
                }
                _binding?.orbWebView?.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setTheme('$mapped');", null)
            }
        }
    }

    private fun pushExternalAudio(amp: Float, bass: Float, high: Float) {
        _binding?.realisticAvatar?.setAmplitude(amp)
        _binding?.waveLeft?.setLevel(amp)
        _binding?.waveRight?.setLevel(amp)
        _binding?.micRing?.setLevel(amp)
        if (!isPageLoaded) return
        val now = System.currentTimeMillis()
        if (now - lastAudioPushTime < 33L) return // ~30 calls per second throttle
        lastAudioPushTime = now
        activity?.runOnUiThread {
            _binding?.orbWebView?.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setExternalAudio($amp, $bass, $high);", null)
        }
    }

    override fun onResume() {
        super.onResume()
        syncOrbMotion()
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        binding.orbWebView.onResume()
        binding.orbWebView.resumeTimers()
        applyAvatarMode()
        renderTaskFallback(com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value)

        // Sync theme with settings
        setOrbTheme(preferences.appTheme, preferences.customThemeColor)
        updateHomeHeaderAndVoiceStatus()

        if (PermissionHelper.hasLocationPermission(requireContext())) {
            fetchLiveWeather()
        }

        // Auto-dismiss resolved live errors on resume (e.g. user just enabled Accessibility or granted permissions)
        val err = com.jarvis.assistant.background.JarvisConversationService.liveError.value
        if (err != null) {
            when (err.errorCode) {
                "ACCESSIBILITY_DISABLED" -> {
                    if (com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel.isOperational()) {
                        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                        hideLiveErrorOverlay()
                    }
                }
                "PERMISSIONS_MISSING" -> {
                    if (PermissionHelper.hasAllRequiredPermissions(requireContext())) {
                        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                        hideLiveErrorOverlay()
                    }
                }
                "API_KEY_MISSING", "API_KEY_INVALID" -> {
                    if (preferences.apiKey.isNotBlank()) {
                        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                        hideLiveErrorOverlay()
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.orbWebView.onPause()
        binding.orbWebView.pauseTimers()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) syncOrbMotion()
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            binding.orbWebView.visibility = View.VISIBLE
            binding.orbWebView.onResume()
            binding.orbWebView.resumeTimers()
            applyAvatarMode()
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            setOrbTheme(preferences.appTheme, preferences.customThemeColor)
            updateHomeHeaderAndVoiceStatus()
        } else if (hidden && _binding != null) {
            binding.orbWebView.onPause()
            binding.orbWebView.pauseTimers()
            binding.orbWebView.visibility = View.GONE
            binding.root.visibility = View.GONE
        }
    }

    private fun checkLocationAndFetchWeather() {
        if (PermissionHelper.hasLocationPermission(requireContext())) {
            fetchLiveWeather()
        }

        // Auto-dismiss resolved live errors on resume (e.g. user just enabled Accessibility or granted permissions)
        val err = com.jarvis.assistant.background.JarvisConversationService.liveError.value
        if (err != null) {
            when (err.errorCode) {
                "ACCESSIBILITY_DISABLED" -> {
                    if (com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel.isOperational()) {
                        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                        hideLiveErrorOverlay()
                    }
                }
                "PERMISSIONS_MISSING" -> {
                    if (PermissionHelper.hasAllRequiredPermissions(requireContext())) {
                        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                        hideLiveErrorOverlay()
                    }
                }
                "API_KEY_MISSING", "API_KEY_INVALID" -> {
                    if (preferences.apiKey.isNotBlank()) {
                        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
                        hideLiveErrorOverlay()
                    }
                }
            }
        }
    }

    private fun fetchLiveWeather() {
        binding.tvWeatherCondition.text = "Locating • Fetching weather..."
        viewLifecycleOwner.lifecycleScope.launch {
            val weather = LocationWeatherHelper.fetchWeather(requireContext())
            if (_binding != null && weather != null) {
                binding.tvTemperature.text = weather.temperature
                binding.tvWeatherCondition.text = weather.condition
                binding.tvWeatherDetails.text = weather.details
                binding.ivWeatherIcon.setImageResource(weather.iconResId)
                // Keep Jarvis's context aligned with the city shown on Home.
                preferences.userLocation = weather.cityName
            } else if (_binding != null) {
                binding.tvWeatherCondition.text = "Live Weather • Tap to retry"
            }
        }
    }

    private fun updateHomeHeaderAndVoiceStatus() {
        if (_binding == null) return
        val userName = preferences.userName.ifBlank { "NAZIB" }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeGreeting = when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Welcome back"
        }
        binding.tvGreeting.text = "$timeGreeting, $userName"

        val currentLang = preferences.language.ifBlank { "Auto" }
        binding.tvQuickLanguageStatus.text = if (currentLang.contains("Auto", ignoreCase = true)) "Auto" else currentLang
    }

    private fun showLanguageDialog() {
        val languages = com.jarvis.assistant.data.model.GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentLang = preferences.language
        val currentIndex = languages.indexOfFirst { it.equals(currentLang, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvQuickLanguageStatus.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.collect { snapshot ->
                        renderTaskFallback(snapshot)
                        val completed = snapshot?.currentStatus.equals("COMPLETED", ignoreCase = true)
                        if (completed && !avatarTaskWasCompleted) _binding?.realisticAvatar?.playAcknowledge()
                        avatarTaskWasCompleted = completed
                    }
                }
                launch {
                    val screenCapture = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
                    combine(screenCapture.isSharing, screenCapture.hasReceivedFrame) { sharing, hasFrame -> sharing to hasFrame }
                        .collect { (sharing, hasFrame) ->
                        binding.tvQuickScreenShareStatus.text = when {
                            !sharing -> "Share screen"
                            !hasFrame -> "Starting…"
                            else -> "Stop sharing"
                        }
                        binding.tvQuickScreenShareStatus.setTextColor(
                            if (sharing && hasFrame) Color.parseColor("#10B981")
                            else ContextCompat.getColor(requireContext(), R.color.text_subtle)
                        )
                    }
                }

                launch {
                    combine(
                        viewModel.isSessionOn,
                        viewModel.isMicMuted,
                        viewModel.conversationState,
                        com.jarvis.assistant.background.JarvisConversationService.isStandby
                    ) { isOn, isMuted, state, isStandby ->
                        HomeStatusState(isOn, isMuted, state, isStandby)
                    }.collect { status ->
                        updateMicVisuals(status)
                        if (!status.isOn) {
                            setOrbState("sleeping")
                            binding.tvListeningStatus.text = "JARVIS OFF • TAP TO START"
                            binding.tvListeningStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_subtle))
                            binding.tvHoldHint.text = "Tap the orb to power on"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#64748B")
                            )
                        } else if (status.isMuted) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "MIC MUTED (OFF) • TAP TO TALK"
                            binding.tvListeningStatus.setTextColor(Color.parseColor("#EF4444"))
                            binding.tvHoldHint.text = "Microphone is muted • Tap the orb to unmute"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#EF4444")
                            )
                        } else if (status.isStandby) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "STANDBY • SAY 'HEY JARVIS'"
                            binding.tvListeningStatus.setTextColor(Color.parseColor("#3B82F6"))
                            binding.tvHoldHint.text = "Listening for wake word • Hold the orb to power off"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#3B82F6")
                            )
                        } else {
                            binding.tvHoldHint.visibility = View.VISIBLE
                            when (status.state) {
                                ConversationState.STANDBY -> {
                                    setOrbState("idle")
                                    binding.tvListeningStatus.text = "STANDBY • SAY 'HEY JARVIS'"
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#3B82F6"))
                                    binding.tvHoldHint.text = "Listening for wake word • Hold the orb to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))
                                }
                                ConversationState.CONNECTING -> {
                                    setOrbState("waking")
                                    binding.tvListeningStatus.text = "CONNECTING TO GEMINI..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.tvHoldHint.text = "Establishing secure voice session..."
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                }
                                ConversationState.CONNECTED -> {
                                    setOrbState("listening")
                                    binding.tvListeningStatus.text = "CONNECTED • READY"
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#10B981"))
                                    binding.tvHoldHint.text = "Speak now • Tap to mute • Hold to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                                }
                                ConversationState.ERROR -> {
                                    setOrbState("error")
                                    binding.tvListeningStatus.text = "CONNECTION ERROR"
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#EF4444"))
                                    binding.tvHoldHint.text = "Check connection settings and retry"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                }
                                ConversationState.LISTENING, ConversationState.INTERRUPTED -> {
                                    setOrbState(if(status.state == ConversationState.INTERRUPTED) "interrupted" else "listening")
                                    binding.tvListeningStatus.text = "MIC ON • LISTENING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#22D3EE")) // matches orb listening ring (cyan)
                                    binding.tvHoldHint.text = "Speak now • Tap to mute • Hold to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#22D3EE")
                                    )
                                }
                                ConversationState.SPEAKING -> {
                                    setOrbState("speaking")
                                    binding.tvListeningStatus.text = "JARVIS SPEAKING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#FF4FA3")) // matches orb speaking ring (pink)
                                    binding.tvHoldHint.text = "Tap the orb to interrupt"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#FF4FA3")
                                    )
                                }
                                ConversationState.THINKING -> {
                                    setOrbState("thinking")
                                    binding.tvListeningStatus.text = "THINKING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.tvHoldHint.text = "Processing request..."
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#F59E0B")
                                    )
                                }
                                ConversationState.IDLE -> {
                                    setOrbState("idle")
                                    binding.tvListeningStatus.text = "MIC ON • READY"
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#10B981"))
                                    binding.tvHoldHint.text = "Tap to mute • Hold to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#10B981")
                                    )
                                }
                                ConversationState.EXECUTING -> {
                                    setOrbState("executing")
                                    binding.tvListeningStatus.text = "EXECUTING MISSION..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#3B82F6"))
                                    binding.tvHoldHint.text = "Tap to view progress or cancel"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#3B82F6")
                                    )
                                }
                                ConversationState.WAITING_FOR_APPROVAL -> {
                                    setOrbState("idle")
                                    binding.tvListeningStatus.text = "WAITING FOR APPROVAL"
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.tvHoldHint.text = "Confirmation required to proceed"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#F59E0B")
                                    )
                                }
                                ConversationState.RECOVERING -> {
                                    setOrbState("thinking")
                                    binding.tvListeningStatus.text = "SELF-HEALING / RECOVERING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.tvHoldHint.text = "Reconnecting service..."
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#F59E0B")
                                    )
                                }
                                ConversationState.FAILED -> {
                                    setOrbState("idle")
                                    binding.tvListeningStatus.text = "ACTION FAILED"
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#EF4444"))
                                    binding.tvHoldHint.text = "Tap to inspect details"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#EF4444")
                                    )
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.audioFrequency.collect { freq ->
                        val amp = freq.amplitude
                        val bass = freq.bass
                        val high = freq.high
                        pushExternalAudio(amp, bass, high)
                    }
                }

                launch {
                    viewModel.liveError.collect { error ->
                        if (error == null) {
                            hideLiveErrorOverlay()
                        } else {
                            showLiveErrorOverlay()
                            binding.tvLiveErrorTitle.text = error.title
                            binding.tvLiveErrorMessage.text = error.message
                            binding.btnFixLiveError.text = error.actionText

                            when (error.errorCode) {
                                "API_KEY_INVALID", "API_KEY_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
                                    }
                                }
                                "ACCESSIBILITY_DISABLED" -> {
                                    val a11yState = com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel.state.value

                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))

                                    if (a11yState == com.jarvis.assistant.automation.accessibility.AccessibilityState.ENABLED) {
                                        binding.tvLiveErrorTitle.text = "ACCESSIBILITY NOT CONNECTED"
                                        binding.tvLiveErrorMessage.text = "Service is enabled in Settings, but the background binder was killed or hasn't connected. Tap FIX to toggle Off and On in Settings."
                                    } else {
                                        binding.tvLiveErrorTitle.text = "ACCESSIBILITY REQUIRED"
                                        binding.tvLiveErrorMessage.text = "Jarvis requires Accessibility to complete actions and navigate your phone. Tap FIX to enable."
                                    }

                                    binding.btnFixLiveError.setOnClickListener {
                                        activity?.let { act ->
                                            PermissionHelper.requestAccessibilityWithRestrictedGuidance(act)
                                        } ?: PermissionHelper.openAccessibilitySettings(requireContext())
                                    }
                                }
                                "PERMISSIONS_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.jarvis.assistant.ui.permission.PermissionActivity::class.java))
                                    }
                                }
                                else -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        (activity as? MainActivity)?.handleMicOrOrbClick()
                                    }
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.connectionStatus.collect { status ->
                        if (status.startsWith("ERROR")) {
                            binding.tvListeningStatus.text = status
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#EF4444")
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showLiveErrorOverlay() {
        if (_binding == null) return
        val card = binding.cardLiveError
        if (card.visibility == View.VISIBLE && card.alpha == 1f) return
        card.alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.visibility = View.VISIBLE
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
            .start()
    }

    private fun hideLiveErrorOverlay(onEnd: (() -> Unit)? = null) {
        if (_binding == null) {
            onEnd?.invoke()
            return
        }
        val card = binding.cardLiveError
        if (card.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        card.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(180)
            .withEndAction {
                if (_binding != null) {
                    card.visibility = View.GONE
                }
                onEnd?.invoke()
            }
            .start()
    }

    private fun createTaskFallbackPanel() {
        val root = binding.root as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        val panel = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(12, 20, 35))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), android.graphics.Color.rgb(34, 211, 238))
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        panel.addView(android.widget.TextView(requireContext()).apply {
            text = "JARVIS AGENT PROGRESS"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.rgb(103, 232, 249))
        })
        val details = android.widget.TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            maxLines = 4
            setPadding(0, dp(5), 0, dp(2))
        }
        panel.addView(details)
        panel.addView(android.widget.Button(requireContext()).apply {
            text = "Enable floating overlay"
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    ))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(requireContext(), "Open Android Settings → Apps → JARVIS → Display over other apps.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, android.widget.LinearLayout.LayoutParams(-2, dp(44)))
        val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, -2).apply {
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = dp(70)
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        root.addView(panel, params)
        taskFallbackPanel = panel
        taskFallbackText = details
    }

    private fun renderTaskFallback(snapshot: com.jarvis.assistant.automation.agent.AgentTaskSnapshot?) {
        val panel = taskFallbackPanel ?: return
        val show = snapshot != null && !android.provider.Settings.canDrawOverlays(requireContext())
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || snapshot == null) return
        val activeStep = snapshot.steps.firstOrNull { it.stepId == snapshot.currentStepId }?.title ?: snapshot.currentStatus
        val checklist = snapshot.steps.take(4).joinToString("\n") { step ->
            val mark = if (step.status == com.jarvis.assistant.automation.agent.TaskStepStatus.COMPLETED) "✓" else "○"
            "$mark ${step.title}"
        }
        taskFallbackText?.text = "${snapshot.originalGoal}\n${snapshot.progressPercentage}% · ${snapshot.currentStatus.replace('_', ' ')}\nCurrent: $activeStep\n$checklist"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        isPageLoaded = false
        _binding?.orbWebView?.apply {
            try {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            } catch (_: Exception) {}
        }
        taskFallbackPanel = null
        taskFallbackText = null
        _binding = null
    }
}

private data class HomeStatusState(
    val isOn: Boolean,
    val isMuted: Boolean,
    val state: ConversationState,
    val isStandby: Boolean
)
