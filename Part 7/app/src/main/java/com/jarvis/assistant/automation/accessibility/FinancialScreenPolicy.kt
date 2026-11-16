package com.jarvis.assistant.automation.accessibility

/** Package identity guard, not a claim that every sensitive app can be identified. */
object FinancialScreenPolicy {
    private val protected = setOf(
        "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp", "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus",
        "com.hdfcbank.payzapp", "com.snapwork.hdfc", "com.csam.icici.bank.imobile"
    )
    @Volatile var projectionBlocked: Boolean = false
        private set
    /** Only real window transitions clear the latch; IME/system overlays cannot expose a bank. */
    fun observeWindow(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        if (isProtected(packageName)) projectionBlocked = true
        else if (packageName !in setOf("com.android.systemui", "com.google.android.inputmethod.latin",
                "com.android.inputmethod.latin", "com.android.permissioncontroller", "android")) {
            projectionBlocked = false
        }
    }
    fun isProtected(packageName: String?): Boolean = packageName in protected
}
