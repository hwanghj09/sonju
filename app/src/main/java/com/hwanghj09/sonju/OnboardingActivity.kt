package com.hwanghj09.sonju

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.hwanghj09.sonju.accessibility.SonjuAccessibilityService

class OnboardingActivity : AppCompatActivity() {
    private lateinit var stepText: TextView
    private lateinit var titleText: TextView
    private lateinit var bodyText: TextView
    private lateinit var detailText: TextView
    private lateinit var primaryButton: MaterialButton
    private lateinit var secondaryButton: MaterialButton
    private lateinit var appInfoButton: MaterialButton
    private var page = 0
    private var microphoneDenied = false

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            page = 2
        } else {
            microphoneDenied = true
        }
        renderPage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboarding)
        val onboardingRoot = findViewById<View>(R.id.onboardingRoot)
        val initialPaddingLeft = onboardingRoot.paddingLeft
        val initialPaddingTop = onboardingRoot.paddingTop
        val initialPaddingRight = onboardingRoot.paddingRight
        val initialPaddingBottom = onboardingRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(onboardingRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialPaddingLeft + bars.left,
                initialPaddingTop + bars.top,
                initialPaddingRight + bars.right,
                initialPaddingBottom + bars.bottom,
            )
            insets
        }
        stepText = findViewById(R.id.tutorialStep)
        titleText = findViewById(R.id.tutorialTitle)
        bodyText = findViewById(R.id.tutorialBody)
        detailText = findViewById(R.id.tutorialDetail)
        primaryButton = findViewById(R.id.tutorialPrimaryButton)
        secondaryButton = findViewById(R.id.tutorialSecondaryButton)
        appInfoButton = findViewById(R.id.tutorialAppInfoButton)
        page = savedInstanceState?.getInt(STATE_PAGE) ?: 0
        renderPage()
    }

    override fun onResume() {
        super.onResume()
        if (page == 2 && isAccessibilityEnabled()) {
            page = 3
            renderPage()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, page)
        super.onSaveInstanceState(outState)
    }

    private fun renderPage() {
        stepText.text = getString(R.string.tutorial_step_format, page + 1, TOTAL_PAGES)
        secondaryButton.visibility = View.VISIBLE
        secondaryButton.isEnabled = page > 0
        secondaryButton.setOnClickListener {
            if (page > 0) {
                navigateToPage(page - 1)
            }
        }
        appInfoButton.visibility = View.GONE
        appInfoButton.setOnClickListener(null)
        when (page) {
            0 -> {
                titleText.setText(R.string.tutorial_welcome_title)
                bodyText.setText(R.string.tutorial_welcome_body)
                detailText.setText(R.string.tutorial_welcome_detail)
                primaryButton.setText(R.string.tutorial_next)
                primaryButton.setOnClickListener {
                    recordAccessibilityDisclosureAcceptance()
                    navigateToPage(if (hasMicrophonePermission()) 2 else 1)
                }
            }

            1 -> {
                titleText.setText(R.string.tutorial_microphone_title)
                bodyText.setText(R.string.tutorial_microphone_body)
                detailText.setText(
                    if (microphoneDenied) {
                        R.string.tutorial_microphone_denied
                    } else {
                        R.string.tutorial_microphone_detail
                    },
                )
                primaryButton.setText(R.string.tutorial_next)
                primaryButton.setOnClickListener {
                    when {
                        hasMicrophonePermission() -> {
                            navigateToPage(2)
                        }
                        microphoneDenied &&
                            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                            openAppInfo()
                        }
                        else -> microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                if (microphoneDenied) {
                    appInfoButton.visibility = View.VISIBLE
                    appInfoButton.setOnClickListener { openAppInfo() }
                }
            }

            2 -> {
                recordAccessibilityDisclosureAcceptance()
                titleText.setText(R.string.tutorial_accessibility_title)
                bodyText.setText(R.string.tutorial_accessibility_body)
                detailText.setText(R.string.tutorial_accessibility_detail)
                primaryButton.setText(R.string.tutorial_next)
                primaryButton.setOnClickListener {
                    if (isAccessibilityEnabled()) {
                        navigateToPage(3)
                    } else {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
                appInfoButton.visibility = View.VISIBLE
                appInfoButton.setOnClickListener { openAppInfo() }
            }

            else -> {
                titleText.setText(R.string.tutorial_ready_title)
                bodyText.setText(R.string.tutorial_ready_body)
                detailText.setText(R.string.tutorial_ready_detail)
                primaryButton.setText(R.string.tutorial_start)
                primaryButton.setOnClickListener { finishTutorial() }
            }
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, SonjuAccessibilityService::class.java)
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    private fun openAppInfo() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun navigateToPage(targetPage: Int) {
        primaryButton.isEnabled = false
        secondaryButton.isEnabled = false
        appInfoButton.isEnabled = false
        primaryButton.postDelayed(
            {
                page = targetPage.coerceIn(0, TOTAL_PAGES - 1)
                appInfoButton.isEnabled = true
                renderPage()
            },
            PAGE_TRANSITION_DEBOUNCE_MILLIS,
        )
    }

    private fun recordAccessibilityDisclosureAcceptance() {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DISCLOSURE_ACCEPTED, true)
            .apply()
    }

    private fun finishTutorial() {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .putBoolean(KEY_DISCLOSURE_ACCEPTED, true)
            .putBoolean(KEY_VOICE_DISCLOSURE_ACCEPTED, true)
            .apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed_v1"
        private const val PREFERENCES = "sonju_preferences"
        private const val KEY_DISCLOSURE_ACCEPTED = "accessibility_disclosure_accepted_v1"
        private const val KEY_VOICE_DISCLOSURE_ACCEPTED = "voice_disclosure_accepted_v1"
        private const val STATE_PAGE = "tutorial_page"
        private const val TOTAL_PAGES = 4
        private const val PAGE_TRANSITION_DEBOUNCE_MILLIS = 180L
    }
}
