package com.zclei.smartsensorball

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import android.widget.VideoView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.zclei.smartsensorball.auth.ActivationApiResult
import com.zclei.smartsensorball.auth.ActivationService
import com.zclei.smartsensorball.auth.ActivationState
import com.zclei.smartsensorball.cloud.CloudBootstrapResult
import com.zclei.smartsensorball.cloud.CloudAchievementItem
import com.zclei.smartsensorball.cloud.CloudLeaderboardEntry
import com.zclei.smartsensorball.cloud.CloudLeaderboardResult
import com.zclei.smartsensorball.cloud.CloudSyncService
import com.zclei.smartsensorball.cloud.CloudTierProgress
import com.zclei.smartsensorball.cloud.CloudTrainingHistoryItem
import com.zclei.smartsensorball.cloud.CloudUserProfile
import com.zclei.smartsensorball.cloud.CloudUserStatistics
import com.zclei.smartsensorball.bluetooth.SensorBallBluetoothCallback
import com.zclei.smartsensorball.bluetooth.SensorBallBluetoothManager
import com.zclei.smartsensorball.bluetooth.SensorBallDevice
import com.zclei.smartsensorball.bluetooth.SensorBallTelemetry
import com.zclei.smartsensorball.bluetooth.SensorBallTransport
import com.zclei.smartsensorball.model.AppLanguage
import com.zclei.smartsensorball.model.TrainingMode
import com.zclei.smartsensorball.model.TrainingReport
import com.zclei.smartsensorball.ui.Haptics
import com.zclei.smartsensorball.ui.HistoryItemAdapter
import com.zclei.smartsensorball.ui.LeaderboardRowAdapter
import com.zclei.smartsensorball.ui.VerticalSpacingDecoration
import com.zclei.smartsensorball.ui.applyRippleOverlay
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val avatarPalette =
        listOf("#CC4400", "#E07010", "#A73A54", "#FFD060", "#5C3D99", "#7A1400", "#8B5E3C", "#C06014")
    private enum class HomePage {
        TrainingCenter,
        TrainingAchievements,
        Leaderboard,
        Profile,
    }

    private enum class LeaderboardBoard(val apiKey: String) {
        Best30("best_30_hits"),
        Best60("best_60_hits"),
        TotalHits("total_hits"),
        LongestStreak("longest_streak"),
    }

    private enum class TrainingPlayMode {
        Classic30,
        Classic60,
        Burst10,
        Burst15,
        LevelChallenge,
        DailyChallenge,
    }

    private data class TrainingLevelDefinition(
        val level: Int,
        val targetHits: Int,
    )

    private data class TrainingGoalPresentation(
        val title: String,
        val body: String,
        val accentColor: String,
        val targetHits: Int? = null,
    )

    private data class LocalSessionSummary(
        val dateKey: String,
        val endedAtMs: Long,
        val durationSeconds: Int,
        val hits: Int,
        val playMode: String,
    )

    private data class TrainingCoachOutcome(
        val playMode: TrainingPlayMode,
        val goalMet: Boolean,
        val levelBefore: Int,
        val levelAfter: Int,
        val targetHits: Int?,
        val streak: Int,
        val xpGain: Int,
    )

    private var selectedMode: TrainingMode = TrainingMode.Seconds30
    private var selectedPlayMode: TrainingPlayMode = TrainingPlayMode.Classic30
    private var lastCoachMessage: String? = null
    private var lastCoachOutcome: TrainingCoachOutcome? = null
    private var selectedLanguage: AppLanguage = defaultLanguage()
    private var selectedHomePage: HomePage = HomePage.TrainingCenter
    private var trainingJob: Job? = null
    private var activationJob: Job? = null
    private var bluetoothTrainingCount: Int = 0
    private var bluetoothTrainingMode: TrainingMode? = null
    private var lastDisplayedCount = 0
    private var lastSpokenCountdown: Int? = null
    private var goSpoken = false
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var ttsReady = false
    private var ttsLocaleInUse: Locale? = null
    private val ttsCompletionCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private var latestReport: TrainingReport? = null
    private var activationState: ActivationState? = null
    private var installId: String = ""
    private var deviceHash: String = ""
    private var authStatusMessageKey: String? = null
    private var authStatusFallbackMessage: String? = null
    private var authStatusColor: Int = Color.parseColor("#FFD060")
    private var cloudJob: Job? = null
    private var cloudProfile: CloudUserProfile? = null
    private var cloudStatistics: CloudUserStatistics? = null
    private var cloudHistory: List<CloudTrainingHistoryItem> = emptyList()
    private var cloudAchievements: List<CloudAchievementItem> = emptyList()
    private var cloudTier: CloudTierProgress? = null
    private var leaderboardResult: CloudLeaderboardResult? = null
    private var leaderboardBoard: LeaderboardBoard = LeaderboardBoard.Best30
    private var cloudStatusMessageKey: String? = null
    private var cloudStatusFallbackMessage: String? = null
    private var cloudStatusColor: Int = Color.parseColor("#FFD060")
    private var pendingAvatarSelection: ((Uri?) -> Unit)? = null
    private var autoRestoreAttempted = false
    private var splashDismissed = false
    private var celebrationShowing = false
    private var dismissingCelebrationForTraining = false
    private var activeCelebrationDialog: AlertDialog? = null
    private val celebrationQueue: ArrayDeque<() -> Unit> = ArrayDeque()

    private val activationService = ActivationService()
    private val cloudSyncService = CloudSyncService()

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var promotionBannerView: TextView
    private lateinit var trainingHeroCard: LinearLayout
    private lateinit var trainingHeroBadgeView: TextView
    private lateinit var trainingHeroHeadlineView: TextView
    private lateinit var trainingHeroSummaryView: TextView
    private lateinit var trainingHeroInsightView: TextView
    private lateinit var trainingHeroProgressView: TextView
    private lateinit var shareTrainingButton: Button
    private lateinit var modeTitleView: TextView
    private lateinit var reportTitleView: TextView
    private lateinit var profileTitleView: TextView
    private lateinit var profileSubtitleView: TextView
    private lateinit var profileCard: LinearLayout
    private lateinit var profileAvatarShell: FrameLayout
    private lateinit var profileAvatarImageView: ImageView
    private lateinit var profileAvatarFallbackView: TextView
    private lateinit var profileHeroTagView: TextView
    private lateinit var profileHeroBadgeView: TextView
    private lateinit var profileSummaryView: TextView
    private lateinit var profileMetaView: TextView
    private lateinit var profileTierView: TextView
    private lateinit var profileStatsView: TextView
    private lateinit var profileBadgesView: TextView
    private lateinit var cloudStatusView: TextView
    private lateinit var editProfileButton: Button
    private lateinit var refreshCloudButton: Button
    private lateinit var developerInfoButton: Button
    private lateinit var historyTitleView: TextView
    private lateinit var historySubtitleView: TextView
    private lateinit var historyCard: LinearLayout
    private lateinit var historyListRecycler: RecyclerView
    private lateinit var historyEmptyView: LinearLayout
    private lateinit var historyItemAdapter: HistoryItemAdapter
    private lateinit var historyView: TextView
    private lateinit var leaderboardTitleView: TextView
    private lateinit var leaderboardSubtitleView: TextView
    private lateinit var leaderboardCard: LinearLayout
    private lateinit var leaderboardPodiumContainer: LinearLayout
    private lateinit var leaderboardListRecycler: RecyclerView
    private lateinit var leaderboardRowAdapter: LeaderboardRowAdapter
    private lateinit var leaderboardMeCard: LinearLayout
    private lateinit var leaderboardMeTitleView: TextView
    private lateinit var leaderboardMeView: TextView
    private lateinit var shareLeaderboardButton: Button
    private lateinit var leaderboardModeGroup: RadioGroup
    private lateinit var leaderboard30Button: RadioButton
    private lateinit var leaderboard60Button: RadioButton
    private lateinit var leaderboardTotalHitsButton: RadioButton
    private lateinit var leaderboardStreakButton: RadioButton
    private lateinit var refreshLeaderboardButton: Button
    private lateinit var leaderboardView: TextView
    private lateinit var achievementsTitleView: TextView
    private lateinit var achievementsSubtitleView: TextView
    private lateinit var achievementsCard: LinearLayout
    private lateinit var achievementsGridContainer: LinearLayout
    private lateinit var achievementsSummaryView: TextView
    private lateinit var shareAchievementsButton: Button
    private lateinit var statusView: TextView
    private lateinit var countdownView: TextView
    private lateinit var countView: TextView
    private lateinit var remainingView: TextView
    private lateinit var reportView: LinearLayout
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var bluetoothHeaderIndicatorView: ImageView
    private lateinit var batteryHeaderView: TextView
    private lateinit var splashOverlay: FrameLayout
    private lateinit var splashBrandCard: LinearLayout
    private lateinit var splashVideoView: VideoView
    private lateinit var quietIconView: ImageView
    private lateinit var modeGroup: RadioGroup
    private lateinit var mode30Button: RadioButton
    private lateinit var mode60Button: RadioButton
    private lateinit var modeBurst10Button: RadioButton
    private lateinit var modeBurst15Button: RadioButton
    private lateinit var modeLevelButton: RadioButton
    private lateinit var modeDailyButton: RadioButton
    private lateinit var trainingPlayCard: LinearLayout
    private lateinit var trainingPlayTitleView: TextView
    private lateinit var trainingPlayBodyView: TextView
    private lateinit var trainingPlayProgressView: TextView

    private lateinit var activationCard: LinearLayout
    private lateinit var activationTitleView: TextView
    private lateinit var activationHintView: TextView
    private lateinit var serialInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var serialInputErrorView: TextView
    private lateinit var codeInputErrorView: TextView
    private var activationInputsValid: Boolean = false
    private lateinit var activateButton: Button
    private lateinit var authStatusView: TextView
    private lateinit var activationDetailsView: TextView
    private lateinit var pageTabsCard: LinearLayout
    private lateinit var pageTrainingButton: TextView
    private lateinit var pageAchievementsButton: TextView
    private lateinit var pageLeaderboardButton: TextView
    private lateinit var pageProfileButton: TextView
    private lateinit var pageHost: FrameLayout
    private lateinit var contentRootView: LinearLayout
    private lateinit var trainingWatermarkPage: FrameLayout
    private lateinit var trainingSwipe: SwipeRefreshLayout
    private lateinit var achievementsSwipe: SwipeRefreshLayout
    private lateinit var leaderboardSwipe: SwipeRefreshLayout
    private lateinit var profileSwipe: SwipeRefreshLayout
    private lateinit var pageTrainingContainer: LinearLayout
    private lateinit var pageAchievementsContainer: LinearLayout
    private lateinit var pageLeaderboardContainer: LinearLayout
    private lateinit var pageProfileContainer: LinearLayout
    private val hideActivationCardRunnable =
        Runnable {
            if (isActivated() && trainingJob?.isActive != true) {
                setActivationVisible(false)
                clearAuthStatusMessage()
            }
        }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    private val sensorBallBluetooth by lazy {
        SensorBallBluetoothManager(
            this,
            object : SensorBallBluetoothCallback {
                override fun onStatus(message: String) {
                    runOnUiThread {
                        bluetoothStatusMessage = message
                        updateBluetoothSettingsViews()
                    }
                }

                override fun onDevicesChanged(devices: List<SensorBallDevice>) {
                    runOnUiThread {
                        bluetoothDevices.clear()
                        bluetoothDevices.addAll(devices)
                        if (bluetoothConnectedDevice == null && devices.size == 1) {
                            selectedBluetoothDevice = devices.first()
                        }
                        bluetoothStatusMessage =
                            if (devices.isEmpty()) {
                                bluetoothStatusMessage
                            } else {
                                if (devices.size == 1) {
                                    bluetoothAutoSelectedText(devices.first().name)
                                } else {
                                    bluetoothDevicesFoundText(devices.size)
                                }
                            }
                        updateBluetoothSettingsViews()
                    }
                }

                override fun onConnected(device: SensorBallDevice) {
                    runOnUiThread {
                        selectedBluetoothDevice = device
                        bluetoothConnectedDevice = device
                        rememberBluetoothDevice(device)
                        saveLastBluetoothDevice(device)
                        bluetoothHitCount = 0
                        bluetoothPeakText = "--"
                        bluetoothRealHitCount = 0
                        lastBluetoothGyroRawCount = null
                        pendingBluetoothGyroHitTimes.clear()
                        ensureGyroscopeOffAfterConnection()
                        bluetoothStatusMessage = bluetoothConnectedText(device.name)
                        updateBluetoothSettingsViews()
                    }
                }

                override fun onDisconnected() {
                    runOnUiThread {
                        bluetoothConnectedDevice = null
                        selectedBluetoothDevice = null
                        bluetoothDevices.clear()
                        bluetoothBatteryText = "--"
                        pendingBluetoothGyroHitTimes.clear()
                        bluetoothStatusMessage = bluetoothDisconnectedText()
                        updateBluetoothSettingsViews()
                    }
                }

                override fun onTelemetry(telemetry: SensorBallTelemetry) {
                    runOnUiThread {
                        bluetoothBatteryText = telemetry.batteryText
                        bluetoothPeakText = telemetry.peak.toString()
                        updateBluetoothGyroHitCount(telemetry.hitCount)
                        bluetoothStatusMessage = bluetoothPacketReceivedText(telemetry.packetIndex)
                        updateBluetoothSettingsViews()
                    }
                }
            },
        )
    }
    private val bluetoothDevices = mutableListOf<SensorBallDevice>()
    private var selectedBluetoothDevice: SensorBallDevice? = null
    private var bluetoothConnectedDevice: SensorBallDevice? = null
    private var bluetoothStatusMessage: String = bluetoothDisconnectedText()
    private var bluetoothBatteryText: String = "--"
    private var bluetoothHitCount: Int? = null
    private var bluetoothPeakText: String = "--"
    private var bluetoothRealHitCount: Int = 0
    private var lastBluetoothGyroRawCount: Int? = null
    private val pendingBluetoothGyroHitTimes = ArrayDeque<Long>()
    private var bluetoothGyroscopeEnabled: Boolean = false
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var bluetoothStatusView: TextView? = null
    private var bluetoothDeviceListView: LinearLayout? = null
    private var bluetoothBatteryView: TextView? = null
    private var bluetoothHitCountView: TextView? = null
    private var bluetoothScanButton: Button? = null
    private var bluetoothConnectButton: Button? = null
    private var bluetoothDisconnectButton: Button? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startTraining()
            } else {
                renderError(tr("permission_required"))
            }
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = requiredBluetoothPermissions().all { permission -> grants[permission] == true }
            val action = pendingBluetoothAction
            pendingBluetoothAction = null
            if (allGranted) {
                action?.invoke()
            } else {
                bluetoothStatusMessage = bluetoothPermissionDeniedText()
                updateBluetoothSettingsViews()
                Toast.makeText(this, bluetoothPermissionDeniedText(), Toast.LENGTH_SHORT).show()
            }
        }

    private val avatarPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingAvatarSelection
            pendingAvatarSelection = null
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
            }
            callback?.invoke(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        loadSettings()
        ensureInstallIdentity()
        loadActivationState()
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        initTextToSpeech()
        renderIdle()
        refreshCloudData(forceLeaderboard = true)
        startLaunchSplash()
        contentRootView.post { autoConnectLastBluetoothDevice() }
        contentRootView.postDelayed({ maybeShowBluetoothFirstUseGuide() }, 1200L)
    }

    override fun onDestroy() {
        trainingJob?.cancel()
        activationJob?.cancel()
        cloudJob?.cancel()
        sensorBallBluetooth.close()
        if (::splashVideoView.isInitialized) {
            try {
                splashVideoView.stopPlayback()
            } catch (_: Throwable) {
            }
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#040C08"))
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        val contentRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        contentRootView = contentRoot
        val topContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(24), dp(20), dp(0))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        pageHost =
            FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#040C08"))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1.0f,
                    )
            }

        val headerRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val headerTextColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1.0f,
                    )
            }

        titleView =
            titleText("", 22f).apply {
                gravity = Gravity.START
                translationY = -dp(4).toFloat()
            }
        subtitleView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#DFFFF0"))
                setPadding(0, dp(2), dp(12), 0)
                translationY = -dp(4).toFloat()
            }
        headerTextColumn.addView(titleView)
        val deviceStatusRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, 0)
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(2)
                    }
            }
        bluetoothHeaderIndicatorView =
            ImageView(this).apply {
                setImageResource(R.drawable.ic_bluetooth_universal)
                setColorFilter(Color.WHITE)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) }
            }
        batteryHeaderView =
            TextView(this).apply {
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                includeFontPadding = false
                setPadding(0, 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(16))
            }
        deviceStatusRow.addView(bluetoothHeaderIndicatorView)
        deviceStatusRow.addView(batteryHeaderView)
        headerTextColumn.addView(deviceStatusRow)
        headerTextColumn.addView(subtitleView)
        headerRow.addView(headerTextColumn)

        settingsButton =
            ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_manage)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.parseColor("#44FF88"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                translationY = -dp(6).toFloat()
                setOnClickListener {
                    if (trainingJob?.isActive != true) {
                        showFormalSettingsDialog()
                    }
            }
        }
        headerRow.addView(settingsButton)
        topContainer.addView(headerRow)
        updateHeaderBluetoothStatus()

        promotionBannerView =
            bodyText("").apply {
                visibility = View.GONE
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = roundedBackground("#008840", "#80FFB0", 22)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(8)
                    }
                alpha = 0f
                translationY = -dp(12).toFloat()
            }
        topContainer.addView(promotionBannerView)

        activationCard =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = surfaceCardBackground()
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(12)
                    }
            }
        activationTitleView = sectionLabel("")
        activationHintView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#DFFFF0"))
                setPadding(0, 0, 0, dp(10))
            }
        serialInput =
            activationInput("").apply {
                filters = arrayOf(InputFilter.LengthFilter(11))
            }
        serialInputErrorView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFB347"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(2), dp(4), 0, 0)
                visibility = View.GONE
            }
        codeInput =
            activationInput("").apply {
                filters = arrayOf(InputFilter.LengthFilter(8))
            }
        codeInputErrorView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFB347"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(2), dp(4), 0, 0)
                visibility = View.GONE
            }
        activateButton =
            actionButton("", "#008840").apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setOnClickListener { activateDevice() }
            }
        serialInput.doAfterTextChanged {
            updateActivationInputState()
        }
        codeInput.doAfterTextChanged {
            updateActivationInputState()
        }
        authStatusView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFD060"))
                setPadding(0, dp(10), 0, 0)
            }
        activationDetailsView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFF0C9"))
                setPadding(0, dp(12), 0, 0)
                visibility = View.GONE
            }
        activationCard.addView(activationTitleView)
        activationCard.addView(activationHintView)
        activationCard.addView(serialInput)
        activationCard.addView(serialInputErrorView)
        activationCard.addView(spacer(dp(8)))
        activationCard.addView(codeInput)
        activationCard.addView(codeInputErrorView)
        activationCard.addView(spacer(dp(12)))
        activationCard.addView(activateButton)
        activationCard.addView(authStatusView)
        activationCard.addView(activationDetailsView)
        activationCard.visibility = View.GONE
        topContainer.addView(activationCard)
        updateActivationInputState()

        pageTabsCard =
            surfaceCard().apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(4)
                    }
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = bottomNavBackground()
                elevation = dp(6).toFloat()
            }
        pageTrainingButton = homePageButton { selectHomePage(HomePage.TrainingCenter) }
        pageAchievementsButton = homePageButton { selectHomePage(HomePage.TrainingAchievements) }
        pageLeaderboardButton = homePageButton { selectHomePage(HomePage.Leaderboard) }
        pageProfileButton = homePageButton { selectHomePage(HomePage.Profile) }
        pageTabsCard.addView(pageTrainingButton)
        pageTabsCard.addView(horizontalSpace(dp(8)))
        pageTabsCard.addView(pageAchievementsButton)
        pageTabsCard.addView(horizontalSpace(dp(8)))
        pageTabsCard.addView(pageLeaderboardButton)
        pageTabsCard.addView(horizontalSpace(dp(8)))
        pageTabsCard.addView(pageProfileButton)
        pageTrainingContainer = pageContentContainer().apply {
            setPadding(dp(20), dp(2), dp(20), dp(24))
        }
        trainingSwipe = wrapInSwipeRefresh(pageTrainingContainer, enabled = false)
        trainingWatermarkPage = buildTrainingWatermarkPage(trainingSwipe)
        pageHost.addView(trainingWatermarkPage)

        pageAchievementsContainer = pageContentContainer().apply {
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        achievementsSwipe = wrapInSwipeRefresh(pageAchievementsContainer, enabled = true) {
            refreshCloudData(forceLeaderboard = false)
        }
        pageHost.addView(achievementsSwipe)

        pageLeaderboardContainer = pageContentContainer().apply {
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        leaderboardSwipe = wrapInSwipeRefresh(pageLeaderboardContainer, enabled = true) {
            refreshLeaderboardOnly()
        }
        pageHost.addView(leaderboardSwipe)

        pageProfileContainer = pageContentContainer().apply {
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        profileSwipe = wrapInSwipeRefresh(pageProfileContainer, enabled = true) {
            refreshCloudData(forceLeaderboard = true)
        }
        pageHost.addView(profileSwipe)

        trainingHeroCard =
            detailCard(fillColor = "#061410", strokeColor = "#00FF88", cornerDp = 26).apply {
                background = heroBackground("#008840")
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = 0
                        bottomMargin = dp(6)
                    }
            }
        trainingHeroBadgeView =
            badgeText(
                text = "",
                textColor = "#140800",
                fillColor = "#80FFB0",
            ).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        trainingHeroHeadlineView =
            titleText("", 28f).apply {
                gravity = Gravity.START
                setTextColor(Color.parseColor("#DFFFF0"))
                setPadding(0, dp(14), 0, 0)
            }
        trainingHeroSummaryView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#C8FFE0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(8), 0, 0)
            }
        trainingHeroInsightView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#DFFFF0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(10), 0, 0)
            }
        trainingHeroProgressView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#AAFF00"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(12), 0, 0)
            }
        shareTrainingButton =
            compactActionButton("", "#0A3A24").apply {
                setOnClickListener { shareTrainingSummary() }
            }
        trainingHeroCard.addView(trainingHeroBadgeView)
        trainingHeroCard.addView(trainingHeroHeadlineView)
        trainingHeroCard.addView(trainingHeroSummaryView)
        trainingHeroCard.addView(trainingHeroInsightView)
        trainingHeroCard.addView(trainingHeroProgressView)

        val trainingControlShell =
            FrameLayout(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                background = surfaceCardBackground()
                elevation = dp(3).toFloat()
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                        }
                    }
                clipToOutline = true
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.training_center_watermark)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0.56f
                        layoutParams =
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                    },
                )
                addView(
                    View(this@MainActivity).apply {
                        background =
                            GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                intArrayOf(
                                    Color.argb(204, 7, 15, 23),
                                    Color.argb(156, 11, 29, 40),
                                    Color.argb(196, 6, 13, 20),
                                ),
                            ).apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(24).toFloat()
                            }
                        layoutParams =
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                    },
                )
            }
        val trainingControlCard =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(8))
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        modeTitleView = sectionTitle("")

        modeGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                gravity = Gravity.START
                setPadding(0, dp(2), 0, dp(10))
            }
        mode30Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                isChecked = true
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        mode60Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        modeBurst10Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        modeBurst15Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        modeLevelButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        modeDailyButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        configureModeButton(mode30Button)
        configureModeButton(mode60Button)
        configureModeButton(modeBurst10Button)
        configureModeButton(modeBurst15Button)
        configureModeButton(modeLevelButton)
        configureModeButton(modeDailyButton)
        modeGroup.addView(mode30Button)
        modeGroup.addView(mode60Button)
        modeGroup.addView(modeBurst10Button)
        modeGroup.addView(modeBurst15Button)
        modeGroup.addView(modeLevelButton)
        modeGroup.addView(modeDailyButton)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedPlayMode = trainingPlayModeForCheckedId(checkedId)
            selectedMode = modeForPlayMode(selectedPlayMode)
            prefs.edit().putString(KEY_SELECTED_PLAY_MODE, selectedPlayMode.name).apply()
            if (trainingJob?.isActive != true) {
                remainingView.text = displayRemaining(selectedMode.durationSeconds * 1_000L)
            }
            refreshModeButtonStyles()
            renderTrainingPlayStatus()
        }

        trainingPlayCard =
            detailCard(fillColor = "#0B1B27", strokeColor = "#2E5E78", cornerDp = 22).apply {
                background = metallicBackground("#142F42", "#08131C", "#FF9A30", 22)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(4)
                        bottomMargin = 0
                    }
            }
        trainingPlayTitleView =
            titleText("", 18f).apply {
                gravity = Gravity.START
                setTextColor(Color.parseColor("#FFF8E8"))
            }
        trainingPlayBodyView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#E5C98A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(0, dp(8), 0, 0)
            }
        trainingPlayProgressView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFF3D3"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(10), 0, 0)
            }
        trainingPlayCard.addView(trainingPlayTitleView)
        trainingPlayCard.addView(trainingPlayBodyView)
        trainingPlayCard.addView(trainingPlayProgressView)

        quietIconView =
            ImageView(this).apply {
                setImageResource(android.R.drawable.ic_lock_silent_mode)
                setColorFilter(Color.parseColor("#FFD060"))
                layoutParams =
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                visibility = View.GONE
                alpha = 0.95f
            }
        trainingControlCard.addView(quietIconView)

        statusView =
            bodyText("").apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
        trainingControlCard.addView(statusView)

        countdownView =
            titleText("3", 40f).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FFD060"))
                setPadding(0, dp(6), 0, dp(2))
            }
        trainingControlCard.addView(countdownView)

        countView =
            titleText("0", 72f).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, dp(2))
            }
        trainingControlCard.addView(countView)

        remainingView =
            bodyText("").apply {
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FFB347"))
                setPadding(0, 0, 0, dp(12))
            }
        trainingControlCard.addView(remainingView)

        val actionRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(4))
            }
        startButton =
            actionButton("", "#E07010").apply {
                setOnClickListener { startTraining() }
            }
        stopButton =
            actionButton("", "#A73A54").apply {
                isEnabled = false
                alpha = 0.5f
                setOnClickListener { stopTraining(showStoppedState = true) }
            }
        actionRow.addView(startButton)
        actionRow.addView(horizontalSpace(dp(12)))
        actionRow.addView(stopButton)
        trainingControlCard.addView(actionRow)
        trainingControlCard.addView(modeTitleView)
        trainingControlCard.addView(modeGroup)
        trainingControlCard.addView(trainingPlayCard)
        trainingControlShell.addView(trainingControlCard)
        pageTrainingContainer.addView(trainingControlShell)
        pageTrainingContainer.addView(trainingHeroCard)

        reportTitleView = sectionTitle("")
        pageTrainingContainer.addView(reportTitleView)
        reportView =
            surfaceCard().apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(6)
                    }
            }
        pageTrainingContainer.addView(reportView)

        profileTitleView = sectionTitle("")
        pageProfileContainer.addView(profileTitleView)
        profileSubtitleView = sectionSubtitle("")
        pageProfileContainer.addView(profileSubtitleView)
        profileCard =
            surfaceCard().apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(6)
                        bottomMargin = dp(8)
                    }
            }
        val profileHeroShell =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = metallicBackground("#2C5B76", "#1A0C00", "#D9B870", 28)
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
        val profileHeroRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
        profileAvatarShell =
            FrameLayout(this).apply {
                background = avatarBackground("#CC4400")
                clipToOutline = true
                elevation = dp(4).toFloat()
                layoutParams =
                    LinearLayout.LayoutParams(dp(74), dp(74)).apply {
                        rightMargin = dp(16)
                    }
            }
        profileAvatarImageView =
            ImageView(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                visibility = View.GONE
            }
        profileAvatarFallbackView =
            TextView(this).apply {
                text = "R"
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        profileAvatarShell.addView(profileAvatarImageView)
        profileAvatarShell.addView(profileAvatarFallbackView)
        val profileHeadlineColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1.0f,
                    )
            }
        profileHeroBadgeView =
            badgeText(
                text = "",
                textColor = "#FFF5E6",
                fillColor = "#153244",
            ).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        profileSummaryView =
            titleText("", 24f).apply {
                gravity = Gravity.START
                setTextColor(Color.parseColor("#FFF5E6"))
            }
        profileMetaView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#B88A54"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            }
        profileTierView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFD060"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(10), 0, 0)
            }
        profileStatsView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFF0C9"))
                setPadding(0, dp(14), 0, 0)
            }
        profileBadgesView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#B88A54"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(12), 0, 0)
            }
        cloudStatusView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFD060"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
        profileHeadlineColumn.addView(profileHeroBadgeView)
        profileHeadlineColumn.addView(profileSummaryView)
        profileHeadlineColumn.addView(profileMetaView)
        profileHeadlineColumn.addView(profileTierView)
        profileHeroTagView =
            TextView(this).apply {
                text = profileHeroTagText()
                setTextColor(Color.parseColor("#140800"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = metallicBackground("#FFE8A8", "#C7932B", "#FFF2CD", 999)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
        profileHeroShell.addView(profileHeroTagView)
        profileHeroRow.addView(profileAvatarShell)
        profileHeroRow.addView(profileHeadlineColumn)
        profileHeroShell.addView(profileHeroRow)
        val profileActionRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(16), 0, 0)
            }
        editProfileButton =
            compactActionButton("", "#16384A").apply {
                setOnClickListener { showEditProfileDialog() }
            }
        refreshCloudButton =
            compactActionButton("", "#E07010").apply {
                setOnClickListener {
                    profileSwipe.isRefreshing = true
                    refreshCloudData(forceLeaderboard = true)
                }
            }
        profileActionRow.addView(editProfileButton)
        profileActionRow.addView(horizontalSpace(dp(12)))
        profileActionRow.addView(refreshCloudButton)
        developerInfoButton =
            compactActionButton("", "#16384A").apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(12)
                    }
                setOnClickListener { showDeveloperInfoDialog() }
            }
        profileCard.addView(profileHeroShell)
        profileCard.addView(profileStatsView)
        profileCard.addView(profileBadgesView)
        profileCard.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(14), 0, 0)
                addView(cloudStatusView)
            },
        )
        profileCard.addView(profileActionRow)
        profileCard.addView(developerInfoButton)
        pageProfileContainer.addView(profileCard)

        achievementsTitleView = sectionTitle("")
        pageAchievementsContainer.addView(achievementsTitleView)
        achievementsSubtitleView = sectionSubtitle("")
        pageAchievementsContainer.addView(achievementsSubtitleView)
        achievementsCard =
            surfaceCard().apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(6)
                        bottomMargin = dp(8)
                    }
            }
        achievementsSummaryView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFF0C9"))
            }
        shareAchievementsButton =
            compactActionButton("", "#16384A").apply {
                setOnClickListener { shareAchievementsSummary() }
            }
        val achievementsHeaderRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        achievementsSummaryView.layoutParams =
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f,
            )
        achievementsHeaderRow.addView(achievementsSummaryView)
        achievementsHeaderRow.addView(shareAchievementsButton)
        achievementsGridContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(14), 0, 0)
            }
        achievementsCard.addView(achievementsHeaderRow)
        achievementsCard.addView(achievementsGridContainer)
        pageAchievementsContainer.addView(achievementsCard)

        historyTitleView = sectionTitle("")
        pageAchievementsContainer.addView(historyTitleView)
        historySubtitleView = sectionSubtitle("")
        pageAchievementsContainer.addView(historySubtitleView)
        historyCard = surfaceCard().apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(8)
                    }
        }
        historyItemAdapter = HistoryItemAdapter { item -> historySessionCard(item) }
        historyListRecycler =
            RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                adapter = historyItemAdapter
                isNestedScrollingEnabled = false
                addItemDecoration(VerticalSpacingDecoration(dp(10)))
            }
        historyEmptyView =
            emptyStateCard(
                badge = historyEmptyBadgeText(),
                title = historyEmptyTitleText(),
                message = tr("no_history"),
                accentColor = "#FFB347",
            ).apply { visibility = View.GONE }
        historyView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFF0C9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        historyCard.addView(historyListRecycler)
        historyCard.addView(historyEmptyView)
        historyCard.addView(historyView)
        pageAchievementsContainer.addView(historyCard)

        leaderboardTitleView = sectionTitle("")
        pageLeaderboardContainer.addView(leaderboardTitleView)
        leaderboardSubtitleView = sectionSubtitle("")
        pageLeaderboardContainer.addView(leaderboardSubtitleView)
        leaderboardModeGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(4), 0, dp(8))
            }
        leaderboard30Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
                isChecked = true
            }
        leaderboard60Button =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
            }
        leaderboardTotalHitsButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
            }
        leaderboardStreakButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                setTextColor(Color.WHITE)
            }
        leaderboardModeGroup.addView(leaderboard30Button)
        leaderboardModeGroup.addView(leaderboard60Button)
        leaderboardModeGroup.addView(leaderboardTotalHitsButton)
        leaderboardModeGroup.addView(leaderboardStreakButton)
        leaderboardModeGroup.setOnCheckedChangeListener { _, checkedId ->
            leaderboardBoard =
                when (checkedId) {
                    leaderboard60Button.id -> LeaderboardBoard.Best60
                    leaderboardTotalHitsButton.id -> LeaderboardBoard.TotalHits
                    leaderboardStreakButton.id -> LeaderboardBoard.LongestStreak
                    else -> LeaderboardBoard.Best30
                }
            leaderboardSubtitleView.text = leaderboardBoardSubtitle(leaderboardBoard)
            if (isActivated() && trainingJob?.isActive != true) {
                refreshLeaderboardOnly()
            }
        }
        pageLeaderboardContainer.addView(leaderboardModeGroup)
        refreshLeaderboardButton =
            compactActionButton("", "#16384A").apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = dp(10)
                    }
                setOnClickListener {
                    leaderboardSwipe.isRefreshing = true
                    refreshLeaderboardOnly()
                }
            }
        pageLeaderboardContainer.addView(refreshLeaderboardButton)
        leaderboardCard =
            surfaceCard().apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(2)
                    }
            }
        leaderboardPodiumContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
        leaderboardRowAdapter = LeaderboardRowAdapter { entry -> leaderboardRowCardPremium(entry) }
        leaderboardListRecycler =
            RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                adapter = leaderboardRowAdapter
                isNestedScrollingEnabled = false
                setPadding(0, dp(12), 0, 0)
                clipToPadding = false
                addItemDecoration(VerticalSpacingDecoration(dp(10)))
            }
        leaderboardMeCard =
            detailCard(fillColor = "#0B1721", strokeColor = "#2A5C7B", cornerDp = 20).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        leaderboardMeTitleView =
            bodyText("").apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#FFB347"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        leaderboardMeView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFF5E6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(8), 0, 0)
            }
        shareLeaderboardButton =
            compactActionButton("", "#16384A").apply {
                setOnClickListener { shareLeaderboardSummary() }
            }
        val leaderboardMeHeaderRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        leaderboardMeTitleView.layoutParams =
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f,
            )
        leaderboardMeHeaderRow.addView(leaderboardMeTitleView)
        leaderboardMeHeaderRow.addView(shareLeaderboardButton)
        leaderboardView =
            bodyText("").apply {
                setTextColor(Color.parseColor("#FFF5E6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        leaderboardCard.addView(leaderboardPodiumContainer)
        leaderboardCard.addView(leaderboardListRecycler)
        leaderboardMeCard.addView(leaderboardMeHeaderRow)
        leaderboardMeCard.addView(leaderboardMeView)
        leaderboardCard.addView(leaderboardMeCard)
        leaderboardCard.addView(leaderboardView)
        pageLeaderboardContainer.addView(leaderboardCard)

        applyStaticTexts()
        contentRoot.addView(topContainer)
        contentRoot.addView(pageHost)
        contentRoot.addView(
            pageTabsCard.apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = dp(14)
                        rightMargin = dp(14)
                        bottomMargin = dp(14)
                    }
            },
        )
        root.addView(contentRoot)
        splashOverlay = buildLaunchSplashOverlay()
        root.addView(splashOverlay)
        return root
    }

    private fun buildLaunchSplashOverlay(): FrameLayout =
        FrameLayout(this).apply {
            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            setBackgroundColor(Color.parseColor("#140800"))
            isClickable = true
            isFocusable = true
            alpha = 1f

            splashVideoView =
                VideoView(this@MainActivity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                }

            val topScrim =
                View(this@MainActivity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(220),
                            Gravity.TOP,
                        )
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.parseColor("#E608111A"), Color.parseColor("#1206001A")),
                        )
                }

            val bottomScrim =
                View(this@MainActivity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(260),
                            Gravity.BOTTOM,
                        )
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.BOTTOM_TOP,
                            intArrayOf(Color.parseColor("#F208111A"), Color.parseColor("#1206001A")),
                        )
                }

            val brandingColumn =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.START,
                        ).apply {
                            leftMargin = dp(26)
                            rightMargin = dp(26)
                            topMargin = dp(42)
                        }
                }

            val brandTitle =
                TextView(this@MainActivity).apply {
                    text = "SMART SENSOR BALL"
                    setTextColor(Color.parseColor("#FFF8E8"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    letterSpacing = 0.08f
                }

            val brandSubtitle =
                TextView(this@MainActivity).apply {
                    text =
                        if (selectedLanguage == AppLanguage.Chinese) {
                            "智能拳击球训练"
                        } else {
                            "Smart sensor ball Training"
                        }
                    setTextColor(Color.parseColor("#CAA26A"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(8), 0, 0)
                }

            splashBrandCard =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    alpha = 0f
                    scaleX = 0.92f
                    scaleY = 0.92f
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        ).apply {
                            leftMargin = dp(24)
                            rightMargin = dp(24)
                        }
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                Color.parseColor("#FFF6E2FF"),
                                Color.parseColor("#FFD88AFF"),
                            ),
                        ).apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(32).toFloat()
                            setStroke(dp(1), Color.parseColor("#E8FBFFFF"))
                        }
                    elevation = dp(8).toFloat()
                    setPadding(dp(24), dp(20), dp(24), dp(18))
                    addView(
                        ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.glowpeak_logo_mark)
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    dp(220),
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "GLOWPEAK"
                            gravity = Gravity.CENTER
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.parseColor("#2D1400"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            letterSpacing = 0.08f
                            setPadding(0, dp(10), 0, 0)
                        },
                    )
                }

            val skipHint =
                TextView(this@MainActivity).apply {
                    text =
                        if (selectedLanguage == AppLanguage.Chinese) {
                            "轻触跳过"
                        } else {
                            "Tap to skip"
                        }
                    setTextColor(Color.parseColor("#B88A54"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                        ).apply {
                            bottomMargin = dp(34)
                        }
                }

            brandingColumn.addView(brandTitle)
            brandingColumn.addView(brandSubtitle)

            addView(splashVideoView)
            addView(topScrim)
            addView(bottomScrim)
            addView(brandingColumn)
            addView(splashBrandCard)
            addView(skipHint)

            setOnClickListener { dismissLaunchSplash() }
        }

    private fun startLaunchSplash() {
        if (!::splashOverlay.isInitialized || !::splashVideoView.isInitialized) {
            return
        }
        splashDismissed = false
        splashOverlay.visibility = View.VISIBLE
        splashOverlay.alpha = 1f
        if (::splashBrandCard.isInitialized) {
            splashBrandCard.animate().cancel()
            splashBrandCard.alpha = 0f
            splashBrandCard.scaleX = 0.78f
            splashBrandCard.scaleY = 0.78f
            splashBrandCard.rotationX = 8f
            splashBrandCard.translationY = dp(32).toFloat()
            splashBrandCard.animate()
                .alpha(1f)
                .scaleX(1.04f)
                .scaleY(1.04f)
                .rotationX(0f)
                .translationY(0f)
                .setStartDelay(180L)
                .setDuration(560L)
                .withEndAction {
                    if (!splashDismissed && ::splashBrandCard.isInitialized) {
                        splashBrandCard.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(220L)
                            .start()
                    }
                }
                .start()
        }
        val splashUri = Uri.parse("android.resource://$packageName/${R.raw.app_launch_intro}")
        splashVideoView.setOnPreparedListener { mediaPlayer ->
            try {
                mediaPlayer.isLooping = false
                mediaPlayer.setVolume(0f, 0f)
                mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            } catch (_: Throwable) {
            }
            splashVideoView.start()
        }
        splashVideoView.setOnCompletionListener { dismissLaunchSplash() }
        splashVideoView.setOnErrorListener { _, _, _ ->
            dismissLaunchSplash()
            true
        }
        splashVideoView.setVideoURI(splashUri)
        splashOverlay.postDelayed({
            if (!splashDismissed) {
                dismissLaunchSplash()
            }
        }, 8000L)
    }

    private fun dismissLaunchSplash() {
        if (!::splashOverlay.isInitialized || splashDismissed) {
            return
        }
        splashDismissed = true
        try {
            if (::splashVideoView.isInitialized) {
                splashVideoView.stopPlayback()
            }
        } catch (_: Throwable) {
        }
        if (::contentRootView.isInitialized) {
            contentRootView.alpha = 0.94f
            contentRootView.translationY = dp(10).toFloat()
            contentRootView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320L)
                .start()
        }
        if (::splashBrandCard.isInitialized) {
            splashBrandCard.animate().cancel()
            splashBrandCard.animate()
                .alpha(0f)
                .scaleX(0.86f)
                .scaleY(0.86f)
                .rotationX(-8f)
                .translationY(-dp(24).toFloat())
                .setDuration(360L)
                .start()
        }
        splashOverlay.animate()
            .alpha(0f)
            .setDuration(320L)
            .withEndAction {
                splashOverlay.visibility = View.GONE
                splashOverlay.alpha = 1f
            }
            .start()
    }

    private fun homePageButton(onClick: () -> Unit): TextView =
        bodyText("").apply {
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(15), dp(12), dp(15))
            compoundDrawablePadding = dp(6)
            isAllCaps = false
            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            applyRippleOverlay()
        }

    private fun pageContentContainer(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }

    private fun wrapInSwipeRefresh(
        content: View,
        enabled: Boolean,
        onRefresh: (() -> Unit)? = null,
    ): SwipeRefreshLayout {
        val scroll =
            ScrollView(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        scroll.addView(content)
        return SwipeRefreshLayout(this).apply {
            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            isEnabled = enabled
            setColorSchemeColors(Color.parseColor("#00FF88"), Color.parseColor("#AAFF00"))
            setProgressBackgroundColorSchemeColor(Color.parseColor("#061410"))
            if (onRefresh != null) {
                setOnRefreshListener { onRefresh() }
            }
            addView(scroll)
        }
    }

    private fun buildTrainingWatermarkPage(content: View): FrameLayout =
        FrameLayout(this).apply {
            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            setBackgroundColor(Color.parseColor("#040C08"))
            addView(
                ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.training_center_watermark)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0.28f
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                },
            )
            addView(
                View(this@MainActivity).apply {
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                Color.parseColor("#F008111A"),
                                Color.parseColor("#B8040C08"),
                                Color.parseColor("#F8040C08"),
                            ),
                        )
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                },
            )
            addView(content)
        }

    private fun bottomNavBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
            colors =
                intArrayOf(
                    Color.parseColor("#0D1822"),
                    Color.parseColor("#061410"),
                )
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(dp(1), Color.parseColor("#1A3A24"))
        }

    private fun homePageIconRes(page: HomePage): Int =
        when (page) {
            HomePage.TrainingCenter -> android.R.drawable.ic_media_play
            HomePage.TrainingAchievements -> android.R.drawable.star_big_on
            HomePage.Leaderboard -> android.R.drawable.ic_menu_sort_by_size
            HomePage.Profile -> android.R.drawable.ic_menu_myplaces
        }

    private fun selectHomePage(page: HomePage) {
        val previousPage = selectedHomePage
        selectedHomePage = page
        refreshHeaderSubtitle()
        refreshHomePageVisibility(previousPage)
    }

    private fun refreshHomePageVisibility(previousPage: HomePage? = null) {
        refreshHeaderSubtitle()
        pageTabsCard.visibility = View.VISIBLE
        trainingSwipe.visibility = if (selectedHomePage == HomePage.TrainingCenter) View.VISIBLE else View.GONE
        achievementsSwipe.visibility =
            if (selectedHomePage == HomePage.TrainingAchievements) View.VISIBLE else View.GONE
        leaderboardSwipe.visibility =
            if (selectedHomePage == HomePage.Leaderboard) View.VISIBLE else View.GONE
        profileSwipe.visibility = if (selectedHomePage == HomePage.Profile) View.VISIBLE else View.GONE
        updateHomePageTabs()
        if (previousPage != null && previousPage != selectedHomePage) {
            when (selectedHomePage) {
                HomePage.Leaderboard -> animatePageEntrance(leaderboardSwipe)
                HomePage.Profile -> animatePageEntrance(profileSwipe)
                else -> {}
            }
        }
    }

    private fun refreshHeaderSubtitle() {
        if (!::subtitleView.isInitialized) {
            return
        }
        val text = headerSubtitleText()
        subtitleView.text = text
        subtitleView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun updateHomePageTabs() {
        applyHomeTabStyle(pageTrainingButton, HomePage.TrainingCenter, selectedHomePage == HomePage.TrainingCenter, true)
        applyHomeTabStyle(pageAchievementsButton, HomePage.TrainingAchievements, selectedHomePage == HomePage.TrainingAchievements, true)
        applyHomeTabStyle(pageLeaderboardButton, HomePage.Leaderboard, selectedHomePage == HomePage.Leaderboard, true)
        applyHomeTabStyle(pageProfileButton, HomePage.Profile, selectedHomePage == HomePage.Profile, true)
    }

    private fun applyHomeTabStyle(
        button: TextView,
        page: HomePage,
        selected: Boolean,
        enabled: Boolean,
    ) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1.0f else 0.45f
        button.translationY = if (selected) -dp(2).toFloat() else 0f
        button.scaleX = if (selected) 1.03f else 1.0f
        button.scaleY = if (selected) 1.03f else 1.0f
        val iconTint =
            if (!enabled) {
                Color.parseColor("#37624A")
            } else if (selected) {
                Color.parseColor("#001A08")
            } else {
                Color.parseColor("#8FEFBC")
            }
        val icon =
            ContextCompat.getDrawable(this, homePageIconRes(page))?.mutate()?.apply {
                setTint(iconTint)
            }
        button.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
        if (selected) {
            button.setTextColor(Color.parseColor("#001A08"))
            button.background = roundedBackground("#80FFB0", "#DFFFF0", 20)
            button.elevation = dp(8).toFloat()
        } else {
            button.setTextColor(Color.parseColor("#8FEFBC"))
            button.background = roundedBackground("#061410", "#1A3A24", 20)
            button.elevation = 0f
        }
    }

    private fun animatePageEntrance(view: View) {
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .start()
    }

    private fun ensurePermissionAndStart() {
        if (trainingJob?.isActive == true) {
            return
        }
        startTraining()
    }

    @Suppress("MissingPermission")










    private fun ensureGyroscopeOffAfterConnection() {
        sensorBallBluetooth.setGyroscopeEnabled(false)
        bluetoothGyroscopeEnabled = false
    }

    private fun startTraining() {
        if (trainingJob?.isActive == true) {
            return
        }
        if (!isActivated()) {
            renderActivationRequired(tr("activation_required"))
            return
        }
        if (bluetoothConnectedDevice == null) {
            renderError(bluetoothConnectFirstText())
            return
        }
        dismissCelebrationBeforeTraining()
        val sessionMode = selectedMode
        val sessionPlayMode = selectedPlayMode
        lastDisplayedCount = 0
        lastSpokenCountdown = null
        goSpoken = false
        bluetoothTrainingCount = 0
        bluetoothTrainingMode = sessionMode
        lastBluetoothGyroRawCount = null
        bluetoothHitCount = 0
        countView.text = "0"
        countdownView.text = "3"
        statusView.text = displayCountdownStatus(3)
        statusView.setTextColor(Color.parseColor("#FFD060"))
        remainingView.text = displayRemaining(sessionMode.durationSeconds * 1_000L)
        quietIconView.visibility = View.GONE
        setTrainingBusyUi(true)
        setActivationVisible(false)
        applyStaticTexts()

        trainingJob =
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    runPreTrainingCueSequence(sessionMode)
                    sensorBallBluetooth.setGyroscopeEnabled(true)
                    bluetoothGyroscopeEnabled = true
                    updateBluetoothSettingsViews()
                    val startMs = SystemClock.elapsedRealtime()
                    val durationMs = sessionMode.durationSeconds * 1_000L
                    while (SystemClock.elapsedRealtime() - startMs < durationMs) {
                        val remainingMs = (durationMs - (SystemClock.elapsedRealtime() - startMs)).coerceAtLeast(0L)
                        remainingView.text = displayRemaining(remainingMs)
                        delay(100L)
                    }
                    val report = buildBluetoothTrainingReport(sessionMode, bluetoothTrainingCount)
                    sensorBallBluetooth.setGyroscopeEnabled(false)
                    bluetoothGyroscopeEnabled = false
                    lastCoachOutcome = updateTrainingGameAfterReport(report, sessionPlayMode)
                    lastCoachMessage = null
                    latestReport = report
                    renderReport(report)
                    renderTrainingPlayStatus()
                    renderTrainingHero()
                    syncTrainingReport(report)
                    setTrainingBusyUi(false)
                    quietIconView.visibility = View.GONE
                    countdownView.text = tr("done_short")
                    statusView.text = tr("training_complete")
                    statusView.setTextColor(Color.parseColor("#FFB347"))
                    remainingView.text = displayRemaining(0L)
                    updateBluetoothSettingsViews()
                    lastCoachOutcome?.let { outcome ->
                        countdownView.postDelayed({
                            if (trainingJob == null) {
                                maybeShowTrainingOutcomeCelebration(report, outcome)
                            }
                        }, 350L)
                    }
                } catch (_: CancellationException) {
                    sensorBallBluetooth.setGyroscopeEnabled(false)
                    bluetoothGyroscopeEnabled = false
                    if (!isDestroyed && !isFinishing && statusView.text != tr("training_stopped")) {
                        renderIdle()
                    }
                } catch (t: Throwable) {
                    sensorBallBluetooth.setGyroscopeEnabled(false)
                    bluetoothGyroscopeEnabled = false
                    renderError(t.message ?: tr("training_failed"))
                } finally {
                    trainingJob = null
                    bluetoothTrainingMode = null
                    updateBluetoothSettingsViews()
                }
            }
    }

    private suspend fun runPreTrainingCueSequence(sessionMode: TrainingMode) {
        quietIconView.visibility = View.GONE
        remainingView.text = displayRemaining(sessionMode.durationSeconds * 1_000L)
        for (value in 3 downTo 1) {
            countdownView.text = value.toString()
            statusView.text = displayCountdownStatus(value)
            statusView.setTextColor(Color.parseColor("#FFD060"))
            lastSpokenCountdown = value
            countdownView.announceForAccessibility(value.toString())
            speakCueAndWait(value.toString(), timeoutMs = 900L)
            delay(120L)
        }
        countdownView.text = displayGoLabel()
        statusView.text = tr("training_live")
        statusView.setTextColor(Color.parseColor("#FFB347"))
        goSpoken = true
        countdownView.announceForAccessibility(displayGoLabel())
        speakCueAndWait(displayGoCue(), timeoutMs = 900L)
    }

    private suspend fun speakCueAndWait(text: String, timeoutMs: Long) {
        val completed = CompletableDeferred<Unit>()
        speakCue(text) {
            if (!completed.isCompleted) {
                completed.complete(Unit)
            }
        }
        withTimeoutOrNull(timeoutMs) {
            completed.await()
        }
    }

    private fun stopTraining(showStoppedState: Boolean) {
        trainingJob?.cancel()
        sensorBallBluetooth.setGyroscopeEnabled(false)
        bluetoothGyroscopeEnabled = false
        tts?.stop()
        trainingJob = null
        bluetoothTrainingMode = null
        lastSpokenCountdown = null
        goSpoken = false
        if (showStoppedState) {
            setTrainingBusyUi(false)
            statusView.text = tr("training_stopped")
            statusView.setTextColor(Color.parseColor("#FFD060"))
            countdownView.text = "--"
            quietIconView.visibility = View.GONE
            applyStaticTexts()
        }
    }

    private fun buildBluetoothTrainingReport(mode: TrainingMode, totalHits: Int): TrainingReport {
        val durationSeconds = mode.durationSeconds.toFloat()
        return TrainingReport(
            mode = mode,
            totalHits = totalHits,
            averageFrequency = if (durationSeconds > 0.0f) totalHits / durationSeconds else 0.0f,
            bestBurstCount = totalHits,
            bestBurstStartSec = 0.0f,
            endedAtEpochMs = System.currentTimeMillis(),
        )
    }


    private fun renderIdle(authMessageKey: String? = null) {
        if (!isActivated()) {
            renderActivationRequired()
            return
        }
        setTrainingBusyUi(false)
        setActivationVisible(authMessageKey != null)
        statusView.text = tr("ready")
        statusView.setTextColor(Color.parseColor("#FFF0C9"))
        countdownView.text = "3"
        countView.text = "0"
        remainingView.text = displayRemaining(selectedMode.durationSeconds * 1_000L)
        quietIconView.visibility = View.GONE
        lastSpokenCountdown = null
        goSpoken = false
        if (authMessageKey != null) {
            setAuthStatusMessage("#FFB347", key = authMessageKey)
        } else {
            clearAuthStatusMessage()
        }
        applyStaticTexts()
        if (latestReport == null) {
            renderEmptyReport()
        }
        if (authMessageKey == "activation_success_ready") {
            activationCard.removeCallbacks(hideActivationCardRunnable)
            activationCard.postDelayed(hideActivationCardRunnable, 3_000L)
        }
    }

    private fun renderActivationRequired(message: String? = null) {
        setTrainingBusyUi(false)
        setActivationBusy(false)
        setActivationVisible(true)
        statusView.text = tr("activation_required")
        statusView.setTextColor(Color.parseColor("#FFB347"))
        countdownView.text = tr("lock_short")
        countView.text = "0"
        remainingView.text = displayRemaining(selectedMode.durationSeconds * 1_000L)
        quietIconView.visibility = View.GONE
        setAuthStatusMessage(
            colorHex = "#FFD060",
            key = if (message == null) "activation_hint" else null,
            fallback = message,
        )
        lastSpokenCountdown = null
        goSpoken = false
        applyStaticTexts()
    }


    private fun renderError(message: String) {
        setTrainingBusyUi(false)
        setActivationVisible(!isActivated())
        statusView.text = message
        statusView.setTextColor(Color.parseColor("#FF8A80"))
        countdownView.text = tr("error_short")
        remainingView.text = displayRemaining(selectedMode.durationSeconds * 1_000L)
        quietIconView.visibility = View.GONE
        lastSpokenCountdown = null
        goSpoken = false
        applyStaticTexts()
    }

    private fun renderReport(report: TrainingReport) {
        reportView.removeAllViews()
        val headerRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val modeChip = badgeText(displayModeLabel(report.mode), fillColor = "#17354A")
        val hitsChip = badgeText("${report.totalHits} ${tr("hits")}", fillColor = "#E07010")
        val spacer =
            View(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        1,
                        1f,
                    )
            }
        headerRow.addView(modeChip)
        headerRow.addView(spacer)
        headerRow.addView(hitsChip)

        val heroTitle =
            titleText(
                localText("本次训练摘要", "Session Summary", "Résumé de séance", "สรุปการฝึก"),
                22f,
            ).apply {
                setTextColor(Color.parseColor("#FFF8E8"))
                setPadding(0, dp(14), 0, 0)
            }
        val summaryLine =
            bodyText(
                displayModeLabel(report.mode),
            ).apply {
                setTextColor(Color.parseColor("#CAA26A"))
                setPadding(0, dp(6), 0, 0)
            }
        val metricsGrid =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(16), 0, 0)
            }
        val topRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        topRow.addView(
            reportMetricCard(
                label = tr("average_frequency"),
                value = String.format(Locale.US, "%.2f %s", report.averageFrequency, tr("hits_per_second")),
                accentColor = "#FF9A30",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        rightMargin = dp(10)
                    }
            },
        )
        topRow.addView(
            reportMetricCard(
                label = tr("best_burst"),
                value = "${report.bestBurstCount} ${tr("hits")}",
                accentColor = "#FFD060",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
            },
        )
        val bottomRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
        bottomRow.addView(
            reportMetricCard(
                label = tr("burst_start"),
                value = String.format(Locale.US, "%.1fs", report.bestBurstStartSec),
                accentColor = "#C084FC",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        rightMargin = dp(10)
                    }
            },
        )
        bottomRow.addView(
            reportMetricCard(
                label = localText("完成时间", "Finished", "Terminé", "เสร็จสิ้น"),
                value = formatReportEndedTime(report.endedAtEpochMs),
                accentColor = "#FFB347",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
            },
        )
        metricsGrid.addView(topRow)
        metricsGrid.addView(bottomRow)

        reportView.addView(headerRow)
        reportView.addView(heroTitle)
        reportView.addView(summaryLine)
        reportView.addView(metricsGrid)
        coachMessageForReport(report)?.takeIf { it.isNotBlank() }?.let { message ->
            reportView.addView(
                detailCard(fillColor = "#241000", strokeColor = "#FFD060", cornerDp = 18).apply {
                    background = metallicBackground("#2D2813", "#1A0C00", "#FFD060", 18)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(14)
                        }
                    addView(
                        bodyText(message).apply {
                            setTextColor(Color.parseColor("#FFF3D3"))
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        },
                    )
                },
            )
        }
        addShareTrainingButtonToReport()
    }

    private fun addShareTrainingButtonToReport() {
        (shareTrainingButton.parent as? ViewGroup)?.removeView(shareTrainingButton)
        val shareRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(16)
                    }
                addView(
                    shareTrainingButton.apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                    },
                )
            }
        reportView.addView(shareRow)
    }

    private fun activateDevice() {
        val serial = normalizeDigits(serialInput.text?.toString()).take(11)
        val code = normalizeDigits(codeInput.text?.toString()).take(8)
        if (serial.length != 11) {
            setAuthStatusMessage("#FFB347", key = "serial_invalid")
            return
        }
        if (code.length != 8) {
            setAuthStatusMessage("#FFB347", key = "code_invalid")
            return
        }

        activationJob?.cancel()
        setActivationBusy(true)
        setAuthStatusMessage("#FFD060", key = "activation_loading")
        activationJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val result =
                    activationService.activate(
                        serial = serial,
                        code = code,
                        installId = installId,
                        deviceHash = deviceHash,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                withContext(Dispatchers.Main) {
                    setActivationBusy(false)
                    handleActivationResult(serial, result)
                }
            }
    }

    private fun handleActivationResult(
        serial: String,
        result: ActivationApiResult,
    ) {
        if (result.success && !result.activationToken.isNullOrBlank()) {
            persistActivationState(result.serial ?: serial, result.activationToken)
            codeInput.setText("")
            renderIdle(authMessageKey = "activation_success_ready")
            refreshCloudData(forceLeaderboard = true)
            return
        }

        setAuthStatusFailure(result.reason, result.message)
    }

    private fun attemptAutoRestoreActivation(force: Boolean = false) {
        if (isActivated()) {
            return
        }
        if (autoRestoreAttempted && !force) {
            return
        }
        autoRestoreAttempted = true
        activationJob?.cancel()
        setActivationBusy(true)
        setAuthStatusMessage("#FFD060", fallback = activationRestoreLoadingMessage())
        activationJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val result =
                    activationService.reactivateByDevice(
                        installId = installId,
                        deviceHash = deviceHash,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                withContext(Dispatchers.Main) {
                    setActivationBusy(false)
                    if (result.success && !result.activationToken.isNullOrBlank() && !result.serial.isNullOrBlank()) {
                        persistActivationState(result.serial, result.activationToken)
                        clearAuthStatusMessage()
                        renderIdle()
                        refreshCloudData(forceLeaderboard = true)
                    } else if (result.reason == ActivationService.NETWORK_REASON) {
                        setAuthStatusMessage("#FFD060", fallback = activationRestoreNetworkMessage())
                    } else {
                        clearAuthStatusMessage()
                        renderActivationRequired()
                    }
                }
            }
    }

    private fun verifyActivationInBackground() {
        val state = activationState ?: return
        activationJob?.cancel()
        activationJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val result =
                    activationService.check(
                        serial = state.serial,
                        activationToken = state.activationToken,
                        installId = installId,
                        deviceHash = deviceHash,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        markActivationCheckedNow()
                        refreshCloudData(forceLeaderboard = true)
                        if (trainingJob?.isActive != true) {
                            clearAuthStatusMessage()
                            applyStaticTexts()
                        }
                    } else if (result.reason != ActivationService.NETWORK_REASON) {
                        clearActivationState()
                        setAuthStatusFailure(result.reason, result.message)
                        renderActivationRequired(currentAuthStatusMessage())
                        attemptAutoRestoreActivation(force = true)
                    }
                }
            }
    }

    private fun ensureInstallIdentity() {
        installId = prefs.getString(KEY_INSTALL_ID, null).orEmpty()
        if (installId.isBlank()) {
            installId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, installId).apply()
        }
        deviceHash = computeDeviceHash()
    }

    private fun loadActivationState() {
        val serial = prefs.getString(KEY_AUTH_SERIAL, null).orEmpty()
        val token = prefs.getString(KEY_AUTH_TOKEN, null).orEmpty()
        if (serial.isBlank() || token.isBlank()) {
            persistActivationState(generateLocalUserSerial(), "local")
            return
        }
        activationState =
            ActivationState(
                serial = serial,
                activationToken = token,
                installId = prefs.getString(KEY_AUTH_INSTALL_ID, installId).orEmpty().ifBlank { installId },
                deviceHash = prefs.getString(KEY_AUTH_DEVICE_HASH, deviceHash).orEmpty().ifBlank { deviceHash },
                activatedAtEpochMs = prefs.getLong(KEY_AUTH_ACTIVATED_AT, System.currentTimeMillis()),
                lastCheckAtEpochMs = prefs.getLong(KEY_AUTH_LAST_CHECK_AT, 0L),
            )
    }

    private fun persistActivationState(
        serial: String,
        activationToken: String,
    ) {
        val now = System.currentTimeMillis()
        activationState =
            ActivationState(
                serial = serial,
                activationToken = activationToken,
                installId = installId,
                deviceHash = deviceHash,
                activatedAtEpochMs = now,
                lastCheckAtEpochMs = now,
            )
        prefs.edit()
            .putString(KEY_AUTH_SERIAL, serial)
            .putString(KEY_AUTH_TOKEN, activationToken)
            .putString(KEY_AUTH_INSTALL_ID, installId)
            .putString(KEY_AUTH_DEVICE_HASH, deviceHash)
            .putLong(KEY_AUTH_ACTIVATED_AT, now)
            .putLong(KEY_AUTH_LAST_CHECK_AT, now)
            .apply()
    }

    private fun clearActivationState() {
        persistActivationState(generateLocalUserSerial(), "local")
    }

    private fun markActivationCheckedNow() {
        val state = activationState ?: return
        val now = System.currentTimeMillis()
        activationState = state.copy(lastCheckAtEpochMs = now)
        prefs.edit().putLong(KEY_AUTH_LAST_CHECK_AT, now).apply()
    }

    private fun isActivated(): Boolean = true

    private fun generateLocalUserSerial(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(installId.toByteArray(Charsets.UTF_8))
        val numeric = digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) % 10).toString() }
        return numeric.take(11).padEnd(11, '0')
    }

    private fun authFailureMessageKey(reason: String?): String? =
        when (reason) {
            "serial_not_found" -> "activation_serial_not_found"
            "invalid_code" -> "activation_invalid_code"
            "already_bound" -> "activation_already_bound"
            "not_activated" -> "activation_not_activated"
            ActivationService.NETWORK_REASON -> "activation_network_error"
            else -> null
        }

    private fun setAuthStatusFailure(
        reason: String?,
        fallbackMessage: String,
    ) {
        val key = authFailureMessageKey(reason)
        setAuthStatusMessage(
            colorHex = if (reason == ActivationService.NETWORK_REASON) "#FFD060" else "#FFB347",
            key = key,
            fallback = if (key == null) fallbackMessage.ifBlank { tr("activation_failed") } else null,
        )
    }

    private fun setAuthStatusMessage(
        colorHex: String,
        key: String? = null,
        fallback: String? = null,
    ) {
        authStatusMessageKey = key
        authStatusFallbackMessage = fallback
        authStatusColor = Color.parseColor(colorHex)
        applyAuthStatusView()
    }

    private fun currentAuthStatusMessage(): String =
        authStatusMessageKey?.let(::tr) ?: authStatusFallbackMessage.orEmpty()

    private fun clearAuthStatusMessage() {
        authStatusMessageKey = null
        authStatusFallbackMessage = null
        applyAuthStatusView()
    }

    private fun applyAuthStatusView() {
        val message = currentAuthStatusMessage()
        authStatusView.text = message
        if (message.isBlank()) {
            authStatusView.visibility = View.GONE
            authStatusView.background = null
            return
        }
        authStatusView.visibility = View.VISIBLE
        authStatusView.setTextColor(authStatusColor)
        authStatusView.background = chipBackground(authStatusColor)
        authStatusView.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun refreshActivationCardState() {
        val activated = isActivated()
        activationCard.background = if (activated) heroBackground("#0E4057") else surfaceCardBackground()
        activationTitleView.text =
            if (activated) tr("activation_ready_title") else tr("activation_title")
        activationHintView.text =
            if (activated) tr("activation_ready_subtitle") else tr("activation_subtitle")
        serialInput.hint = tr("serial_hint")
        codeInput.hint = tr("code_hint")
        activateButton.text = tr("activate")

        serialInput.visibility = if (activated) View.GONE else View.VISIBLE
        codeInput.visibility = if (activated) View.GONE else View.VISIBLE
        if (activated) {
            serialInputErrorView.visibility = View.GONE
            codeInputErrorView.visibility = View.GONE
        }
        activateButton.visibility = if (activated) View.GONE else View.VISIBLE
        activationDetailsView.visibility = if (activated) View.VISIBLE else View.GONE

        if (activated) {
            activationDetailsView.text = buildActivationDetailsText()
            applyAuthStatusView()
        } else {
            activationDetailsView.text = ""
            if (authStatusMessageKey == null && authStatusFallbackMessage.isNullOrBlank()) {
                setAuthStatusMessage("#FFD060", key = "activation_hint")
            } else {
                applyAuthStatusView()
            }
        }
    }

    private fun buildActivationDetailsText(): String {
        val state = activationState ?: return ""
        val serialText = if (state.serial.length <= 4) state.serial else "*******" + state.serial.takeLast(4)
        val checkedAt = formatActivationCheckTime(state.lastCheckAtEpochMs)
        return buildString {
            append(localUserLabel())
            append(": ")
            append(serialText)
            append('\n')
            append(readyStatusLabel())
            append(": ")
            append(readyStatusValue())
            append('\n')
            append(lastSeenLabel())
            append(": ")
            append(checkedAt)
        }
    }

    private fun maskSerial(serial: String): String =
        if (serial.length <= 4) serial else "•••••••" + serial.takeLast(4)

    private fun localUserLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "用户ID"
            AppLanguage.English -> "User ID"
            AppLanguage.French -> "ID utilisateur"
            AppLanguage.Thai -> "รหัสผู้ใช้"
        }

    private fun readyStatusLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "状态"
            AppLanguage.English -> "Status"
            AppLanguage.French -> "Statut"
            AppLanguage.Thai -> "สถานะ"
        }

    private fun readyStatusValue(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "可用"
            AppLanguage.English -> "Ready"
            AppLanguage.French -> "Prêt"
            AppLanguage.Thai -> "พร้อมใช้งาน"
        }

    private fun lastSeenLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "最近同步"
            AppLanguage.English -> "Last sync"
            AppLanguage.French -> "Derniere sync"
            AppLanguage.Thai -> "ซิงก์ล่าสุด"
        }

    private fun formatActivationCheckTime(epochMs: Long): String {
        if (epochMs <= 0L) {
            return tr("activation_just_now")
        }
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
    }

    private fun normalizeDigits(value: String?): String = value.orEmpty().filter { it.isDigit() }

    private fun activationRestoreLoadingMessage(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "正在准备本机用户资料..."
            AppLanguage.English -> "Preparing this device profile..."
            AppLanguage.French -> "Preparation du profil de cet appareil..."
            AppLanguage.Thai -> "กำลังเตรียมโปรไฟล์ของอุปกรณ์นี้..."
        }

    private fun activationRestoreNetworkMessage(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "暂时无法同步本机用户资料，请联网后重试。"
            AppLanguage.English -> "Unable to sync this device profile right now. Please connect to the internet and try again."
            AppLanguage.French -> "Impossible de synchroniser ce profil pour le moment. Connectez-vous a Internet puis reessayez."
            AppLanguage.Thai -> "ยังไม่สามารถซิงก์โปรไฟล์อุปกรณ์นี้ได้ โปรดเชื่อมต่ออินเทอร์เน็ตแล้วลองอีกครั้ง"
        }

    private fun computeDeviceHash(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return sha256Hex(if (androidId.isBlank()) "unknown-device" else androidId)
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun refreshCloudData(forceLeaderboard: Boolean = true) {
        val state = activationState ?: return
        cloudJob?.cancel()
        setCloudStatusMessage("#FFD060", key = "cloud_sync_loading")
        cloudJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val bootstrap =
                    cloudSyncService.bootstrap(
                        state = state,
                        language = selectedLanguage,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                val leaderboard =
                    if (bootstrap.success && forceLeaderboard) {
                        cloudSyncService.fetchLeaderboard(
                            state = state,
                            boardKey = leaderboardBoard.apiKey,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    } else {
                        leaderboardResult
                    }
                withContext(Dispatchers.Main) {
                    applyCloudBootstrap(bootstrap)
                    leaderboard?.let { applyLeaderboardResult(it) }
                }
            }
    }

    private fun refreshLeaderboardOnly() {
        val state = activationState ?: return
        cloudJob?.cancel()
        setCloudStatusMessage("#FFD060", key = "leaderboard_loading")
        cloudJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val leaderboard =
                    cloudSyncService.fetchLeaderboard(
                        state = state,
                        boardKey = leaderboardBoard.apiKey,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                withContext(Dispatchers.Main) {
                    applyLeaderboardResult(leaderboard)
                }
            }
    }

    private fun syncTrainingReport(report: TrainingReport) {
        val state = activationState ?: return
        val previousUnlockedKeys = cloudAchievements.filter { it.unlocked }.map { it.key }.toSet()
        val previousTierLevel = cloudTier?.level ?: cloudProfile?.currentTier ?: prefs.getInt(KEY_LAST_SEEN_TIER, 0)
        lifecycleScope.launch(Dispatchers.IO) {
            val upload =
                cloudSyncService.uploadTrainingSession(
                    state = state,
                    report = report,
                    appVersion = BuildConfig.VERSION_NAME,
                )
                val leaderboard =
                    if (upload.success) {
                        cloudSyncService.fetchLeaderboard(
                            state = state,
                            boardKey = leaderboardBoard.apiKey,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    } else {
                    null
                }
            withContext(Dispatchers.Main) {
                if (upload.success) {
                    val newlyUnlocked = computeNewlyUnlockedAchievements(previousUnlockedKeys, upload.achievements)
                    val promotedTier =
                        upload.tier?.takeIf {
                            shouldCelebrateTier(
                                tier = it,
                                promotedHint = upload.promoted,
                                previousLevel = previousTierLevel,
                            )
                        }
                    cloudProfile = upload.profile ?: cloudProfile
                    cloudStatistics = upload.statistics ?: cloudStatistics
                    cloudHistory = if (upload.history.isNotEmpty()) upload.history else cloudHistory
                    if (upload.achievements.isNotEmpty()) {
                        cloudAchievements = upload.achievements
                    }
                    cloudTier = upload.tier ?: cloudTier
                    syncSeenTier(upload.tier)
                    setCloudStatusMessage("#FFB347", key = "cloud_sync_ready")
                    leaderboard?.let { applyLeaderboardResult(it) }
                    refreshCloudViews()
                    maybeShowPostTrainingCelebrations(newlyUnlocked, promotedTier)
                } else {
                    setCloudStatusMessage(
                        colorHex = "#FFD060",
                        key = if (upload.reason == CloudSyncService.NETWORK_REASON) "cloud_sync_network" else null,
                        fallback = upload.message,
                    )
                    refreshCloudViews()
                }
            }
        }
    }

    private fun applyCloudBootstrap(result: CloudBootstrapResult) {
        if (result.success) {
            cloudProfile = result.profile ?: cloudProfile
            cloudStatistics = result.statistics ?: cloudStatistics
            if (result.history.isNotEmpty()) {
                cloudHistory = result.history
            }
            if (result.achievements.isNotEmpty()) {
                cloudAchievements = result.achievements
            }
            cloudTier = result.tier ?: cloudTier
            syncSeenTier(result.tier)
            setCloudStatusMessage("#FFB347", key = "cloud_sync_ready")
        } else {
            setCloudStatusMessage(
                colorHex = "#FFD060",
                key = if (result.reason == CloudSyncService.NETWORK_REASON) "cloud_sync_network" else null,
                fallback = result.message,
            )
        }
        refreshCloudViews()
    }

    private fun applyLeaderboardResult(result: CloudLeaderboardResult) {
        leaderboardResult = result
        leaderboardBoard = leaderboardBoardFromKey(result.boardKey)
        if (result.success) {
            setCloudStatusMessage("#FFB347", key = "leaderboard_ready")
        } else {
            setCloudStatusMessage(
                colorHex = "#FFD060",
                key = if (result.reason == CloudSyncService.NETWORK_REASON) "cloud_sync_network" else null,
                fallback = result.message,
            )
        }
        refreshCloudViews()
    }

    private fun showEditProfileDialog() {
        val currentProfile = cloudProfile ?: return
        val dialogRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(4))
            }
        var selectedAvatarColor = sanitizeAvatarColor(currentProfile.avatarColor)
        var selectedAvatarUri = currentAvatarImageUri()
        val avatarSwatches = mutableListOf<View>()
        val nicknameInput =
            EditText(this).apply {
                setText(currentProfile.nickname)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#8F6A44"))
                setBackgroundColor(Color.parseColor("#2A1000"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                filters = arrayOf(InputFilter.LengthFilter(64))
            }
        val avatarPreviewShell =
            FrameLayout(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(12)
                    }
                background = avatarBackground(selectedAvatarColor)
                clipToOutline = true
            }
        val avatarPreviewImageView =
            ImageView(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                visibility = View.GONE
            }
        val avatarPreviewFallbackView =
            TextView(this).apply {
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        avatarPreviewShell.addView(avatarPreviewImageView)
        avatarPreviewShell.addView(avatarPreviewFallbackView)
        val avatarPaletteRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        avatarPalette.forEachIndexed { index, color ->
            val swatchVisual =
                View(this).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(dp(24), dp(24)).apply {
                            gravity = Gravity.CENTER
                        }
                    background = roundedBackground(color, if (color == selectedAvatarColor) "#FFFFFF" else color, 999)
                }
            val swatchTouch =
                FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    isClickable = true
                    isFocusable = true
                    contentDescription =
                        String.format(Locale.US, tr("cd_avatar_swatch"), index + 1)
                    addView(swatchVisual)
                    setOnClickListener {
                        selectedAvatarColor = color
                        bindAvatarPresentation(
                            container = avatarPreviewShell,
                            imageView = avatarPreviewImageView,
                            fallbackView = avatarPreviewFallbackView,
                            seedText = nicknameInput.text?.toString(),
                            colorHex = selectedAvatarColor,
                            imageUri = selectedAvatarUri,
                        )
                        avatarSwatches.forEachIndexed { idx, child ->
                            val paletteColor = avatarPalette[idx]
                            child.background =
                                roundedBackground(
                                    paletteColor,
                                    if (paletteColor == selectedAvatarColor) "#FFFFFF" else paletteColor,
                                    999,
                                )
                        }
                    }
                }
            avatarSwatches += swatchVisual
            avatarPaletteRow.addView(swatchTouch)
        }
        val avatarPaletteScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                addView(
                    avatarPaletteRow,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            }
        val avatarActionRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        val chooseAvatarButton =
            compactActionButton(avatarChooseButtonLabel(), "#16384A").apply {
                setOnClickListener {
                    pendingAvatarSelection = { uri ->
                        if (uri != null) {
                            selectedAvatarUri = uri
                            bindAvatarPresentation(
                                container = avatarPreviewShell,
                                imageView = avatarPreviewImageView,
                                fallbackView = avatarPreviewFallbackView,
                                seedText = nicknameInput.text?.toString(),
                                colorHex = selectedAvatarColor,
                                imageUri = selectedAvatarUri,
                            )
                        }
                    }
                    avatarPickerLauncher.launch(arrayOf("image/*"))
                }
            }
        val clearAvatarButton =
            compactActionButton(avatarClearButtonLabel(), "#5C3D99").apply {
                setOnClickListener {
                    selectedAvatarUri = null
                    bindAvatarPresentation(
                        container = avatarPreviewShell,
                        imageView = avatarPreviewImageView,
                        fallbackView = avatarPreviewFallbackView,
                        seedText = nicknameInput.text?.toString(),
                        colorHex = selectedAvatarColor,
                        imageUri = selectedAvatarUri,
                    )
                }
            }
        avatarActionRow.addView(chooseAvatarButton)
        avatarActionRow.addView(horizontalSpace(dp(12)))
        avatarActionRow.addView(clearAvatarButton)
        dialogRoot.addView(sectionLabel(tr("profile_nickname")))
        dialogRoot.addView(nicknameInput)
        dialogRoot.addView(spacer(dp(12)))
        dialogRoot.addView(sectionLabel(tr("profile_avatar")))
        dialogRoot.addView(avatarPreviewShell)
        dialogRoot.addView(avatarActionRow)
        dialogRoot.addView(
            bodyText(avatarImageHintText()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(Color.parseColor("#B88A54"))
                setPadding(0, dp(8), 0, dp(8))
            },
        )
        dialogRoot.addView(avatarPaletteScroll)
        bindAvatarPresentation(
            container = avatarPreviewShell,
            imageView = avatarPreviewImageView,
            fallbackView = avatarPreviewFallbackView,
            seedText = nicknameInput.text?.toString(),
            colorHex = selectedAvatarColor,
            imageUri = selectedAvatarUri,
        )
        nicknameInput.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    bindAvatarPresentation(
                        container = avatarPreviewShell,
                        imageView = avatarPreviewImageView,
                        fallbackView = avatarPreviewFallbackView,
                        seedText = s?.toString(),
                        colorHex = selectedAvatarColor,
                        imageUri = selectedAvatarUri,
                    )
                }

                override fun afterTextChanged(s: android.text.Editable?) = Unit
            },
        )

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(tr("profile_edit"))
                .setView(dialogRoot)
                .setNegativeButton(tr("cancel"), null)
                .setPositiveButton(tr("save"), null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val state = activationState ?: return@setOnClickListener
                val nickname = nicknameInput.text?.toString()?.trim().orEmpty()
                if (nickname.isBlank()) {
                    setCloudStatusMessage("#FFB347", key = "profile_save_failed")
                    refreshCloudViews()
                    return@setOnClickListener
                }
                storeAvatarImageUri(selectedAvatarUri)
                refreshProfileAvatar()
                setCloudStatusMessage("#FFD060", key = "cloud_sync_loading")
                refreshCloudViews()
                lifecycleScope.launch(Dispatchers.IO) {
                    val result =
                        cloudSyncService.updateProfile(
                            state = state,
                            nickname = nickname,
                            language = selectedLanguage,
                            avatarColor = selectedAvatarColor,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    withContext(Dispatchers.Main) {
                        applyCloudBootstrap(result)
                        if (result.success) {
                            setCloudStatusMessage("#FFB347", key = "profile_saved")
                            dialog.dismiss()
                        } else if (result.reason != CloudSyncService.NETWORK_REASON) {
                            setCloudStatusMessage("#FFB347", key = "profile_save_failed", fallback = result.message)
                        }
                        refreshCloudViews()
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#1A0C00"))
    }

    private fun showDeveloperInfoDialog() {
        val scrollView =
            ScrollView(this).apply {
                isFillViewport = true
            }
        val dialogRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(12))
            }
        scrollView.addView(
            dialogRoot,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        dialogRoot.addView(
            bodyText(developerInfoPageSubtitle()).apply {
                setTextColor(Color.parseColor("#FFD88A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, 0, dp(12))
            },
        )

        dialogRoot.addView(sectionLabel(developerCompanySectionTitle()))
        dialogRoot.addView(
            detailCard(fillColor = "#0B1721", strokeColor = "#20384A").apply {
                addView(
                    titleText(developerCompanyName(), 18f).apply {
                        setTextColor(Color.parseColor("#FFF8E8"))
                    },
                )
                addView(
                    bodyText(developerCompanyDescription()).apply {
                        setTextColor(Color.parseColor("#FFD88A"))
                        setPadding(0, dp(8), 0, 0)
                    },
                )
            },
        )

        dialogRoot.addView(spacer(dp(12)))
        dialogRoot.addView(sectionLabel(developerContactSectionTitle()))
        dialogRoot.addView(
            detailCard(fillColor = "#0B1721", strokeColor = "#20384A").apply {
                addView(
                    bodyText(developerEmailLabel()).apply {
                        setTextColor(Color.parseColor("#B88A54"))
                        setTypeface(Typeface.DEFAULT_BOLD)
                    },
                )
                addView(
                    titleText(DEVELOPER_EMAIL, 18f).apply {
                        setTextColor(Color.parseColor("#FFB347"))
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                addView(
                    compactActionButton(developerEmailActionLabel(), "#E07010").apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(12)
                            }
                        setOnClickListener { openDeveloperEmail() }
                    },
                )
            },
        )

        dialogRoot.addView(spacer(dp(12)))
        dialogRoot.addView(sectionLabel(developerExtrasSectionTitle()))
        dialogRoot.addView(
            detailCard(fillColor = "#0B1721", strokeColor = "#20384A").apply {
                addView(
                    bodyText("${developerVersionLabel()}: ${displayAppVersion()}").apply {
                        setTextColor(Color.parseColor("#FFF0C9"))
                        setTypeface(Typeface.DEFAULT_BOLD)
                    },
                )
                addView(
                    bodyText(developerDocumentHint()).apply {
                        setTextColor(Color.parseColor("#B88A54"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                addView(
                    compactActionButton(privacyPolicyEntryLabel(), "#16384A").apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(12)
                            }
                        setOnClickListener {
                            showDeveloperDocumentDialog(
                                title = privacyPolicyEntryLabel(),
                                assetFile = developerPrivacyPolicyAssetFile(),
                            )
                        }
                    },
                )
                addView(
                    compactActionButton(userAgreementEntryLabel(), "#1F3B52").apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(10)
                            }
                        setOnClickListener {
                            showDeveloperDocumentDialog(
                                title = userAgreementEntryLabel(),
                                assetFile = developerUserAgreementAssetFile(),
                            )
                        }
                    },
                )
            },
        )

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(developerInfoPageTitle())
                .setView(scrollView)
                .setPositiveButton(closeLabel(), null)
                .create()
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#1A0C00"))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.88f).toInt(),
        )
    }

    private fun showDeveloperDocumentDialog(
        title: String,
        assetFile: String,
    ) {
        val content = loadAssetText(assetFile).ifBlank { developerDocumentUnavailableText() }
        val scrollView =
            ScrollView(this).apply {
                isFillViewport = true
            }
        val body =
            bodyText(content).apply {
                setTextColor(Color.parseColor("#FFF0C9"))
                setLineSpacing(dp(4).toFloat(), 1.15f)
                setPadding(dp(20), dp(18), dp(20), dp(12))
            }
        scrollView.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton(closeLabel(), null)
                .create()
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#1A0C00"))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.88f).toInt(),
        )
    }

    private fun refreshCloudViews() {
        val activated = isActivated()
        refreshHomePageVisibility()
        renderTrainingHero()
        if (!activated) {
            return
        }

        profileSummaryView.text = buildProfileSummaryText()
        profileMetaView.text = buildProfileMetaSummary()
        profileTierView.text = buildProfileTierSummary()
        profileStatsView.text = buildProfileStatsOverview()
        profileBadgesView.text = buildRecentBadgeSummary()
        refreshProfileAvatar()
        cloudStatusView.setTextColor(cloudStatusColor)
        cloudStatusView.text = currentCloudStatusMessage().ifBlank { tr("cloud_sync_idle") }
        cloudStatusView.background = chipBackground(cloudStatusColor)
        renderAchievements()
        renderHistoryCards()
        renderLeaderboard()
        refreshCloudListLocaleBindings()
        stopSwipeRefreshSpinners()
    }

    private fun refreshCloudListLocaleBindings() {
        if (::historyItemAdapter.isInitialized && historyItemAdapter.currentList.isNotEmpty()) {
            historyItemAdapter.notifyDataSetChanged()
        }
        if (::leaderboardRowAdapter.isInitialized && leaderboardRowAdapter.currentList.isNotEmpty()) {
            leaderboardRowAdapter.notifyDataSetChanged()
        }
    }

    private fun stopSwipeRefreshSpinners() {
        if (::trainingSwipe.isInitialized) trainingSwipe.isRefreshing = false
        if (::achievementsSwipe.isInitialized) achievementsSwipe.isRefreshing = false
        if (::leaderboardSwipe.isInitialized) leaderboardSwipe.isRefreshing = false
        if (::profileSwipe.isInitialized) profileSwipe.isRefreshing = false
    }

    private fun renderTrainingHero() {
        val activated = isActivated()
        val stats = cloudStatistics
        val tier = cloudTier
        trainingHeroBadgeView.text =
            when (selectedLanguage) {
                AppLanguage.Chinese -> "训练中心"
                AppLanguage.English -> "TRAINING CENTER"
                AppLanguage.French -> "CENTRE D'ENTRAÎNEMENT"
                AppLanguage.Thai -> "ศูนย์ฝึก"
            }
        trainingHeroHeadlineView.text =
            when {
                activated && tier != null -> tierLabelForKey(tier.key)
                else -> tr("title")
            }
        trainingHeroSummaryView.text =
            when {
                !activated ->
                    when (selectedLanguage) {
                        AppLanguage.Chinese -> "准备好本机用户资料后，即可开启训练成长与段位挑战。"
                        AppLanguage.English -> "Your device profile is getting ready for training progress and rank challenges."
                        AppLanguage.French -> "Votre profil appareil se prepare pour la progression et les defis de rang."
                        AppLanguage.Thai -> "กำลังเตรียมโปรไฟล์อุปกรณ์สำหรับความก้าวหน้าและความท้าทายด้านระดับ"
                    }
                stats != null ->
                    when (selectedLanguage) {
                        AppLanguage.Chinese -> "最佳30秒 ${stats.best30Hits} 次 · 最佳60秒 ${stats.best60Hits} 次 · 累计 ${stats.totalHits} 次"
                        AppLanguage.English -> "Best 30s ${stats.best30Hits} · Best 60s ${stats.best60Hits} · ${stats.totalHits} total hits"
                        AppLanguage.French -> "Meilleur 30 s ${stats.best30Hits} · Meilleur 60 s ${stats.best60Hits} · ${stats.totalHits} frappes au total"
                        AppLanguage.Thai -> "ดีที่สุด 30 วินาที ${stats.best30Hits} · ดีที่สุด 60 วินาที ${stats.best60Hits} · สะสมทั้งหมด ${stats.totalHits} ครั้ง"
                    }
                else ->
                    when (selectedLanguage) {
                        AppLanguage.Chinese -> "云端训练数据正在同步，稍后即可看到你的成绩、段位与成长进度。"
                        AppLanguage.English -> "Cloud data is syncing. Your scores, rank, and growth progress will appear soon."
                        AppLanguage.French -> "Les données cloud sont en cours de synchronisation. Vos scores et votre progression apparaîtront bientôt."
                        AppLanguage.Thai -> "กำลังซิงก์ข้อมูลคลาวด์ ไม่นานคุณจะเห็นคะแนน ระดับ และความคืบหน้า"
                    }
            }
        trainingHeroInsightView.text =
            latestReport?.let { report ->
                when (selectedLanguage) {
                    AppLanguage.Chinese ->
                        "最新战报：${displayModeLabel(report.mode)} · ${report.totalHits} 次 · ${tr("best_burst")} ${report.bestBurstCount}"
                    else ->
                        "Latest report: ${displayModeLabel(report.mode)} · ${report.totalHits} hits · ${tr("best_burst")} ${report.bestBurstCount}"
                }
            } ?: when (selectedLanguage) {
                AppLanguage.Chinese -> "暂无最新战报，完成一轮训练后这里会展示你的核心成绩。"
                else -> "No battle report yet. Finish a session and your key stats will appear here."
            }
        trainingHeroProgressView.text =
            when {
                !activated ->
                    when (selectedLanguage) {
                        AppLanguage.Chinese -> "训练记录、成就、榜单与个人成长数据将自动同步"
                        AppLanguage.English -> "Cloud records, achievements, leaderboards, and profile progress sync automatically."
                        AppLanguage.French -> "Historique cloud, succes, classements et progression se synchronisent automatiquement."
                        AppLanguage.Thai -> "ประวัติ ความสำเร็จ กระดานจัดอันดับ และความก้าวหน้าจะซิงก์อัตโนมัติ"
                    }
                tier != null && tier.nextHits != null && tier.nextKey != null -> {
                    val best30 = stats?.best30Hits ?: tier.bestHits
                    val remaining = (tier.nextHits - best30).coerceAtLeast(0)
                    when (selectedLanguage) {
                        AppLanguage.Chinese -> "距离 ${tierLabelForKey(tier.nextKey)} 还差 $remaining 击"
                        AppLanguage.English -> "$remaining hits to ${tierLabelForKey(tier.nextKey)}"
                        AppLanguage.French -> "Encore $remaining frappes pour ${tierLabelForKey(tier.nextKey)}"
                        AppLanguage.Thai -> "อีก $remaining ครั้งจะถึง ${tierLabelForKey(tier.nextKey)}"
                    }
                }
                tier != null ->
                    when (selectedLanguage) {
                        AppLanguage.Chinese -> "已达到当前最高段位，继续保持你的拳击状态"
                        AppLanguage.English -> "Top tier reached. Keep pushing your boxing pace."
                        AppLanguage.French -> "Rang maximal atteint. Continuez à maintenir votre rythme."
                        AppLanguage.Thai -> "ถึงระดับสูงสุดแล้ว รักษาจังหวะการฝึกของคุณต่อไป"
                    }
                else -> currentCloudStatusMessage().ifBlank { tr("cloud_sync_idle") }
            }
        trainingHeroCard.background = heroBackground("#008840")
        shareTrainingButton.alpha = if (latestReport != null) 1.0f else 0.72f
        shareTrainingButton.isEnabled = latestReport != null
    }

    private fun trainingPlayModeForCheckedId(checkedId: Int): TrainingPlayMode =
        when (checkedId) {
            mode60Button.id -> TrainingPlayMode.Classic60
            modeBurst10Button.id -> TrainingPlayMode.Burst10
            modeBurst15Button.id -> TrainingPlayMode.Burst15
            modeLevelButton.id -> TrainingPlayMode.LevelChallenge
            modeDailyButton.id -> TrainingPlayMode.DailyChallenge
            else -> TrainingPlayMode.Classic30
        }

    private fun configureModeButton(button: RadioButton) {
        button.includeFontPadding = false
        button.minHeight = dp(38)
        button.minimumHeight = dp(38)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        button.setPadding(dp(12), dp(7), dp(12), dp(7))
        button.layoutParams =
            RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(6)
            }
    }

    private fun refreshModeButtonStyles() {
        if (!::mode30Button.isInitialized || !::modeDailyButton.isInitialized) {
            return
        }
        val items =
            listOf(
                Triple(mode30Button, TrainingPlayMode.Classic30, "#FF9A30"),
                Triple(mode60Button, TrainingPlayMode.Classic60, "#FFB347"),
                Triple(modeBurst10Button, TrainingPlayMode.Burst10, "#FFD060"),
                Triple(modeBurst15Button, TrainingPlayMode.Burst15, "#FF9A30"),
                Triple(modeLevelButton, TrainingPlayMode.LevelChallenge, "#C084FC"),
                Triple(modeDailyButton, TrainingPlayMode.DailyChallenge, "#E07010"),
            )
        items.forEach { (button, playMode, accentColor) ->
            val selected = selectedPlayMode == playMode
            button.setTextColor(Color.parseColor(if (selected) "#FFF8E8" else "#E5C98A"))
            button.text = coloredModeLabel(playMode, accentColor)
            button.setTypeface(if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            button.background =
                roundedBackground(
                    fillColor = if (selected) "#173247" else "#0A1721",
                    strokeColor = if (selected) accentColor else "#1B3344",
                    cornerDp = 18,
                )
            button.alpha = if (trainingJob?.isActive == true) 0.62f else 1.0f
        }
    }

    private fun coloredModeLabel(
        playMode: TrainingPlayMode,
        accentColor: String,
    ): SpannableString {
        val label = playModeLabel(playMode)
        return SpannableString(label).apply {
            if (label.isNotEmpty()) {
                setSpan(
                    ForegroundColorSpan(Color.parseColor(accentColor)),
                    0,
                    1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    RelativeSizeSpan(1.22f),
                    0,
                    1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun modeForPlayMode(playMode: TrainingPlayMode): TrainingMode =
        when (playMode) {
            TrainingPlayMode.Classic30,
            TrainingPlayMode.LevelChallenge,
            TrainingPlayMode.DailyChallenge,
            -> TrainingMode.Seconds30

            TrainingPlayMode.Classic60 -> TrainingMode.Seconds60
            TrainingPlayMode.Burst10 -> TrainingMode.Burst10
            TrainingPlayMode.Burst15 -> TrainingMode.Burst15
        }

    private fun playModeLabel(playMode: TrainingPlayMode): String =
        when (playMode) {
            TrainingPlayMode.Classic30 ->
                localText("● 经典30秒", "● Classic 30s", "● Classique 30s", "● คลาสสิก 30วิ")

            TrainingPlayMode.Classic60 ->
                localText("◆ 60秒耐力", "◆ Endurance 60s", "◆ Endurance 60s", "◆ อึด 60วิ")

            TrainingPlayMode.Burst10 ->
                localText("▲ 10秒爆发", "▲ Burst 10s", "▲ Explosif 10s", "▲ ระเบิด 10วิ")

            TrainingPlayMode.Burst15 ->
                localText("▲ 15秒爆发", "▲ Burst 15s", "▲ Explosif 15s", "▲ ระเบิด 15วิ")

            TrainingPlayMode.LevelChallenge ->
                localText("★ 闯关挑战", "★ Level Challenge", "★ Défi niveau", "★ ด่านท้าทาย")

            TrainingPlayMode.DailyChallenge ->
                localText("✓ 每日挑战", "✓ Daily Challenge", "✓ Défi quotidien", "✓ ภารกิจวันนี้")
        }

    private fun renderTrainingPlayStatus() {
        if (!::trainingPlayTitleView.isInitialized) {
            return
        }
        val goal = currentTrainingGoalPresentation()
        trainingPlayTitleView.text = goal.title
        trainingPlayBodyView.text = goal.body
        trainingPlayProgressView.text = buildTrainingProgressLine(goal.targetHits)
        trainingPlayCard.background = metallicBackground("#142F42", "#08131C", goal.accentColor, 22)
    }

    private fun currentTrainingGoalPresentation(): TrainingGoalPresentation {
        return trainingGoalPresentationFor(selectedPlayMode)
    }

    private fun trainingGoalPresentationFor(playMode: TrainingPlayMode): TrainingGoalPresentation {
        val level = currentTrainingLevelDefinition()
        val dailyTarget = dailyChallengeTargetHits()
        return when (playMode) {
            TrainingPlayMode.Classic30 ->
                TrainingGoalPresentation(
                    title = localText("经典训练", "Classic Training", "Entraînement classique", "ฝึกคลาสสิก"),
                    body = localText(
                        "30 秒稳定计数，适合每天热身、测试节奏和刷新历史最佳。",
                        "A steady 30-second session for warm-up, rhythm checks, and best-score attempts.",
                        "Session stable de 30 s pour s'échauffer, tester le rythme et battre son record.",
                        "ฝึก 30 วินาทีแบบมั่นคง เหมาะสำหรับวอร์มอัพ เช็กจังหวะ และทำสถิติใหม่",
                    ),
                    accentColor = "#FF9A30",
                )

            TrainingPlayMode.Classic60 ->
                TrainingGoalPresentation(
                    title = localText("耐力训练", "Endurance Training", "Entraînement endurance", "ฝึกความอึด"),
                    body = localText(
                        "60 秒持续输出，适合练稳定性、耐力和连续节奏。",
                        "A 60-second session for consistency, stamina, and sustained rhythm.",
                        "Session de 60 s pour travailler la régularité, l'endurance et le rythme continu.",
                        "ฝึก 60 วินาที เพื่อความสม่ำเสมอ ความอึด และจังหวะต่อเนื่อง",
                    ),
                    accentColor = "#FFB347",
                )

            TrainingPlayMode.Burst10 ->
                TrainingGoalPresentation(
                    title = localText("10 秒爆发", "10-second Burst", "Explosif 10 s", "ระเบิด 10 วิ"),
                    body = localText(
                        "短时间冲刺，目标 25 击；更适合练启动速度和瞬间爆发。",
                        "Short sprint mode. Target 25 hits for launch speed and explosive rhythm.",
                        "Sprint court. Objectif 25 coups pour travailler le départ et l'explosivité.",
                        "โหมดสปรินต์สั้น เป้าหมาย 25 ครั้ง เพื่อฝึกความเร็วเริ่มต้นและแรงระเบิด",
                    ),
                    accentColor = "#FFD060",
                    targetHits = 25,
                )

            TrainingPlayMode.Burst15 ->
                TrainingGoalPresentation(
                    title = localText("15 秒爆发", "15-second Burst", "Explosif 15 s", "ระเบิด 15 วิ"),
                    body = localText(
                        "稍长冲刺，目标 38 击；兼顾爆发和控制。",
                        "A longer sprint. Target 38 hits while keeping control.",
                        "Sprint plus long. Objectif 38 coups en gardant le contrôle.",
                        "สปรินต์นานขึ้น เป้าหมาย 38 ครั้ง พร้อมคุมจังหวะให้ดี",
                    ),
                    accentColor = "#FFD060",
                    targetHits = 38,
                )

            TrainingPlayMode.LevelChallenge ->
                TrainingGoalPresentation(
                    title = localText(
                        "第 ${level.level} 关 · 目标 ${level.targetHits} 击",
                        "Level ${level.level} · ${level.targetHits} hits",
                        "Niveau ${level.level} · ${level.targetHits} coups",
                        "ด่าน ${level.level} · เป้าหมาย ${level.targetHits} ครั้ง",
                    ),
                    body = localText(
                        "完成本关即可解锁下一关。闯关模式会把训练变成可持续推进的成长路线。",
                        "Clear the target to unlock the next level and turn training into steady progression.",
                        "Atteignez l'objectif pour débloquer le niveau suivant et progresser régulièrement.",
                        "ทำให้ถึงเป้าหมายเพื่อปลดล็อกด่านถัดไป และเปลี่ยนการฝึกให้เป็นเส้นทางเติบโต",
                    ),
                    accentColor = "#C084FC",
                    targetHits = level.targetHits,
                )

            TrainingPlayMode.DailyChallenge ->
                TrainingGoalPresentation(
                    title = localText(
                        "今日挑战 · 目标 $dailyTarget 击",
                        "Daily Challenge · $dailyTarget hits",
                        "Défi du jour · $dailyTarget coups",
                        "ภารกิจวันนี้ · $dailyTarget ครั้ง",
                    ),
                    body = localText(
                        "每天一个轻量目标，完成后记录今日任务奖励，适合培养连续训练习惯。",
                        "A lightweight daily target that rewards consistency and helps build a training habit.",
                        "Un objectif léger chaque jour pour récompenser la régularité et créer l'habitude.",
                        "เป้าหมายเบา ๆ รายวัน ช่วยให้ฝึกต่อเนื่องและสร้างนิสัยการฝึก",
                    ),
                    accentColor = "#E07010",
                    targetHits = dailyTarget,
                )
        }
    }

    private fun updateTrainingGameAfterReport(
        report: TrainingReport,
        playMode: TrainingPlayMode,
    ): TrainingCoachOutcome {
        val today = todayKey()
        ensureDailyTaskDate(today)
        saveLocalSessionSummary(report)
        val streak = updateTrainingStreak(today)
        prefs.edit().putBoolean(KEY_DAILY_TASK_TRAINED, true).apply()

        var xpGain = 10
        val levelBefore = currentTrainingLevelDefinition().level
        var levelAfter = levelBefore
        val currentGoal = trainingGoalPresentationFor(playMode)
        val goalMet = currentGoal.targetHits?.let { report.totalHits >= it } == true
        if (goalMet) {
            xpGain += 10
            prefs.edit().putBoolean(KEY_DAILY_TASK_TARGET_DONE, true).apply()
        }

        when (playMode) {
            TrainingPlayMode.LevelChallenge -> {
                val level = currentTrainingLevelDefinition()
                if (report.totalHits >= level.targetHits) {
                    val nextLevel = (level.level + 1).coerceAtMost(trainingLevelDefinitions().size)
                    prefs.edit().putInt(KEY_TRAINING_LEVEL, nextLevel).apply()
                    levelAfter = nextLevel
                    xpGain += 25
                }
            }

            TrainingPlayMode.DailyChallenge -> {
                val target = dailyChallengeTargetHits()
                if (report.totalHits >= target) {
                    xpGain += 20
                }
            }

            TrainingPlayMode.Burst10,
            TrainingPlayMode.Burst15,
            -> {
                if (goalMet) {
                    xpGain += 15
                }
            }

            TrainingPlayMode.Classic30,
            TrainingPlayMode.Classic60,
            -> Unit
        }

        addTrainingXp(xpGain)
        return TrainingCoachOutcome(
            playMode = playMode,
            goalMet = goalMet,
            levelBefore = levelBefore,
            levelAfter = levelAfter,
            targetHits = currentGoal.targetHits,
            streak = streak,
            xpGain = xpGain,
        )
    }

    private fun coachMessageForReport(report: TrainingReport): String? =
        lastCoachOutcome?.let { buildCoachMessage(report, it) } ?: lastCoachMessage

    private fun buildCoachMessage(
        report: TrainingReport,
        outcome: TrainingCoachOutcome,
    ): String {
        val challengeMessage = challengeMessageForOutcome(report, outcome)
        val trend = sevenDayTrendText()
        val paceLine =
            when {
                report.averageFrequency >= 3.0f ->
                    localText("高爆发节奏，注意保持动作质量。", "High burst rhythm. Keep the movement quality clean.")

                report.averageFrequency >= 2.0f ->
                    localText("节奏很稳，适合继续挑战更高目标。", "Solid rhythm. You are ready to chase a higher target.")

                else ->
                    localText("先稳住命中质量，再逐步加速。", "Lock in clean hits first, then build speed gradually.")
            }
        return localText(
            "$challengeMessage $paceLine 连续训练 ${outcome.streak} 天，XP +${outcome.xpGain}。$trend",
            "$challengeMessage $paceLine Streak ${outcome.streak} day(s), XP +${outcome.xpGain}. $trend",
        )
    }

    private fun challengeMessageForOutcome(
        report: TrainingReport,
        outcome: TrainingCoachOutcome,
    ): String =
        when (outcome.playMode) {
            TrainingPlayMode.LevelChallenge -> {
                val target = outcome.targetHits ?: 0
                if (outcome.goalMet) {
                    if (outcome.levelAfter > outcome.levelBefore) {
                        localText(
                            "闯关成功，已解锁第 ${outcome.levelAfter} 关。",
                            "Level cleared. Level ${outcome.levelAfter} unlocked.",
                        )
                    } else {
                        localText("已完成最高关卡，继续刷新极限。", "Top level cleared. Keep pushing your limit.")
                    }
                } else {
                    val remaining = (target - report.totalHits).coerceAtLeast(0)
                    localText(
                        "本关还差 $remaining 击，下次优先稳住节奏。",
                        "$remaining hits to clear this level. Keep the rhythm steady next time.",
                    )
                }
            }

            TrainingPlayMode.DailyChallenge -> {
                val target = outcome.targetHits ?: dailyChallengeTargetHits()
                if (outcome.goalMet) {
                    localText("今日挑战完成，已记录任务奖励。", "Daily challenge completed and rewarded.")
                } else {
                    val remaining = (target - report.totalHits).coerceAtLeast(0)
                    localText(
                        "今日挑战还差 $remaining 击，可以再来一轮。",
                        "$remaining hits short of today's challenge. One more run can do it.",
                    )
                }
            }

            TrainingPlayMode.Burst10,
            TrainingPlayMode.Burst15,
            -> {
                if (outcome.goalMet) {
                    localText("爆发目标达成，启动速度很漂亮。", "Burst target reached. Your launch speed looks sharp.")
                } else {
                    localText("爆发训练已完成，下一轮可以尝试把前 3 秒打得更主动。", "Burst session complete. Try attacking the first 3 seconds harder next round.")
                }
            }

            TrainingPlayMode.Classic30,
            TrainingPlayMode.Classic60,
            -> localText("训练已记录，今天的节奏又往前推进了一步。", "Session recorded. Today's rhythm moved one step forward.")
        }

    private fun buildTrainingProgressLine(targetHits: Int?): String {
        ensureDailyTaskDate()
        val targetText =
            targetHits?.let {
                localText("本轮目标 $it 击", "Target $it hits")
            } ?: localText("自由训练", "Free training")
        return localText(
            "$targetText · ${dailyTaskSummaryText()} · ${sevenDayTrendText()}",
            "$targetText · ${dailyTaskSummaryText()} · ${sevenDayTrendText()}",
        )
    }

    private fun dailyTaskSummaryText(): String {
        ensureDailyTaskDate()
        val doneCount =
            listOf(
                prefs.getBoolean(KEY_DAILY_TASK_TRAINED, false),
                prefs.getBoolean(KEY_DAILY_TASK_TARGET_DONE, false),
                prefs.getBoolean(KEY_DAILY_TASK_SHARED, false),
            ).count { it }
        val streak = prefs.getInt(KEY_TRAINING_STREAK, 0)
        val xp = prefs.getInt(KEY_TRAINING_XP, 0)
        return localText(
            "今日任务 $doneCount/3 · 连续 $streak 天 · XP $xp",
            "Daily tasks $doneCount/3 · Streak $streak · XP $xp",
        )
    }

    private fun markTrainingSharedForDailyTask() {
        ensureDailyTaskDate()
        prefs.edit().putBoolean(KEY_DAILY_TASK_SHARED, true).apply()
        renderTrainingPlayStatus()
    }

    private fun trainingLevelDefinitions(): List<TrainingLevelDefinition> =
        listOf(
            TrainingLevelDefinition(1, 12),
            TrainingLevelDefinition(2, 18),
            TrainingLevelDefinition(3, 25),
            TrainingLevelDefinition(4, 32),
            TrainingLevelDefinition(5, 40),
            TrainingLevelDefinition(6, 50),
            TrainingLevelDefinition(7, 60),
            TrainingLevelDefinition(8, 75),
            TrainingLevelDefinition(9, 90),
        )

    private fun currentTrainingLevelDefinition(): TrainingLevelDefinition {
        val levels = trainingLevelDefinitions()
        val level = prefs.getInt(KEY_TRAINING_LEVEL, 1).coerceIn(1, levels.size)
        return levels.firstOrNull { it.level == level } ?: levels.first()
    }

    private fun dailyChallengeTargetHits(): Int {
        val best30 = max(cloudStatistics?.best30Hits ?: 0, bestLocalThirtySecondHits())
        return if (best30 <= 0) {
            20
        } else {
            max(18, (best30 * 0.82f).toInt()).coerceIn(18, 120)
        }
    }

    private fun bestLocalThirtySecondHits(): Int =
        loadLocalSessionSummaries()
            .filter { it.durationSeconds == 30 }
            .map { it.hits }
            .maxOrNull() ?: 0

    private fun addTrainingXp(value: Int) {
        val current = prefs.getInt(KEY_TRAINING_XP, 0)
        prefs.edit().putInt(KEY_TRAINING_XP, (current + value).coerceAtMost(999_999)).apply()
    }

    private fun updateTrainingStreak(today: String): Int {
        val previousDate = prefs.getString(KEY_TRAINING_LAST_DATE, null)
        val current = prefs.getInt(KEY_TRAINING_STREAK, 0)
        val next =
            when {
                previousDate == today -> current.coerceAtLeast(1)
                previousDate == dayKey(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)) -> current + 1
                else -> 1
            }
        val best = max(prefs.getInt(KEY_BEST_TRAINING_STREAK, 0), next)
        prefs.edit()
            .putString(KEY_TRAINING_LAST_DATE, today)
            .putInt(KEY_TRAINING_STREAK, next)
            .putInt(KEY_BEST_TRAINING_STREAK, best)
            .apply()
        return next
    }

    private fun ensureDailyTaskDate(today: String = todayKey()) {
        if (prefs.getString(KEY_DAILY_TASK_DATE, null) == today) {
            return
        }
        prefs.edit()
            .putString(KEY_DAILY_TASK_DATE, today)
            .putBoolean(KEY_DAILY_TASK_TRAINED, false)
            .putBoolean(KEY_DAILY_TASK_TARGET_DONE, false)
            .putBoolean(KEY_DAILY_TASK_SHARED, false)
            .apply()
    }

    private fun saveLocalSessionSummary(report: TrainingReport) {
        val summaries = loadLocalSessionSummaries().toMutableList()
        summaries.add(
            LocalSessionSummary(
                dateKey = dayKey(report.endedAtEpochMs),
                endedAtMs = report.endedAtEpochMs,
                durationSeconds = report.mode.durationSeconds,
                hits = report.totalHits,
                playMode = selectedPlayMode.name,
            ),
        )
        val array = JSONArray()
        summaries.sortedByDescending { it.endedAtMs }.take(60).forEach { item ->
            array.put(
                JSONObject()
                    .put("date", item.dateKey)
                    .put("endedAt", item.endedAtMs)
                    .put("duration", item.durationSeconds)
                    .put("hits", item.hits)
                    .put("playMode", item.playMode),
            )
        }
        prefs.edit().putString(KEY_LOCAL_TRAINING_SESSIONS, array.toString()).apply()
    }

    private fun loadLocalSessionSummaries(): List<LocalSessionSummary> {
        val raw = prefs.getString(KEY_LOCAL_TRAINING_SESSIONS, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        LocalSessionSummary(
                            dateKey = item.optString("date"),
                            endedAtMs = item.optLong("endedAt"),
                            durationSeconds = item.optInt("duration"),
                            hits = item.optInt("hits"),
                            playMode = item.optString("playMode"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun sevenDayTrendText(): String {
        val sessions = loadLocalSessionSummaries().sortedBy { it.endedAtMs }.takeLast(12)
        if (sessions.size < 2) {
            return localText("7天趋势：等待更多数据", "7-day trend: collecting data")
        }
        val splitIndex = (sessions.size / 2).coerceAtLeast(1)
        val early = sessions.take(splitIndex)
        val recent = sessions.drop(splitIndex).ifEmpty { sessions.takeLast(1) }
        val earlyAverage = early.map { it.hits }.average()
        val recentAverage = recent.map { it.hits }.average()
        val diff = recentAverage - earlyAverage
        return when {
            diff >= 2.0 ->
                localText(
                    "7天趋势：提升 +${String.format(Locale.US, "%.1f", diff)}",
                    "7-day trend: +${String.format(Locale.US, "%.1f", diff)}",
                )

            diff <= -2.0 ->
                localText(
                    "7天趋势：回落 ${String.format(Locale.US, "%.1f", diff)}",
                    "7-day trend: ${String.format(Locale.US, "%.1f", diff)}",
                )

            else ->
                localText("7天趋势：稳定", "7-day trend: stable")
        }
    }

    private fun todayKey(): String = dayKey(System.currentTimeMillis())

    private fun dayKey(epochMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(epochMs))

    private fun localText(
        chinese: String,
        english: String,
        french: String = english,
        thai: String = english,
    ): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> chinese
            AppLanguage.English -> english
            AppLanguage.French -> french
            AppLanguage.Thai -> thai
        }

    private fun buildProfileSummaryText(): String {
        val profile = cloudProfile
        if (profile == null) {
            return localText(
                "等待你的拳击档案",
                "Waiting for your fighter profile",
                "En attente de votre profil boxe",
                "กำลังรอโปรไฟล์นักชกของคุณ",
            )
        }
        return profile.nickname
    }

    private fun headerSubtitleText(): String = ""

    private fun buildProfileMetaSummary(): String {
        val profile = cloudProfile ?: return ""
        val parts =
            mutableListOf(
                "${localUserLabel()}: ${profile.serialMasked}",
                "${tr("profile_language")}: ${languageDisplayName(AppLanguage.fromStorage(profile.languageCode))}",
            )
        val countryCode = normalizedCountryCode(profile.countryCode)
        if (countryCode != null) {
            parts += "${tr("profile_country")}: $countryCode"
        }
        return parts.joinToString(" | ")
    }

    private fun buildProfileStatsOverview(): String {
        val stats =
            cloudStatistics
                ?: return if (selectedLanguage == AppLanguage.Chinese) {
                    "完成首次训练并同步云端后，这里会生成你的训练资产与成长统计。"
                } else {
                    "Finish and sync your first session to build your training stats and profile assets."
                }
        return buildString {
            append("${tr("total_sessions")}: ${stats.totalSessions}")
            append(" | ")
            append("${tr("total_hits")}: ${stats.totalHits}")
            append('\n')
            append("${tr("best_30_hits")}: ${stats.best30Hits}")
            append(" | ")
            append("${tr("best_60_hits")}: ${stats.best60Hits}")
            append('\n')
            append(tr("best_burst"))
            append(": ${stats.bestBurstRecord}")
            append(" | ")
            append(streakLabel())
            append(": ${stats.longestStreak}")
            append('\n')
            append(activeDaysLabel())
            append(": ${stats.activeDays}")
            append(" | ")
            append(profileBestScoreLabel())
            append(": ${stats.personalBestHits}")
        }
    }

    private fun buildProfileTierSummary(): String {
        val tier = cloudTier ?: return tierLabelForLevel(cloudProfile?.currentTier ?: 1)
        val tierName = tierLabelForKey(tier.key)
        return if (tier.nextHits != null && tier.nextKey != null) {
            "$tierName  Lv.${tier.level}  |  ${nextTierLabel()}: ${tierLabelForKey(tier.nextKey)} (${tier.bestHits}/${tier.nextHits})"
        } else {
            "$tierName  Lv.${tier.level}  |  ${championLabel()}"
        }
    }

    private fun buildRecentBadgeSummary(): String {
        val unlocked = cloudAchievements.filter { it.unlocked }.sortedByDescending { it.unlockedAt.orEmpty() }.take(3)
        if (unlocked.isEmpty()) {
            return if (selectedLanguage == AppLanguage.Chinese) {
                "最近徽章：继续训练以解锁首枚徽章"
            } else {
                "Recent badges: keep training to unlock your first badge"
            }
        }
        val names = unlocked.joinToString(" · ") { achievementDisplayName(it.key) }
        return if (selectedLanguage == AppLanguage.Chinese) {
            "最近徽章：$names"
        } else {
            "Recent badges: $names"
        }
    }

    private fun refreshProfileAvatar() {
        val profile = cloudProfile
        bindAvatarPresentation(
            container = profileAvatarShell,
            imageView = profileAvatarImageView,
            fallbackView = profileAvatarFallbackView,
            seedText = profile?.nickname,
            colorHex = profile?.avatarColor ?: "#CC4400",
            imageUri = currentAvatarImageUri(),
        )
        profileHeroBadgeView.text = tierLabelForLevel(profile?.currentTier ?: cloudTier?.level ?: 1)
        profileHeroBadgeView.background = metallicBackground("#FFE8A8", "#B68026", "#FFF3D2", 999)
        profileHeroBadgeView.setTextColor(Color.parseColor("#140800"))
    }

    private fun currentAvatarImageUri(): Uri? =
        prefs.getString(KEY_PROFILE_AVATAR_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                try {
                    Uri.parse(it)
                } catch (_: Throwable) {
                    null
                }
            }

    private fun storeAvatarImageUri(uri: Uri?) {
        prefs.edit().putString(KEY_PROFILE_AVATAR_URI, uri?.toString()).apply()
    }

    private fun bindAvatarPresentation(
        container: FrameLayout,
        imageView: ImageView,
        fallbackView: TextView,
        seedText: String?,
        colorHex: String,
        imageUri: Uri?,
    ) {
        container.background = avatarBackground(sanitizeAvatarColor(colorHex))
        fallbackView.text = avatarInitial(seedText)
        if (imageUri != null && loadAvatarImage(imageView, imageUri)) {
            imageView.visibility = View.VISIBLE
            fallbackView.visibility = View.GONE
        } else {
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            fallbackView.visibility = View.VISIBLE
        }
    }

    private fun loadAvatarImage(
        imageView: ImageView,
        uri: Uri,
    ): Boolean =
        try {
            imageView.setImageURI(null)
            imageView.setImageURI(uri)
            imageView.drawable != null
        } catch (_: Throwable) {
            false
        }

    private fun avatarInitial(seedText: String?): String {
        val normalized = seedText?.trim().orEmpty().ifBlank { "R" }
        return normalized.first().uppercaseChar().toString()
    }

    private fun renderAchievements() {
        achievementsGridContainer.removeAllViews()
        val items = cloudAchievements.sortedBy { it.sortOrder }
        if (items.isEmpty()) {
            achievementsSummaryView.text = achievementsSubtitleText(0, 0)
            achievementsGridContainer.addView(
                emptyStateCard(
                    badge = localText("徽章", "HONOR", "HONNEUR", "เกียรติยศ"),
                    title = localText("荣誉馆等待点亮", "Your honor hall is waiting", "Votre galerie d'honneur vous attend", "หอเกียรติยศกำลังรอคุณ"),
                    message =
                        if (selectedLanguage == AppLanguage.Chinese) {
                            "完成训练并同步云端后，这里会展示你的段位与徽章成长。"
                        } else {
                            "Finish and sync a session to light up your tier and badge collection here."
                        },
                    accentColor = "#FFD060",
                ),
            )
            shareAchievementsButton.alpha = 0.72f
            shareAchievementsButton.isEnabled = false
            return
        }
        shareAchievementsButton.alpha = 1.0f
        shareAchievementsButton.isEnabled = true
        val unlockedCount = items.count { it.unlocked }
        achievementsSummaryView.text = achievementsSubtitleText(unlockedCount, items.size)
        cloudTier?.let { tier ->
            achievementsGridContainer.addView(achievementTierHeroCardPremium(tier, unlockedCount, items.size))
            achievementsGridContainer.addView(spacer(dp(14)))
        }

        val recentUnlocked = items.filter { it.unlocked }.sortedByDescending { it.unlockedAt.orEmpty() }.take(3)
        if (recentUnlocked.isNotEmpty()) {
            achievementsGridContainer.addView(sectionLabel(achievementsRecentUnlockedTitle()))
            achievementsGridContainer.addView(spacer(dp(8)))
            val recentRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                }
            recentUnlocked.forEachIndexed { index, item ->
                recentRow.addView(
                    badgeText(
                        text = achievementDisplayName(item.key),
                        textColor = "#FFF5E6",
                        fillColor = "#16384A",
                    ).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                if (index > 0) {
                                    leftMargin = dp(8)
                                }
                            }
                    },
                )
            }
            achievementsGridContainer.addView(recentRow)
            achievementsGridContainer.addView(spacer(dp(14)))
        }

        val itemMap = items.associateBy { it.key }
        achievementGroupSpecs().forEachIndexed { groupIndex, group ->
            val groupItems = group.second.mapNotNull(itemMap::get)
            val unlockedInGroup = groupItems.count { it.unlocked }
            val groupCard = detailCard(fillColor = "#0D1822", strokeColor = "#264558", cornerDp = 22)
            val groupHeader =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            val headerTitle =
                sectionLabel(group.first).apply {
                    setPadding(0, 0, 0, 0)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1.0f,
                        )
                }
            val headerBadge =
                badgeText(
                    text = "$unlockedInGroup/${groupItems.size}",
                    textColor = "#FFF8E8",
                    fillColor = "#173649",
                ).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
            groupHeader.addView(headerTitle)
            groupHeader.addView(headerBadge)
            groupCard.addView(groupHeader)
            groupCard.addView(spacer(dp(10)))
            groupItems.chunked(2).forEachIndexed { rowIndex, rowItems ->
                val row =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.START
                    }
                    rowItems.forEachIndexed { index, item ->
                        row.addView(
                            achievementBadgeCardPremium(item).apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1.0f,
                                ).apply {
                                    if (index > 0) {
                                        leftMargin = dp(10)
                                    }
                                }
                        },
                    )
                }
                repeat(2 - rowItems.size) {
                    row.addView(horizontalSpace(0).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f) })
                }
                groupCard.addView(row)
                if (rowIndex < (groupItems.size - 1) / 2) {
                    groupCard.addView(spacer(dp(10)))
                }
            }
            achievementsGridContainer.addView(groupCard)
            if (groupIndex < achievementGroupSpecs().lastIndex) {
                achievementsGridContainer.addView(spacer(dp(14)))
            }
        }
    }

    private fun achievementGroupSpecs(): List<Pair<String, List<String>>> =
        listOf(
            achievementGroupTitle("milestone") to listOf("first_training", "sessions_5", "sessions_15", "sessions_30"),
            achievementGroupTitle("total_hits") to listOf("hits_100", "hits_500", "hits_1000", "hits_5000"),
            achievementGroupTitle("best30") to listOf("best_30_40", "best_30_60", "best_30_80", "best_30_100"),
            achievementGroupTitle("best60") to listOf("best_60_90", "best_60_120", "best_60_150", "best_60_180"),
            achievementGroupTitle("burst") to listOf("burst_6", "burst_10", "burst_12", "burst_15"),
            achievementGroupTitle("streak") to listOf("streak_3", "streak_7", "streak_14", "streak_30"),
        )

    private fun achievementGroupTitle(key: String): String =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                when (key) {
                    "milestone" -> "训练里程碑"
                    "total_hits" -> "累计击打"
                    "best30" -> "30 秒成绩徽章"
                    "best60" -> "60 秒成绩徽章"
                    "burst" -> "爆发能力"
                    else -> "坚持打卡"
                }
            else ->
                when (key) {
                    "milestone" -> "Training Milestones"
                    "total_hits" -> "Total Hits"
                    "best30" -> "30s Badges"
                    "best60" -> "60s Badges"
                    "burst" -> "Burst Power"
                    else -> "Streak Badges"
                }
        }

    private fun achievementsRecentUnlockedTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "最近解锁"
            AppLanguage.French -> "Déblocages récents"
            AppLanguage.Thai -> "ปลดล็อกล่าสุด"
            else -> "Recently unlocked"
        }

    private fun achievementTierHeroCard(
        tier: CloudTierProgress,
        unlockedCount: Int,
        totalCount: Int,
    ): LinearLayout =
        detailCard(fillColor = "#102533", strokeColor = "#2B5870", cornerDp = 22).apply {
            addView(
                badgeText(
                    text = localText("当前段位", "Current Tier", "Rang actuel", "ระดับปัจจุบัน"),
                    textColor = "#140800",
                    fillColor = "#FFD060",
                ),
            )
            addView(
                titleText(tierLabelForKey(tier.key), 22f).apply {
                    setPadding(0, dp(12), 0, 0)
                    setTextColor(Color.parseColor("#FFF8E8"))
                },
            )
            addView(
                bodyText(achievementsSubtitleText(unlockedCount, totalCount)).apply {
                    setTextColor(Color.parseColor("#FFF0C9"))
                    setPadding(0, dp(6), 0, 0)
                },
            )
            addView(
                bodyText(tierHeroProgressText(tier)).apply {
                    setTextColor(Color.parseColor("#B88A54"))
                    setPadding(0, dp(10), 0, 0)
                },
            )
        }

    private fun tierHeroProgressText(tier: CloudTierProgress): String {
        val best30 = cloudStatistics?.best30Hits ?: tier.bestHits
        return if (tier.nextHits != null && tier.nextKey != null) {
            val remaining = (tier.nextHits - best30).coerceAtLeast(0)
            localText(
                "30 秒最佳：$best30 | 距离 ${tierLabelForKey(tier.nextKey)} 还差 $remaining 击",
                "Best 30s: $best30 | $remaining hits to ${tierLabelForKey(tier.nextKey)}",
                "Meilleur 30 s : $best30 | Encore $remaining coups pour ${tierLabelForKey(tier.nextKey)}",
                "ดีที่สุด 30 วิ: $best30 | อีก $remaining ครั้งจะถึง ${tierLabelForKey(tier.nextKey)}",
            )
        } else {
            localText(
                "30 秒最佳：$best30 | 已达到最高段位",
                "Best 30s: $best30 | Top tier reached",
                "Meilleur 30 s : $best30 | Rang maximum atteint",
                "ดีที่สุด 30 วิ: $best30 | ถึงระดับสูงสุดแล้ว",
            )
        }
    }

    private fun renderHistoryCards() {
        val items = cloudHistory.take(6)
        historyView.visibility = View.GONE
        if (items.isEmpty()) {
            historyListRecycler.visibility = View.GONE
            historyEmptyView.visibility = View.VISIBLE
            historyItemAdapter.submitList(emptyList())
            return
        }
        historyEmptyView.visibility = View.GONE
        historyListRecycler.visibility = View.VISIBLE
        historyItemAdapter.submitList(items)
    }

    private fun renderLeaderboard() {
        leaderboardPodiumContainer.removeAllViews()
        leaderboardMeView.text = ""

        val result = leaderboardResult
        if (result == null || !result.success || result.top.isEmpty()) {
            leaderboardView.visibility = View.VISIBLE
            leaderboardView.text = ""
            leaderboardPodiumContainer.visibility = View.GONE
            leaderboardListRecycler.visibility = View.GONE
            leaderboardRowAdapter.submitList(emptyList())
            leaderboardMeCard.visibility = View.VISIBLE
            leaderboardMeCard.background = metallicBackground("#163246", "#0A141C", "#FF9A30", 22)
            shareLeaderboardButton.alpha = 0.72f
            shareLeaderboardButton.isEnabled = false
            leaderboardMeTitleView.setTextColor(Color.parseColor("#FF9A30"))
            leaderboardMeTitleView.text =
                localText("榜单竞技", "Leaderboard Arena", "Arène du classement", "สนามจัดอันดับ")
            leaderboardMeView.text = ""
            leaderboardMeView.gravity = Gravity.START
            leaderboardMeCard.removeAllViews()
            val emptyHeader =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            leaderboardMeTitleView.layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                )
            detachFromParent(leaderboardMeTitleView)
            detachFromParent(shareLeaderboardButton)
            emptyHeader.addView(leaderboardMeTitleView)
            emptyHeader.addView(shareLeaderboardButton)
            leaderboardMeCard.addView(emptyHeader)
            leaderboardMeCard.addView(
                emptyStateCard(
                    badge = localText("竞技", "RANK", "RANG", "อันดับ"),
                    title =
                        localText(
                            "等待第一份排名",
                            "Waiting for your first ranking",
                            "En attente de votre premier classement",
                            "รออันดับแรกของคุณ",
                        ),
                    message = tr("leaderboard_empty"),
                    accentColor = "#FF9A30",
                ),
            )
            return
        }

        leaderboardView.visibility = View.GONE
        shareLeaderboardButton.alpha = 1.0f
        shareLeaderboardButton.isEnabled = true
        val boardAccent = leaderboardAccentColor(leaderboardBoard)
        val topThree = result.top.take(3)
        leaderboardPodiumContainer.visibility = if (topThree.isNotEmpty()) View.VISIBLE else View.GONE
        buildPodiumEntries(topThree).forEachIndexed { index, entry ->
            leaderboardPodiumContainer.addView(
                podiumCardPremium(
                    entry = entry,
                    accentColor = podiumAccentForRank(entry.rank),
                    elevated = entry.rank == 1,
                    leftMargin = if (index == 0) 0 else dp(10),
                ),
            )
        }

        val others = result.top.drop(3)
        leaderboardListRecycler.visibility = if (others.isNotEmpty()) View.VISIBLE else View.GONE
        leaderboardRowAdapter.submitList(others)

        leaderboardMeCard.visibility = View.VISIBLE
        leaderboardMeCard.background = metallicBackground("#21485F", leaderboardAccentFill(leaderboardBoard), boardAccent, 22)
        leaderboardMeTitleView.setTextColor(Color.parseColor(boardAccent))
        leaderboardMeTitleView.text = "${tr("leaderboard_me").uppercase(localeForLanguage())} · ${leaderboardBoardLabel(leaderboardBoard)}"
        leaderboardMeView.text =
            result.me?.let { entry ->
                buildString {
                    append(rankLabel(entry.rank))
                    append(" | ")
                    append(entry.nickname)
                    append(" | ")
                    append(tierLabelForKey(entry.tierKey))
                    append('\n')
                    append(leaderboardPrimaryValueText(entry))
                    val countryCode = normalizedCountryCode(entry.countryCode)
                    if (countryCode != null) {
                        append('\n')
                        append("${tr("profile_country")}: $countryCode")
                    }
                }
            } ?: tr("leaderboard_no_rank")
        leaderboardMeView.gravity = Gravity.START
        leaderboardMeCard.removeAllViews()
        val meHeader =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        leaderboardMeTitleView.layoutParams =
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f,
            )
        detachFromParent(leaderboardMeTitleView)
        detachFromParent(shareLeaderboardButton)
        meHeader.addView(leaderboardMeTitleView)
        meHeader.addView(shareLeaderboardButton)
        leaderboardMeCard.addView(meHeader)
        leaderboardMeCard.addView(leaderboardMeView)
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun buildProfileMetaText(): String {
        val profile = cloudProfile ?: return ""
        return buildString {
            append(localUserLabel())
            append(": ")
            append(profile.serialMasked)
            append("   ·   ")
            append(tr("profile_language"))
            append(": ")
            append(languageDisplayName(AppLanguage.fromStorage(profile.languageCode)))
            val countryCode = normalizedCountryCode(profile.countryCode)
            if (countryCode != null) {
                append('\n')
                append(tr("profile_country"))
                append(": ")
                append(countryCode)
            }
        }
    }

    private fun normalizedCountryCode(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return if (normalized.equals("null", ignoreCase = true)) null else normalized
    }

    private fun buildProfileStatsText(): String {
        val stats = cloudStatistics ?: return tr("cloud_sync_idle")
        return buildString {
            append(tr("total_sessions"))
            append(": ")
            append(stats.totalSessions)
            append("   ·   ")
            append(tr("total_hits"))
            append(": ")
            append(stats.totalHits)
            append('\n')
            append(tr("best_30_hits"))
            append(": ")
            append(stats.best30Hits)
            append("   ·   ")
            append(tr("best_60_hits"))
            append(": ")
            append(stats.best60Hits)
            append('\n')
            append(tr("average_frequency"))
            append(": ")
            append(String.format(Locale.US, "%.2f %s", maxOf(stats.average30Frequency, stats.average60Frequency), tr("hits_per_second")))
        }
    }

    private fun buildHistoryText(): String {
        if (cloudHistory.isEmpty()) {
            return tr("no_history")
        }
        return cloudHistory.take(6).joinToString("\n\n") { item ->
            buildString {
                append(displayModeLabel(secondsToMode(item.modeSeconds)))
                append("  •  ")
                append(item.totalHits)
                append(" ")
                append(tr("hits"))
                append('\n')
                append(String.format(Locale.US, "%.2f %s", item.averageFrequency, tr("hits_per_second")))
                append("  •  ")
                append(tr("best_burst"))
                append(": ")
                append(item.bestBurstCount)
                append('\n')
                append(formatHistoryTime(item.endedAt))
            }
        }
    }

    private fun buildLeaderboardText(): String {
        val result = leaderboardResult
        if (result == null || !result.success || result.top.isEmpty()) {
            return tr("leaderboard_empty")
        }
        val lines =
            result.top.joinToString("\n") { entry ->
                "${rankLabel(entry.rank)} ${entry.nickname}\n${leaderboardPrimaryValueText(entry)}\n${entry.serialMasked}"
            }
        val meLine =
            result.me?.let { entry ->
                "\n\n${tr("leaderboard_me")}: ${rankLabel(entry.rank)}  ${entry.nickname}  |  ${leaderboardPrimaryValueText(entry)}"
            } ?: "\n\n${tr("leaderboard_no_rank")}"
        return lines + meLine
    }

    private fun leaderboardBoardLabel(board: LeaderboardBoard): String =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                when (board) {
                    LeaderboardBoard.Best30 -> "30秒榜"
                    LeaderboardBoard.Best60 -> "60秒榜"
                    LeaderboardBoard.TotalHits -> "累计榜"
                    LeaderboardBoard.LongestStreak -> "连练榜"
                }
            else ->
                when (board) {
                    LeaderboardBoard.Best30 -> "30s"
                    LeaderboardBoard.Best60 -> "60s"
                    LeaderboardBoard.TotalHits -> "Total Hits"
                    LeaderboardBoard.LongestStreak -> "Streak"
                }
        }

    private fun leaderboardBoardSubtitle(board: LeaderboardBoard): String =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                when (board) {
                    LeaderboardBoard.Best30 -> "按 30 秒历史最佳成绩排名"
                    LeaderboardBoard.Best60 -> "按 60 秒历史最佳成绩排名"
                    LeaderboardBoard.TotalHits -> "按累计击打总数排名"
                    LeaderboardBoard.LongestStreak -> "按最长连续打卡天数排名"
                }
            else ->
                when (board) {
                    LeaderboardBoard.Best30 -> "Ranked by best 30-second score"
                    LeaderboardBoard.Best60 -> "Ranked by best 60-second score"
                    LeaderboardBoard.TotalHits -> "Ranked by lifetime hit count"
                    LeaderboardBoard.LongestStreak -> "Ranked by longest streak"
                }
        }

    private fun leaderboardBoardFromKey(key: String?): LeaderboardBoard =
        when (key) {
            LeaderboardBoard.Best60.apiKey -> LeaderboardBoard.Best60
            LeaderboardBoard.TotalHits.apiKey -> LeaderboardBoard.TotalHits
            LeaderboardBoard.LongestStreak.apiKey -> LeaderboardBoard.LongestStreak
            else -> LeaderboardBoard.Best30
        }

    private fun leaderboardPrimaryValueText(entry: CloudLeaderboardEntry): String =
        when (leaderboardBoard) {
            LeaderboardBoard.LongestStreak ->
                if (selectedLanguage == AppLanguage.Chinese) {
                    "${entry.bestHits} 天连练"
                } else {
                    "${entry.bestHits} day streak"
                }
            LeaderboardBoard.TotalHits ->
                if (selectedLanguage == AppLanguage.Chinese) {
                    "累计 ${entry.bestHits} 次"
                } else {
                    "${entry.bestHits} total hits"
                }
            LeaderboardBoard.Best30 ->
                if (selectedLanguage == AppLanguage.Chinese) {
                    "30 秒最佳 ${entry.bestHits} 次"
                } else {
                    "Best 30s ${entry.bestHits} hits"
                }
            LeaderboardBoard.Best60 ->
                if (selectedLanguage == AppLanguage.Chinese) {
                    "60 秒最佳 ${entry.bestHits} 次"
                } else {
                    "Best 60s ${entry.bestHits} hits"
                }
        }

    private fun leaderboardSecondaryValueText(entry: CloudLeaderboardEntry): String =
        when (leaderboardBoard) {
            LeaderboardBoard.Best30, LeaderboardBoard.Best60 ->
                if (selectedLanguage == AppLanguage.Chinese) {
                    "段位 ${tierLabelForKey(entry.tierKey)}"
                } else {
                    "Tier ${tierLabelForKey(entry.tierKey)}"
                }
            LeaderboardBoard.TotalHits ->
                if (selectedLanguage == AppLanguage.Chinese) {
                    "最佳爆发 ${entry.bestBurstCount}"
                } else {
                    "Best burst ${entry.bestBurstCount}"
                }
            LeaderboardBoard.LongestStreak ->
                if (selectedLanguage == AppLanguage.Chinese) {
                    "当前段位 ${tierLabelForKey(entry.tierKey)}"
                } else {
                    tierLabelForKey(entry.tierKey)
                }
        }

    private fun formatHistoryTime(value: String?): String {
        if (value.isNullOrBlank()) {
            return tr("activation_just_now")
        }
        val parsed = parseCloudDate(value)
        if (parsed == null) {
            return value.replace('T', ' ').replace("Z", "").replace(".000", "")
        }
        val pattern =
            when (selectedLanguage) {
                AppLanguage.Chinese -> "MM-dd HH:mm"
                AppLanguage.English -> "MMM dd, HH:mm"
                AppLanguage.French -> "dd MMM HH:mm"
                AppLanguage.Thai -> "dd/MM HH:mm"
            }
        return SimpleDateFormat(pattern, localeForLanguage()).apply {
            timeZone = TimeZone.getDefault()
        }.format(parsed)
    }

    private fun reportCardText(report: TrainingReport): String {
        val frequency = String.format(Locale.US, "%.2f", report.averageFrequency)
        val bestStart = String.format(Locale.US, "%.1f", report.bestBurstStartSec)
        val burstUnit = localText("秒", "s", "s", "วิ")
        return buildString {
            append("${tr("mode")}: ${displayModeLabel(report.mode)}")
            append('\n')
            append("${tr("total_hits")}: ${report.totalHits}")
            append('\n')
            append("${tr("average_frequency")}: $frequency ${tr("hits_per_second")}")
            append('\n')
            append("${tr("best_burst")}: ${report.bestBurstCount} ${tr("hits")}")
            append('\n')
            append("${tr("burst_start")}: $bestStart$burstUnit")
        }
    }

    private fun emptyStateCard(
        badge: String,
        title: String,
        message: String,
        accentColor: String = "#FF9A30",
    ): LinearLayout =
        detailCard(fillColor = "#0D1822", strokeColor = accentColor, cornerDp = 22).apply {
            background = metallicBackground("#163246", "#0A141C", accentColor, 22)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                TextView(this@MainActivity).apply {
                    text = badge
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.parseColor("#140800"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    background = metallicBackground("#BCEEFF", accentColor, "#E8FBFF", 999)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                },
            )
            addView(
                titleText(title, 20f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14), 0, 0)
                },
            )
            addView(
                bodyText(message).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#B7CFE0"))
                    setPadding(dp(4), dp(8), dp(4), dp(4))
                },
            )
        }

    private fun renderEmptyReport() {
        reportView.removeAllViews()
        reportView.addView(
            emptyStateCard(
                badge = localText("战报", "REPORT", "RAPPORT", "รายงาน"),
                title =
                    localText(
                        "等待首份训练战报",
                        "Waiting for your first report",
                        "En attente de votre premier rapport",
                        "รอรายงานการฝึกครั้งแรก",
                    ),
                message = tr("no_report"),
                accentColor = "#FF9A30",
            ),
        )
    }

    private fun reportMetricCard(
        label: String,
        value: String,
        accentColor: String,
    ): LinearLayout =
        detailCard(fillColor = "#0B1721", strokeColor = accentColor, cornerDp = 18).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(
                bodyText(label).apply {
                    setTextColor(Color.parseColor("#B88A54"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
            )
            addView(
                titleText(value, 18f).apply {
                    setTextColor(Color.parseColor("#FFF8E8"))
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }

    private fun formatReportEndedTime(epochMs: Long): String {
        val pattern =
            when (selectedLanguage) {
                AppLanguage.Chinese -> "MM-dd HH:mm"
                AppLanguage.English -> "MMM dd, HH:mm"
                AppLanguage.French -> "dd MMM HH:mm"
                AppLanguage.Thai -> "dd/MM HH:mm"
            }
        return SimpleDateFormat(pattern, localeForLanguage()).format(Date(epochMs))
    }

    private fun setCloudStatusMessage(
        colorHex: String,
        key: String? = null,
        fallback: String? = null,
    ) {
        cloudStatusMessageKey = key
        cloudStatusFallbackMessage = fallback
        cloudStatusColor = Color.parseColor(colorHex)
        if (::cloudStatusView.isInitialized) {
            cloudStatusView.setTextColor(cloudStatusColor)
            cloudStatusView.text = currentCloudStatusMessage()
            cloudStatusView.background = chipBackground(cloudStatusColor)
        }
    }

    private fun currentCloudStatusMessage(): String =
        cloudStatusMessageKey?.let(::tr) ?: cloudStatusFallbackMessage.orEmpty()

    private fun secondsToMode(seconds: Int): TrainingMode =
        when {
            seconds >= 60 -> TrainingMode.Seconds60
            seconds >= 30 -> TrainingMode.Seconds30
            seconds >= 15 -> TrainingMode.Burst15
            else -> TrainingMode.Burst10
        }

    private fun setTrainingBusyUi(isBusy: Boolean) {
        val activated = isActivated()
        startButton.isEnabled = !isBusy && activated
        startButton.alpha = if (startButton.isEnabled) 1.0f else 0.5f
        stopButton.isEnabled = isBusy
        stopButton.alpha = if (isBusy) 1.0f else 0.5f
        settingsButton.isEnabled = !isBusy
        settingsButton.alpha = if (isBusy) 0.5f else 1.0f
        activateButton.isEnabled = !isBusy && !activated && activationInputsValid
        activateButton.alpha = if (activateButton.isEnabled) 1.0f else 0.6f
        serialInput.isEnabled = !isBusy && !activated
        codeInput.isEnabled = !isBusy && !activated
        for (index in 0 until modeGroup.childCount) {
            modeGroup.getChildAt(index).isEnabled = !isBusy
            modeGroup.getChildAt(index).alpha = if (isBusy) 0.6f else 1.0f
        }
        refreshModeButtonStyles()
    }

    private fun setActivationVisible(visible: Boolean) {
        if (!visible && ::activationCard.isInitialized) {
            activationCard.removeCallbacks(hideActivationCardRunnable)
        }
        activationCard.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setActivationBusy(isBusy: Boolean) {
        val allowInput = !isBusy && !isActivated()
        activateButton.isEnabled = allowInput && activationInputsValid
        activateButton.alpha = if (activateButton.isEnabled) 1.0f else 0.6f
        serialInput.isEnabled = allowInput
        codeInput.isEnabled = allowInput
    }

    private fun updateActivationInputState() {
        val serialDigits = normalizeDigits(serialInput.text?.toString())
        val codeDigits = normalizeDigits(codeInput.text?.toString())
        val serialRaw = serialInput.text?.toString().orEmpty()
        val codeRaw = codeInput.text?.toString().orEmpty()
        val serialOk = serialDigits.length == 11
        val codeOk = codeDigits.length == 8

        if (serialRaw.isEmpty() || serialOk) {
            serialInputErrorView.text = ""
            serialInputErrorView.visibility = View.GONE
        } else {
            serialInputErrorView.text = tr("serial_invalid")
            serialInputErrorView.visibility = View.VISIBLE
        }
        if (codeRaw.isEmpty() || codeOk) {
            codeInputErrorView.text = ""
            codeInputErrorView.visibility = View.GONE
        } else {
            codeInputErrorView.text = tr("code_invalid")
            codeInputErrorView.visibility = View.VISIBLE
        }

        activationInputsValid = serialOk && codeOk
        val activated = isActivated()
        val busy = activationJob?.isActive == true || trainingJob?.isActive == true
        val enabled = !activated && !busy && activationInputsValid
        activateButton.isEnabled = enabled
        activateButton.alpha = if (enabled) 1.0f else 0.6f
    }

    private fun showFormalSettingsDialog() {
        val dialogRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(8))
            }

        dialogRoot.addView(createBluetoothSettingsPanel())
        val languageCard =
            detailCard(fillColor = "#101821", strokeColor = "#2A6A8F", cornerDp = 20).apply {
                setPadding(dp(14), dp(13), dp(14), dp(12))
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                addView(
                    settingsSectionHeader(
                        title = tr("language"),
                        subtitle = tr("language_helper"),
                        accentColor = "#8FD8FF",
                    ),
                )
            }
        val languageGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
        val zhOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_chinese")
                isChecked = selectedLanguage == AppLanguage.Chinese
                setTextColor(Color.WHITE)
            }
        val enOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_english")
                isChecked = selectedLanguage == AppLanguage.English
                setTextColor(Color.WHITE)
            }
        val frOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_french")
                isChecked = selectedLanguage == AppLanguage.French
                setTextColor(Color.WHITE)
            }
        val thOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_thai")
                isChecked = selectedLanguage == AppLanguage.Thai
                setTextColor(Color.WHITE)
            }
        languageGroup.addView(zhOption)
        languageGroup.addView(enOption)
        languageGroup.addView(frOption)
        languageGroup.addView(thOption)
        languageCard.addView(languageGroup)
        dialogRoot.addView(languageCard)

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(settingsDialogTitle())
                .setView(
                    ScrollView(this).apply {
                        addView(dialogRoot)
                    },
                )
                .setNegativeButton(tr("cancel"), null)
                .setPositiveButton(tr("save"), null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                applyLanguageAndSensitivitySettings(
                    language =
                        when (languageGroup.checkedRadioButtonId) {
                            enOption.id -> AppLanguage.English
                            frOption.id -> AppLanguage.French
                            thOption.id -> AppLanguage.Thai
                            else -> AppLanguage.Chinese
                        },
                    refreshCloud = true,
                )
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            bluetoothStatusView = null
            bluetoothDeviceListView = null
            bluetoothScanButton = null
            bluetoothConnectButton = null
            bluetoothDisconnectButton = null
        }
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#1A0C00"))
        dialog.window?.let { window ->
            val attributes = window.attributes
            attributes.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            attributes.y = 0
            attributes.width = (resources.displayMetrics.widthPixels * 0.94f).toInt()
            window.attributes = attributes
        }
    }

    private fun createBluetoothSettingsPanel(): LinearLayout =
        detailCard(fillColor = "#061410", strokeColor = "#00FF88", cornerDp = 20).apply {
            setPadding(dp(14), dp(13), dp(14), dp(14))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(16) }

            addView(
                settingsSectionHeader(
                    title = bluetoothSectionTitle(),
                    subtitle = bluetoothSectionSubtitle(),
                    accentColor = "#80FFB0",
                ),
            )
            bluetoothStatusView =
                bodyText(bluetoothStatusMessage).apply {
                    setTextColor(Color.parseColor("#B9F8D0"))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = roundedBackground("#082018", "#1D5C3D", 16)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(10); bottomMargin = dp(10) }
                }
            addView(bluetoothStatusView)

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        compactActionButton(bluetoothScanLabel(), "#008840").apply {
                            bluetoothScanButton = this
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnClickListener {
                                runWithBluetoothPermissions {
                                    sensorBallBluetooth.startScan()
                                }
                            }
                        },
                    )
                    addView(horizontalSpace(dp(8)))
                    addView(
                        compactActionButton(bluetoothConnectLabel(), "#16384A").apply {
                            bluetoothConnectButton = this
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnClickListener {
                                runWithBluetoothPermissions {
                                    val device = selectedBluetoothDevice ?: bluetoothDevices.firstOrNull()
                                    if (device == null) {
                                        bluetoothStatusMessage = bluetoothSelectDeviceText()
                                        updateBluetoothSettingsViews()
                                    } else {
                                        selectedBluetoothDevice = device
                                        sensorBallBluetooth.connect(device)
                                    }
                                }
                            }
                        },
                    )
                    addView(horizontalSpace(dp(8)))
                    addView(
                        compactActionButton(bluetoothDisconnectLabel(), "#5B2D2D").apply {
                            bluetoothDisconnectButton = this
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnClickListener {
                                runWithBluetoothPermissions {
                                    sensorBallBluetooth.disconnect()
                                }
                            }
                        },
                    )
                },
            )
            bluetoothDeviceListView =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(12), 0, 0)
            }
            addView(bluetoothDeviceListView)

            updateBluetoothSettingsViews()
        }

    private fun settingsSectionHeader(title: String, subtitle: String, accentColor: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                sectionLabel(title).apply {
                    setTextColor(Color.parseColor(accentColor))
                    setPadding(0, 0, 0, dp(4))
                },
            )
            addView(
                bodyText(subtitle).apply {
                    setTextColor(Color.parseColor("#D6E8DA"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 0, 0, dp(2))
                },
            )
        }

    private fun bluetoothMetricView(label: String, value: String): TextView =
        bodyText("$label\n$value").apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#DFFFF0"))
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground("#0A241A", "#1E6C46", 16)
        }

    private fun updateBluetoothGyroHitCount(rawCount: Int) {
        val previous = lastBluetoothGyroRawCount
        if (previous == null) {
            lastBluetoothGyroRawCount = rawCount
            if (bluetoothHitCount == null) {
                bluetoothHitCount = 0
            }
            return
        }
        val delta =
            when {
                rawCount >= previous -> rawCount - previous
                previous >= 240 && rawCount <= 15 -> rawCount + 256 - previous
                else -> 0
        }
        if (delta > 0) {
            bluetoothHitCount = (bluetoothHitCount ?: 0) + delta
            if (trainingJob?.isActive == true && bluetoothTrainingMode != null) {
                bluetoothTrainingCount += delta
                countView.text = bluetoothTrainingCount.toString()
                pulseCount()
                Haptics.tap(this)
                lastDisplayedCount = bluetoothTrainingCount
            }
        }
        lastBluetoothGyroRawCount = rawCount
    }




    private fun updateBluetoothSettingsViews() {
        updateHeaderBluetoothStatus()
        updateBluetoothActionButtons()
        bluetoothStatusView?.text =
            buildString {
                append(bluetoothStatusMessage)
                bluetoothConnectedDevice?.let { append(" · ").append(it.name) }
                if (bluetoothGyroscopeEnabled) {
                    append(" · ").append(bluetoothGyroOnStateText())
                }
        }
        bluetoothBatteryView?.text = "${bluetoothBatteryLabel()}\n$bluetoothBatteryText"
        bluetoothHitCountView?.text = "${bluetoothHitCountLabel()}\n${bluetoothHitCount?.toString() ?: "--"}"
        bluetoothDeviceListView?.let { list ->
            list.removeAllViews()
            val visibleDevices = visibleBluetoothDevices()
            if (visibleDevices.isEmpty()) {
                list.addView(
                    bodyText(bluetoothNoDeviceText()).apply {
                        setTextColor(Color.parseColor("#8FEFBC"))
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                    },
                )
            } else {
                visibleDevices.forEach { device ->
                    val selected =
                        selectedBluetoothDevice?.matchesBluetoothDevice(device) == true ||
                            bluetoothConnectedDevice?.matchesBluetoothDevice(device) == true
                    list.addView(
                        bodyText("${device.name}\n${device.address} · ${device.transportLabel()} · RSSI ${device.rssi}").apply {
                            setTextColor(Color.parseColor(if (selected) "#001A08" else "#DFFFF0"))
                            setPadding(dp(12), dp(9), dp(12), dp(9))
                            background =
                                roundedBackground(
                                    if (selected) "#80FFB0" else "#0A241A",
                                    if (selected) "#DFFFF0" else "#1E6C46",
                                    14,
                                )
                            setOnClickListener {
                                selectedBluetoothDevice = device
                                bluetoothStatusMessage = bluetoothDeviceSelectedText(device.name)
                                updateBluetoothSettingsViews()
                            }
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply { bottomMargin = dp(6) }
                        },
                    )
                }
            }
        }
    }

    private fun visibleBluetoothDevices(): List<SensorBallDevice> {
        val result = bluetoothDevices.toMutableList()
        bluetoothConnectedDevice?.let { connected ->
            if (result.none { it.matchesBluetoothDevice(connected) }) {
                result.add(0, connected)
            }
        }
        selectedBluetoothDevice?.let { selected ->
            if (result.none { it.matchesBluetoothDevice(selected) }) {
                result.add(selected)
            }
        }
        return result
    }

    private fun rememberBluetoothDevice(device: SensorBallDevice) {
        val existingIndex = bluetoothDevices.indexOfFirst { it.matchesBluetoothDevice(device) }
        if (existingIndex >= 0) {
            bluetoothDevices[existingIndex] = device
        } else {
            bluetoothDevices.add(0, device)
        }
    }

    private fun updateHeaderBluetoothStatus() {
        if (!::bluetoothHeaderIndicatorView.isInitialized || !::batteryHeaderView.isInitialized) {
            return
        }
        val connected = bluetoothConnectedDevice != null
        bluetoothHeaderIndicatorView.setColorFilter(Color.parseColor(if (connected) "#2E8BFF" else "#FF4A6A"))
        bluetoothHeaderIndicatorView.background = null
        bluetoothHeaderIndicatorView.contentDescription =
            if (connected) "Bluetooth connected" else "Bluetooth disconnected"
        val batteryText = bluetoothBatteryText.takeIf { it != "--" } ?: if (connected) "..." else "--"
        batteryHeaderView.text = batteryText
        batteryHeaderView.setTextColor(Color.parseColor(if (connected) "#FFFFFF" else "#AAB3C2"))
        batteryHeaderView.setBackgroundResource(R.drawable.battery_status_background)
        batteryHeaderView.contentDescription =
            localText("电量 $batteryText", "Battery $batteryText", "Batterie $batteryText", "แบตเตอรี่ $batteryText")
    }

    private fun updateBluetoothActionButtons() {
        val connected = bluetoothConnectedDevice != null
        val selected = selectedBluetoothDevice != null
        setBluetoothButtonEnabled(bluetoothScanButton, !connected)
        setBluetoothButtonEnabled(bluetoothConnectButton, !connected && selected)
        setBluetoothButtonEnabled(bluetoothDisconnectButton, connected)
    }

    private fun setBluetoothButtonEnabled(button: Button?, enabled: Boolean) {
        button?.isEnabled = enabled
        button?.alpha = if (enabled) 1.0f else 0.42f
    }

    private fun runWithBluetoothPermissions(action: () -> Unit) {
        val missing =
            requiredBluetoothPermissions().filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingBluetoothAction = action
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun maybeShowBluetoothFirstUseGuide() {
        if (isFinishing || isDestroyed || trainingJob?.isActive == true || bluetoothConnectedDevice != null) {
            return
        }
        if (prefs.getBoolean(KEY_BLUETOOTH_FIRST_USE_GUIDE_SHOWN, false)) {
            return
        }
        prefs.edit().putBoolean(KEY_BLUETOOTH_FIRST_USE_GUIDE_SHOWN, true).apply()
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), 0)
                addView(
                    bodyText(bluetoothFirstUseGuideMessage()).apply {
                        setTextColor(Color.parseColor("#FFF5E6"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setPadding(0, 0, 0, dp(12))
                    },
                )
                addView(
                    bodyText(bluetoothFirstUseGuideHint()).apply {
                        setTextColor(Color.parseColor("#B9F8D0"))
                        background = roundedBackground("#082018", "#1D5C3D", 16)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    },
                )
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(bluetoothFirstUseGuideTitle())
                .setView(content)
                .setNegativeButton(bluetoothFirstUseLaterLabel(), null)
                .setPositiveButton(bluetoothFirstUseOpenSettingsLabel()) { _, _ ->
                    showFormalSettingsDialog()
                }
                .create()
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#1A0C00"))
    }

    private fun autoConnectLastBluetoothDevice() {
        if (bluetoothConnectedDevice != null) {
            return
        }
        val device = loadLastBluetoothDevice() ?: return
        selectedBluetoothDevice = device
        bluetoothStatusMessage = bluetoothAutoConnectingText(device.name)
        updateBluetoothSettingsViews()
        runWithBluetoothPermissions {
            if (bluetoothConnectedDevice == null) {
                sensorBallBluetooth.connect(device)
            }
        }
    }

    private fun saveLastBluetoothDevice(device: SensorBallDevice) {
        prefs.edit()
            .putString(KEY_LAST_BLUETOOTH_NAME, device.name)
            .putString(KEY_LAST_BLUETOOTH_ADDRESS, device.address)
            .putString(KEY_LAST_BLUETOOTH_TRANSPORT, device.transport.name)
            .putBoolean(KEY_LAST_BLUETOOTH_HAS_BLE, device.hasBle)
            .putBoolean(KEY_LAST_BLUETOOTH_HAS_CLASSIC, device.hasClassic)
            .putString(KEY_LAST_BLUETOOTH_BLE_ADDRESS, device.bleAddress)
            .putString(KEY_LAST_BLUETOOTH_CLASSIC_ADDRESS, device.classicAddress)
            .apply()
    }

    private fun loadLastBluetoothDevice(): SensorBallDevice? {
        val name = prefs.getString(KEY_LAST_BLUETOOTH_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val address = prefs.getString(KEY_LAST_BLUETOOTH_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        val transport =
            runCatching {
                SensorBallTransport.valueOf(prefs.getString(KEY_LAST_BLUETOOTH_TRANSPORT, SensorBallTransport.Classic.name).orEmpty())
            }.getOrElse { SensorBallTransport.Classic }
        val hasBle = prefs.getBoolean(KEY_LAST_BLUETOOTH_HAS_BLE, transport == SensorBallTransport.Ble)
        val hasClassic = prefs.getBoolean(KEY_LAST_BLUETOOTH_HAS_CLASSIC, transport == SensorBallTransport.Classic)
        val bleAddress = prefs.getString(KEY_LAST_BLUETOOTH_BLE_ADDRESS, null)?.takeIf { it.isNotBlank() }
        val classicAddress = prefs.getString(KEY_LAST_BLUETOOTH_CLASSIC_ADDRESS, null)?.takeIf { it.isNotBlank() }
        return SensorBallDevice(
            name = name,
            address = address,
            rssi = 0,
            transport = transport,
            hasBle = hasBle,
            hasClassic = hasClassic,
            bleAddress = bleAddress,
            classicAddress = classicAddress,
        )
    }

    private fun requiredBluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun bluetoothSectionTitle(): String =
        localText("蓝牙连接", "Bluetooth Connection", "Connexion Bluetooth", "การเชื่อมต่อบลูทูธ")

    private fun bluetoothSectionSubtitle(): String =
        localText(
            "请先扫描 SENBALL# 设备，连接成功后即可开始训练。",
            "Scan for a SENBALL# device first. Training is available after connection.",
            "Scannez d'abord un appareil SENBALL#. L'entraînement sera disponible après connexion.",
            "โปรดสแกนอุปกรณ์ SENBALL# ก่อน เมื่อเชื่อมต่อแล้วจึงเริ่มฝึกได้",
        )

    private fun bluetoothScanLabel(): String =
        localText("扫描", "Scan", "Scanner", "สแกน")

    private fun bluetoothConnectLabel(): String =
        localText("连接", "Connect", "Connecter", "เชื่อมต่อ")

    private fun bluetoothDisconnectLabel(): String =
        localText("断开", "Disconnect", "Déconnecter", "ตัดการเชื่อมต่อ")

    private fun bluetoothBatteryLabel(): String =
        localText("电量", "Battery", "Batterie", "แบตเตอรี่")

    private fun bluetoothHitCountLabel(): String =
        localText("拳击次数", "Punch Count", "Nombre de coups", "จำนวนหมัด")




    private fun bluetoothGyroOnLabel(): String =
        localText("开启", "Enable", "Activer", "เปิด")

    private fun bluetoothGyroOffLabel(): String =
        localText("关闭", "Disable", "Désactiver", "ปิด")

    private fun bluetoothGyroOnStateText(): String =
        localText("陀螺仪已开启", "Gyro on", "Gyroscope activé", "ไจโรเปิดอยู่")






    private fun bluetoothNoDeviceText(): String =
        localText("未扫描到 SENBALL# 设备", "No SENBALL# devices found", "Aucun appareil SENBALL# trouvé", "ไม่พบอุปกรณ์ SENBALL#")

    private fun bluetoothSelectDeviceText(): String =
        localText("请先扫描并选择设备", "Scan and select a device first", "Scannez puis sélectionnez un appareil", "โปรดสแกนและเลือกอุปกรณ์ก่อน")

    private fun bluetoothDeviceSelectedText(name: String): String =
        localText("已选择 $name", "Selected $name", "$name sélectionné", "เลือก $name แล้ว")

    private fun bluetoothPermissionDeniedText(): String =
        localText("未获得蓝牙权限，无法连接设备。", "Bluetooth permission is required to connect.", "L'autorisation Bluetooth est nécessaire pour se connecter.", "ต้องอนุญาตบลูทูธเพื่อเชื่อมต่อ")

    private fun bluetoothConnectFirstText(): String =
        localText("请先在设置中连接蓝牙设备。", "Connect the Bluetooth device in Settings first.", "Connectez d'abord l'appareil Bluetooth dans les paramètres.", "โปรดเชื่อมต่ออุปกรณ์บลูทูธในตั้งค่าก่อน")

    private fun bluetoothAutoSelectedText(name: String): String =
        localText("已自动选择 $name", "Auto-selected $name", "$name sélectionné automatiquement", "เลือก $name โดยอัตโนมัติ")

    private fun bluetoothDevicesFoundText(count: Int): String =
        localText("已扫描到 $count 个设备，请选择设备", "$count devices found. Select one.", "$count appareils trouvés. Sélectionnez-en un.", "พบอุปกรณ์ $count เครื่อง โปรดเลือกอุปกรณ์")

    private fun bluetoothConnectedText(name: String): String =
        localText("已连接 $name", "Connected to $name", "Connecté à $name", "เชื่อมต่อกับ $name แล้ว")

    private fun bluetoothDisconnectedText(): String =
        localText("蓝牙已断开", "Bluetooth disconnected", "Bluetooth déconnecté", "ตัดการเชื่อมต่อบลูทูธแล้ว")

    private fun bluetoothPacketReceivedText(packetIndex: Int): String =
        localText("已接收数据包 $packetIndex", "Packet $packetIndex received", "Paquet $packetIndex reçu", "ได้รับแพ็กเก็ต $packetIndex แล้ว")

    private fun bluetoothAutoConnectingText(name: String): String =
        localText("正在自动连接上次设备 $name...", "Auto-connecting last device $name...", "Connexion automatique au dernier appareil $name...", "กำลังเชื่อมต่ออุปกรณ์ล่าสุด $name อัตโนมัติ...")

    private fun settingsDialogTitle(): String =
        localText("蓝牙连接及语言选择", "Bluetooth Connection and Language", "Connexion Bluetooth et langue", "การเชื่อมต่อบลูทูธและภาษา")

    private fun bluetoothFirstUseGuideTitle(): String =
        localText("连接蓝牙设备", "Connect Bluetooth Device", "Connecter l'appareil Bluetooth", "เชื่อมต่ออุปกรณ์บลูทูธ")

    private fun bluetoothFirstUseGuideMessage(): String =
        localText(
            "首次使用前，请进入设置界面扫描并连接 Smart sensor ball 蓝牙设备。",
            "Before your first session, open Settings to scan and connect your Smart sensor ball device.",
            "Avant la première séance, ouvrez les paramètres pour scanner et connecter votre appareil Smart sensor ball.",
            "ก่อนใช้งานครั้งแรก โปรดเปิดตั้งค่าเพื่อสแกนและเชื่อมต่ออุปกรณ์ Smart sensor ball",
        )

    private fun bluetoothFirstUseGuideHint(): String =
        localText(
            "连接成功后，顶部蓝牙图标会变为蓝色，并显示电量。",
            "After connection, the top Bluetooth icon turns blue and shows battery level.",
            "Après connexion, l'icône Bluetooth en haut devient bleue et affiche la batterie.",
            "หลังเชื่อมต่อ ไอคอนบลูทูธด้านบนจะเป็นสีน้ำเงินและแสดงแบตเตอรี่",
        )

    private fun bluetoothFirstUseOpenSettingsLabel(): String =
        localText("去设置", "Open Settings", "Ouvrir les paramètres", "ไปที่ตั้งค่า")

    private fun bluetoothFirstUseLaterLabel(): String =
        localText("稍后", "Later", "Plus tard", "ภายหลัง")

    private fun SensorBallDevice.transportLabel(): String =
        when {
            hasBle && hasClassic -> "BLE/CLASSIC"
            hasBle -> "BLE"
            hasClassic -> "CLASSIC"
            else -> transport.name
        }

    private fun SensorBallDevice.matchesBluetoothDevice(other: SensorBallDevice): Boolean {
        val thisAddresses = listOf(address, bleAddress, classicAddress).filter { !it.isNullOrBlank() }
        val otherAddresses = listOf(other.address, other.bleAddress, other.classicAddress).filter { !it.isNullOrBlank() }
        return thisAddresses.any { left -> otherAddresses.any { right -> left.equals(right, ignoreCase = true) } } ||
            name.trim().equals(other.name.trim(), ignoreCase = true)
    }

    private fun showSettingsDialog() {
        val dialogRoot =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(4))
            }

        dialogRoot.addView(sectionLabel(tr("language")))
        val languageGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(0, dp(4), 0, dp(16))
            }
        val zhOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr("language_chinese")
                isChecked = selectedLanguage == AppLanguage.Chinese
                setTextColor(Color.WHITE)
            }
        val enOption =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = "English"
                isChecked = selectedLanguage == AppLanguage.English
                setTextColor(Color.WHITE)
            }
        languageGroup.addView(zhOption)
        languageGroup.addView(enOption)
        dialogRoot.addView(languageGroup)

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(tr("settings"))
                .setView(dialogRoot)
                .setNegativeButton(tr("cancel"), null)
                .setPositiveButton(tr("save"), null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                applyLanguageAndSensitivitySettings(
                    language =
                        if (languageGroup.checkedRadioButtonId == enOption.id) {
                            AppLanguage.English
                    } else {
                        AppLanguage.Chinese
                    },
                    refreshCloud = false,
                )
                dialog.dismiss()
            }
        }
        dialog.show()
        dialog.window?.decorView?.setBackgroundColor(Color.parseColor("#1A0C00"))
    }

    private fun applyLanguageAndSensitivitySettings(
        language: AppLanguage,
        refreshCloud: Boolean,
    ) {
        selectedLanguage = language
        saveSettings()
        updateTtsLanguage()
        clearLanguageSensitiveFallbackMessages()
        if (isActivated()) {
            renderIdle()
            latestReport?.let(::renderReport) ?: renderEmptyReport()
            renderTrainingPlayStatus()
            refreshModeButtonStyles()
            refreshCloudViews()
            if (refreshCloud) {
                refreshCloudData(forceLeaderboard = false)
            }
        } else {
            renderActivationRequired()
        }
    }

    private fun clearLanguageSensitiveFallbackMessages() {
        if (cloudStatusMessageKey == null) {
            cloudStatusFallbackMessage = null
        }
        if (authStatusMessageKey == null) {
            authStatusFallbackMessage = null
        }
        lastCoachMessage = null
    }

    private fun profileHeroTagText(): String =
        localText("拳击训练档案", "FIGHTER PROFILE", "DOSSIER DU BOXEUR", "โปรไฟล์นักชก")

    private fun historyEmptyBadgeText(): String =
        localText("记录", "HISTORY", "HISTORIQUE", "ประวัติ")

    private fun historyEmptyTitleText(): String =
        localText(
            "训练记录尚未生成",
            "No training history yet",
            "Aucun historique pour le moment",
            "ยังไม่มีประวัติการฝึก",
        )

    private fun updateEmptyStateCardText(
        card: LinearLayout,
        badge: String,
        title: String,
        message: String,
    ) {
        (card.getChildAt(0) as? TextView)?.text = badge
        (card.getChildAt(1) as? TextView)?.text = title
        (card.getChildAt(2) as? TextView)?.text = message
    }

    private fun applyStaticTexts() {
        titleView.text = tr("title")
        subtitleView.text = headerSubtitleText()
        subtitleView.visibility = if (headerSubtitleText().isBlank()) View.GONE else View.VISIBLE
        modeTitleView.text = tr("mode")
        mode30Button.text = playModeLabel(TrainingPlayMode.Classic30)
        mode60Button.text = playModeLabel(TrainingPlayMode.Classic60)
        modeBurst10Button.text = playModeLabel(TrainingPlayMode.Burst10)
        modeBurst15Button.text = playModeLabel(TrainingPlayMode.Burst15)
        modeLevelButton.text = playModeLabel(TrainingPlayMode.LevelChallenge)
        modeDailyButton.text = playModeLabel(TrainingPlayMode.DailyChallenge)
        when (selectedPlayMode) {
            TrainingPlayMode.Classic30 -> mode30Button.isChecked = true
            TrainingPlayMode.Classic60 -> mode60Button.isChecked = true
            TrainingPlayMode.Burst10 -> modeBurst10Button.isChecked = true
            TrainingPlayMode.Burst15 -> modeBurst15Button.isChecked = true
            TrainingPlayMode.LevelChallenge -> modeLevelButton.isChecked = true
            TrainingPlayMode.DailyChallenge -> modeDailyButton.isChecked = true
        }
        refreshModeButtonStyles()
        renderTrainingPlayStatus()
        startButton.text = tr("start")
        stopButton.text = tr("stop")
        pageTrainingButton.text = tr("page_training_center")
        pageAchievementsButton.text = tr("page_training_achievements")
        pageLeaderboardButton.text = tr("page_leaderboard")
        pageProfileButton.text = tr("page_profile")
        reportTitleView.text = tr("latest_report")
        profileTitleView.text = tr("profile_title")
        profileSubtitleView.text = profilePageSubtitle()
        profileSubtitleView.visibility = View.VISIBLE
        if (::profileHeroTagView.isInitialized) {
            profileHeroTagView.text = profileHeroTagText()
        }
        achievementsTitleView.text = achievementsTitleText()
        achievementsSubtitleView.text = achievementsSectionHint()
        achievementsSubtitleView.visibility = View.VISIBLE
        historyTitleView.text = tr("history_title")
        historySubtitleView.text = historySectionSubtitle()
        historySubtitleView.visibility = View.VISIBLE
        if (::historyEmptyView.isInitialized) {
            updateEmptyStateCardText(
                historyEmptyView,
                historyEmptyBadgeText(),
                historyEmptyTitleText(),
                tr("no_history"),
            )
        }
        leaderboardTitleView.text = tr("leaderboard_title")
        leaderboardSubtitleView.text = leaderboardBoardSubtitle(leaderboardBoard)
        leaderboardSubtitleView.visibility = View.VISIBLE
        leaderboard30Button.text = leaderboardBoardLabel(LeaderboardBoard.Best30)
        leaderboard60Button.text = leaderboardBoardLabel(LeaderboardBoard.Best60)
        leaderboardTotalHitsButton.text = leaderboardBoardLabel(LeaderboardBoard.TotalHits)
        leaderboardStreakButton.text = leaderboardBoardLabel(LeaderboardBoard.LongestStreak)
        when (leaderboardBoard) {
            LeaderboardBoard.Best30 -> leaderboard30Button.isChecked = true
            LeaderboardBoard.Best60 -> leaderboard60Button.isChecked = true
            LeaderboardBoard.TotalHits -> leaderboardTotalHitsButton.isChecked = true
            LeaderboardBoard.LongestStreak -> leaderboardStreakButton.isChecked = true
        }
        editProfileButton.text = tr("profile_edit")
        refreshCloudButton.text = tr("cloud_refresh")
        developerInfoButton.text = developerInfoButtonLabel()
        refreshLeaderboardButton.text = tr("leaderboard_refresh")
        shareTrainingButton.text = shareTrainingLabel()
        shareAchievementsButton.text = shareAchievementsLabel()
        shareLeaderboardButton.text = shareLeaderboardLabel()
        settingsButton.contentDescription = tr("settings")
        quietIconView.contentDescription = tr("keep_quiet")
        refreshActivationCardState()
        if (::serialInput.isInitialized && ::codeInput.isInitialized) {
            updateActivationInputState()
        }
        refreshHomePageVisibility()
        if (latestReport == null) {
            renderEmptyReport()
        }
        refreshCloudViews()
    }
































































    private fun shareTrainingLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "分享战报"
            AppLanguage.English -> "Share Report"
            AppLanguage.French -> "Partager"
            AppLanguage.Thai -> "แชร์"
        }

    private fun shareAchievementsLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "分享荣誉"
            AppLanguage.English -> "Share Honors"
            AppLanguage.French -> "Partager"
            AppLanguage.Thai -> "แชร์"
        }

    private fun shareLeaderboardLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "分享排名"
            AppLanguage.English -> "Share Rank"
            AppLanguage.French -> "Partager"
            AppLanguage.Thai -> "แชร์"
        }

    private fun currentTierShareLabel(): String =
        cloudTier?.let { tierLabelForKey(it.key) } ?: tierLabelForLevel(cloudProfile?.currentTier ?: 1)

    private fun posterRoot(
        accentColor: String,
        secondaryAccent: String = "#17384B",
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(32), dp(42), dp(32), dp(42))
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor("#140800"),
                        Color.parseColor("#1A0C00"),
                        Color.parseColor("#071019"),
                    ),
                ).apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(34).toFloat()
                    setStroke(dp(2), Color.parseColor(accentColor))
                }
            addView(
                TextView(this@MainActivity).apply {
                    text = "SMART SENSOR BALL"
                    setTextColor(Color.parseColor("#FFF0BF"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    letterSpacing = 0.08f
                },
            )
            addView(
                View(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(1),
                        ).apply {
                            topMargin = dp(16)
                            bottomMargin = dp(20)
                        }
                    background = roundedBackground(secondaryAccent, secondaryAccent, 999)
                    alpha = 0.55f
                },
            )
        }

    private fun posterSectionCard(
        accentColor: String,
        fillColor: String = "#0D1822",
        strokeColor: String = accentColor,
    ): LinearLayout =
        detailCard(fillColor = fillColor, strokeColor = strokeColor, cornerDp = 26).apply {
            background = metallicBackground("#152B39", fillColor, strokeColor, 26)
        }

    private fun posterMetricCard(
        label: String,
        value: String,
        accentColor: String,
    ): LinearLayout =
        detailCard(fillColor = "#0B1720", strokeColor = accentColor, cornerDp = 18).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(
                bodyText(label).apply {
                    setTextColor(Color.parseColor("#B88A54"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
            )
            addView(
                titleText(value, 19f).apply {
                    gravity = Gravity.START
                    setTextColor(Color.parseColor("#FFF8E8"))
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }

    private fun posterIdentityCard(
        nickname: String,
        subline: String,
        accentColor: String,
    ): LinearLayout =
        posterSectionCard(accentColor = accentColor, fillColor = "#0A141C", strokeColor = "#294558").apply {
            val row =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            val avatarShell =
                FrameLayout(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                            rightMargin = dp(16)
                        }
                }
            val avatarImage =
                ImageView(this@MainActivity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                }
            val avatarFallback =
                TextView(this@MainActivity).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                }
            bindAvatarPresentation(
                container = avatarShell,
                imageView = avatarImage,
                fallbackView = avatarFallback,
                seedText = nickname,
                colorHex = cloudProfile?.avatarColor ?: "#2A5C7B",
                imageUri = currentAvatarImageUri(),
            )
            avatarShell.addView(avatarImage)
            avatarShell.addView(avatarFallback)

            val textColumn =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                }
            textColumn.addView(
                titleText(nickname, 20f).apply {
                    gravity = Gravity.START
                    setTextColor(Color.parseColor("#FFF8E8"))
                },
            )
            textColumn.addView(
                bodyText(subline).apply {
                    setTextColor(Color.parseColor("#C9A46A"))
                    setPadding(0, dp(6), 0, 0)
                },
            )
            row.addView(avatarShell)
            row.addView(textColumn)
            addView(row)
        }

    private fun renderPosterBitmap(root: View): Bitmap {
        val widthPx = 1080
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            root.draw(canvas)
        }
    }

    private fun sharePosterBitmap(
        bitmap: Bitmap,
        filePrefix: String,
        chooserTitle: String,
        shareText: String,
    ) {
        val shareDir = File(cacheDir, "shared").apply { mkdirs() }
        val outputFile = File(shareDir, "${filePrefix}_${System.currentTimeMillis()}.png")
        FileOutputStream(outputFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", outputFile)
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    private fun shareTextPlain(
        text: String,
        chooserTitle: String = shareTrainingLabel(),
    ) {
        if (text.isBlank()) {
            return
        }
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    private fun buildTrainingPosterBitmap(report: TrainingReport): Bitmap {
        val accentColor = "#FF9A30"
        val root = posterRoot(accentColor)
        root.addView(
            TextView(this).apply {
                text = localText("训练战报", "TRAINING REPORT", "RAPPORT", "รายงานการฝึก")
                setTextColor(Color.parseColor("#140800"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = metallicBackground("#BCEEFF", accentColor, "#FFF8E8", 999)
                setPadding(dp(14), dp(7), dp(14), dp(7))
            },
        )
        root.addView(
            posterSectionCard(accentColor = accentColor, fillColor = "#1A0C00", strokeColor = accentColor).apply {
                val heroRow =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                    }
                val leftColumn =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        minimumWidth = dp(280)
                        layoutParams =
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply {
                                rightMargin = dp(22)
                            }
                    }
                leftColumn.addView(
                    bodyText(localText("本次训练成绩", "SESSION RESULT", "RÉSULTAT", "ผลการฝึก")).apply {
                        setTextColor(Color.parseColor("#B88A54"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    },
                )
                leftColumn.addView(
                    titleText(localText("速度战报", "Performance Snapshot", "Aperçu performance", "ภาพรวมผลงาน"), 30f).apply {
                        gravity = Gravity.START
                        setTextColor(Color.parseColor("#FFF8E8"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                leftColumn.addView(
                    bodyText(displayModeLabel(report.mode)).apply {
                        setTextColor(Color.parseColor("#FFE49A"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                leftColumn.addView(
                    bodyText(formatReportEndedTime(report.endedAtEpochMs)).apply {
                        setTextColor(Color.parseColor("#B88A54"))
                        setPadding(0, dp(12), 0, 0)
                    },
                )
                val scoreOrb =
                    FrameLayout(this@MainActivity).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(dp(196), dp(196)).apply {
                                gravity = Gravity.CENTER_VERTICAL
                            }
                        background = metallicBackground("#214159", "#0A131B", "#D9F2FF", 999)
                        addView(
                            FrameLayout(this@MainActivity).apply {
                                layoutParams =
                                    FrameLayout.LayoutParams(dp(166), dp(166), Gravity.CENTER)
                                background = metallicBackground("#FF9A30", "#1A0C00", "#E7FBFF", 999)
                                addView(
                                    LinearLayout(this@MainActivity).apply {
                                        orientation = LinearLayout.VERTICAL
                                        gravity = Gravity.CENTER
                                        layoutParams =
                                            FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                            )
                                        addView(
                                            bodyText("TOTAL").apply {
                                                gravity = Gravity.CENTER
                                                setTextColor(Color.parseColor("#FFF0BF"))
                                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                            },
                                        )
                                        addView(
                                            titleText(report.totalHits.toString(), 40f).apply {
                                                gravity = Gravity.CENTER
                                                setTextColor(Color.parseColor("#FFFFFF"))
                                                setPadding(0, dp(4), 0, 0)
                                            },
                                        )
                                        addView(
                                            bodyText(tr("hits")).apply {
                                                gravity = Gravity.CENTER
                                                setTextColor(Color.parseColor("#FFF8E8"))
                                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                heroRow.addView(leftColumn)
                heroRow.addView(scoreOrb)
                addView(heroRow)
                val statusRow =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.START
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(18) }
                    }
                statusRow.addView(
                    badgeText(
                        text = currentTierShareLabel(),
                        textColor = "#FFF8E8",
                        fillColor = "#16384A",
                    ).apply {
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    },
                )
                statusRow.addView(
                    badgeText(
                        text =
                            if (selectedLanguage == AppLanguage.Chinese) {
                                "最佳爆发 ${report.bestBurstCount}"
                            } else {
                                "Burst ${report.bestBurstCount}"
                            },
                        textColor = "#140800",
                        fillColor = "#F0B94B",
                    ).apply {
                        (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(10)
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    },
                )
                addView(statusRow)
            }.apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) }
            },
        )
        val metricsRow1 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(18) }
            }
        metricsRow1.addView(
            posterMetricCard(
                tr("average_frequency"),
                "${String.format(Locale.US, "%.2f", report.averageFrequency)} ${tr("hits_per_second")}",
                "#4FB6FF",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
            },
        )
        metricsRow1.addView(
            posterMetricCard(tr("best_burst"), "${report.bestBurstCount} ${tr("hits")}", "#F0B94B").apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        root.addView(metricsRow1)
        val metricsRow2 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
            }
        metricsRow2.addView(
            posterMetricCard(tr("burst_start"), "${String.format(Locale.US, "%.1f", report.bestBurstStartSec)}s", "#FFB347").apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
            },
        )
        metricsRow2.addView(
            posterMetricCard(tr("tier"), currentTierShareLabel(), "#D8B76A").apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        root.addView(metricsRow2)
        root.addView(
            posterIdentityCard(
                nickname = cloudProfile?.nickname.orEmpty().ifBlank { "Fighter" },
                subline = localText("继续冲击更高段位", "Keep climbing to the next tier", "Continuez vers le rang suivant", "ไต่ระดับต่อไป"),
                accentColor = "#244458",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(22) }
            },
        )
        return renderPosterBitmap(root)
    }

    private fun buildAchievementsPosterBitmap(): Bitmap {
        val recent = cloudAchievements.filter { it.unlocked }.sortedByDescending { it.unlockedAt.orEmpty() }.take(1).firstOrNull()
        val nextLocked = cloudAchievements.filterNot { it.unlocked }.sortedBy { it.sortOrder }.firstOrNull()
        val badgeName = recent?.let { achievementDisplayName(it.key) } ?: currentTierShareLabel()
        val badgeCode = recent?.let { achievementBadgeCode(it.key) } ?: "TIER"
        val unlockedCount = cloudAchievements.count { it.unlocked }
        val accentColor = recent?.let { achievementAccentColor(it.key) } ?: "#D8B76A"
        val root = posterRoot(accentColor, "#224357")
        root.addView(
            TextView(this).apply {
                text = localText("新徽章解锁", "NEW HONOR", "NOUVEL HONNEUR", "เกียรติยศใหม่")
                setTextColor(Color.parseColor("#140800"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = metallicBackground("#FFE8A8", accentColor, "#FFF5D8", 999)
                setPadding(dp(14), dp(7), dp(14), dp(7))
            },
        )
        root.addView(
            posterSectionCard(accentColor = accentColor, fillColor = "#0F1820", strokeColor = accentColor).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(4) }
                        val ribbonRow =
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER
                            }
                        ribbonRow.addView(
                            View(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(40), dp(92)).apply { rightMargin = dp(14) }
                                background = metallicBackground("#466A89", "#1A2F40", "#8FD8FF", 16)
                            },
                        )
                        ribbonRow.addView(
                            FrameLayout(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(188), dp(188))
                                background = metallicBackground("#2A3A49", "#0B1318", accentColor, 999)
                                addView(
                                    FrameLayout(this@MainActivity).apply {
                                        layoutParams =
                                            FrameLayout.LayoutParams(dp(152), dp(152), Gravity.CENTER)
                                        background = metallicBackground("#FFE8A8", accentColor, "#FFF5D8", 999)
                                        addView(
                                            LinearLayout(this@MainActivity).apply {
                                                orientation = LinearLayout.VERTICAL
                                                gravity = Gravity.CENTER
                                                layoutParams =
                                                    FrameLayout.LayoutParams(
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                    )
                                                addView(
                                                    bodyText("BADGE").apply {
                                                        gravity = Gravity.CENTER
                                                        setTextColor(Color.parseColor("#7A5A1F"))
                                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                                    },
                                                )
                                                addView(
                                                    titleText(badgeCode, 30f).apply {
                                                        gravity = Gravity.CENTER
                                                        setTextColor(Color.parseColor("#4A3510"))
                                                        setPadding(0, dp(6), 0, 0)
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        ribbonRow.addView(
                            View(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(40), dp(92)).apply { leftMargin = dp(14) }
                                background = metallicBackground("#466A89", "#1A2F40", "#8FD8FF", 16)
                            },
                        )
                        addView(ribbonRow)
                        addView(
                            badgeText(
                                text = localText("荣誉馆珍藏", "HONOR VAULT", "GALERIE D'HONNEUR", "หอเกียรติยศ"),
                                textColor = "#140800",
                                fillColor = "#D8B76A",
                            ).apply {
                                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(16)
                                setPadding(dp(12), dp(6), dp(12), dp(6))
                            },
                        )
                    },
                )
                addView(
                    titleText(badgeName, 28f).apply {
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#FFF8E7"))
                        setPadding(0, dp(18), 0, 0)
                    },
                )
                addView(
                    bodyText(localText("当前段位：${currentTierShareLabel()}", "Current tier: ${currentTierShareLabel()}", "Rang actuel : ${currentTierShareLabel()}", "ระดับปัจจุบัน: ${currentTierShareLabel()}")).apply {
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#FFF0C9"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                addView(
                    bodyText(localText("已解锁徽章：$unlockedCount / ${cloudAchievements.size}", "Unlocked badges: $unlockedCount / ${cloudAchievements.size}", "Badges débloqués : $unlockedCount / ${cloudAchievements.size}", "เหรียญที่ปลดล็อก: $unlockedCount / ${cloudAchievements.size}")).apply {
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#FFD88A"))
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                if (nextLocked != null) {
                    addView(
                        detailCard(fillColor = "#12202B", strokeColor = "#38546B", cornerDp = 18).apply {
                            setPadding(dp(16), dp(14), dp(16), dp(14))
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply { topMargin = dp(16) }
                            addView(
                                bodyText(localText("下一枚目标", "NEXT TARGET", "PROCHAIN OBJECTIF", "เป้าหมายถัดไป")).apply {
                                    setTextColor(Color.parseColor("#B88A54"))
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                },
                            )
                            addView(
                                titleText(achievementDisplayName(nextLocked.key), 18f).apply {
                                    gravity = Gravity.START
                                    setTextColor(Color.parseColor("#FFF8E8"))
                                    setPadding(0, dp(8), 0, 0)
                                },
                            )
                            addView(
                                bodyText("${nextLocked.progress}/${nextLocked.goal}").apply {
                                    setTextColor(Color.parseColor("#FFD88A"))
                                    setPadding(0, dp(8), 0, 0)
                                },
                            )
                        },
                    )
                }
            }.apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
            },
        )
        root.addView(
            posterIdentityCard(
                nickname = cloudProfile?.nickname.orEmpty().ifBlank { "Fighter" },
                subline = localText("每一次训练都在积累成长", "Every session adds to your growth", "Chaque séance nourrit votre progression", "ทุกการฝึกช่วยเพิ่มพัฒนาการ"),
                accentColor = accentColor,
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(20) }
            },
        )
        return renderPosterBitmap(root)
    }

    private fun buildLeaderboardPosterBitmap(): Bitmap {
        val me = leaderboardResult?.me
        val topThree = leaderboardResult?.top?.take(3).orEmpty()
        val accentColor = leaderboardAccentColor(leaderboardBoard)
        val root = posterRoot(accentColor, leaderboardAccentFill(leaderboardBoard))
        root.addView(
            TextView(this).apply {
                text = leaderboardBoardLabel(leaderboardBoard)
                setTextColor(Color.parseColor("#140800"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = metallicBackground("#BCEEFF", accentColor, "#FFF8E8", 999)
                setPadding(dp(14), dp(7), dp(14), dp(7))
            },
        )
        root.addView(
            posterSectionCard(accentColor = accentColor, fillColor = "#0E1821", strokeColor = accentColor).apply {
                val heroRow =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                val rankBlock =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply { rightMargin = dp(14) }
                    }
                rankBlock.addView(
                    bodyText(localText("当前排名", "CURRENT RANK", "RANG ACTUEL", "อันดับปัจจุบัน")).apply {
                        setTextColor(Color.parseColor("#B88A54"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    },
                )
                rankBlock.addView(
                    titleText(me?.let { "NO.${it.rank}" } ?: "NO.--", 42f).apply {
                        gravity = Gravity.START
                        setTextColor(Color.parseColor("#FFF8E8"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                rankBlock.addView(
                    bodyText(me?.let { leaderboardPrimaryValueText(it) } ?: localText("准备冲榜", "Ready to climb", "Prêt à monter", "พร้อมไต่อันดับ")).apply {
                        setTextColor(Color.parseColor("#FFF0BF"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                rankBlock.addView(
                    bodyText(currentTierShareLabel()).apply {
                        setTextColor(Color.parseColor("#D8B76A"))
                        setPadding(0, dp(10), 0, 0)
                    },
                )
                val trophySeal =
                    FrameLayout(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(174), dp(174))
                        background = metallicBackground("#284455", "#0D1821", accentColor, 999)
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                layoutParams =
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                addView(
                                    bodyText("RANK").apply {
                                        gravity = Gravity.CENTER
                                        setTextColor(Color.parseColor("#FFD88A"))
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                    },
                                )
                                addView(
                                    titleText(me?.rank?.toString() ?: "--", 38f).apply {
                                        gravity = Gravity.CENTER
                                        setTextColor(Color.parseColor("#FFFFFF"))
                                        setPadding(0, dp(6), 0, 0)
                                    },
                                )
                                addView(
                                    bodyText(leaderboardBoardLabel(leaderboardBoard)).apply {
                                        gravity = Gravity.CENTER
                                        setTextColor(Color.parseColor("#FFF0C9"))
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                    },
                                )
                            },
                        )
                    }
                heroRow.addView(rankBlock)
                heroRow.addView(trophySeal)
                addView(heroRow)
            }.apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
            },
        )
        if (topThree.isNotEmpty()) {
            val podiumRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(18) }
                }
            topThree.forEachIndexed { index, entry ->
                val podiumAccent =
                    when (entry.rank) {
                        1 -> "#F2C14E"
                        2 -> "#FFF0C9"
                        else -> "#D39A6A"
                    }
                podiumRow.addView(
                    detailCard(fillColor = "#0D1924", strokeColor = podiumAccent, cornerDp = 22).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply { if (index > 0) leftMargin = dp(10) }
                        background =
                            metallicBackground(
                                when (entry.rank) {
                                    1 -> "#4A3A16"
                                    2 -> "#35414B"
                                    else -> "#4B3428"
                                },
                                "#0D1924",
                                podiumAccent,
                                22,
                            )
                        minimumHeight =
                            when (entry.rank) {
                                1 -> dp(226)
                                2 -> dp(192)
                                else -> dp(178)
                            }
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(
                            badgeText("TOP ${entry.rank}", textColor = "#140800", fillColor = podiumAccent).apply {
                                setPadding(dp(12), dp(6), dp(12), dp(6))
                            },
                        )
                        addView(
                            titleText(entry.nickname, if (entry.rank == 1) 22f else 18f).apply {
                                gravity = Gravity.CENTER
                                setPadding(0, dp(16), 0, 0)
                                setTextColor(Color.parseColor("#FFF8E8"))
                            },
                        )
                        addView(
                            badgeText(tierLabelForKey(entry.tierKey), textColor = "#FFF5E6", fillColor = "#17384B").apply {
                                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
                                setPadding(dp(10), dp(5), dp(10), dp(5))
                            },
                        )
                        addView(
                            titleText(entry.bestHits.toString(), if (entry.rank == 1) 32f else 26f).apply {
                                gravity = Gravity.CENTER
                                setTextColor(Color.parseColor(podiumAccent))
                                setPadding(0, dp(14), 0, 0)
                            },
                        )
                        addView(
                            bodyText(leaderboardBoardLabel(leaderboardBoard)).apply {
                                gravity = Gravity.CENTER
                                setTextColor(Color.parseColor("#C9A46A"))
                                setPadding(0, dp(4), 0, 0)
                            },
                        )
                        addView(
                            bodyText(leaderboardSecondaryValueText(entry)).apply {
                                gravity = Gravity.CENTER
                                setTextColor(Color.parseColor("#B88A54"))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                setPadding(0, dp(10), 0, 0)
                            },
                        )
                    },
                )
            }
            root.addView(podiumRow)
        }
        root.addView(
            posterIdentityCard(
                nickname = cloudProfile?.nickname.orEmpty().ifBlank { "Fighter" },
                subline = localText("来挑战我的成绩", "Come challenge my score", "Venez défier mon score", "มาท้าคะแนนของฉัน"),
                accentColor = accentColor,
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(20) }
            },
        )
        return renderPosterBitmap(root)
    }

    private fun shareTrainingSummary() {
        val report = latestReport
        val shareText =
            if (report != null) {
                if (selectedLanguage == AppLanguage.Chinese) {
                    "我刚完成一轮 ${displayModeLabel(report.mode)} 训练，击打 ${report.totalHits} 次，平均 ${String.format(Locale.US, "%.2f", report.averageFrequency)} 次/秒。当前段位：${currentTierShareLabel()}。"
                } else {
                    "I just finished a ${displayModeLabel(report.mode)} session with ${report.totalHits} hits at ${String.format(Locale.US, "%.2f", report.averageFrequency)} hits/s. Current tier: ${currentTierShareLabel()}."
                }
            } else {
                if (selectedLanguage == AppLanguage.Chinese) {
                    "我的 Smart sensor ball 训练已经开始，欢迎来挑战我的成绩。"
                } else {
                    "My Smart sensor ball training is on. Come challenge my score."
                }
            }
        if (report == null) {
            shareTextPlain(shareText, shareTrainingLabel())
            return
        }
        runCatching {
            sharePosterBitmap(
                bitmap = buildTrainingPosterBitmap(report),
                filePrefix = "training_report",
                chooserTitle = shareTrainingLabel(),
                shareText = shareText,
            )
        }.getOrElse {
            shareTextPlain(shareText, shareTrainingLabel())
        }
        markTrainingSharedForDailyTask()
        return
        val text =
            if (report != null) {
                when (selectedLanguage) {
                    AppLanguage.Chinese ->
                        "我刚完成了一轮${displayModeLabel(report.mode)}训练，击打 ${report.totalHits} 次，平均 ${String.format(Locale.US, "%.2f", report.averageFrequency)} 次/秒。当前段位：${cloudTier?.let { tierLabelForKey(it.key) } ?: tierLabelForLevel(cloudProfile?.currentTier ?: 1)}。"
                    else ->
                        "I just finished a ${displayModeLabel(report.mode)} session with ${report.totalHits} hits at ${String.format(Locale.US, "%.2f", report.averageFrequency)} hits/s. Current tier: ${cloudTier?.let { tierLabelForKey(it.key) } ?: tierLabelForLevel(cloudProfile?.currentTier ?: 1)}."
                }
            } else {
                when (selectedLanguage) {
                    AppLanguage.Chinese -> "我的 Smart sensor ball 训练已经开始，欢迎来挑战我的成绩。"
                    else -> "My Smart sensor ball training is on. Come challenge my score."
                }
            }
        shareTextPlain(text)
    }

    private fun shareAchievementsSummary() {
        val recent = cloudAchievements.filter { it.unlocked }.sortedByDescending { it.unlockedAt.orEmpty() }.take(3)
        val shareText =
            if (recent.isNotEmpty()) {
                val names = recent.joinToString(", ") { achievementDisplayName(it.key) }
                if (selectedLanguage == AppLanguage.Chinese) {
                    "我已解锁 ${recent.size} 枚最新训练徽章：$names。当前段位：${currentTierShareLabel()}。"
                } else {
                    "I unlocked ${recent.size} new training badges: $names. Current tier: ${currentTierShareLabel()}."
                }
            } else {
                if (selectedLanguage == AppLanguage.Chinese) {
                    "我正在 Smart sensor ball 训练中持续成长，下一枚徽章很快就会解锁。"
                } else {
                    "I am progressing through Smart sensor ball training and my next badge is on the way."
                }
            }
        runCatching {
            sharePosterBitmap(
                bitmap = buildAchievementsPosterBitmap(),
                filePrefix = "training_honor",
                chooserTitle = shareAchievementsLabel(),
                shareText = shareText,
            )
        }.getOrElse {
            shareTextPlain(shareText, shareAchievementsLabel())
        }
        return
        val text =
            if (recent.isNotEmpty()) {
                val names = recent.joinToString(", ") { achievementDisplayName(it.key) }
                when (selectedLanguage) {
                    AppLanguage.Chinese -> "我已解锁 ${recent.size} 枚最新训练徽章：$names。当前段位：${cloudTier?.let { tierLabelForKey(it.key) } ?: tierLabelForLevel(cloudProfile?.currentTier ?: 1)}。"
                    else -> "I unlocked ${recent.size} new training badges: $names. Current tier: ${cloudTier?.let { tierLabelForKey(it.key) } ?: tierLabelForLevel(cloudProfile?.currentTier ?: 1)}."
                }
            } else {
                when (selectedLanguage) {
                    AppLanguage.Chinese -> "我正在 Smart sensor ball 训练中持续成长，下一枚徽章很快就会解锁。"
                    else -> "I am progressing through Smart sensor ball training and my next badge is on the way."
                }
            }
        shareTextPlain(text)
    }

    private fun shareLeaderboardSummary() {
        val me = leaderboardResult?.me
        val shareText =
            if (me != null) {
                if (selectedLanguage == AppLanguage.Chinese) {
                    "我当前在${leaderboardBoardLabel(leaderboardBoard)}中排名 ${me.rank}，成绩 ${leaderboardPrimaryValueText(me)}。来挑战我的成绩。"
                } else {
                    "I am ranked ${me.rank} on the ${leaderboardBoardLabel(leaderboardBoard)} with ${leaderboardPrimaryValueText(me)}. Come challenge my score."
                }
            } else {
                if (selectedLanguage == AppLanguage.Chinese) {
                    "我正在冲击${leaderboardBoardLabel(leaderboardBoard)}，欢迎来挑战。"
                } else {
                    "I am climbing the ${leaderboardBoardLabel(leaderboardBoard)}. Come challenge me."
                }
            }
        runCatching {
            sharePosterBitmap(
                bitmap = buildLeaderboardPosterBitmap(),
                filePrefix = "leaderboard_rank",
                chooserTitle = shareLeaderboardLabel(),
                shareText = shareText,
            )
        }.getOrElse {
            shareTextPlain(shareText, shareLeaderboardLabel())
        }
        return
        val text =
            if (me != null) {
                when (selectedLanguage) {
                    AppLanguage.Chinese -> "我当前在${leaderboardBoardLabel(leaderboardBoard)}中排名 ${me.rank}，成绩 ${leaderboardPrimaryValueText(me)}。来挑战我的成绩。"
                    else -> "I am ranked ${me.rank} on the ${leaderboardBoardLabel(leaderboardBoard)} with ${leaderboardPrimaryValueText(me)}. Come challenge my score."
                }
            } else {
                when (selectedLanguage) {
                    AppLanguage.Chinese -> "我正在冲击${leaderboardBoardLabel(leaderboardBoard)}，欢迎来挑战。"
                    else -> "I am climbing the ${leaderboardBoardLabel(leaderboardBoard)}. Come challenge me."
                }
            }
        shareTextPlain(text)
    }
    private fun displayCountdownStatus(value: Int): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "${value} 秒后开始..."
            AppLanguage.English -> "Starting in $value..."
            AppLanguage.French -> "Départ dans $value..."
            AppLanguage.Thai -> "เริ่มใน $value..."
        }

    private fun displayGoCue(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "开始"
            AppLanguage.English -> "Go"
            AppLanguage.French -> "Go"
            AppLanguage.Thai -> "ไป"
        }

    private fun displayGoLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "开始"
            AppLanguage.English -> "GO"
            AppLanguage.French -> "GO"
            AppLanguage.Thai -> "ไป"
        }

    private fun displayModeLabel(mode: TrainingMode): String =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                when (mode) {
                    TrainingMode.Seconds30 -> "30 秒"
                    TrainingMode.Seconds60 -> "60 秒"
                    TrainingMode.Burst10 -> "10 秒爆发"
                    TrainingMode.Burst15 -> "15 秒爆发"
                }

            AppLanguage.English ->
                when (mode) {
                    TrainingMode.Seconds30 -> "30 sec"
                    TrainingMode.Seconds60 -> "60 sec"
                    TrainingMode.Burst10 -> "10 sec burst"
                    TrainingMode.Burst15 -> "15 sec burst"
                }

            AppLanguage.French ->
                when (mode) {
                    TrainingMode.Seconds30 -> "30 s"
                    TrainingMode.Seconds60 -> "60 s"
                    TrainingMode.Burst10 -> "Explosif 10 s"
                    TrainingMode.Burst15 -> "Explosif 15 s"
                }

            AppLanguage.Thai ->
                when (mode) {
                    TrainingMode.Seconds30 -> "30 วินาที"
                    TrainingMode.Seconds60 -> "60 วินาที"
                    TrainingMode.Burst10 -> "ระเบิด 10 วิ"
                    TrainingMode.Burst15 -> "ระเบิด 15 วิ"
                }
        }

    private fun displayRemaining(remainingMillis: Long): String {
        val seconds = remainingMillis.coerceAtLeast(0L) / 100L / 10.0f
        return when (selectedLanguage) {
            AppLanguage.Chinese -> String.format(Locale.US, "剩余 %.1f 秒", seconds)
            AppLanguage.English -> String.format(Locale.US, "%.1fs left", seconds)
            AppLanguage.French -> String.format(Locale.US, "%.1fs restantes", seconds)
            AppLanguage.Thai -> String.format(Locale.US, "เหลือ %.1f วินาที", seconds)
        }
    }

    private fun loadSettings() {
        selectedLanguage = AppLanguage.fromStorage(prefs.getString(KEY_LANGUAGE, defaultLanguage().storageValue))
        selectedPlayMode =
            runCatching {
                TrainingPlayMode.valueOf(prefs.getString(KEY_SELECTED_PLAY_MODE, TrainingPlayMode.Classic30.name).orEmpty())
            }.getOrDefault(TrainingPlayMode.Classic30)
        selectedMode = modeForPlayMode(selectedPlayMode)
    }

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_LANGUAGE, selectedLanguage.storageValue)
            .apply()
    }

    private fun initTextToSpeech() {
        tts =
            TextToSpeech(applicationContext) { status ->
                val speaker = tts
                ttsInitialized = true
                if (status != TextToSpeech.SUCCESS || speaker == null) {
                    ttsReady = false
                    ttsLocaleInUse = null
                } else {
                    ttsReady = true
                    updateTtsLanguage()
                    speaker.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit

                            override fun onDone(utteranceId: String?) {
                                completeTtsCue(utteranceId)
                            }

                            @Deprecated("Deprecated in Android framework")
                            override fun onError(utteranceId: String?) {
                                completeTtsCue(utteranceId)
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                completeTtsCue(utteranceId)
                            }
                        },
                    )
                    speaker.setSpeechRate(1.0f)
                }
            }
    }

    private fun preferredTtsLocales(): LinkedHashSet<Locale> =
        when (selectedLanguage) {
            AppLanguage.Chinese ->
                linkedSetOf(
                    Locale.CHINA,
                    Locale.SIMPLIFIED_CHINESE,
                    Locale.CHINESE,
                    Locale.US,
                    Locale.ENGLISH,
                )

            AppLanguage.English ->
                linkedSetOf(
                    Locale.US,
                    Locale.UK,
                    Locale.ENGLISH,
                )

            AppLanguage.French ->
                linkedSetOf(
                    Locale.FRANCE,
                    Locale.FRENCH,
                    Locale.CANADA_FRENCH,
                    Locale.US,
                    Locale.ENGLISH,
                )

            AppLanguage.Thai ->
                linkedSetOf(
                    Locale("th", "TH"),
                    Locale("th"),
                    Locale.US,
                    Locale.ENGLISH,
                )
        }

    private fun selectedSpeechLanguageCode(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> Locale.CHINESE.language
            AppLanguage.English -> Locale.ENGLISH.language
            AppLanguage.French -> Locale.FRENCH.language
            AppLanguage.Thai -> "th"
        }

    private fun usesEnglishSpeechFallback(): Boolean {
        val currentLanguage = ttsLocaleInUse?.language ?: return false
        return selectedLanguage != AppLanguage.English &&
            !currentLanguage.equals(selectedSpeechLanguageCode(), ignoreCase = true) &&
            currentLanguage.equals(Locale.ENGLISH.language, ignoreCase = true)
    }

    private fun updateTtsLanguage() {
        if (!ttsInitialized) {
            return
        }
        val speaker = tts ?: return
        val preferredLocale = preferredTtsLocales().first()
        val cached = ttsLocaleInUse
        if (cached != null && cached.language == preferredLocale.language) {
            // Already serving the right language family — skip the slow setLanguage round-trip.
            return
        }
        val candidates = preferredTtsLocales()
        var appliedLocale: Locale? = null
        candidates.forEach { locale ->
            if (appliedLocale != null) {
                return@forEach
            }
            val result = speaker.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                appliedLocale = locale
            }
        }
        ttsLocaleInUse = appliedLocale
        ttsReady = appliedLocale != null
    }

    private fun speakCue(text: String, onDone: (() -> Unit)? = null) {
        if (!ttsReady) {
            onDone?.invoke()
            return
        }
        val cueText = spokenCueText(text)
        if (cueText.isBlank()) {
            onDone?.invoke()
            return
        }
        val utteranceId = "cue-${UUID.randomUUID()}"
        if (onDone != null) {
            ttsCompletionCallbacks[utteranceId] = onDone
        }
        val result = tts?.speak(cueText, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            ttsCompletionCallbacks.remove(utteranceId)
            onDone?.invoke()
        }
    }

    private fun completeTtsCue(utteranceId: String?) {
        if (utteranceId.isNullOrBlank()) {
            return
        }
        ttsCompletionCallbacks.remove(utteranceId)?.invoke()
    }

    private fun spokenCueText(text: String): String =
        when {
            usesEnglishSpeechFallback() ->
                when (text) {
                    displayGoCue(), displayGoLabel(), "开始", "ไป", "GO", "Go" -> "Go"
                    else -> text
                }

            selectedLanguage == AppLanguage.French ->
                when (text) {
                    displayGoCue(), displayGoLabel() -> "Partez"
                    else -> text
                }

            selectedLanguage == AppLanguage.Thai ->
                when (text) {
                    displayGoCue(), displayGoLabel() -> "เริ่ม"
                    else -> text
                }

            else ->
                when (text) {
                    displayGoCue(), displayGoLabel() -> if (selectedLanguage == AppLanguage.Chinese) "开始" else "Go"
                    else -> text
                }
        }

    private var countPulseAnimator: AnimatorSet? = null

    private fun pulseCount() {
        countPulseAnimator?.cancel()
        countView.scaleX = 1.0f
        countView.scaleY = 1.0f
        val growX = ObjectAnimator.ofFloat(countView, View.SCALE_X, 1.0f, 1.12f)
        val growY = ObjectAnimator.ofFloat(countView, View.SCALE_Y, 1.0f, 1.12f)
        val shrinkX = ObjectAnimator.ofFloat(countView, View.SCALE_X, 1.12f, 1.0f)
        val shrinkY = ObjectAnimator.ofFloat(countView, View.SCALE_Y, 1.12f, 1.0f)
        countPulseAnimator = AnimatorSet().apply {
            play(growX).with(growY)
            play(shrinkX).with(shrinkY).after(growX)
            duration = 110L
            start()
        }
    }


    private fun tr(key: String): String = UiStrings.get(selectedLanguage, key)

    private fun languageDisplayName(language: AppLanguage): String =
        when (language) {
            AppLanguage.Chinese -> tr("language_chinese")
            AppLanguage.English -> tr("language_english")
            AppLanguage.French -> tr("language_french")
            AppLanguage.Thai -> tr("language_thai")
        }

    private fun countdownStatus(value: Int): String = displayCountdownStatus(value)

    private fun goCue(): String = displayGoCue()

    private fun goLabel(): String = displayGoLabel()

    private fun modeLabel(mode: TrainingMode): String = displayModeLabel(mode)

    private fun formatRemaining(remainingMillis: Long): String = displayRemaining(remainingMillis)

    private fun defaultLanguage(): AppLanguage =
        when (Locale.getDefault().language.lowercase(Locale.US)) {
            "zh" -> AppLanguage.Chinese
            "fr" -> AppLanguage.French
            "th" -> AppLanguage.Thai
            else -> AppLanguage.English
        }

    private fun profileSubtitleText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "账号状态、语言与训练概览"
            AppLanguage.English -> "Account status, language, and training overview"
            AppLanguage.French -> "Statut du compte, langue et aperçu d'entraînement"
            AppLanguage.Thai -> "สถานะบัญชี ภาษา และภาพรวมการฝึก"
        }

    private fun historySubtitleText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "最近训练结果会自动同步到云端"
            AppLanguage.English -> "Recent sessions are synced to the cloud automatically"
            AppLanguage.French -> "Les dernières séances sont synchronisées automatiquement"
            AppLanguage.Thai -> "ผลการฝึกล่าสุดจะซิงก์ขึ้นคลาวด์อัตโนมัติ"
        }

    private fun leaderboardSubtitleText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "查看 30 秒与 60 秒模式下的最佳成绩"
            AppLanguage.English -> "See the best scores for 30-second and 60-second modes"
            AppLanguage.French -> "Consultez les meilleurs scores en 30 s et 60 s"
            AppLanguage.Thai -> "ดูคะแนนสูงสุดของโหมด 30 และ 60 วินาที"
        }

    private fun avatarChooseButtonLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "选择图片"
            AppLanguage.English -> "Choose Photo"
            AppLanguage.French -> "Choisir une photo"
            AppLanguage.Thai -> "เลือกรูปภาพ"
        }

    private fun avatarClearButtonLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "移除图片"
            AppLanguage.English -> "Remove Photo"
            AppLanguage.French -> "Supprimer la photo"
            AppLanguage.Thai -> "ลบรูปภาพ"
        }

    private fun avatarImageHintText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "可从手机相册选择头像图片，未选择时使用颜色头像。"
            AppLanguage.English -> "Choose an avatar photo from this phone, or keep the color avatar."
            AppLanguage.French -> "Choisissez une photo sur ce téléphone ou gardez l'avatar coloré."
            AppLanguage.Thai -> "เลือกรูปโปรไฟล์จากโทรศัพท์ หรือใช้รูปแบบสีเดิม"
        }

    private fun developerInfoButtonLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "联系我们"
            AppLanguage.English -> "Contact Us"
            AppLanguage.French -> "Nous contacter"
            AppLanguage.Thai -> "ติดต่อเรา"
        }

    private fun developerInfoPageTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "关于我们"
            AppLanguage.English -> "About Us"
            AppLanguage.French -> "À propos"
            AppLanguage.Thai -> "เกี่ยวกับเรา"
        }

    private fun developerInfoPageSubtitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "开发者信息、联系邮箱及平台协议入口"
            AppLanguage.English -> "Developer details, contact email and policy links"
            AppLanguage.French -> "Informations développeur, e-mail et accès aux politiques"
            AppLanguage.Thai -> "ข้อมูลผู้พัฒนา อีเมลติดต่อ และลิงก์นโยบาย"
        }

    private fun developerCompanySectionTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "公司信息"
            AppLanguage.English -> "Company"
            AppLanguage.French -> "Entreprise"
            AppLanguage.Thai -> "ข้อมูลบริษัท"
        }

    private fun developerContactSectionTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "联系方式"
            AppLanguage.English -> "Contact"
            AppLanguage.French -> "Contact"
            AppLanguage.Thai -> "ช่องทางติดต่อ"
        }

    private fun developerExtrasSectionTitle(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "附加信息"
            AppLanguage.English -> "More Info"
            AppLanguage.French -> "Informations"
            AppLanguage.Thai -> "ข้อมูลเพิ่มเติม"
        }

    private fun developerCompanyName(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> DEVELOPER_COMPANY_NAME_ZH
            AppLanguage.English -> DEVELOPER_COMPANY_NAME_EN
            AppLanguage.French -> DEVELOPER_COMPANY_NAME_FR
            AppLanguage.Thai -> DEVELOPER_COMPANY_NAME_TH
        }

    private fun developerCompanyDescription(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "专注于智能拳击产品与运动数据体验"
            AppLanguage.English -> "Focused on smart sensor ball products and sports data experiences"
            AppLanguage.French -> "Spécialisée dans les produits de balle à capteurs intelligents et l'expérience des données sportives"
            AppLanguage.Thai -> "มุ่งเน้นผลิตภัณฑ์ลูกบอลเซ็นเซอร์อัจฉริยะและประสบการณ์ข้อมูลการกีฬา"
        }

    private fun developerEmailLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "邮箱"
            AppLanguage.English -> "Email"
            AppLanguage.French -> "E-mail"
            AppLanguage.Thai -> "อีเมล"
        }

    private fun developerEmailActionLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "发送邮件"
            AppLanguage.English -> "Send Email"
            AppLanguage.French -> "Envoyer un e-mail"
            AppLanguage.Thai -> "ส่งอีเมล"
        }

    private fun developerVersionLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "当前 APP 版本号"
            AppLanguage.English -> "App Version"
            AppLanguage.French -> "Version de l'application"
            AppLanguage.Thai -> "เวอร์ชันแอป"
        }

    private fun privacyPolicyEntryLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "隐私政策"
            AppLanguage.English -> "Privacy Policy"
            AppLanguage.French -> "Politique de confidentialité"
            AppLanguage.Thai -> "นโยบายความเป็นส่วนตัว"
        }

    private fun userAgreementEntryLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "用户协议"
            AppLanguage.English -> "User Agreement"
            AppLanguage.French -> "Accord utilisateur"
            AppLanguage.Thai -> "ข้อตกลงผู้ใช้"
        }

    private fun developerDocumentHint(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "可查看本应用当前版本对应的隐私政策与用户协议。"
            AppLanguage.English -> "Review the privacy policy and user agreement for the current app version."
            AppLanguage.French -> "Consultez la politique de confidentialité et l'accord utilisateur de cette version."
            AppLanguage.Thai -> "ดูนโยบายความเป็นส่วนตัวและข้อตกลงผู้ใช้ของแอปเวอร์ชันนี้"
        }

    private fun developerPrivacyPolicyAssetFile(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "privacy_policy_zh.txt"
            AppLanguage.English -> "privacy_policy_en.txt"
            AppLanguage.French -> "privacy_policy_fr.txt"
            AppLanguage.Thai -> "privacy_policy_th.txt"
        }

    private fun developerUserAgreementAssetFile(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "user_agreement_zh.txt"
            AppLanguage.English -> "user_agreement_en.txt"
            AppLanguage.French -> "user_agreement_fr.txt"
            AppLanguage.Thai -> "user_agreement_th.txt"
        }

    private fun developerDocumentUnavailableText(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "当前文档暂不可用，请稍后重试。"
            AppLanguage.English -> "This document is currently unavailable. Please try again later."
            AppLanguage.French -> "Ce document n'est pas disponible pour le moment."
            AppLanguage.Thai -> "เอกสารนี้ยังไม่พร้อมใช้งานในขณะนี้"
        }

    private fun closeLabel(): String =
        when (selectedLanguage) {
            AppLanguage.Chinese -> "关闭"
            AppLanguage.English -> "Close"
            AppLanguage.French -> "Fermer"
            AppLanguage.Thai -> "ปิด"
        }

    private fun achievementsTitleText(): String =
        localText("成就徽章", "Achievements", "Succès", "ความสำเร็จ")

    private fun achievementsSectionHint(): String =
        localText("解锁 20+ 个训练成就徽章，记录你的成长", "Unlock 20+ badge milestones and track your growth.", "Débloquez plus de 20 badges et suivez votre progression.", "ปลดล็อกเหรียญความสำเร็จกว่า 20 รายการและติดตามพัฒนาการ")

    private fun profilePageSubtitle(): String =
        localText("查看你的段位、训练统计与最近获得的徽章", "View your tier, key stats and recently unlocked badges.", "Consultez votre rang, vos statistiques et vos badges récents.", "ดูระดับ สถิติหลัก และเหรียญล่าสุดของคุณ")

    private fun historySectionSubtitle(): String =
        localText("最近训练结果会自动同步到云端", "Recent training sessions sync to the cloud automatically.", "Les dernières séances se synchronisent automatiquement dans le cloud.", "ผลการฝึกล่าสุดจะซิงก์ขึ้นคลาวด์อัตโนมัติ")

    private fun achievementsSubtitleText(unlockedCount: Int, totalCount: Int): String =
        localText("已解锁 $unlockedCount / $totalCount", "Unlocked $unlockedCount / $totalCount", "$unlockedCount / $totalCount débloqués", "ปลดล็อกแล้ว $unlockedCount / $totalCount")

    private fun profileBestScoreLabel(): String =
        localText("全局最佳", "Overall best", "Meilleur score", "ดีที่สุดรวม")

    private fun streakLabel(): String =
        localText("最长连练", "Best streak", "Meilleure série", "ต่อเนื่องสูงสุด")

    private fun activeDaysLabel(): String =
        localText("活跃天数", "Active days", "Jours actifs", "วันที่ใช้งาน")

    private fun nextTierLabel(): String =
        localText("下一段位", "Next tier", "Rang suivant", "ระดับถัดไป")

    private fun displayAppVersion(): String {
        val parts = BuildConfig.VERSION_NAME.split('.')
        return if (parts.size >= 2) {
            "V${parts[0]}.${parts[1]}"
        } else {
            "V${BuildConfig.VERSION_NAME}"
        }
    }

    private fun openDeveloperEmail() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL"))
        intent.putExtra(Intent.EXTRA_SUBJECT, DEVELOPER_EMAIL_SUBJECT)
        try {
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun loadAssetText(assetFile: String): String =
        try {
            assets.open(assetFile).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            ""
        }

    private fun championLabel(): String =
        localText("已达最高段位", "Top tier reached", "Rang maximal atteint", "ถึงระดับสูงสุดแล้ว")

    private fun tierLabelForLevel(level: Int): String = tierLabelForKey(tierKeyForLevel(level))

    private fun tierKeyForLevel(level: Int): String =
        when (level.coerceIn(1, 9)) {
            1 -> "beginner"
            2 -> "prospect"
            3 -> "contender"
            4 -> "striker"
            5 -> "challenger"
            6 -> "elite"
            7 -> "master"
            8 -> "legend"
            else -> "champion"
        }

    private fun tierLabelForKey(key: String?): String =
        when (key) {
            "beginner" -> localText("拳坛新丁", "New Blood", "Débutant", "มือใหม่")
            "prospect" -> localText("热血新秀", "Rising Rookie", "Espoir montant", "ดาวรุ่ง")
            "contender" -> localText("擂台争锋者", "Arena Contender", "Prétendant", "ผู้ท้าชิง")
            "striker" -> localText("铁拳出击手", "Iron Fist Striker", "Frappeur d'acier", "หมัดเหล็ก")
            "challenger" -> localText("风暴挑战者", "Storm Challenger", "Challenger tempête", "ผู้ท้าชิงพายุ")
            "elite" -> localText("荣耀精英", "Glory Elite", "Élite glorieuse", "ยอดฝีมือ")
            "master" -> localText("宗师", "Grand Master", "Grand maître", "ปรมาจารย์")
            "legend" -> localText("不朽传奇", "Immortal Legend", "Légende immortelle", "ตำนาน")
            "champion" -> localText("至尊拳王", "Supreme Champion", "Champion suprême", "แชมป์สูงสุด")
            else -> localText("拳坛新丁", "New Blood", "Débutant", "มือใหม่")
        }

    private fun achievementDisplayName(key: String): String =
        when (key) {
            "first_training" -> localText("初次登台", "Debut", "Début", "เปิดตัว")
            "sessions_5" -> localText("持续热身", "Warmup Run", "Échauffement régulier", "วอร์มอัพต่อเนื่อง")
            "sessions_15" -> localText("训练常客", "Regular Fighter", "Combattant régulier", "นักชกประจำ")
            "sessions_30" -> localText("擂台老兵", "Ring Veteran", "Vétéran du ring", "มือเก๋า")
            "hits_100" -> localText("百拳试锋", "100-Hit Trial", "Essai 100 coups", "ทดสอบ 100 หมัด")
            "hits_500" -> localText("五百重击", "500 Heavy Hits", "500 frappes", "500 หมัดหนัก")
            "hits_1000" -> localText("千拳风暴", "1K Punch Storm", "Tempête 1K coups", "พายุ 1K หมัด")
            "hits_5000" -> localText("万击宗匠", "5K Master", "Maître 5K coups", "ปรมาจารย์ 5K")
            "best_30_40" -> localText("30秒·40击", "30s • 40 Hits", "30 s • 40 coups", "30วิ • 40 หมัด")
            "best_30_60" -> localText("30秒·60击", "30s • 60 Hits", "30 s • 60 coups", "30วิ • 60 หมัด")
            "best_30_80" -> localText("30秒·80击", "30s • 80 Hits", "30 s • 80 coups", "30วิ • 80 หมัด")
            "best_30_100" -> localText("30秒·100击", "30s • 100 Hits", "30 s • 100 coups", "30วิ • 100 หมัด")
            "best_60_90" -> localText("60秒·90击", "60s • 90 Hits", "60 s • 90 coups", "60วิ • 90 หมัด")
            "best_60_120" -> localText("60秒·120击", "60s • 120 Hits", "60 s • 120 coups", "60วิ • 120 หมัด")
            "best_60_150" -> localText("60秒·150击", "60s • 150 Hits", "60 s • 150 coups", "60วิ • 150 หมัด")
            "best_60_180" -> localText("60秒·180击", "60s • 180 Hits", "60 s • 180 coups", "60วิ • 180 หมัด")
            "burst_6" -> localText("爆发新星", "Burst Rookie", "Rookie explosif", "มือใหม่สายสปีด")
            "burst_10" -> localText("爆发高手", "Burst Expert", "Expert explosif", "ผู้เชี่ยวชาญสปีด")
            "burst_12" -> localText("爆发大师", "Burst Master", "Maître explosif", "มาสเตอร์สปีด")
            "burst_15" -> localText("爆发之王", "Burst King", "Roi explosif", "ราชาสปีด")
            "streak_3" -> localText("连练3天", "3-Day Streak", "Série 3 jours", "ต่อเนื่อง 3 วัน")
            "streak_7" -> localText("连练7天", "7-Day Streak", "Série 7 jours", "ต่อเนื่อง 7 วัน")
            "streak_14" -> localText("连练14天", "14-Day Streak", "Série 14 jours", "ต่อเนื่อง 14 วัน")
            "streak_30" -> localText("连练30天", "30-Day Streak", "Série 30 jours", "ต่อเนื่อง 30 วัน")
            else -> key
        }

    private fun achievementBadgeCode(key: String): String =
        when (key) {
            "first_training" -> "1ST"
            "sessions_5" -> "S5"
            "sessions_15" -> "S15"
            "sessions_30" -> "S30"
            "hits_100" -> "H100"
            "hits_500" -> "H500"
            "hits_1000" -> "1K"
            "hits_5000" -> "5K"
            "best_30_40" -> "30/40"
            "best_30_60" -> "30/60"
            "best_30_80" -> "30/80"
            "best_30_100" -> "30/100"
            "best_60_90" -> "60/90"
            "best_60_120" -> "60/120"
            "best_60_150" -> "60/150"
            "best_60_180" -> "60/180"
            "burst_6" -> "B6"
            "burst_10" -> "B10"
            "burst_12" -> "B12"
            "burst_15" -> "B15"

            "streak_3" -> "D3"
            "streak_7" -> "D7"
            "streak_14" -> "D14"
            "streak_30" -> "D30"
            else -> "BADGE"
        }

    private fun achievementBadgeImageRes(key: String): Int? =
        when (key) {
            "first_training" -> R.drawable.achievement_milestone_01
            "sessions_5" -> R.drawable.achievement_milestone_02
            "sessions_15" -> R.drawable.achievement_milestone_03
            "sessions_30" -> R.drawable.achievement_milestone_04
            "hits_100" -> R.drawable.achievement_hits_01
            "hits_500" -> R.drawable.achievement_hits_02
            "hits_1000" -> R.drawable.achievement_hits_03
            "hits_5000" -> R.drawable.achievement_hits_04
            "best_30_40" -> R.drawable.achievement_best30_05
            "best_30_60" -> R.drawable.achievement_best30_06
            "best_30_80" -> R.drawable.achievement_best30_07
            "best_30_100" -> R.drawable.achievement_best30_08
            "best_60_90" -> R.drawable.achievement_best60_09
            "best_60_120" -> R.drawable.achievement_best60_10
            "best_60_150" -> R.drawable.achievement_best60_11
            "best_60_180" -> R.drawable.achievement_best60_12
            "burst_6" -> R.drawable.achievement_burst_13
            "burst_10" -> R.drawable.achievement_burst_14
            "burst_12" -> R.drawable.achievement_burst_15
            "burst_15" -> R.drawable.achievement_burst_16

            "streak_3" -> R.drawable.achievement_streak_17
            "streak_7" -> R.drawable.achievement_streak_18
            "streak_14" -> R.drawable.achievement_streak_19
            "streak_30" -> R.drawable.achievement_streak_20
            else -> null
        }

    private fun achievementAccentColor(key: String): String =
        when {
            key.startsWith("best_30") -> "#00FF88"
            key.startsWith("best_60") -> "#AAFF00"
            key.startsWith("hits_") -> "#40E090"
            key.startsWith("burst_") -> "#00FFCC"
            key.startsWith("streak_") -> "#80FFB0"
            else -> "#00FF88"
        }

    private data class MetallicPalette(
        val highlight: String,
        val base: String,
        val stroke: String,
        val text: String,
    )

    private fun metallicBackground(
        highlight: String,
        base: String,
        stroke: String,
        cornerDp: Int = 18,
    ): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor(highlight),
                Color.parseColor(base),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun achievementMetalPalette(
        key: String,
        unlocked: Boolean,
    ): MetallicPalette =
        when {
            key.startsWith("best_30") ->
                if (unlocked) MetallicPalette("#FFE7A2", "#C8942C", "#FBE2A0", "#FFF8E6") else MetallicPalette("#32404B", "#1A232A", "#465B69", "#B88A54")
            key.startsWith("best_60") ->
                if (unlocked) MetallicPalette("#FFD3A2", "#B86F2E", "#F1C18B", "#FFF2E6") else MetallicPalette("#383B43", "#1D2026", "#4B5361", "#B88A54")
            key.startsWith("hits_") ->
                if (unlocked) MetallicPalette("#A6F3E5", "#23927C", "#CAFCEF", "#EFFFFB") else MetallicPalette("#2E4044", "#162328", "#425761", "#B88A54")
            key.startsWith("burst_") ->
                if (unlocked) MetallicPalette("#FFD1BE", "#B7653E", "#F8B999", "#FFF3EC") else MetallicPalette("#413631", "#211A19", "#5B4A44", "#B88A54")
            key.startsWith("streak_") ->
                if (unlocked) MetallicPalette("#E0D0FF", "#6D4BC7", "#CDBBFF", "#F7F2FF") else MetallicPalette("#383645", "#1E1E27", "#55556A", "#B88A54")
            else ->
                if (unlocked) MetallicPalette("#D2F4F0", "#2C8476", "#C7F9F2", "#F2FFFD") else MetallicPalette("#324049", "#1A2329", "#465963", "#B88A54")
        }

    private fun achievementBadgeCard(item: CloudAchievementItem): LinearLayout {
        val unlocked = item.unlocked
        val accentColor = achievementAccentColor(item.key)
        val fillColor = if (unlocked) "#11242F" else "#0C1822"
        val strokeColor = if (unlocked) accentColor else "#233A4B"
        val progressFraction = if (item.goal > 0) item.progress.toFloat() / item.goal.toFloat() else 0f
        return detailCard(fillColor = fillColor, strokeColor = strokeColor, cornerDp = 18).apply {
            val codeView =
                TextView(this@MainActivity).apply {
                    text = achievementBadgeCode(item.key)
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(if (unlocked) Color.parseColor("#FFF8E8") else Color.parseColor("#B88A54"))
                    background = roundedBackground(if (unlocked) accentColor else "#12222E", strokeColor, 999)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
            val titleView =
                bodyText(achievementDisplayName(item.key)).apply {
                    setTextColor(Color.parseColor("#FFF5E6"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setPadding(0, dp(10), 0, 0)
                }
            val progressView =
                bodyText("${item.progress}/${item.goal}").apply {
                    setTextColor(if (unlocked) Color.parseColor(accentColor) else Color.parseColor("#CAA26A"))
                    setPadding(0, dp(6), 0, 0)
                }
            val progressBar =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = dp(7)
                    background = roundedBackground("#10212E", "#1B3446", 999)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(7),
                        ).apply {
                            topMargin = dp(10)
                        }
                    val safeProgress = progressFraction.coerceIn(0f, 1f)
                    if (safeProgress > 0f) {
                        addView(
                            View(this@MainActivity).apply {
                                background = roundedBackground(accentColor, accentColor, 999)
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        safeProgress,
                                    )
                            },
                        )
                    }
                    if (safeProgress < 1f) {
                        addView(
                            View(this@MainActivity).apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        (1f - safeProgress).coerceAtLeast(0.0001f),
                                    )
                            },
                        )
                    }
                }
            addView(codeView)
            addView(titleView)
            addView(progressBar)
            addView(progressView)
        }
    }

    private fun shouldCelebrateTier(
        tier: CloudTierProgress?,
        promotedHint: Boolean,
        previousLevel: Int,
    ): Boolean {
        if (tier == null || previousLevel <= 0) {
            return false
        }
        return (promotedHint || tier.level > previousLevel) && tier.level > previousLevel
    }

    private fun syncSeenTier(tier: CloudTierProgress?) {
        if (tier == null) {
            return
        }
        val previousLevel = prefs.getInt(KEY_LAST_SEEN_TIER, 0)
        if (tier.level != previousLevel) {
            prefs.edit().putInt(KEY_LAST_SEEN_TIER, tier.level).apply()
        }
    }

    private fun computeNewlyUnlockedAchievements(
        previousUnlockedKeys: Set<String>,
        incoming: List<CloudAchievementItem>,
    ): List<CloudAchievementItem> =
        incoming
            .filter { it.unlocked && !previousUnlockedKeys.contains(it.key) }
            .sortedBy { it.sortOrder }

    private fun dismissCelebrationBeforeTraining() {
        dismissingCelebrationForTraining = true
        activeCelebrationDialog?.dismiss()
        activeCelebrationDialog = null
        celebrationShowing = false
        dismissingCelebrationForTraining = false
        tts?.stop()
        resetCelebrationVoice()
    }

    private fun maybeShowTrainingOutcomeCelebration(
        report: TrainingReport,
        outcome: TrainingCoachOutcome,
    ) {
        enqueueCelebration { showTrainingOutcomeDialog(report, outcome) }
    }

    private fun showTrainingOutcomeDialog(
        report: TrainingReport,
        outcome: TrainingCoachOutcome,
    ) {
        val title =
            when (outcome.playMode) {
                TrainingPlayMode.LevelChallenge ->
                    if (outcome.goalMet) {
                        localText("闯关成功", "Level Cleared")
                    } else {
                        localText("闯关继续", "Keep Challenging")
                    }

                TrainingPlayMode.DailyChallenge ->
                    if (outcome.goalMet) {
                        localText("挑战成功", "Challenge Complete")
                    } else {
                        localText("每日挑战进行中", "Daily Challenge Progress")
                    }

                TrainingPlayMode.Burst10,
                TrainingPlayMode.Burst15,
                ->
                    if (outcome.goalMet) {
                        localText("爆发达成", "Burst Target Hit")
                    } else {
                        localText("爆发训练完成", "Burst Session Complete")
                    }

                TrainingPlayMode.Classic30,
                TrainingPlayMode.Classic60,
                -> localText("训练完成", "Training Complete")
            }
        val chips =
            buildList {
                add("XP +${outcome.xpGain}" to "#FFD060")
                add(localText("连练 ${outcome.streak} 天", "${outcome.streak}-day streak") to "#FFB347")
                add(displayModeLabel(report.mode) to "#FF9A30")
                if (outcome.goalMet) {
                    add(localText("任务完成", "Task done") to "#E07010")
                }
            }
        showCelebrationDialog(
            accentColor = if (outcome.goalMet) "#FFD060" else "#FF9A30",
            eyebrow = if (outcome.goalMet) "VICTORY" else "GOOD WORK",
            title = title,
            body = buildCoachMessage(report, outcome),
            chips = chips,
        )
    }

    private fun maybeShowPostTrainingCelebrations(
        unlockedAchievements: List<CloudAchievementItem>,
        promotedTier: CloudTierProgress?,
    ) {
        if (unlockedAchievements.isNotEmpty()) {
            enqueueCelebration { showAchievementUnlockDialog(unlockedAchievements) }
        }
        promotedTier?.let { tier ->
            enqueueCelebration { showTierPromotionDialog(tier) }
        }
    }

    private fun enqueueCelebration(action: () -> Unit) {
        if (trainingJob?.isActive == true) {
            celebrationQueue.addLast(action)
            return
        }
        if (celebrationShowing) {
            celebrationQueue.addLast(action)
        } else {
            celebrationShowing = true
            action()
        }
    }

    private fun onCelebrationDismissed() {
        activeCelebrationDialog = null
        resetCelebrationVoice()
        if (dismissingCelebrationForTraining || trainingJob?.isActive == true) {
            celebrationShowing = false
            return
        }
        celebrationShowing = false
        showNextCelebrationIfIdle()
    }

    private fun showNextCelebrationIfIdle() {
        if (celebrationShowing || trainingJob?.isActive == true || celebrationQueue.isEmpty()) {
            return
        }
        celebrationShowing = true
        celebrationQueue.removeFirst().invoke()
    }

    private fun showTierPromotionBanner(tier: CloudTierProgress) {
        val message =
            if (selectedLanguage == AppLanguage.Chinese) {
                "段位升级：${tierLabelForKey(tier.key)} Lv.${tier.level}"
            } else {
                "Rank Up! ${tierLabelForKey(tier.key)} Lv.${tier.level}"
            }
        promotionBannerView.text = message
        promotionBannerView.visibility = View.VISIBLE
        promotionBannerView.alpha = 0f
        promotionBannerView.translationY = -dp(12).toFloat()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(promotionBannerView, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(promotionBannerView, View.TRANSLATION_Y, -dp(12).toFloat(), 0f),
            )
            duration = 320L
            start()
        }
        promotionBannerView.postDelayed({
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(promotionBannerView, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(promotionBannerView, View.TRANSLATION_Y, 0f, -dp(8).toFloat()),
                )
                duration = 320L
                start()
            }
            promotionBannerView.postDelayed({ promotionBannerView.visibility = View.GONE }, 340L)
        }, 2200L)
    }

    private fun showAchievementUnlockDialog(items: List<CloudAchievementItem>) {
        val chips =
            items.take(3).map { achievementDisplayName(it.key) to achievementAccentColor(it.key) }
        val title =
            if (selectedLanguage == AppLanguage.Chinese) {
                "新徽章解锁"
            } else {
                "New badges unlocked"
            }
        val body =
            if (selectedLanguage == AppLanguage.Chinese) {
                "本次训练解锁 ${items.size} 枚徽章，继续保持节奏，冲击更高段位。"
            } else {
                "You unlocked ${items.size} new badges in this session. Keep pushing for the next tier."
            }
        showCelebrationDialog(
            accentColor = "#FFB347",
            eyebrow = "NEW BADGES",
            title = title,
            body = body,
            chips = chips,
        )
    }

    private fun showTierPromotionDialog(tier: CloudTierProgress) {
        val title =
            if (selectedLanguage == AppLanguage.Chinese) {
                "段位升级：${tierLabelForKey(tier.key)}"
            } else {
                "Rank Up: ${tierLabelForKey(tier.key)}"
            }
        val body =
            if (selectedLanguage == AppLanguage.Chinese) {
                "当前最佳 30 秒成绩提升至 ${tier.bestHits}，成功晋升为 ${tierLabelForKey(tier.key)}。"
            } else {
                "Your best 30-second score is now ${tier.bestHits}. You have been promoted to ${tierLabelForKey(tier.key)}."
            }
        showCelebrationDialog(
            accentColor = "#FFD060",
            eyebrow = "RANK UP",
            title = title,
            body = body,
            chips = listOf("Lv.${tier.level}" to "#FFD060"),
        )
    }

    private fun showCelebrationDialog(
        accentColor: String,
        eyebrow: String,
        title: String,
        body: String,
        chips: List<Pair<String, String>>,
    ) {
        val overlay =
            FrameLayout(this).apply {
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(Color.argb(155, 4, 10, 18))
            }
        val card =
            detailCard(fillColor = "#0F2130", strokeColor = accentColor, cornerDp = 26).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                minimumHeight = dp(220)
            }
        card.addView(
            badgeText(
                text = eyebrow,
                textColor = "#140800",
                fillColor = accentColor,
            ),
        )
        card.addView(
            titleText(title, 24f).apply {
                setTextColor(Color.parseColor("#FFF8E8"))
                setPadding(0, dp(16), 0, 0)
            },
        )
        card.addView(
            bodyText(body).apply {
                setTextColor(Color.parseColor("#C7D6E4"))
                setPadding(0, dp(10), 0, 0)
            },
        )
        if (chips.isNotEmpty()) {
            val chipRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    setPadding(0, dp(16), 0, 0)
                }
            chips.forEachIndexed { index, chip ->
                chipRow.addView(
                    badgeText(
                        text = chip.first,
                        textColor = "#FFF8E8",
                        fillColor = chip.second,
                    ).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                if (index > 0) {
                                    leftMargin = dp(8)
                                }
                            }
                    },
                )
            }
            card.addView(chipRow)
        }
        card.addView(
            bodyText(localText("点击任意位置继续，或 10 秒后自动关闭", "Tap anywhere to continue, or it closes in 10 seconds", "Touchez n'importe où pour continuer, ou fermeture dans 10 secondes", "แตะที่ใดก็ได้เพื่อดำเนินการต่อ หรือปิดอัตโนมัติใน 10 วินาที")).apply {
                setTextColor(Color.parseColor("#7F97AA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(18), 0, 0)
            },
        )
        overlay.addView(card)

        val dialog =
            AlertDialog.Builder(this)
                .setView(overlay)
                .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { onCelebrationDismissed() }
        overlay.setOnClickListener { dialog.dismiss() }
        dialog.show()
        activeCelebrationDialog = dialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        speakCelebration(celebrationVoiceText(title))

        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.translationY = dp(20).toFloat()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(card, View.SCALE_X, 0.9f, 1f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, 0.9f, 1f),
                ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, dp(20).toFloat(), 0f),
            )
            duration = 280L
            start()
        }
        overlay.postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }, 10_000L)
    }

    private fun celebrationVoiceText(title: String): String =
        if (usesEnglishSpeechFallback()) {
            "Amazing! $title! Keep going strong!"
        } else {
            when (selectedLanguage) {
                AppLanguage.Chinese -> "太棒了！$title！继续保持，向更强进发！"
                AppLanguage.English -> "Amazing! $title! Keep going strong!"
                AppLanguage.French -> "Magnifique ! $title ! Continuez comme ça !"
                AppLanguage.Thai -> "ยอดเยี่ยม! $title! ลุยต่อไป!"
            }
        }

    private fun speakCelebration(text: String) {
        val speaker = tts ?: return
        if (!ttsReady || text.isBlank()) {
            return
        }
        runCatching {
            speaker.setPitch(1.35f)
            speaker.setSpeechRate(1.08f)
            speaker.speak(spokenCueText(text), TextToSpeech.QUEUE_ADD, null, "celebration-${UUID.randomUUID()}")
            promotionBannerView.postDelayed({
                resetCelebrationVoice()
            }, 2_800L)
        }
    }

    private fun resetCelebrationVoice() {
        runCatching {
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
        }
    }

    private fun rankLabel(rank: Int): String =
        when (rank) {
            1 -> "#1"
            2 -> "#2"
            3 -> "#3"
            else -> "#$rank"
        }

    private fun buildPodiumEntries(topThree: List<CloudLeaderboardEntry>): List<CloudLeaderboardEntry> =
        when (topThree.size) {
            0 -> emptyList()
            1 -> topThree
            2 -> listOf(topThree[0], topThree[1])
            else -> listOf(topThree[1], topThree[0], topThree[2])
        }

    private fun podiumAccentForRank(rank: Int): String =
        when (rank) {
            1 -> "#FFD060"
            2 -> "#A9C6D8"
            3 -> "#E3A36B"
            else -> "#FFB347"
        }

    private fun leaderboardAccentColor(board: LeaderboardBoard = leaderboardBoard): String =
        when (board) {
            LeaderboardBoard.Best30 -> "#00FF88"
            LeaderboardBoard.Best60 -> "#AAFF00"
            LeaderboardBoard.TotalHits -> "#40E090"
            LeaderboardBoard.LongestStreak -> "#00FFCC"
        }

    private fun leaderboardAccentFill(board: LeaderboardBoard = leaderboardBoard): String =
        when (board) {
            LeaderboardBoard.Best30 -> "#062016"
            LeaderboardBoard.Best60 -> "#13200A"
            LeaderboardBoard.TotalHits -> "#082218"
            LeaderboardBoard.LongestStreak -> "#05221E"
        }

    private fun sanitizeAvatarColor(colorHex: String?): String {
        val normalized = colorHex?.trim().orEmpty()
        return if (normalized.matches(Regex("^#[0-9A-Fa-f]{6}$"))) normalized.uppercase(Locale.US) else "#008840"
    }

    private fun avatarBackground(colorHex: String): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(sanitizeAvatarColor(colorHex)))
            setStroke(dp(2), Color.parseColor("#DFFFF0"))
        }

    private fun heroBackground(primaryColor: String): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor(primaryColor),
                Color.parseColor("#061410"),
                Color.parseColor("#040C08"),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), Color.parseColor("#00FF88"))
        }

    private fun detailCard(
        fillColor: String = "#0C1822",
        strokeColor: String = "#1C3344",
        cornerDp: Int = 18,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(cornerDp).toFloat()
                    setColor(Color.parseColor(fillColor))
                    setStroke(dp(1), Color.parseColor(strokeColor))
                }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }

    private fun badgeText(
        text: String,
        textColor: String = "#FFF5E6",
        fillColor: String = "#16384A",
    ): TextView =
        bodyText(text).apply {
            setTextColor(Color.parseColor(textColor))
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(fillColor, fillColor, 999)
        }

    private fun roundedBackground(
        fillColor: String,
        strokeColor: String = fillColor,
        cornerDp: Int = 14,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(Color.parseColor(fillColor))
            setStroke(dp(1), Color.parseColor(strokeColor))
        }

    private fun historySessionCard(item: CloudTrainingHistoryItem): LinearLayout {
        val card = detailCard(fillColor = "#0B1721", strokeColor = "#20384A")
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val modeChip = badgeText(displayModeLabel(secondsToMode(item.modeSeconds)), fillColor = "#17354A")
        val hitsChip = badgeText("${item.totalHits} ${tr("hits")}", fillColor = "#E07010")
        val headerSpacer =
            View(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        1,
                        1.0f,
                    )
            }
        header.addView(modeChip)
        header.addView(headerSpacer)
        header.addView(hitsChip)

        val titleLine =
            bodyText(formatHistoryTime(item.endedAt ?: item.startedAt)).apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#FFF8E8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(12), 0, 0)
            }
        val metricsRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dp(12), 0, 0)
            }
        val avgChip =
            badgeText(
                text = String.format(Locale.US, "%.2f %s", item.averageFrequency, tr("hits_per_second")),
                textColor = "#FFF0C9",
                fillColor = "#123246",
            )
        val burstChip =
            badgeText(
                text = "${tr("best_burst")}: ${item.bestBurstCount}",
                textColor = "#FFD060",
                fillColor = "#2B2412",
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = dp(10)
                    }
            }
        metricsRow.addView(avgChip)
        metricsRow.addView(burstChip)
        val detailLine =
            bodyText(
                "${tr("burst_start")}: ${String.format(Locale.US, "%.1f", item.bestBurstStartSec)}s",
            ).apply {
                setTextColor(Color.parseColor("#FFF0C9"))
                setTextColor(Color.parseColor("#B88A54"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(8), 0, 0)
            }
        card.addView(header)
        card.addView(titleLine)
        card.addView(metricsRow)
        card.addView(detailLine)
        return card
    }

    private fun podiumCard(
        entry: CloudLeaderboardEntry,
        accentColor: String,
        elevated: Boolean,
        leftMargin: Int,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                ).apply {
                    this.leftMargin = leftMargin
                    topMargin = if (elevated) 0 else dp(18)
                }
            addView(
                detailCard(fillColor = "#0D1924", strokeColor = accentColor, cornerDp = 22).apply {
                    minimumHeight = if (elevated) dp(176) else dp(148)
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(
                        badgeText(
                            text = "TOP ${entry.rank}",
                            textColor = "#140800",
                            fillColor = accentColor,
                        ),
                    )
                    addView(
                        bodyText(rankLabel(entry.rank)).apply {
                            gravity = Gravity.CENTER
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.parseColor(accentColor))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (elevated) 28f else 22f)
                            setPadding(0, dp(14), 0, 0)
                        },
                    )
                    addView(
                        titleText(entry.nickname, if (elevated) 20f else 18f).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(6), 0, 0)
                            setTextColor(Color.parseColor("#FFF8E8"))
                        },
                    )
                    addView(
                        badgeText(
                            text = tierLabelForKey(entry.tierKey),
                            textColor = "#FFF5E6",
                            fillColor = "#16384A",
                        ).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                            setPadding(dp(10), dp(5), dp(10), dp(5))
                        },
                    )
                    addView(
                        badgeText(
                            text = leaderboardBoardLabel(leaderboardBoard),
                            textColor = "#FFF0C9",
                            fillColor = "#102738",
                        ).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                        },
                    )
                    addView(
                        bodyText(leaderboardPrimaryValueText(entry)).apply {
                            gravity = Gravity.CENTER
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (elevated) 20f else 18f)
                            setTextColor(Color.parseColor(accentColor))
                            setPadding(0, dp(10), 0, 0)
                        },
                    )
                    addView(
                        bodyText(leaderboardSecondaryValueText(entry)).apply {
                            gravity = Gravity.CENTER
                            setTextColor(Color.parseColor("#C9A46A"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            setPadding(0, dp(8), 0, 0)
                        },
                    )
                },
            )
            addView(
                View(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            when (entry.rank) {
                                1 -> dp(52)
                                2 -> dp(36)
                                else -> dp(28)
                            },
                        ).apply {
                            topMargin = dp(10)
                        }
                    background = roundedBackground(fillColor = accentColor, strokeColor = accentColor, cornerDp = 18)
                },
            )
        }

    private fun leaderboardRowCard(entry: CloudLeaderboardEntry): LinearLayout {
        val accentColor = leaderboardAccentColor(leaderboardBoard)
        val card = detailCard(fillColor = "#0C1822", strokeColor = "#1C3344")
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val rankView =
            bodyText(rankLabel(entry.rank)).apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor(accentColor))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        rightMargin = dp(12)
                    }
            }
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1.0f,
                    )
            }
        content.addView(
            bodyText(entry.nickname).apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#FFF5E6"))
            },
        )
        content.addView(
            badgeText(tierLabelForKey(entry.tierKey), textColor = "#FFF0C9", fillColor = leaderboardAccentFill(leaderboardBoard)).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            },
        )
        content.addView(
            bodyText(
                "${leaderboardPrimaryValueText(entry)} | ${leaderboardSecondaryValueText(entry)}",
            ).apply {
                setTextColor(Color.parseColor("#B88A54"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            },
        )
        val serialBadge =
            badgeText(entry.serialMasked, textColor = "#FFF0C9", fillColor = "#132635").apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        row.addView(rankView)
        row.addView(content)
        row.addView(serialBadge)
        card.addView(row)
        return card
    }

    private fun achievementTierHeroCardPremium(
        tier: CloudTierProgress,
        unlockedCount: Int,
        totalCount: Int,
    ): LinearLayout =
        detailCard(fillColor = "#0F1820", strokeColor = "#D4B16B", cornerDp = 24).apply {
            background = metallicBackground("#224B63", "#0D1A23", "#D8B97A", 24)
            addView(
                TextView(this@MainActivity).apply {
                    text = localText("荣誉段位", "Honor Tier", "Rang d'honneur", "ระดับเกียรติยศ")
                    setTextColor(Color.parseColor("#140800"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    background = metallicBackground("#FFE8A8", "#C7932B", "#FFF2CD", 999)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                },
            )
            addView(
                titleText(tierLabelForKey(tier.key), 24f).apply {
                    gravity = Gravity.START
                    setPadding(0, dp(14), 0, 0)
                    setTextColor(Color.parseColor("#FFF8E7"))
                },
            )
            addView(
                bodyText(achievementsSubtitleText(unlockedCount, totalCount)).apply {
                    setTextColor(Color.parseColor("#FFF0C9"))
                    setPadding(0, dp(6), 0, 0)
                },
            )
            addView(
                bodyText(tierHeroProgressText(tier)).apply {
                    setTextColor(Color.parseColor("#A7C8DD"))
                    setPadding(0, dp(10), 0, 0)
                },
            )
        }

    private fun achievementBadgeCardPremium(item: CloudAchievementItem): LinearLayout {
        val unlocked = item.unlocked
        val accentColor = achievementAccentColor(item.key)
        val palette = achievementMetalPalette(item.key, unlocked)
        val badgeImageRes = achievementBadgeImageRes(item.key)
        val progressFraction = if (item.goal > 0) item.progress.toFloat() / item.goal.toFloat() else 0f
        return detailCard(fillColor = "#0C1822", strokeColor = if (unlocked) palette.stroke else "#233A4B", cornerDp = 20).apply {
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor(if (unlocked) "#13202A" else "#0D1822"),
                        Color.parseColor(if (unlocked) "#0A1218" else "#140800"),
                    ),
                ).apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), Color.parseColor(if (unlocked) palette.stroke else "#24384A"))
                }
            val topRow =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            val medal =
                FrameLayout(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                            rightMargin = dp(12)
                        }
                    background =
                        if (badgeImageRes == null) {
                            metallicBackground(
                                if (unlocked) palette.highlight else "#243441",
                                if (unlocked) palette.base else "#121D26",
                                if (unlocked) palette.stroke else "#314755",
                                999,
                            )
                        } else {
                            roundedBackground(
                                if (unlocked) "#09131C" else "#101B24",
                                if (unlocked) palette.stroke else "#314755",
                                999,
                            )
                        }
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    elevation = dp(2).toFloat()
                    if (badgeImageRes != null) {
                        addView(
                            ImageView(this@MainActivity).apply {
                                setImageResource(badgeImageRes)
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                alpha = if (unlocked) 1f else 0.42f
                                contentDescription = achievementDisplayName(item.key)
                                outlineProvider =
                                    object : ViewOutlineProvider() {
                                        override fun getOutline(view: View, outline: Outline) {
                                            outline.setOval(0, 0, view.width, view.height)
                                        }
                                    }
                                clipToOutline = true
                                layoutParams =
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                            },
                        )
                    } else {
                        addView(
                            TextView(this@MainActivity).apply {
                                text = achievementBadgeCode(item.key)
                                gravity = Gravity.CENTER
                                setTypeface(Typeface.DEFAULT_BOLD)
                                setTextColor(Color.parseColor(if (unlocked) palette.text else "#B88A54"))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                layoutParams =
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                            },
                        )
                    }
                }
            val titleColumn =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1.0f,
                        )
                }
            titleColumn.addView(
                bodyText(achievementDisplayName(item.key)).apply {
                    setTextColor(Color.parseColor("#FFF5E6"))
                    setTypeface(Typeface.DEFAULT_BOLD)
                },
            )
            titleColumn.addView(
                bodyText(
                    if (unlocked) {
                        localText("已解锁", "Unlocked", "Débloqué", "ปลดล็อกแล้ว")
                    } else {
                        localText("成长中", "In Progress", "En progression", "กำลังพัฒนา")
                    },
                ).apply {
                    setTextColor(Color.parseColor(if (unlocked) palette.stroke else "#B88A54"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(4), 0, 0)
                },
            )
            topRow.addView(medal)
            topRow.addView(titleColumn)

            val progressBar =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = dp(8)
                    background = roundedBackground("#0F1C27", "#1B3446", 999)
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(8),
                        ).apply {
                            topMargin = dp(12)
                        }
                    val safeProgress = progressFraction.coerceIn(0f, 1f)
                    if (safeProgress > 0f) {
                        addView(
                            View(this@MainActivity).apply {
                                background =
                                    metallicBackground(
                                        if (unlocked) palette.highlight else accentColor,
                                        if (unlocked) palette.base else "#203545",
                                        if (unlocked) palette.stroke else accentColor,
                                        999,
                                    )
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        safeProgress,
                                    )
                            },
                        )
                    }
                    if (safeProgress < 1f) {
                        addView(
                            View(this@MainActivity).apply {
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        (1f - safeProgress).coerceAtLeast(0.0001f),
                                    )
                            },
                        )
                    }
                }
            addView(topRow)
            addView(progressBar)
            addView(
                bodyText("${item.progress}/${item.goal}").apply {
                    setTextColor(if (unlocked) Color.parseColor(accentColor) else Color.parseColor("#CAA26A"))
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }
    }

    private fun podiumCardPremium(
        entry: CloudLeaderboardEntry,
        accentColor: String,
        elevated: Boolean,
        leftMargin: Int,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                ).apply {
                    this.leftMargin = leftMargin
                    topMargin = if (elevated) 0 else dp(18)
                }
            addView(
                detailCard(fillColor = "#0D1924", strokeColor = accentColor, cornerDp = 24).apply {
                    background =
                        metallicBackground(
                            when (entry.rank) {
                                1 -> "#4A3A16"
                                2 -> "#35414B"
                                else -> "#4B3428"
                            },
                            "#0D1924",
                            accentColor,
                            24,
                        )
                    minimumHeight = if (elevated) dp(186) else dp(156)
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "TOP ${entry.rank}"
                            setTextColor(Color.parseColor("#140800"))
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                            background =
                                metallicBackground(
                                    when (entry.rank) {
                                        1 -> "#FFE7A1"
                                        2 -> "#E3EBF1"
                                        else -> "#F1C19A"
                                    },
                                    accentColor,
                                    "#FFF5DA",
                                    999,
                                )
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                        },
                    )
                    addView(
                        bodyText(rankLabel(entry.rank)).apply {
                            gravity = Gravity.CENTER
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.parseColor(accentColor))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (elevated) 28f else 22f)
                            setPadding(0, dp(16), 0, 0)
                        },
                    )
                    addView(
                        titleText(entry.nickname, if (elevated) 20f else 18f).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(8), 0, 0)
                            setTextColor(Color.parseColor("#FFF8E8"))
                        },
                    )
                    addView(
                        badgeText(
                            text = tierLabelForKey(entry.tierKey),
                            textColor = "#FFF5E6",
                            fillColor = "#17384B",
                        ).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                            setPadding(dp(10), dp(5), dp(10), dp(5))
                        },
                    )
                    addView(
                        bodyText(leaderboardPrimaryValueText(entry)).apply {
                            gravity = Gravity.CENTER
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (elevated) 21f else 18f)
                            setTextColor(Color.parseColor(accentColor))
                            setPadding(0, dp(12), 0, 0)
                        },
                    )
                    addView(
                        bodyText(leaderboardSecondaryValueText(entry)).apply {
                            gravity = Gravity.CENTER
                            setTextColor(Color.parseColor("#C9A46A"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            setPadding(0, dp(8), 0, 0)
                        },
                    )
                },
            )
            addView(
                View(this@MainActivity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            when (entry.rank) {
                                1 -> dp(54)
                                2 -> dp(38)
                                else -> dp(30)
                            },
                        ).apply {
                            topMargin = dp(10)
                        }
                    background = metallicBackground("#2A4B5E", accentColor, accentColor, 18)
                },
            )
        }

    private fun leaderboardRowCardPremium(entry: CloudLeaderboardEntry): LinearLayout {
        val accentColor = leaderboardAccentColor(leaderboardBoard)
        val card = detailCard(fillColor = "#0C1822", strokeColor = "#27485B", cornerDp = 20)
        card.background = metallicBackground("#162733", "#0B1720", "#244458", 20)
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val accentBar =
            View(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(6), dp(54)).apply {
                        rightMargin = dp(12)
                    }
                background = metallicBackground(accentColor, "#17384B", accentColor, 999)
            }
        val rankView =
            bodyText(rankLabel(entry.rank)).apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor(accentColor))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        rightMargin = dp(12)
                    }
            }
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1.0f,
                    )
            }
        content.addView(
            bodyText(entry.nickname).apply {
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#FFF5E6"))
            },
        )
        content.addView(
            bodyText("${leaderboardPrimaryValueText(entry)} | ${leaderboardSecondaryValueText(entry)}").apply {
                setTextColor(Color.parseColor("#B88A54"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            },
        )
        val sideColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
        sideColumn.addView(
            badgeText(tierLabelForKey(entry.tierKey), textColor = "#FFF0C9", fillColor = leaderboardAccentFill(leaderboardBoard)).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            },
        )
        sideColumn.addView(
            badgeText(entry.serialMasked, textColor = "#FFF0C9", fillColor = "#132635").apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
            },
        )
        row.addView(accentBar)
        row.addView(rankView)
        row.addView(content)
        row.addView(sideColumn)
        card.addView(row)
        return card
    }

    private fun localeForLanguage(): Locale =
        when (selectedLanguage) {
            AppLanguage.Chinese -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.English -> Locale.US
            AppLanguage.French -> Locale.FRANCE
            AppLanguage.Thai -> Locale("th", "TH")
        }

    private fun parseCloudDate(value: String): Date? {
        val patterns =
            listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
            )
        patterns.forEach { pattern ->
            runCatching {
                val parser = SimpleDateFormat(pattern, Locale.US)
                if (pattern.contains("'Z'") || pattern == "yyyy-MM-dd'T'HH:mm:ss.SSS" || pattern == "yyyy-MM-dd'T'HH:mm:ss" || pattern == "yyyy-MM-dd HH:mm:ss") {
                    parser.timeZone = TimeZone.getTimeZone("UTC")
                }
                return parser.parse(value)
            }
        }
        return null
    }

    private fun titleText(
        text: String,
        sizeSp: Float,
    ): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            letterSpacing = 0.01f
        }

    private fun sectionTitle(text: String): TextView =
        bodyText(text).apply {
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#FFF8E8"))
            setPadding(0, dp(10), 0, dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            letterSpacing = 0.01f
        }

    private fun sectionSubtitle(text: String): TextView =
        bodyText(text).apply {
            setTextColor(Color.parseColor("#8EA6B9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, 0, 0, dp(12))
        }

    private fun sectionLabel(text: String): TextView =
        bodyText(text).apply {
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#FFF8E8"))
            setPadding(0, 0, 0, dp(6))
        }

    private fun activationInput(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#4A8A5A"))
            background = roundedBackground("#061410", "#1A3A24", 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#DFFFF0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setLineSpacing(0f, 1.18f)
        }

    private fun surfaceCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surfaceCardBackground()
            setPadding(dp(20), dp(20), dp(20), dp(20))
            elevation = dp(3).toFloat()
        }

    private fun surfaceCardBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            colors =
                intArrayOf(
                    Color.parseColor("#112230"),
                    Color.parseColor("#061410"),
                )
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(dp(1), Color.parseColor("#1A3A24"))
        }

    private fun chipBackground(accentColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.argb(38, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            setStroke(dp(1), accentColor)
        }

    private fun actionButton(
        text: String,
        color: String,
    ): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            background = roundedBackground(color, "#DFFFF0", 22)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            textSize = 15f
            isAllCaps = false
            elevation = dp(3).toFloat()
            layoutParams =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f,
                )
            applyRippleOverlay()
        }

    private fun compactActionButton(
        text: String,
        color: String,
    ): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            background = roundedBackground(color, "#DFFFF0", 20)
            setTypeface(Typeface.DEFAULT_BOLD)
            minWidth = 0
            minimumWidth = 0
            textSize = 13f
            isAllCaps = false
            setPadding(dp(16), dp(11), dp(16), dp(11))
            elevation = dp(2).toFloat()
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            applyRippleOverlay()
        }

    private fun horizontalSpace(width: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, 1)
        }

    private fun spacer(height: Int): View =
        View(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    height,
                )
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private companion object {
        const val PREFS_NAME = "reflex_ball_settings"
        const val KEY_LANGUAGE = "language"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_AUTH_SERIAL = "auth_serial"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_AUTH_INSTALL_ID = "auth_install_id"
        const val KEY_AUTH_DEVICE_HASH = "auth_device_hash"
        const val KEY_AUTH_ACTIVATED_AT = "auth_activated_at"
        const val KEY_AUTH_LAST_CHECK_AT = "auth_last_check_at"
        const val KEY_LAST_SEEN_TIER = "last_seen_tier"
        const val KEY_PROFILE_AVATAR_URI = "profile_avatar_uri"
        const val KEY_LOCAL_BACKGROUND_PROFILES = "local_background_noise_profiles"
        const val KEY_SELECTED_PLAY_MODE = "selected_play_mode"
        const val KEY_TRAINING_LEVEL = "training_play_level"
        const val KEY_TRAINING_XP = "training_play_xp"
        const val KEY_TRAINING_LAST_DATE = "training_last_date"
        const val KEY_TRAINING_STREAK = "training_current_streak"
        const val KEY_BEST_TRAINING_STREAK = "training_best_streak"
        const val KEY_DAILY_TASK_DATE = "daily_task_date"
        const val KEY_DAILY_TASK_TRAINED = "daily_task_trained"
        const val KEY_DAILY_TASK_TARGET_DONE = "daily_task_target_done"
        const val KEY_DAILY_TASK_SHARED = "daily_task_shared"
        const val KEY_LOCAL_TRAINING_SESSIONS = "local_training_sessions"
        const val KEY_LAST_BLUETOOTH_NAME = "last_bluetooth_name"
        const val KEY_LAST_BLUETOOTH_ADDRESS = "last_bluetooth_address"
        const val KEY_LAST_BLUETOOTH_TRANSPORT = "last_bluetooth_transport"
        const val KEY_LAST_BLUETOOTH_HAS_BLE = "last_bluetooth_has_ble"
        const val KEY_LAST_BLUETOOTH_HAS_CLASSIC = "last_bluetooth_has_classic"
        const val KEY_LAST_BLUETOOTH_BLE_ADDRESS = "last_bluetooth_ble_address"
        const val KEY_LAST_BLUETOOTH_CLASSIC_ADDRESS = "last_bluetooth_classic_address"
        const val KEY_BLUETOOTH_FIRST_USE_GUIDE_SHOWN = "bluetooth_first_use_guide_shown"
        const val DEVELOPER_COMPANY_NAME_ZH = "绍兴维脉科技有限公司"
        const val DEVELOPER_COMPANY_NAME_EN = "Shaoxing Weimai Technology Co., Ltd."
        const val DEVELOPER_COMPANY_NAME_FR = "Société Shaoxing Weimai Technology Co., Ltd."
        const val DEVELOPER_COMPANY_NAME_TH = "บริษัท Shaoxing Weimai Technology Co., Ltd."
        const val DEVELOPER_EMAIL = "zclei@vip.sina.com"
        const val DEVELOPER_EMAIL_SUBJECT = "Smart sensor ball APP咨询"
    }
}
