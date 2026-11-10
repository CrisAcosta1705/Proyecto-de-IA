package com.proyectoia.asistencia

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFERENCES_NAME = "asistencia_ia"
        private const val LOG_TAG = "AsistenciaIA"
        private const val PROCESSING_MODE_KEY = "processing_mode"
        private const val STATE_ACTIVE_SESSION_ID = "active_session_id"
        private const val STATE_LOCAL_SESSION_ID = "local_session_id"
        private const val STATE_LENS_FACING = "lens_facing"
        private const val STATE_HISTORY = "history"
        private const val STATE_STATUS = "status"
        private const val STATE_APP_SECTION = "app_section"
        private const val GUIDED_STABLE_FRAMES = 3
        private const val REQUIRED_REFERENCE_PHOTOS = 4
        private const val MAX_GALLERY_IMAGE_BYTES = 10 * 1024 * 1024
        private const val MIN_CAPTURE_BRIGHTNESS = 34f
        private const val MAX_CAPTURE_BRIGHTNESS = 236f
        private const val MIN_CAPTURE_CONTRAST = 10f
        private const val MIN_LIVE_SHARPNESS = 0.65f
        private const val MIN_CAPTURE_SHARPNESS = 0.9f
    }

    private lateinit var classSpinner: Spinner
    private lateinit var rootLayout: FrameLayout
    private lateinit var appContent: View
    private lateinit var loginScreen: View
    private lateinit var loginTitleText: TextView
    private lateinit var loginSubtitleText: TextView
    private lateinit var loginUsernameEditText: EditText
    private lateinit var loginPasswordEditText: EditText
    private lateinit var loginConfirmPasswordEditText: EditText
    private lateinit var confirmPasswordLabel: TextView
    private lateinit var showPasswordCheckBox: CheckBox
    private lateinit var loginErrorText: TextView
    private lateinit var loginButton: Button
    private lateinit var cancelCredentialChangeButton: Button
    private lateinit var loggedInUserText: TextView
    private lateinit var serverConfigurationDescriptionText: TextView
    private lateinit var serverControlsPanel: View
    private lateinit var syncStudentsButton: Button
    private lateinit var uploadStudentsButton: Button
    private lateinit var createLocalUserButton: Button
    private lateinit var changeCredentialsButton: Button
    private lateinit var processingModeSpinner: Spinner
    private lateinit var serverUrlEditText: EditText
    private lateinit var attendanceDateEditText: EditText
    private lateinit var studentIdEditText: EditText
    private lateinit var studentNameEditText: EditText
    private lateinit var studentSexSpinner: Spinner
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var historyText: TextView
    private lateinit var analysisProgress: ProgressBar
    private lateinit var previewView: PreviewView
    private lateinit var registrationPreviewView: PreviewView
    private lateinit var registrationCapturedImageView: ImageView
    private lateinit var attendanceCameraContainer: FrameLayout
    private lateinit var registrationCameraContainer: FrameLayout
    private lateinit var capturedImageView: ImageView
    private lateinit var faceOverlay: FaceBoxOverlay
    private lateinit var registrationFaceOverlay: FaceBoxOverlay
    private lateinit var registrationDirectionArrowText: TextView
    private lateinit var registrationCountdownText: TextView
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraSwitchButton: Button
    private lateinit var registrationInstructionText: TextView
    private lateinit var registrationStepText: TextView
    private lateinit var registrationProgress: ProgressBar
    private lateinit var cancelRegistrationButton: Button
    private lateinit var attendanceFullscreenCloseButton: ImageButton
    private lateinit var attendanceFullscreenCaptureButton: ImageButton
    private lateinit var registrationFullscreenCancelButton: ImageButton
    private lateinit var registrationFullscreenStepText: TextView
    private lateinit var screenTitleText: TextView
    private lateinit var screenSubtitleText: TextView
    private lateinit var attendanceHistoryContainer: LinearLayout
    private lateinit var studentsTableContainer: LinearLayout
    private lateinit var studentsCountText: TextView
    private lateinit var historyTotalListsText: TextView
    private lateinit var historyTotalStudentsText: TextView
    private lateinit var historyMaleText: TextView
    private lateinit var historyFemaleText: TextView
    private lateinit var poseStatusViews: List<TextView>
    private lateinit var sectionViews: Map<AppSection, View>
    private lateinit var sectionButtons: Map<AppSection, Button>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var networkExecutor: ExecutorService
    private lateinit var localStore: LocalAttendanceStore
    private lateinit var authStore: LocalAuthStore

    private val isAnalyzing = AtomicBoolean(false)
    private val isNetworkBusy = AtomicBoolean(false)
    private val isLocalModelLoading = AtomicBoolean(false)
    private val isStudentSyncing = AtomicBoolean(false)
    private val isShowingRecognitionResult = AtomicBoolean(false)
    private val isGuidedCaptureRunning = AtomicBoolean(false)
    private var activeSessionId: Int? = null
    private var activeLocalSessionId: Long? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var attendanceLensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeSection = AppSection.ATTENDANCE
    private var fullscreenCameraMode: FullscreenCameraMode? = null
    private var fullscreenOriginalParent: ViewGroup? = null
    private var fullscreenOriginalIndex = -1
    private var fullscreenOriginalLayoutParams: ViewGroup.LayoutParams? = null
    @Volatile private var guidedRegistrationActive = false
    @Volatile private var guidedVerificationActive = false
    @Volatile private var guidedPoseIndex = -1
    private var guidedStableFrames = 0
    private var verificationCountdown: CountDownTimer? = null
    private val guidedSamples = mutableListOf<GuidedRegistrationSample>()
    private var guidedContext: GuidedRegistrationContext? = null
    private var pendingGalleryUpdateContext: GuidedRegistrationContext? = null
    private var pendingReferenceReplacementContext: FaceReferenceReplacementContext? = null
    private var pendingReferenceUpdateStudentId: String? = null
    private var currentStudentProfiles: List<StudentProfileData> = emptyList()
    private var authMode = AuthMode.LOGIN
    private var appServicesStarted = false
    private var serverServicesStarted = false
    private val hideRecognitionResult = Runnable {
        isShowingRecognitionResult.set(false)
        capturedImageView.setImageDrawable(null)
        capturedImageView.visibility = View.GONE
        faceOverlay.clear()
    }

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .enableTracking()
            .setMinFaceSize(0.03f)
            .build()
        FaceDetection.getClient(options)
    }

    private val localFaceDetectorDelegate = lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
        FaceDetection.getClient(options)
    }
    private val localFaceDetector by localFaceDetectorDelegate
    private val galleryFaceDetectorDelegate = lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.02f)
            .build()
        FaceDetection.getClient(options)
    }
    private val galleryFaceDetector by galleryFaceDetectorDelegate
    private val localRecognizerDelegate = lazy { LocalFaceRecognizer(applicationContext) }
    private val localRecognizer by localRecognizerDelegate

    private enum class ProcessingMode {
        AUTOMATIC,
        PHONE,
        SERVER
    }

    private enum class AuthMode {
        LOGIN,
        INITIAL_SETUP,
        CHANGE_CREDENTIALS,
        CREATE_LOCAL_USER
    }

    private enum class AppSection {
        CONFIGURATION,
        ATTENDANCE,
        HISTORY,
        STUDENTS,
        REGISTRATION
    }

    private enum class FullscreenCameraMode {
        ATTENDANCE,
        REGISTRATION
    }

    private enum class GuidedPose(val instructionRes: Int, val arrowRes: Int?) {
        FRONT(R.string.registration_front_instruction, null),
        PROFILE(R.string.registration_profile_instruction, R.string.registration_arrow_profile),
        UP(R.string.registration_up_instruction, R.string.registration_arrow_up),
        DOWN(R.string.registration_down_instruction, R.string.registration_arrow_down)
    }

    private data class LocalFaceExtraction(
        val bitmap: Bitmap,
        val samples: List<LocalFaceSample>,
        val faces: List<Face>
    )

    private data class CaptureQuality(
        val brightness: Float,
        val contrast: Float,
        val sharpness: Float
    )

    private data class GuidedFrameEvaluation(
        val accepted: Boolean,
        val message: String,
        val overlayLabel: String,
        val qualityIssue: Boolean = false
    )

    private data class GalleryProcessingUi(
        val dialog: AlertDialog,
        val progress: ProgressBar,
        val detail: TextView
    )

    private data class FaceReferenceMigrationResult(
        val migrated: Int,
        val failed: Int
    )

    private data class ServerStudentSyncResult(
        val imported: Int,
        val unchanged: Int,
        val failed: Int
    )

    private data class ServerStudentUploadResult(
        val uploaded: Int,
        val skipped: Int,
        val failed: Int
    )

    private data class AttendanceSelection(
        val course: String,
        val attendanceDate: String,
        val mode: ProcessingMode,
        val serverUrl: String?
    )

    private data class AttendanceListItem(
        val studentId: String,
        val name: String,
        val sex: String
    )

    private data class StudentProfileData(
        val studentId: String,
        val name: String,
        val sex: String,
        val embeddingCount: Int,
        val photoJpeg: ByteArray? = null
    )

    private data class AttendanceSummaryData(
        val course: String,
        val attendanceDate: String,
        val total: Int,
        val male: Int,
        val female: Int,
        val unspecified: Int
    )

    private data class GuidedRegistrationSample(
        val embedding: FloatArray,
        val jpeg: ByteArray
    )

    private data class GuidedRegistrationContext(
        val studentId: String,
        val name: String,
        val sex: String,
        val mode: ProcessingMode,
        val serverUrl: String?,
        val existingStudentId: String? = null
    )

    private data class FaceReferenceReplacementContext(
        val profile: StudentProfileData,
        val referenceId: Long,
        val mode: ProcessingMode,
        val serverUrl: String?
    )

    private data class AttendanceReportData(
        val course: String,
        val attendanceDate: String,
        val students: List<AttendanceListItem>
    )

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && authStore.isSessionActive()) startCamera()
        else statusText.text = getString(R.string.camera_permission_denied)
    }

    private val pickFaceReferencePhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(REQUIRED_REFERENCE_PHOTOS)
    ) { uris ->
        val context = pendingGalleryUpdateContext
        pendingGalleryUpdateContext = null
        if (context == null) return@registerForActivityResult
        Log.i(
            LOG_TAG,
            "Selector de referencias: ${uris.size} archivo(s): " +
                uris.joinToString { galleryImageName(it) }
        )
        if (uris.isEmpty()) {
            showStatus(getString(R.string.gallery_selection_cancelled))
            return@registerForActivityResult
        }
        if (uris.size !in 1..REQUIRED_REFERENCE_PHOTOS) {
            showStatus(getString(R.string.face_photos_range_required))
            return@registerForActivityResult
        }
        updateFaceReferencesFromGallery(context, uris)
    }

    private val pickFaceReferenceReplacement = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val context = pendingReferenceReplacementContext
        pendingReferenceReplacementContext = null
        if (context == null) return@registerForActivityResult
        if (uri == null) {
            showStatus(getString(R.string.gallery_selection_cancelled))
            return@registerForActivityResult
        }
        replaceFaceReferenceFromGallery(context, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        rootLayout = findViewById(R.id.rootLayout)
        appContent = findViewById(R.id.appContent)
        loginScreen = findViewById(R.id.loginScreen)
        loginTitleText = findViewById(R.id.loginTitleText)
        loginSubtitleText = findViewById(R.id.loginSubtitleText)
        loginUsernameEditText = findViewById(R.id.loginUsernameEditText)
        loginPasswordEditText = findViewById(R.id.loginPasswordEditText)
        loginConfirmPasswordEditText = findViewById(R.id.loginConfirmPasswordEditText)
        confirmPasswordLabel = findViewById(R.id.confirmPasswordLabel)
        showPasswordCheckBox = findViewById(R.id.showPasswordCheckBox)
        loginErrorText = findViewById(R.id.loginErrorText)
        loginButton = findViewById(R.id.loginButton)
        cancelCredentialChangeButton = findViewById(R.id.cancelCredentialChangeButton)
        loggedInUserText = findViewById(R.id.loggedInUserText)
        serverConfigurationDescriptionText = findViewById(
            R.id.serverConfigurationDescriptionText
        )
        serverControlsPanel = findViewById(R.id.serverControlsPanel)
        syncStudentsButton = findViewById(R.id.syncStudentsButton)
        uploadStudentsButton = findViewById(R.id.uploadStudentsButton)
        createLocalUserButton = findViewById(R.id.createLocalUserButton)
        changeCredentialsButton = findViewById(R.id.changeCredentialsButton)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        classSpinner = findViewById(R.id.classSpinner)
        processingModeSpinner = findViewById(R.id.processingModeSpinner)
        serverUrlEditText = findViewById(R.id.serverUrlEditText)
        attendanceDateEditText = findViewById(R.id.attendanceDateEditText)
        studentIdEditText = findViewById(R.id.studentIdEditText)
        studentNameEditText = findViewById(R.id.studentNameEditText)
        studentSexSpinner = findViewById(R.id.studentSexSpinner)
        countText = findViewById(R.id.countText)
        statusText = findViewById(R.id.statusText)
        historyText = findViewById(R.id.historyText)
        analysisProgress = findViewById(R.id.analysisProgress)
        previewView = findViewById(R.id.previewView)
        registrationPreviewView = findViewById(R.id.registrationPreviewView)
        registrationCapturedImageView = findViewById(R.id.registrationCapturedImageView)
        attendanceCameraContainer = findViewById(R.id.cameraContainer)
        registrationCameraContainer = findViewById(R.id.registrationCameraContainer)
        capturedImageView = findViewById(R.id.capturedImageView)
        faceOverlay = findViewById(R.id.faceOverlay)
        registrationFaceOverlay = findViewById(R.id.registrationFaceOverlay)
        registrationDirectionArrowText = findViewById(
            R.id.registrationDirectionArrowText
        )
        registrationCountdownText = findViewById(R.id.registrationCountdownText)
        cameraSwitchButton = findViewById(R.id.cameraSwitchButton)
        registrationInstructionText = findViewById(R.id.registrationInstructionText)
        registrationStepText = findViewById(R.id.registrationStepText)
        registrationProgress = findViewById(R.id.registrationProgress)
        cancelRegistrationButton = findViewById(R.id.cancelRegistrationButton)
        attendanceFullscreenCloseButton = findViewById(
            R.id.attendanceFullscreenCloseButton
        )
        attendanceFullscreenCaptureButton = findViewById(
            R.id.attendanceFullscreenCaptureButton
        )
        registrationFullscreenCancelButton = findViewById(
            R.id.registrationFullscreenCancelButton
        )
        registrationFullscreenStepText = findViewById(
            R.id.registrationFullscreenStepText
        )
        screenTitleText = findViewById(R.id.screenTitleText)
        screenSubtitleText = findViewById(R.id.screenSubtitleText)
        attendanceHistoryContainer = findViewById(R.id.attendanceHistoryContainer)
        studentsTableContainer = findViewById(R.id.studentsTableContainer)
        studentsCountText = findViewById(R.id.studentsCountText)
        historyTotalListsText = findViewById(R.id.historyTotalListsText)
        historyTotalStudentsText = findViewById(R.id.historyTotalStudentsText)
        historyMaleText = findViewById(R.id.historyMaleText)
        historyFemaleText = findViewById(R.id.historyFemaleText)
        poseStatusViews = listOf(
            findViewById(R.id.poseFrontStatus),
            findViewById(R.id.poseProfileStatus),
            findViewById(R.id.poseUpStatus),
            findViewById(R.id.poseDownStatus)
        )
        sectionViews = mapOf(
            AppSection.CONFIGURATION to findViewById(R.id.configurationScreen),
            AppSection.ATTENDANCE to findViewById(R.id.attendanceScreen),
            AppSection.HISTORY to findViewById(R.id.historyScreen),
            AppSection.STUDENTS to findViewById(R.id.studentsScreen),
            AppSection.REGISTRATION to findViewById(R.id.registrationScreen)
        )
        sectionButtons = mapOf(
            AppSection.CONFIGURATION to findViewById(R.id.navConfigButton),
            AppSection.ATTENDANCE to findViewById(R.id.navAttendanceButton),
            AppSection.HISTORY to findViewById(R.id.navHistoryButton),
            AppSection.STUDENTS to findViewById(R.id.navStudentsButton),
            AppSection.REGISTRATION to findViewById(R.id.navRegistrationButton)
        )
        cameraExecutor = Executors.newSingleThreadExecutor()
        networkExecutor = Executors.newSingleThreadExecutor()
        localStore = LocalAttendanceStore(applicationContext)
        authStore = LocalAuthStore(applicationContext)
        localStore.writableDatabase

        if (savedInstanceState != null) {
            activeSessionId = if (savedInstanceState.containsKey(STATE_ACTIVE_SESSION_ID)) {
                savedInstanceState.getInt(STATE_ACTIVE_SESSION_ID)
            } else {
                null
            }
            activeLocalSessionId = if (
                savedInstanceState.containsKey(STATE_LOCAL_SESSION_ID)
            ) {
                savedInstanceState.getLong(STATE_LOCAL_SESSION_ID)
            } else {
                null
            }
            lensFacing = savedInstanceState.getInt(
                STATE_LENS_FACING,
                CameraSelector.LENS_FACING_BACK
            )
            attendanceLensFacing = lensFacing
            activeSection = AppSection.entries.getOrElse(
                savedInstanceState.getInt(
                    STATE_APP_SECTION,
                    AppSection.ATTENDANCE.ordinal
                )
            ) { AppSection.ATTENDANCE }
            savedInstanceState.getString(STATE_HISTORY)
                ?.takeIf { it.isNotBlank() }
                ?.let { historyText.text = it }
            savedInstanceState.getString(STATE_STATUS)?.let { statusText.text = it }
        }

        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        serverUrlEditText.setText(BuildConfig.API_BASE_URL)
        serverUrlEditText.isEnabled = false
        serverUrlEditText.isFocusable = false

        classSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Matematica", "Programacion", "Base de datos", "Inteligencia artificial")
        )
        processingModeSpinner.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.processing_modes,
            android.R.layout.simple_spinner_dropdown_item
        )
        processingModeSpinner.setSelection(
            preferences.getInt(PROCESSING_MODE_KEY, ProcessingMode.AUTOMATIC.ordinal)
                .coerceIn(0, ProcessingMode.entries.lastIndex)
        )
        studentSexSpinner.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.student_sexes,
            android.R.layout.simple_spinner_dropdown_item
        )
        if (attendanceDateEditText.text.isNullOrBlank()) {
            attendanceDateEditText.setText(LocalDate.now().toString())
        }
        attendanceDateEditText.setOnClickListener { showAttendanceDatePicker() }

        loginButton.setOnClickListener { submitAuthentication() }
        cancelCredentialChangeButton.setOnClickListener {
            showAuthenticatedApp()
        }
        showPasswordCheckBox.setOnCheckedChangeListener { _, checked ->
            setPasswordsVisible(checked)
        }
        loginPasswordEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && authMode == AuthMode.LOGIN) {
                submitAuthentication()
                true
            } else {
                false
            }
        }
        loginConfirmPasswordEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitAuthentication()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.registerButton).setOnClickListener {
            pendingReferenceUpdateStudentId = null
            showGuidedRegistrationConditions()
        }
        cancelRegistrationButton.setOnClickListener { cancelGuidedRegistration() }
        registrationFullscreenCancelButton.setOnClickListener {
            cancelGuidedRegistration()
        }
        attendanceFullscreenCloseButton.setOnClickListener { exitCameraFullscreen() }
        attendanceFullscreenCaptureButton.setOnClickListener { recognizeAttendance() }
        val expandAttendanceCamera = View.OnClickListener {
            enterCameraFullscreen(
                attendanceCameraContainer,
                FullscreenCameraMode.ATTENDANCE
            )
        }
        attendanceCameraContainer.setOnClickListener(expandAttendanceCamera)
        previewView.setOnClickListener(expandAttendanceCamera)
        capturedImageView.setOnClickListener(expandAttendanceCamera)
        faceOverlay.setOnClickListener(expandAttendanceCamera)
        cameraSwitchButton.setOnClickListener {
            switchCamera()
        }
        syncStudentsButton.setOnClickListener {
            syncConfiguredServerStudents()
        }
        uploadStudentsButton.setOnClickListener {
            confirmLocalStudentsUpload()
        }
        createLocalUserButton.setOnClickListener {
            showAuthenticationScreen(AuthMode.CREATE_LOCAL_USER)
        }
        changeCredentialsButton.setOnClickListener {
            showAuthenticationScreen(AuthMode.CHANGE_CREDENTIALS)
        }
        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            confirmLogout()
        }
        findViewById<Button>(R.id.scanButton).setOnClickListener {
            recognizeAttendance()
        }
        findViewById<Button>(R.id.viewAttendanceButton).setOnClickListener {
            showAttendanceReport()
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener {
            finishCurrentSession()
        }
        findViewById<ImageButton>(R.id.refreshHistoryButton).setOnClickListener {
            refreshAttendanceHistory()
        }
        findViewById<ImageButton>(R.id.refreshStudentsButton).setOnClickListener {
            showRegisteredStudents()
        }
        sectionButtons.forEach { (section, button) ->
            button.setOnClickListener { selectSection(section) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (loginScreen.visibility == View.VISIBLE) {
                    if (
                        authMode == AuthMode.CHANGE_CREDENTIALS ||
                        authMode == AuthMode.CREATE_LOCAL_USER
                    ) {
                        showAuthenticatedApp()
                    } else {
                        finish()
                    }
                    return
                }
                when (fullscreenCameraMode) {
                    FullscreenCameraMode.REGISTRATION -> cancelGuidedRegistration()
                    FullscreenCameraMode.ATTENDANCE -> exitCameraFullscreen()
                    null -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (authStore.isSessionActive()) {
            showAuthenticatedApp()
        } else {
            showAuthenticationScreen(
                if (authStore.isConfigured()) AuthMode.LOGIN else AuthMode.INITIAL_SETUP
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        activeSessionId?.let { outState.putInt(STATE_ACTIVE_SESSION_ID, it) }
        activeLocalSessionId?.let { outState.putLong(STATE_LOCAL_SESSION_ID, it) }
        outState.putInt(STATE_LENS_FACING, lensFacing)
        outState.putInt(STATE_APP_SECTION, activeSection.ordinal)
        outState.putString(STATE_HISTORY, historyText.text.toString())
        outState.putString(STATE_STATUS, statusText.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (authStore.isSessionActive()) {
            rootLayout.post { rebindCameraForSection() }
        }
    }

    override fun onDestroy() {
        stopVerificationCountdown()
        super.onDestroy()
        faceOverlay.removeCallbacks(hideRecognitionResult)
        faceDetector.close()
        if (!isNetworkBusy.get() && !isLocalModelLoading.get() && !isStudentSyncing.get()) {
            if (localFaceDetectorDelegate.isInitialized()) localFaceDetector.close()
            if (galleryFaceDetectorDelegate.isInitialized()) galleryFaceDetector.close()
            if (localRecognizerDelegate.isInitialized()) localRecognizer.close()
            localStore.close()
        }
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
    }

    private fun showAuthenticationScreen(mode: AuthMode) {
        authMode = mode
        cameraProvider?.unbindAll()
        appContent.visibility = View.GONE
        loginScreen.visibility = View.VISIBLE
        loginErrorText.text = ""
        loginPasswordEditText.text.clear()
        loginConfirmPasswordEditText.text.clear()
        showPasswordCheckBox.isChecked = false

        val configuringAccess = mode != AuthMode.LOGIN
        confirmPasswordLabel.visibility = if (configuringAccess) View.VISIBLE else View.GONE
        loginConfirmPasswordEditText.visibility = if (configuringAccess) View.VISIBLE else View.GONE
        cancelCredentialChangeButton.visibility = if (
            mode == AuthMode.CHANGE_CREDENTIALS ||
            mode == AuthMode.CREATE_LOCAL_USER
        ) View.VISIBLE else View.GONE

        when (mode) {
            AuthMode.LOGIN -> {
                loginTitleText.setText(R.string.login_title)
                loginSubtitleText.setText(R.string.login_subtitle)
                loginButton.setText(R.string.sign_in)
                loginUsernameEditText.setText(authStore.username())
            }
            AuthMode.INITIAL_SETUP -> {
                loginTitleText.setText(R.string.setup_access_title)
                loginSubtitleText.setText(R.string.setup_access_subtitle)
                loginButton.setText(R.string.create_access)
                loginUsernameEditText.text.clear()
            }
            AuthMode.CHANGE_CREDENTIALS -> {
                loginTitleText.setText(R.string.change_access_title)
                loginSubtitleText.setText(R.string.change_access_subtitle)
                loginButton.setText(R.string.save_new_access)
                loginUsernameEditText.setText(authStore.username())
            }
            AuthMode.CREATE_LOCAL_USER -> {
                loginTitleText.setText(R.string.create_local_user_title)
                loginSubtitleText.setText(R.string.create_local_user_subtitle)
                loginButton.setText(R.string.create_local_user_action)
                loginUsernameEditText.text.clear()
            }
        }

        loginScreen.post {
            val target = if (loginUsernameEditText.text.isNullOrBlank()) {
                loginUsernameEditText
            } else {
                loginPasswordEditText
            }
            target.requestFocus()
        }
    }

    private fun submitAuthentication() {
        val username = loginUsernameEditText.text.toString().trim()
        val password = loginPasswordEditText.text.toString()
        loginErrorText.text = ""

        if (authMode == AuthMode.LOGIN) {
            if (username.isBlank() || password.isBlank()) {
                loginErrorText.setText(R.string.authentication_required)
                return
            }
            val attempt = authStore.authenticate(username, password.toCharArray())
            loginPasswordEditText.text.clear()
            when {
                attempt.authenticated -> showAuthenticatedApp()
                attempt.lockedForSeconds > 0 -> loginErrorText.text = getString(
                    R.string.login_temporarily_locked,
                    attempt.lockedForSeconds
                )
                else -> loginErrorText.text = getString(
                    R.string.invalid_credentials,
                    attempt.remainingAttempts
                )
            }
            return
        }

        val confirmation = loginConfirmPasswordEditText.text.toString()
        when {
            username.length !in 3..40 -> {
                loginErrorText.setText(R.string.username_invalid)
            }
            password.length < 8 -> {
                loginErrorText.setText(R.string.password_invalid)
            }
            password != confirmation -> {
                loginErrorText.setText(R.string.passwords_do_not_match)
            }
            else -> {
                val passwordChars = password.toCharArray()
                val message = when (authMode) {
                    AuthMode.INITIAL_SETUP -> {
                        authStore.createInitialAdministrator(username, passwordChars)
                        getString(R.string.access_created)
                    }
                    AuthMode.CHANGE_CREDENTIALS -> {
                        if (!authStore.updateCurrentCredentials(username, passwordChars)) {
                            loginErrorText.setText(R.string.username_already_exists)
                            return
                        }
                        getString(R.string.access_updated)
                    }
                    AuthMode.CREATE_LOCAL_USER -> {
                        if (!authStore.isAdministrator()) {
                            passwordChars.fill('\u0000')
                            loginErrorText.setText(R.string.server_access_restricted)
                            return
                        }
                        val created = runCatching {
                            authStore.createLocalUser(username, passwordChars)
                        }.getOrElse { error ->
                            loginErrorText.text = error.message
                                ?: getString(R.string.local_user_creation_failed)
                            return
                        }
                        if (!created) {
                            loginErrorText.setText(R.string.username_already_exists)
                            return
                        }
                        getString(R.string.local_user_created, username)
                    }
                    AuthMode.LOGIN -> return
                }
                loginPasswordEditText.text.clear()
                loginConfirmPasswordEditText.text.clear()
                showAuthenticatedApp(message)
            }
        }
    }

    private fun showAuthenticatedApp(message: String? = null) {
        hideKeyboard()
        loginScreen.visibility = View.GONE
        appContent.visibility = View.VISIBLE
        configureAccessForCurrentUser()
        selectSection(activeSection, force = true)
        if (!appServicesStarted) {
            appServicesStarted = true
            ensureCameraPermission()
            preloadLocalModel()
        }
        if (authStore.isAdministrator() && !serverServicesStarted) {
            serverServicesStarted = true
            connectFixedApiServer(showResult = false)
        }
        message?.let(::showStatus)
    }

    private fun configureAccessForCurrentUser() {
        val administrator = authStore.isAdministrator()
        val roleLabel = getString(
            if (administrator) R.string.administrator_role else R.string.local_user_role
        )
        loggedInUserText.text = getString(
            R.string.logged_in_user_with_role,
            authStore.username(),
            roleLabel
        )
        serverControlsPanel.visibility = if (administrator) View.VISIBLE else View.GONE
        createLocalUserButton.visibility = if (administrator) View.VISIBLE else View.GONE
        processingModeSpinner.isEnabled = administrator
        if (administrator) {
            serverConfigurationDescriptionText.setText(
                R.string.server_configuration_description
            )
            val savedMode = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getInt(PROCESSING_MODE_KEY, ProcessingMode.AUTOMATIC.ordinal)
                .coerceIn(0, ProcessingMode.entries.lastIndex)
            processingModeSpinner.setSelection(savedMode)
        } else {
            serverConfigurationDescriptionText.setText(R.string.server_access_restricted)
            processingModeSpinner.setSelection(ProcessingMode.PHONE.ordinal)
            serverServicesStarted = false
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.logout) { _, _ ->
                authStore.logout()
                serverServicesStarted = false
                showAuthenticationScreen(AuthMode.LOGIN)
            }
            .show()
    }

    private fun setPasswordsVisible(visible: Boolean) {
        val inputType = InputType.TYPE_CLASS_TEXT or if (visible) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(loginPasswordEditText, loginConfirmPasswordEditText).forEach { field ->
            val cursor = field.selectionStart.coerceAtLeast(0)
            field.inputType = inputType
            field.setSelection(cursor.coerceAtMost(field.text.length))
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { focused ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
    }

    private fun preloadLocalModel() {
        if (!isLocalModelLoading.compareAndSet(false, true)) return
        networkExecutor.execute {
            try {
                localRecognizer.warmUp()
                val migration = migrateLegacyFaceReferences()
                Log.i(LOG_TAG, "Modelo facial local cargado y validado.")
                if (migration.migrated > 0 || migration.failed > 0) {
                    Log.i(
                        LOG_TAG,
                        "Referencias reconstruidas: ${migration.migrated}; " +
                            "fallidas: ${migration.failed}."
                    )
                    val message = if (migration.failed == 0) {
                        getString(
                            R.string.face_references_rebuilt,
                            migration.migrated
                        )
                    } else {
                        getString(
                            R.string.face_references_rebuild_partial,
                            migration.migrated,
                            migration.failed
                        )
                    }
                    showStatus(message)
                }
            } catch (error: Exception) {
                Log.e(LOG_TAG, "No se pudo cargar el modelo facial local.", error)
                showStatus("El reconocimiento local no pudo iniciarse: ${error.message}")
            } finally {
                isLocalModelLoading.set(false)
            }
        }
    }

    private fun migrateLegacyFaceReferences(): FaceReferenceMigrationResult {
        var migrated = 0
        var failed = 0
        localStore.legacyFaceReferences().forEach { reference ->
            try {
                val extraction = extractGalleryFaceReference(reference.photoJpeg) {}
                if (
                    extraction.samples.size == 1 &&
                    localStore.replaceLegacyFaceReference(
                        reference,
                        extraction.samples.single().embedding
                    )
                ) {
                    migrated++
                } else {
                    failed++
                    Log.w(
                        LOG_TAG,
                        "No se reconstruyo la referencia ${reference.embeddingId} " +
                            "de ${reference.studentId}: " +
                            "${extraction.samples.size} rostros."
                    )
                }
            } catch (error: Exception) {
                failed++
                Log.e(
                    LOG_TAG,
                    "Error reconstruyendo la referencia ${reference.embeddingId} " +
                        "de ${reference.studentId}.",
                    error
                )
            }
        }
        return FaceReferenceMigrationResult(migrated, failed)
    }

    private fun showAttendanceDatePicker() {
        val selected = runCatching {
            LocalDate.parse(attendanceDateEditText.text.toString())
        }.getOrDefault(LocalDate.now())
        DatePickerDialog(
            this,
            { _, year, month, day ->
                attendanceDateEditText.setText(
                    LocalDate.of(year, month + 1, day).toString()
                )
            },
            selected.year,
            selected.monthValue - 1,
            selected.dayOfMonth
        ).show()
    }

    private fun enterCameraFullscreen(
        container: FrameLayout,
        mode: FullscreenCameraMode
    ) {
        if (fullscreenCameraMode != null) return
        val parent = container.parent as? ViewGroup ?: return
        fullscreenOriginalParent = parent
        fullscreenOriginalIndex = parent.indexOfChild(container)
        fullscreenOriginalLayoutParams = container.layoutParams
        parent.removeView(container)
        rootLayout.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenCameraMode = mode
        attendanceFullscreenCloseButton.visibility = if (
            mode == FullscreenCameraMode.ATTENDANCE
        ) View.VISIBLE else View.GONE
        attendanceFullscreenCaptureButton.visibility = if (
            mode == FullscreenCameraMode.ATTENDANCE
        ) View.VISIBLE else View.GONE
        registrationFullscreenCancelButton.visibility = if (
            mode == FullscreenCameraMode.REGISTRATION
        ) View.VISIBLE else View.GONE
        registrationFullscreenStepText.visibility = if (
            mode == FullscreenCameraMode.REGISTRATION
        ) View.VISIBLE else View.GONE

        currentFocus?.let { focused ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        container.post { rebindCameraForSection() }
    }

    private fun exitCameraFullscreen() {
        val mode = fullscreenCameraMode ?: return
        val container = when (mode) {
            FullscreenCameraMode.ATTENDANCE -> attendanceCameraContainer
            FullscreenCameraMode.REGISTRATION -> registrationCameraContainer
        }
        val originalParent = fullscreenOriginalParent ?: return
        val originalLayoutParams = fullscreenOriginalLayoutParams ?: return

        (container.parent as? ViewGroup)?.removeView(container)
        originalParent.addView(
            container,
            fullscreenOriginalIndex.coerceIn(0, originalParent.childCount),
            originalLayoutParams
        )
        attendanceFullscreenCloseButton.visibility = View.GONE
        attendanceFullscreenCaptureButton.visibility = View.GONE
        registrationFullscreenCancelButton.visibility = View.GONE
        registrationFullscreenStepText.visibility = View.GONE
        fullscreenCameraMode = null
        fullscreenOriginalParent = null
        fullscreenOriginalIndex = -1
        fullscreenOriginalLayoutParams = null

        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(rootLayout)
        container.post { rebindCameraForSection() }
    }

    private fun selectSection(section: AppSection, force: Boolean = false) {
        if (!force && guidedRegistrationActive && section != AppSection.REGISTRATION) {
            showStatus("Finaliza o cancela el recorrido facial antes de cambiar de seccion.")
            return
        }
        activeSection = section
        sectionViews.values.forEach { it.visibility = View.GONE }
        val selectedView = sectionViews.getValue(section)
        selectedView.visibility = View.VISIBLE
        selectedView.post { (selectedView as? ScrollView)?.scrollTo(0, 0) }
        sectionButtons.forEach { (item, button) -> button.isSelected = item == section }

        val (title, subtitle) = when (section) {
            AppSection.CONFIGURATION -> R.string.nav_config to R.string.config_screen_subtitle
            AppSection.ATTENDANCE -> R.string.nav_attendance to R.string.attendance_screen_subtitle
            AppSection.HISTORY -> R.string.nav_history to R.string.history_screen_subtitle
            AppSection.STUDENTS -> R.string.nav_students to R.string.students_screen_subtitle
            AppSection.REGISTRATION -> {
                R.string.nav_registration to R.string.registration_screen_subtitle
            }
        }
        screenTitleText.setText(title)
        screenSubtitleText.setText(subtitle)

        when (section) {
            AppSection.ATTENDANCE -> {
                lensFacing = attendanceLensFacing
                rebindCameraForSection()
            }
            AppSection.REGISTRATION -> {
                lensFacing = CameraSelector.LENS_FACING_FRONT
                rebindCameraForSection()
            }
            AppSection.HISTORY -> {
                cameraProvider?.unbindAll()
                refreshAttendanceHistory()
            }
            AppSection.STUDENTS -> {
                cameraProvider?.unbindAll()
                showRegisteredStudents()
            }
            AppSection.CONFIGURATION -> cameraProvider?.unbindAll()
        }
    }

    private fun rebindCameraForSection() {
        if (!authStore.isSessionActive() || appContent.visibility != View.VISIBLE) return
        val provider = cameraProvider ?: return
        if (
            activeSection == AppSection.ATTENDANCE ||
            activeSection == AppSection.REGISTRATION
        ) {
            bindCamera(provider)
        }
    }

    private fun selectedStudentSex(): String? {
        return when (studentSexSpinner.selectedItemPosition) {
            1 -> SEX_MALE
            2 -> SEX_FEMALE
            else -> null
        }
    }

    private fun ensureCameraPermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) startCamera()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (!authStore.isSessionActive()) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            rebindCameraForSection()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider) {
            val targetPreview = if (activeSection == AppSection.REGISTRATION) {
                registrationPreviewView
            } else {
                previewView
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(targetPreview.surfaceProvider)
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }
            val captureResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(captureResolutionSelector)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build(),
                    preview,
                    imageAnalysis,
                    imageCapture
                )
                cameraSwitchButton.text = getString(
                    if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        R.string.use_front_camera
                    } else {
                        R.string.use_back_camera
                    }
                )
                if (!guidedRegistrationActive) {
                    statusText.text = getString(R.string.scan_ready)
                }
            } catch (error: Exception) {
                statusText.text = error.localizedMessage ?: "No se pudo iniciar la camara."
            }
    }

    private fun switchCamera() {
        val targetLens = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val selector = CameraSelector.Builder()
                .requireLensFacing(targetLens)
                .build()
            if (!cameraProvider.hasCamera(selector)) {
                showStatus(getString(R.string.camera_unavailable))
                return@addListener
            }
            lensFacing = targetLens
            attendanceLensFacing = targetLens
            hideRecognitionResult.run()
            bindCamera(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        val rotated = imageProxy.imageInfo.rotationDegrees % 180 != 0
        val analyzedWidth = if (rotated) imageProxy.height else imageProxy.width
        val analyzedHeight = if (rotated) imageProxy.width else imageProxy.height
        val mirrorPreview = lensFacing == CameraSelector.LENS_FACING_FRONT
        val captureQuality = measureFrameQuality(imageProxy)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                val boxes = faces.map { it.boundingBox }
                val guidedPose = when {
                    guidedVerificationActive -> GuidedPose.FRONT
                    guidedRegistrationActive -> GuidedPose.entries.getOrNull(guidedPoseIndex)
                    else -> null
                }
                val guidedEvaluation = guidedPose?.let { pose ->
                    evaluateGuidedFrame(
                        faces,
                        analyzedWidth,
                        analyzedHeight,
                        captureQuality,
                        pose,
                        verification = guidedVerificationActive
                    )
                }
                runOnUiThread {
                    if (activeSection == AppSection.REGISTRATION) {
                        if (registrationCapturedImageView.visibility == View.VISIBLE) {
                            // Keep the frozen verification result and its colored box visible.
                        } else if (guidedEvaluation != null) {
                            registrationFaceOverlay.setResults(
                                guidedOverlayResults(faces, guidedEvaluation),
                                analyzedWidth,
                                analyzedHeight,
                                mirrorPreview
                            )
                        } else {
                            registrationFaceOverlay.setFaces(
                                boxes,
                                analyzedWidth,
                                analyzedHeight,
                                mirrorPreview
                            )
                        }
                    } else if (activeSection == AppSection.ATTENDANCE) {
                        if (!isShowingRecognitionResult.get()) {
                            countText.text = getString(R.string.people_count, faces.size)
                            faceOverlay.setFaces(
                                boxes,
                                analyzedWidth,
                                analyzedHeight,
                                mirrorPreview
                            )
                        }
                    }
                }
                if (
                    activeSection == AppSection.REGISTRATION &&
                    guidedRegistrationActive &&
                    guidedEvaluation != null
                ) {
                    if (guidedVerificationActive) {
                        processGuidedVerificationFrame(guidedEvaluation)
                    } else {
                        processGuidedRegistrationFrame(guidedEvaluation)
                    }
                }
            }
            .addOnFailureListener { error -> showStatus(error.message ?: "Error facial.") }
            .addOnCompleteListener {
                isAnalyzing.set(false)
                imageProxy.close()
            }
    }

    private fun showGuidedRegistrationConditions() {
        if (guidedRegistrationActive || isGuidedCaptureRunning.get()) return
        val studentId = studentIdEditText.text.toString().trim()
        val name = studentNameEditText.text.toString().trim()
        val sex = selectedStudentSex()
        if (studentId.isBlank() || name.length < 2 || sex == null) {
            showStatus(getString(R.string.invalid_registration))
            return
        }
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        val existingStudentId = pendingReferenceUpdateStudentId
        runNetworkRequest {
            val conflict = findRegistrationIdentityConflict(
                studentId,
                name,
                mode,
                serverUrl,
                existingStudentId
            )
            runOnUiThread {
                if (conflict != null) {
                    showRegistrationIdentityConflict(conflict)
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.registration_conditions_title)
                    .setMessage(R.string.registration_conditions_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.registration_conditions_start) { _, _ ->
                        startGuidedRegistration()
                    }
                    .show()
            }
        }
    }

    private fun findRegistrationIdentityConflict(
        studentId: String,
        name: String,
        mode: ProcessingMode,
        serverUrl: String?,
        existingStudentId: String?
    ): StudentIdentityConflict? {
        if (mode != ProcessingMode.SERVER) {
            localStore.findStudentIdentityConflict(
                studentId,
                name,
                existingStudentId
            )?.let { return it }
        }
        if (mode == ProcessingMode.PHONE || serverUrl == null) return null
        val serverAvailable = isHealthyServer(serverUrl)
        if (!serverAvailable) {
            if (mode == ProcessingMode.SERVER) {
                throw ConnectException(getString(R.string.server_unreachable))
            }
            return null
        }
        return findStudentIdentityConflict(
            loadServerStudentIdentities(serverUrl),
            studentId,
            name,
            existingStudentId
        )
    }

    private fun findStudentIdentityConflict(
        profiles: List<StudentProfileData>,
        studentId: String,
        name: String,
        existingStudentId: String?
    ): StudentIdentityConflict? {
        val canonicalId = canonicalStudentIdentity(studentId)
        val excludedId = existingStudentId
            ?.takeIf { canonicalStudentIdentity(it) == canonicalId }
            ?.let(::canonicalStudentIdentity)
            ?.takeIf { it.isNotBlank() }
        val available = profiles.filterNot {
            canonicalStudentIdentity(it.studentId) == excludedId
        }
        if (available.any { canonicalStudentIdentity(it.studentId) == canonicalId }) {
            return StudentIdentityConflict.CARNET
        }
        val canonicalName = canonicalStudentIdentity(name)
        if (available.any { canonicalStudentIdentity(it.name) == canonicalName }) {
            return StudentIdentityConflict.NAME
        }
        return null
    }

    private fun showRegistrationIdentityConflict(conflict: StudentIdentityConflict) {
        when (conflict) {
            StudentIdentityConflict.CARNET -> {
                val message = getString(R.string.duplicate_student_carnet)
                studentIdEditText.error = message
                studentIdEditText.requestFocus()
                showStatus(message)
            }
            StudentIdentityConflict.NAME -> {
                val message = getString(R.string.duplicate_student_name)
                studentNameEditText.error = message
                studentNameEditText.requestFocus()
                showStatus(message)
            }
        }
    }

    private fun startGuidedRegistration() {
        if (guidedRegistrationActive || isGuidedCaptureRunning.get()) return
        val studentId = studentIdEditText.text.toString().trim()
        val name = studentNameEditText.text.toString().trim()
        val sex = selectedStudentSex()
        if (studentId.isBlank() || name.length < 2 || sex == null) {
            showStatus(getString(R.string.invalid_registration))
            return
        }
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        val existingStudentId = pendingReferenceUpdateStudentId?.takeIf {
            canonicalStudentIdentity(it) == canonicalStudentIdentity(studentId)
        }
        guidedContext = GuidedRegistrationContext(
            studentId,
            name,
            sex,
            mode,
            serverUrl,
            existingStudentId
        )
        enterCameraFullscreen(
            registrationCameraContainer,
            FullscreenCameraMode.REGISTRATION
        )
        guidedSamples.clear()
        guidedPoseIndex = 0
        guidedStableFrames = 0
        guidedRegistrationActive = true
        guidedVerificationActive = false
        stopVerificationCountdown()
        isGuidedCaptureRunning.set(false)
        registrationCapturedImageView.setImageDrawable(null)
        registrationCapturedImageView.visibility = View.GONE
        setGuidedProfileFieldsEnabled(false)
        cancelRegistrationButton.visibility = View.VISIBLE
        registrationProgress.progress = 0
        registrationFullscreenStepText.setText(R.string.registration_fullscreen_initial)
        poseStatusViews.forEach {
            it.setBackgroundResource(R.drawable.pose_pending_background)
            it.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
        updateGuidedRegistrationUi()
        lensFacing = CameraSelector.LENS_FACING_FRONT
        showStatus("Recorrido iniciado. Sigue las indicaciones de la camara.")
    }

    private fun cancelGuidedRegistration() {
        guidedRegistrationActive = false
        guidedVerificationActive = false
        stopVerificationCountdown()
        guidedPoseIndex = -1
        guidedStableFrames = 0
        guidedSamples.clear()
        guidedContext = null
        pendingReferenceUpdateStudentId = null
        isGuidedCaptureRunning.set(false)
        setGuidedProfileFieldsEnabled(true)
        cancelRegistrationButton.visibility = View.GONE
        registrationProgress.progress = 0
        registrationStepText.setText(R.string.registration_step_two)
        registrationInstructionText.setText(R.string.registration_ready_instruction)
        registrationCapturedImageView.setImageDrawable(null)
        registrationCapturedImageView.visibility = View.GONE
        registrationFaceOverlay.clear()
        registrationDirectionArrowText.visibility = View.GONE
        poseStatusViews.forEach {
            it.setBackgroundResource(R.drawable.pose_pending_background)
            it.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
        if (fullscreenCameraMode == FullscreenCameraMode.REGISTRATION) {
            exitCameraFullscreen()
        }
        showStatus(getString(R.string.registration_cancelled))
    }

    private fun setGuidedProfileFieldsEnabled(enabled: Boolean) {
        studentIdEditText.isEnabled = enabled
        studentNameEditText.isEnabled = enabled
        studentSexSpinner.isEnabled = enabled
        findViewById<Button>(R.id.registerButton).isEnabled = enabled
    }

    private fun updateGuidedRegistrationUi() {
        val pose = GuidedPose.entries.getOrNull(guidedPoseIndex) ?: return
        runOnUiThread {
            registrationStepText.text = getString(
                R.string.registration_step_progress,
                guidedSamples.size
            )
            registrationProgress.progress = guidedSamples.size
            registrationFullscreenStepText.text = getString(
                R.string.registration_fullscreen_progress,
                guidedSamples.size
            )
            registrationInstructionText.setText(pose.instructionRes)
            showGuidedDirectionArrow(pose)
        }
    }

    private fun showGuidedDirectionArrow(pose: GuidedPose) {
        val arrowRes = pose.arrowRes
        if (arrowRes == null) {
            registrationDirectionArrowText.visibility = View.GONE
            return
        }
        val layout = registrationDirectionArrowText.layoutParams as FrameLayout.LayoutParams
        layout.setMargins(0, 0, 0, 0)
        layout.marginStart = 0
        layout.marginEnd = 0
        when (pose) {
            GuidedPose.PROFILE -> {
                layout.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                layout.marginEnd = dp(18)
            }
            GuidedPose.UP -> {
                layout.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                layout.topMargin = dp(72)
            }
            GuidedPose.DOWN -> {
                layout.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layout.bottomMargin = dp(78)
            }
            GuidedPose.FRONT -> Unit
        }
        registrationDirectionArrowText.layoutParams = layout
        registrationDirectionArrowText.setText(arrowRes)
        registrationDirectionArrowText.visibility = View.VISIBLE
    }

    private fun processGuidedRegistrationFrame(evaluation: GuidedFrameEvaluation) {
        if (!guidedRegistrationActive || isGuidedCaptureRunning.get()) return
        val pose = GuidedPose.entries.getOrNull(guidedPoseIndex) ?: return
        if (!evaluation.accepted) {
            guidedStableFrames = if (evaluation.qualityIssue) {
                (guidedStableFrames - 1).coerceAtLeast(0)
            } else {
                0
            }
            runOnUiThread { registrationInstructionText.text = evaluation.message }
            return
        }

        guidedStableFrames++
        runOnUiThread {
            registrationInstructionText.text = getString(
                R.string.registration_hold_quality_instruction,
                guidedStableFrames,
                GUIDED_STABLE_FRAMES
            )
        }
        if (guidedStableFrames < GUIDED_STABLE_FRAMES) return
        guidedStableFrames = 0
        captureGuidedPose(pose)
    }

    private fun matchesGuidedPose(face: Face, pose: GuidedPose): Boolean {
        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX
        return when (pose) {
            GuidedPose.FRONT -> abs(yaw) <= 12f && abs(pitch) <= 12f
            GuidedPose.PROFILE -> abs(yaw) in 23f..48f && abs(pitch) <= 18f
            GuidedPose.UP -> pitch >= 13f && abs(yaw) <= 18f
            GuidedPose.DOWN -> pitch <= -13f && abs(yaw) <= 18f
        }
    }

    private fun evaluateGuidedFrame(
        faces: List<Face>,
        imageWidth: Int,
        imageHeight: Int,
        quality: CaptureQuality,
        pose: GuidedPose,
        verification: Boolean,
        capturedImage: Boolean = false
    ): GuidedFrameEvaluation {
        if (faces.isEmpty()) {
            return GuidedFrameEvaluation(
                false,
                getString(R.string.registration_no_face_quality),
                getString(R.string.registration_overlay_no_face)
            )
        }
        if (faces.size != 1) {
            return GuidedFrameEvaluation(
                false,
                getString(R.string.registration_single_face_required),
                getString(R.string.registration_overlay_multiple_faces)
            )
        }
        val face = faces.single()
        val minimumSide = minOf(imageWidth, imageHeight) * 0.18f
        if (
            face.boundingBox.width() < minimumSide ||
            face.boundingBox.height() < minimumSide
        ) {
            return GuidedFrameEvaluation(
                false,
                getString(R.string.registration_move_closer),
                getString(R.string.registration_overlay_too_far)
            )
        }
        qualityProblem(quality, capturedImage)?.let { problem ->
            return problem
        }
        if (!matchesGuidedPose(face, pose)) {
            return GuidedFrameEvaluation(
                false,
                getString(
                    if (verification) {
                        R.string.registration_verification_front_instruction
                    } else {
                        pose.instructionRes
                    }
                ),
                getString(R.string.registration_overlay_wrong_pose)
            )
        }
        return GuidedFrameEvaluation(
            true,
            getString(R.string.registration_pose_quality_ready),
            getString(R.string.registration_overlay_correct_pose)
        )
    }

    private fun qualityProblem(
        quality: CaptureQuality,
        capturedImage: Boolean
    ): GuidedFrameEvaluation? {
        val minimumSharpness = if (capturedImage) {
            MIN_CAPTURE_SHARPNESS
        } else {
            MIN_LIVE_SHARPNESS
        }
        return when {
            quality.brightness < MIN_CAPTURE_BRIGHTNESS -> GuidedFrameEvaluation(
                false,
                getString(R.string.registration_quality_dark),
                getString(R.string.registration_overlay_dark),
                qualityIssue = true
            )
            quality.brightness > MAX_CAPTURE_BRIGHTNESS -> GuidedFrameEvaluation(
                false,
                getString(R.string.registration_quality_bright),
                getString(R.string.registration_overlay_bright),
                qualityIssue = true
            )
            quality.contrast < MIN_CAPTURE_CONTRAST -> GuidedFrameEvaluation(
                false,
                getString(R.string.registration_quality_contrast),
                getString(R.string.registration_overlay_low_contrast),
                qualityIssue = true
            )
            quality.sharpness < minimumSharpness -> GuidedFrameEvaluation(
                false,
                getString(R.string.registration_quality_blurry),
                getString(R.string.registration_overlay_blurry),
                qualityIssue = true
            )
            else -> null
        }
    }

    private fun guidedOverlayResults(
        faces: List<Face>,
        evaluation: GuidedFrameEvaluation
    ): List<FaceOverlayBox> {
        val state = if (evaluation.accepted) {
            FaceBoxState.GUIDANCE_VALID
        } else {
            FaceBoxState.GUIDANCE_INVALID
        }
        return faces.mapIndexed { index, face ->
            FaceOverlayBox(
                Rect(face.boundingBox),
                state,
                if (index == 0) evaluation.overlayLabel else null
            )
        }
    }

    private fun measureFrameQuality(imageProxy: ImageProxy): CaptureQuality {
        val plane = imageProxy.planes.firstOrNull()
            ?: return CaptureQuality(0f, 0f, 0f)
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()
        return summarizeQuality(imageProxy.width, imageProxy.height) { x, y ->
            val index = y * rowStride + x * pixelStride
            if (index in 0 until limit) buffer.get(index).toInt() and 0xFF else 0
        }
    }

    private fun measureBitmapQuality(bitmap: Bitmap, faceBounds: Rect): CaptureQuality {
        val region = Rect(faceBounds)
        if (!region.intersect(0, 0, bitmap.width, bitmap.height) || region.isEmpty) {
            return CaptureQuality(0f, 0f, 0f)
        }
        return summarizeQuality(region.width(), region.height()) { x, y ->
            val pixel = bitmap.getPixel(region.left + x, region.top + y)
            (
                Color.red(pixel) * 77 +
                    Color.green(pixel) * 150 +
                    Color.blue(pixel) * 29
                ) shr 8
        }
    }

    private fun summarizeQuality(
        width: Int,
        height: Int,
        luminanceAt: (Int, Int) -> Int
    ): CaptureQuality {
        if (width < 2 || height < 2) return CaptureQuality(0f, 0f, 0f)
        val step = (minOf(width, height) / 96).coerceAtLeast(2)
        var count = 0
        var sum = 0.0
        var squaredSum = 0.0
        var edgeCount = 0
        var edgeSum = 0.0
        var y = 0
        while (y < height - 1) {
            var x = 0
            while (x < width - 1) {
                val value = luminanceAt(x, y).coerceIn(0, 255)
                sum += value
                squaredSum += value.toDouble() * value.toDouble()
                count++
                edgeSum += abs(value - luminanceAt(x + 1, y))
                edgeSum += abs(value - luminanceAt(x, y + 1))
                edgeCount += 2
                x += step
            }
            y += step
        }
        if (count == 0) return CaptureQuality(0f, 0f, 0f)
        val mean = sum / count
        val variance = (squaredSum / count - mean * mean).coerceAtLeast(0.0)
        return CaptureQuality(
            mean.toFloat(),
            sqrt(variance).toFloat(),
            if (edgeCount == 0) 0f else (edgeSum / edgeCount).toFloat()
        )
    }

    private fun captureGuidedPose(pose: GuidedPose) {
        if (!isGuidedCaptureRunning.compareAndSet(false, true)) return
        runOnUiThread {
            registrationDirectionArrowText.visibility = View.GONE
            registrationInstructionText.setText(R.string.registration_processing_pose)
        }
        capturePhoto(
            onSuccess = { jpeg ->
                networkExecutor.execute {
                    try {
                        val extraction = extractLocalFaces(jpeg)
                        if (extraction.samples.size != 1) {
                            throw IOException(getString(R.string.local_face_required))
                        }
                        val capturedFace = extraction.faces.singleOrNull()
                            ?: throw IOException(getString(R.string.local_face_required))
                        val capturedQuality = measureBitmapQuality(
                            extraction.bitmap,
                            capturedFace.boundingBox
                        )
                        val capturedEvaluation = evaluateGuidedFrame(
                            extraction.faces,
                            extraction.bitmap.width,
                            extraction.bitmap.height,
                            capturedQuality,
                            pose,
                            verification = false,
                            capturedImage = true
                        )
                        if (!capturedEvaluation.accepted) {
                            throw IOException(capturedEvaluation.message)
                        }
                        if (!guidedRegistrationActive || guidedPoseIndex != pose.ordinal) return@execute
                        guidedSamples += GuidedRegistrationSample(
                            extraction.samples.single().embedding,
                            jpeg
                        )
                        guidedPoseIndex++
                        runOnUiThread {
                            poseStatusViews[pose.ordinal].setBackgroundResource(
                                R.drawable.pose_complete_background
                            )
                            poseStatusViews[pose.ordinal].setTextColor(
                                ContextCompat.getColor(this, R.color.accent)
                            )
                            registrationProgress.progress = guidedSamples.size
                            registrationFullscreenStepText.text = getString(
                                R.string.registration_fullscreen_progress,
                                guidedSamples.size
                            )
                            registrationStepText.text = getString(
                                R.string.registration_step_progress,
                                guidedSamples.size
                            )
                        }
                        if (guidedPoseIndex >= GuidedPose.entries.size) {
                            isGuidedCaptureRunning.set(false)
                            promptGuidedRecognitionTest()
                        } else {
                            isGuidedCaptureRunning.set(false)
                            updateGuidedRegistrationUi()
                        }
                    } catch (error: Exception) {
                        isGuidedCaptureRunning.set(false)
                        showStatus(error.message ?: getString(R.string.registration_pose_rejected))
                        runOnUiThread {
                            registrationInstructionText.setText(
                                R.string.registration_pose_rejected
                            )
                            showGuidedDirectionArrow(pose)
                        }
                    }
                }
            },
            onError = { message ->
                isGuidedCaptureRunning.set(false)
                showStatus(message)
                updateGuidedRegistrationUi()
            }
        )
    }

    private fun promptGuidedRecognitionTest() {
        guidedVerificationActive = false
        guidedStableFrames = 0
        stopVerificationCountdown()
        runOnUiThread {
            registrationFaceOverlay.clear()
            registrationDirectionArrowText.visibility = View.GONE
            registrationInstructionText.setText(R.string.registration_verification_prompt)
            registrationFullscreenStepText.setText(R.string.registration_fullscreen_verification)
            AlertDialog.Builder(this)
                .setTitle(R.string.registration_verification_title)
                .setMessage(R.string.registration_verification_message)
                .setNegativeButton(R.string.cancel_registration) { _, _ ->
                    cancelGuidedRegistration()
                }
                .setPositiveButton(R.string.registration_verification_start) { _, _ ->
                    startGuidedRecognitionTest()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun startGuidedRecognitionTest() {
        stopVerificationCountdown()
        guidedVerificationActive = true
        guidedStableFrames = 0
        isGuidedCaptureRunning.set(false)
        registrationCapturedImageView.setImageDrawable(null)
        registrationCapturedImageView.visibility = View.GONE
        registrationFaceOverlay.clear()
        registrationDirectionArrowText.visibility = View.GONE
        registrationInstructionText.setText(
            R.string.registration_verification_front_instruction
        )
        registrationFullscreenStepText.setText(R.string.registration_fullscreen_verification)
        showStatus(getString(R.string.registration_verification_live))
    }

    private fun processGuidedVerificationFrame(evaluation: GuidedFrameEvaluation) {
        if (
            !guidedVerificationActive ||
            isGuidedCaptureRunning.get() ||
            verificationCountdown != null
        ) return
        if (!evaluation.accepted) {
            guidedStableFrames = if (evaluation.qualityIssue) {
                (guidedStableFrames - 1).coerceAtLeast(0)
            } else {
                0
            }
            runOnUiThread { registrationInstructionText.text = evaluation.message }
            return
        }
        guidedStableFrames++
        runOnUiThread {
            registrationInstructionText.text = getString(
                R.string.registration_verification_hold,
                guidedStableFrames,
                GUIDED_STABLE_FRAMES
            )
        }
        if (guidedStableFrames < GUIDED_STABLE_FRAMES) return
        guidedStableFrames = 0
        runOnUiThread { startGuidedVerificationCountdown() }
    }

    private fun startGuidedVerificationCountdown() {
        if (!guidedVerificationActive || verificationCountdown != null) return
        guidedVerificationActive = false
        guidedStableFrames = 0
        registrationDirectionArrowText.visibility = View.GONE
        registrationCountdownText.text = "3"
        registrationCountdownText.visibility = View.VISIBLE
        registrationInstructionText.text = getString(
            R.string.registration_verification_countdown,
            3
        )
        verificationCountdown = object : CountDownTimer(3_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = ((millisUntilFinished + 999L) / 1_000L)
                    .toInt()
                    .coerceIn(1, 3)
                registrationCountdownText.text = seconds.toString()
                registrationInstructionText.text = getString(
                    R.string.registration_verification_countdown,
                    seconds
                )
            }

            override fun onFinish() {
                verificationCountdown = null
                registrationCountdownText.visibility = View.GONE
                if (!guidedRegistrationActive || guidedContext == null) return
                captureGuidedRecognitionTest()
            }
        }.start()
    }

    private fun stopVerificationCountdown() {
        verificationCountdown?.cancel()
        verificationCountdown = null
        if (::registrationCountdownText.isInitialized) {
            val hideCountdown = Runnable {
                registrationCountdownText.visibility = View.GONE
                registrationCountdownText.text = ""
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                hideCountdown.run()
            } else {
                registrationCountdownText.post(hideCountdown)
            }
        }
    }

    private fun captureGuidedRecognitionTest() {
        if (!isGuidedCaptureRunning.compareAndSet(false, true)) return
        guidedVerificationActive = false
        runOnUiThread {
            registrationCountdownText.visibility = View.GONE
            registrationInstructionText.setText(R.string.registration_verification_processing)
        }
        capturePhoto(
            onSuccess = { jpeg ->
                networkExecutor.execute {
                    try {
                        val context = guidedContext
                            ?: throw IOException(getString(R.string.registration_cancelled))
                        val extraction = extractLocalFaces(jpeg)
                        if (extraction.samples.size != 1 || extraction.faces.size != 1) {
                            throw IOException(getString(R.string.registration_single_face_required))
                        }
                        val face = extraction.faces.single()
                        val quality = measureBitmapQuality(
                            extraction.bitmap,
                            face.boundingBox
                        )
                        val qualityEvaluation = evaluateGuidedFrame(
                            extraction.faces,
                            extraction.bitmap.width,
                            extraction.bitmap.height,
                            quality,
                            GuidedPose.FRONT,
                            verification = true,
                            capturedImage = true
                        )
                        if (!qualityEvaluation.accepted) {
                            throw IOException(qualityEvaluation.message)
                        }
                        val result = localStore.verifyRegistrationCandidate(
                            context.studentId,
                            context.name,
                            guidedSamples.map { it.embedding },
                            extraction.samples,
                            extraction.bitmap.width,
                            extraction.bitmap.height
                        )
                        val recognized = result.faces.singleOrNull()
                        val passed = recognized?.studentId == context.studentId
                        if (passed && guidedSamples.size < 5) {
                            guidedSamples += GuidedRegistrationSample(
                                extraction.samples.single().embedding,
                                jpeg
                            )
                        }
                        showFrozenRegistrationVerification(
                            extraction.bitmap,
                            recognized,
                            context,
                            passed
                        )
                        if (passed) {
                            showGuidedVerificationSuccess(
                                context,
                                recognized?.similarity ?: 0f
                            )
                        } else {
                            showGuidedVerificationFailure(
                                getString(R.string.registration_verification_not_recognized)
                            )
                        }
                    } catch (error: Exception) {
                        showGuidedVerificationFailure(
                            error.message ?: getString(
                                R.string.registration_verification_not_recognized
                            )
                        )
                    }
                }
            },
            onError = { message -> showGuidedVerificationFailure(message) }
        )
    }

    private fun showFrozenRegistrationVerification(
        bitmap: Bitmap,
        recognized: LocalRecognitionFace?,
        context: GuidedRegistrationContext,
        passed: Boolean
    ) {
        runOnUiThread {
            registrationCapturedImageView.setImageBitmap(bitmap)
            registrationCapturedImageView.visibility = View.VISIBLE
            val state = if (passed) {
                FaceBoxState.GUIDANCE_VALID
            } else {
                FaceBoxState.GUIDANCE_INVALID
            }
            val label = if (passed) {
                getString(R.string.registration_verification_overlay_passed, context.name)
            } else {
                getString(R.string.registration_verification_overlay_failed)
            }
            val boxes = recognized?.let {
                listOf(FaceOverlayBox(Rect(it.bounds), state, label))
            }.orEmpty()
            registrationFaceOverlay.setResults(
                boxes,
                bitmap.width,
                bitmap.height,
                false
            )
            registrationInstructionText.setText(
                if (passed) {
                    R.string.registration_verification_passed_instruction
                } else {
                    R.string.registration_verification_failed_instruction
                }
            )
        }
    }

    private fun showGuidedVerificationSuccess(
        context: GuidedRegistrationContext,
        similarity: Float
    ) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(R.string.registration_verification_passed_title)
                .setMessage(
                    getString(
                        R.string.registration_verification_passed_message,
                        context.name,
                        similarity
                    )
                )
                .setPositiveButton(R.string.registration_save_verified) { _, _ ->
                    networkExecutor.execute { saveGuidedRegistration() }
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showGuidedVerificationFailure(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(R.string.registration_verification_failed_title)
                .setMessage(
                    getString(R.string.registration_verification_failed_message, message)
                )
                .setNegativeButton(R.string.cancel_registration) { _, _ ->
                    isGuidedCaptureRunning.set(false)
                    cancelGuidedRegistration()
                }
                .setPositiveButton(R.string.registration_verification_retry) { _, _ ->
                    startGuidedRecognitionTest()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun saveGuidedRegistration() {
        val context = guidedContext ?: return
        guidedRegistrationActive = false
        guidedVerificationActive = false
        stopVerificationCountdown()
        setProcessing(true, getString(R.string.registration_saving))
        runOnUiThread {
            registrationInstructionText.setText(R.string.registration_saving)
        }
        var localStored = false
        var serverStored = false
        var localError: Exception? = null
        var serverError: Exception? = null

        if (context.mode != ProcessingMode.SERVER) {
            try {
                localStore.registerStudentSamples(
                    context.studentId,
                    context.name,
                    context.sex,
                    guidedSamples.map { it.embedding to it.jpeg },
                    existingStudentId = context.existingStudentId
                )
                localStored = true
            } catch (error: Exception) {
                localError = error
            }
        }
        if (context.mode != ProcessingMode.PHONE && context.serverUrl != null) {
            val available = context.mode == ProcessingMode.SERVER ||
                runCatching { isHealthyServer(context.serverUrl) }.getOrDefault(false)
            if (available) {
                try {
                    registerOnServer(
                        context.serverUrl,
                        context.studentId,
                        context.name,
                        context.sex,
                        guidedSamples.map { it.jpeg },
                        allowExistingStudent = context.existingStudentId != null
                    )
                    serverStored = true
                } catch (error: Exception) {
                    serverError = error
                }
            }
        }

        if (!localStored && !serverStored) {
            val error = localError ?: serverError ?: IOException(
                getString(R.string.server_unreachable)
            )
            showStatus(networkErrorMessage(error))
            resetGuidedRegistrationAfterSave(clearFields = false)
            return
        }

        localStore.allowStudentRegistration(context.studentId)

        runOnUiThread {
            showStatus(getString(R.string.registration_complete_four, context.name))
            appendHistory(
                "Registro facial: ${context.studentId} | ${context.name} | 4 posiciones"
            )
            serverError?.message?.let { appendHistory("Servidor no sincronizado: $it") }
            resetGuidedRegistrationAfterSave(clearFields = true)
        }
    }

    private fun resetGuidedRegistrationAfterSave(clearFields: Boolean) {
        runOnUiThread {
            guidedRegistrationActive = false
            guidedVerificationActive = false
            stopVerificationCountdown()
            guidedPoseIndex = -1
            guidedStableFrames = 0
            guidedSamples.clear()
            guidedContext = null
            isGuidedCaptureRunning.set(false)
            setProcessing(false)
            setGuidedProfileFieldsEnabled(true)
            cancelRegistrationButton.visibility = View.GONE
            registrationInstructionText.setText(R.string.registration_ready_instruction)
            registrationFullscreenStepText.setText(R.string.registration_fullscreen_initial)
            registrationCapturedImageView.setImageDrawable(null)
            registrationCapturedImageView.visibility = View.GONE
            registrationFaceOverlay.clear()
            registrationDirectionArrowText.visibility = View.GONE
            if (fullscreenCameraMode == FullscreenCameraMode.REGISTRATION) {
                exitCameraFullscreen()
            }
            if (clearFields) {
                studentIdEditText.text.clear()
                studentNameEditText.text.clear()
                studentSexSpinner.setSelection(0)
            }
            pendingReferenceUpdateStudentId = null
        }
    }

    private fun registerCurrentFace() {
        val studentId = studentIdEditText.text.toString().trim()
        val name = studentNameEditText.text.toString().trim()
        val sex = selectedStudentSex()
        if (studentId.isBlank() || name.isBlank() || sex == null) {
            showStatus(getString(R.string.invalid_registration))
            return
        }
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        captureAndRunNetwork { jpeg ->
            var localStored = false
            var serverStored = false
            var localError: Exception? = null
            var serverError: Exception? = null

            if (mode != ProcessingMode.SERVER) {
                try {
                    setProcessing(true, getString(R.string.local_processing))
                    val localFaces = extractLocalFaces(jpeg)
                    if (localFaces.samples.size != 1) {
                        throw IOException(getString(R.string.local_face_required))
                    }
                    localStore.registerStudent(
                        studentId,
                        name,
                        sex,
                        localFaces.samples.single().embedding,
                        jpeg
                    )
                    localStored = true
                } catch (error: Exception) {
                    localError = error
                    if (mode == ProcessingMode.PHONE) throw error
                }
            }

            if (mode != ProcessingMode.PHONE && serverUrl != null) {
                val serverAvailable = mode == ProcessingMode.SERVER ||
                    runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
                if (serverAvailable) {
                    try {
                        registerOnServer(serverUrl, studentId, name, sex, jpeg)
                        serverStored = true
                    } catch (error: Exception) {
                        serverError = error
                        if (mode == ProcessingMode.SERVER) throw error
                    }
                }
            }

            if (!localStored && !serverStored) {
                throw localError ?: serverError
                    ?: IOException(getString(R.string.server_unreachable))
            }
            runOnUiThread {
                val message = when {
                    localStored && serverStored -> {
                        getString(R.string.hybrid_registration_complete, name)
                    }
                    localStored -> getString(R.string.local_registration_complete, name)
                    else -> getString(R.string.registration_complete, name)
                }
                showStatus(message)
                val destinations = when {
                    localStored && serverStored -> "telefono y servidor"
                    localStored -> "telefono"
                    else -> "servidor"
                }
                appendHistory("Rostro registrado en $destinations: $studentId | $name")
                serverError?.message?.let { appendHistory("Servidor no sincronizado: $it") }
                scheduleReturnToLive(2_500L)
            }
        }
    }

    private fun confirmDeleteRecognition(
        studentId: String = studentIdEditText.text.toString().trim()
    ) {
        if (studentId.isBlank()) {
            showStatus(getString(R.string.student_id_required))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_recognition_title)
            .setMessage(getString(R.string.delete_recognition_message, studentId))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteRecognition(studentId)
            }
            .show()
    }

    private fun saveStudentSex() {
        val studentId = studentIdEditText.text.toString().trim()
        val sex = selectedStudentSex()
        if (studentId.isBlank() || sex == null) {
            showStatus(getString(R.string.student_sex_required))
            return
        }
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        runNetworkRequest {
            var name: String? = null
            if (mode != ProcessingMode.SERVER) {
                name = localStore.updateStudentSex(studentId, sex)
            }
            if (mode != ProcessingMode.PHONE && serverUrl != null) {
                val serverAvailable = mode == ProcessingMode.SERVER ||
                    runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
                if (serverAvailable) {
                    val encodedId = URLEncoder.encode(
                        studentId,
                        StandardCharsets.UTF_8.toString()
                    ).replace("+", "%20")
                    val result = patchJson(
                        "$serverUrl/students/$encodedId/sex",
                        JSONObject().put("sex", sex)
                    )
                    name = result.optString("name", name ?: studentId)
                }
            }
            val updatedName = name
                ?: throw IOException("No existe un estudiante con el carnet $studentId.")
            runOnUiThread {
                if (studentNameEditText.text.isNullOrBlank()) {
                    studentNameEditText.setText(updatedName)
                }
                showStatus(getString(R.string.student_sex_updated, updatedName))
            }
        }
    }

    private fun showRegisteredStudents() {
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        runNetworkRequest {
            val localProfiles = if (mode == ProcessingMode.SERVER) {
                emptyList()
            } else {
                localStore.listStudents().map {
                    StudentProfileData(
                        it.studentId,
                        it.name,
                        it.sex,
                        it.embeddingCount,
                        it.photoJpeg
                    )
                }
            }
            val profiles = when (mode) {
                ProcessingMode.PHONE -> localProfiles
                ProcessingMode.SERVER -> loadServerStudentProfiles(serverUrl!!)
                ProcessingMode.AUTOMATIC -> {
                    if (localProfiles.isNotEmpty()) {
                        localProfiles
                    } else if (
                        serverUrl != null &&
                        runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
                    ) {
                        runCatching { loadServerStudentProfiles(serverUrl) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }
            }
            runOnUiThread {
                currentStudentProfiles = profiles
                renderStudentTable(profiles)
                showStatus(
                    getString(R.string.registered_students_table_count, profiles.size)
                )
            }
        }
    }

    private fun loadServerStudentProfiles(serverUrl: String): List<StudentProfileData> {
        val response = getJsonArray("$serverUrl/students")
        return buildList {
            for (index in 0 until response.length()) {
                val item = response.optJSONObject(index) ?: continue
                val studentId = item.optString("student_id").trim()
                val name = item.optString("name").trim()
                if (studentId.isBlank() || name.isBlank()) continue
                if (localStore.isStudentPermanentlyDeleted(studentId)) continue
                val photo = if (item.optInt("has_photo", 0) == 1) {
                    val encodedId = URLEncoder.encode(
                        studentId,
                        StandardCharsets.UTF_8.toString()
                    ).replace("+", "%20")
                    runCatching { getBytes("$serverUrl/students/$encodedId/photo") }.getOrNull()
                } else {
                    null
                }
                add(
                    StudentProfileData(
                        studentId = studentId,
                        name = name,
                        sex = item.optString("sex", SEX_UNSPECIFIED).trim(),
                        embeddingCount = item.optInt("embedding_count", 0),
                        photoJpeg = photo
                    )
                )
            }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun loadServerStudentIdentities(serverUrl: String): List<StudentProfileData> {
        val response = getJsonArray("$serverUrl/students")
        return buildList {
            for (index in 0 until response.length()) {
                val item = response.optJSONObject(index) ?: continue
                val studentId = item.optString("student_id").trim()
                val name = item.optString("name").trim()
                if (studentId.isBlank() || name.isBlank()) continue
                add(
                    StudentProfileData(
                        studentId = studentId,
                        name = name,
                        sex = item.optString("sex", SEX_UNSPECIFIED).trim(),
                        embeddingCount = item.optInt("embedding_count", 0),
                        photoJpeg = null
                    )
                )
            }
        }
    }

    private fun renderStudentTable(profiles: List<StudentProfileData>) {
        studentsCountText.text = getString(
            R.string.registered_students_table_count,
            profiles.size
        )
        studentsTableContainer.removeAllViews()
        if (profiles.isEmpty()) {
            studentsTableContainer.addView(TextView(this).apply {
                text = getString(R.string.no_registered_students)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(28))
            })
            return
        }

        profiles.forEach { profile ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(4), dp(10))
                setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
                isClickable = true
                isFocusable = true
                setOnClickListener { showEditStudentDialog(profile) }
            }
            val photo = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginEnd = dp(8)
                }
                background = ContextCompat.getDrawable(context, R.drawable.face_thumbnail_background)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = profile.name
                profile.photoJpeg?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::setImageBitmap)
                }
            }
            val identity = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = profile.name
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 2
                })
                addView(TextView(context).apply {
                    text = getString(
                        R.string.student_row_detail,
                        profile.studentId,
                        profile.embeddingCount
                    )
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
            val sex = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(82), LinearLayout.LayoutParams.WRAP_CONTENT)
                text = studentSexLabel(profile.sex)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
            }
            val edit = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setImageResource(android.R.drawable.ic_menu_edit)
                val selectable = TypedValue()
                this@MainActivity.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless,
                    selectable,
                    true
                )
                setBackgroundResource(selectable.resourceId)
                contentDescription = getString(R.string.edit_student_tooltip)
                tooltipText = getString(R.string.edit_student_tooltip)
                setOnClickListener { showEditStudentDialog(profile) }
            }
            row.addView(photo)
            row.addView(identity)
            row.addView(sex)
            row.addView(edit)
            studentsTableContainer.addView(row)
            studentsTableContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
                setBackgroundColor(ContextCompat.getColor(context, R.color.border))
            })
        }
    }

    private fun showEditStudentDialog(profile: StudentProfileData) {
        val content = layoutInflater.inflate(R.layout.dialog_edit_student, null)
        val nameEditText = content.findViewById<EditText>(R.id.editStudentNameEditText)
        val sexSpinner = content.findViewById<Spinner>(R.id.editStudentSexSpinner)
        val viewFacesButton = content.findViewById<Button>(
            R.id.viewFaceReferencesButton
        )
        val updateFacesButton = content.findViewById<Button>(
            R.id.updateFaceReferencesButton
        )
        val clearFacesButton = content.findViewById<Button>(
            R.id.clearFaceReferencesButton
        )
        val deleteStudentButton = content.findViewById<Button>(
            R.id.deleteStudentPermanentlyButton
        )
        nameEditText.setText(profile.name)
        sexSpinner.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.editable_student_sexes,
            android.R.layout.simple_spinner_dropdown_item
        )
        sexSpinner.setSelection(if (profile.sex == SEX_FEMALE) 1 else 0)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_student)
            .setMessage(getString(R.string.student_id_value, profile.studentId))
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            viewFacesButton.setOnClickListener {
                dialog.dismiss()
                showFaceReferences(profile)
            }
            updateFacesButton.setOnClickListener {
                val name = nameEditText.text.toString().trim()
                if (name.length < 2) {
                    nameEditText.error = getString(R.string.student_name_required)
                    return@setOnClickListener
                }
                val sex = if (sexSpinner.selectedItemPosition == 1) {
                    SEX_FEMALE
                } else {
                    SEX_MALE
                }
                dialog.dismiss()
                showFaceReferenceUpdateOptions(profile.studentId, name, sex)
            }
            clearFacesButton.setOnClickListener {
                dialog.dismiss()
                confirmDeleteRecognition(profile.studentId)
            }
            deleteStudentButton.setOnClickListener {
                dialog.dismiss()
                confirmDeleteStudent(profile)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEditText.text.toString().trim()
                if (name.length < 2) {
                    nameEditText.error = getString(R.string.student_name_required)
                    return@setOnClickListener
                }
                val sex = if (sexSpinner.selectedItemPosition == 1) {
                    SEX_FEMALE
                } else {
                    SEX_MALE
                }
                dialog.dismiss()
                saveStudentProfile(profile.studentId, name, sex)
            }
        }
        dialog.show()
    }

    private fun showFaceReferences(profile: StudentProfileData) {
        runNetworkRequest {
            val references = localStore.faceReferences(profile.studentId)
            runOnUiThread {
                showFaceReferencesDialog(profile, references)
            }
        }
    }

    private fun showFaceReferencesDialog(
        profile: StudentProfileData,
        references: List<LocalFaceReference>
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_face_references, null)
        val summary = content.findViewById<TextView>(R.id.faceReferencesSummaryText)
        val list = content.findViewById<LinearLayout>(R.id.faceReferencesList)
        val empty = content.findViewById<TextView>(R.id.emptyFaceReferencesText)
        summary.text = getString(R.string.face_references_summary, references.size)
        empty.visibility = if (references.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (references.isEmpty()) View.GONE else View.VISIBLE

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.face_references_title, profile.name))
            .setView(content)
            .setPositiveButton(R.string.close, null)
            .create()

        references.forEachIndexed { index, reference ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val photo = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(
                    R.string.face_reference_photo_description,
                    index + 1
                )
                decodeOrientedBitmap(reference.photoJpeg)?.let(::setImageBitmap)
                setOnClickListener {
                    showFaceReferencePreview(profile, reference, index + 1)
                }
            }
            val details = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                    marginStart = dp(12)
                }
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            details.addView(TextView(this).apply {
                text = getString(R.string.face_reference_number, index + 1)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            details.addView(TextView(this).apply {
                text = getString(
                    R.string.face_reference_date,
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT,
                        java.text.DateFormat.SHORT
                    ).format(java.util.Date(reference.createdAt))
                )
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val selectable = TypedValue().also {
                theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless,
                    it,
                    true
                )
            }
            actions.addView(ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setImageResource(android.R.drawable.ic_menu_edit)
                setBackgroundResource(selectable.resourceId)
                contentDescription = getString(R.string.replace_face_reference)
                tooltipText = getString(R.string.replace_face_reference)
                setOnClickListener {
                    dialog.dismiss()
                    startFaceReferenceReplacement(profile, reference)
                }
            })
            actions.addView(ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setImageResource(android.R.drawable.ic_menu_delete)
                setColorFilter(ContextCompat.getColor(context, R.color.danger))
                setBackgroundResource(selectable.resourceId)
                contentDescription = getString(R.string.delete_face_reference)
                tooltipText = getString(R.string.delete_face_reference)
                setOnClickListener {
                    dialog.dismiss()
                    confirmDeleteFaceReference(profile, reference)
                }
            })
            row.addView(photo)
            row.addView(details)
            row.addView(actions)
            list.addView(row)
            if (index < references.lastIndex) {
                list.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    setBackgroundColor(ContextCompat.getColor(context, R.color.border))
                })
            }
        }
        dialog.show()
    }

    private fun showFaceReferencePreview(
        profile: StudentProfileData,
        reference: LocalFaceReference,
        position: Int
    ) {
        val image = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            contentDescription = getString(
                R.string.face_reference_photo_description,
                position
            )
            decodeOrientedBitmap(reference.photoJpeg)?.let(::setImageBitmap)
        }
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.face_reference_preview_title,
                    position,
                    profile.name
                )
            )
            .setView(image)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun startFaceReferenceReplacement(
        profile: StudentProfileData,
        reference: LocalFaceReference
    ) {
        val mode = selectedProcessingMode()
        pendingReferenceReplacementContext = FaceReferenceReplacementContext(
            profile = profile,
            referenceId = reference.referenceId,
            mode = mode,
            serverUrl = if (mode == ProcessingMode.PHONE) null else serverUrlFor(mode)
        )
        pickFaceReferenceReplacement.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun confirmDeleteFaceReference(
        profile: StudentProfileData,
        reference: LocalFaceReference
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_face_reference_title)
            .setMessage(getString(R.string.delete_face_reference_message, profile.name))
            .setNegativeButton(R.string.cancel) { _, _ ->
                showFaceReferences(profile)
            }
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteFaceReference(profile, reference)
            }
            .show()
    }

    private fun startFaceReferenceUpdate(studentId: String, name: String, sex: String) {
        pendingReferenceUpdateStudentId = studentId
        studentIdEditText.setText(studentId)
        studentNameEditText.setText(name)
        studentSexSpinner.setSelection(if (sex == SEX_FEMALE) 2 else 1)
        selectSection(AppSection.REGISTRATION)
        registrationCameraContainer.post { showGuidedRegistrationConditions() }
    }

    private fun showFaceReferenceUpdateOptions(
        studentId: String,
        name: String,
        sex: String
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_face_reference_options, null)
        val routeButton = content.findViewById<Button>(R.id.startFaceRouteOptionButton)
        val galleryButton = content.findViewById<Button>(R.id.chooseSavedPhotosOptionButton)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_face_references)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .create()
        routeButton.setOnClickListener {
            dialog.dismiss()
            startFaceReferenceUpdate(studentId, name, sex)
        }
        galleryButton.setOnClickListener {
            dialog.dismiss()
            startGalleryFaceReferenceUpdate(studentId, name, sex)
        }
        dialog.show()
    }

    private fun startGalleryFaceReferenceUpdate(
        studentId: String,
        name: String,
        sex: String
    ) {
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        pendingGalleryUpdateContext = GuidedRegistrationContext(
            studentId,
            name,
            sex,
            mode,
            serverUrl,
            existingStudentId = studentId
        )
        pickFaceReferencePhotos.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun updateFaceReferencesFromGallery(
        context: GuidedRegistrationContext,
        uris: List<Uri>
    ) {
        if (!isNetworkBusy.compareAndSet(false, true)) {
            showStatus(getString(R.string.network_busy))
            return
        }
        val processingUi = showGalleryProcessingDialog(uris.size)
        setProcessing(true, getString(R.string.processing_saved_face_photos))
        networkExecutor.execute {
            try {
                val samples = uris.mapIndexed { index, uri ->
                    updateGalleryProcessing(
                        processingUi,
                        index,
                        getString(
                            R.string.gallery_processing_photo,
                            index + 1,
                            uris.size
                        )
                    )
                    val bytes = readGalleryImage(uri)
                    val extraction = extractGalleryFaceReference(bytes) {
                        updateGalleryProcessing(
                            processingUi,
                            index,
                            getString(
                                R.string.gallery_processing_verification,
                                index + 1,
                                uris.size
                            )
                        )
                    }
                    if (extraction.samples.size != 1) {
                        val message = if (extraction.samples.isEmpty()) {
                            getString(R.string.saved_face_photo_no_face, index + 1)
                        } else {
                            getString(
                                R.string.saved_face_photo_multiple_faces,
                                index + 1,
                                extraction.samples.size
                            )
                        }
                        throw IOException(message)
                    }
                    updateGalleryProcessing(
                        processingUi,
                        index + 1,
                        getString(
                            R.string.gallery_processing_photo_ready,
                            index + 1,
                            uris.size
                        )
                    )
                    GuidedRegistrationSample(
                        extraction.samples.single().embedding,
                        bytes
                    )
                }

                updateGalleryProcessing(
                    processingUi,
                    uris.size,
                    getString(R.string.gallery_processing_saving)
                )
                var localStored = false
                var serverStored = false
                var localError: Exception? = null
                var serverError: Exception? = null
                if (context.mode != ProcessingMode.SERVER) {
                    try {
                        localStore.registerStudentSamples(
                            context.studentId,
                            context.name,
                            context.sex,
                            samples.map { it.embedding to it.jpeg },
                            existingStudentId = context.existingStudentId
                        )
                        localStored = true
                    } catch (error: Exception) {
                        localError = error
                    }
                }
                if (context.mode != ProcessingMode.PHONE && context.serverUrl != null) {
                    val available = context.mode == ProcessingMode.SERVER ||
                        runCatching { isHealthyServer(context.serverUrl) }.getOrDefault(false)
                    if (available) {
                        try {
                            registerOnServer(
                                context.serverUrl,
                                context.studentId,
                                context.name,
                                context.sex,
                                samples.map { it.jpeg },
                                allowExistingStudent = true
                            )
                            serverStored = true
                        } catch (error: Exception) {
                            serverError = error
                        }
                    }
                }
                if (!localStored && !serverStored) {
                    throw localError ?: serverError ?: IOException(
                        getString(R.string.server_unreachable)
                    )
                }
                localStore.allowStudentRegistration(context.studentId)
                runOnUiThread {
                    currentStudentProfiles = currentStudentProfiles.map { profile ->
                        if (profile.studentId == context.studentId) {
                            profile.copy(
                                name = context.name,
                                sex = context.sex,
                                embeddingCount = samples.size,
                                photoJpeg = samples.first().jpeg
                            )
                        } else {
                            profile
                        }
                    }
                    renderStudentTable(currentStudentProfiles)
                    showStatus(
                        getString(R.string.saved_face_photos_updated, context.name)
                    )
                    appendHistory(
                        "Referencias actualizadas desde galeria: " +
                            "${context.studentId} | ${context.name}"
                    )
                    serverError?.message?.let {
                        appendHistory("Servidor no actualizado: $it")
                    }
                }
            } catch (error: Exception) {
                showStatus(networkErrorMessage(error))
            } finally {
                isNetworkBusy.set(false)
                setProcessing(false)
                runOnUiThread {
                    if (processingUi.dialog.isShowing) processingUi.dialog.dismiss()
                }
            }
        }
    }

    private fun replaceFaceReferenceFromGallery(
        context: FaceReferenceReplacementContext,
        uri: Uri
    ) {
        if (!isNetworkBusy.compareAndSet(false, true)) {
            showStatus(getString(R.string.network_busy))
            return
        }
        setProcessing(true, getString(R.string.replace_face_reference_processing))
        networkExecutor.execute {
            try {
                val bytes = readGalleryImage(uri)
                val extraction = extractGalleryFaceReference(bytes) {}
                if (extraction.samples.size != 1) {
                    val message = if (extraction.samples.isEmpty()) {
                        getString(R.string.saved_face_photo_no_face, 1)
                    } else {
                        getString(
                            R.string.saved_face_photo_multiple_faces,
                            1,
                            extraction.samples.size
                        )
                    }
                    throw IOException(message)
                }
                val replaced = localStore.replaceFaceReference(
                    context.profile.studentId,
                    context.referenceId,
                    extraction.samples.single().embedding,
                    bytes
                )
                if (!replaced) throw IOException(getString(R.string.face_reference_not_found))

                val serverWarning = synchronizeFaceReferencesWithServer(
                    context.profile,
                    context.mode,
                    context.serverUrl
                )
                val updatedProfile = refreshedLocalStudentProfile(context.profile)
                val references = localStore.faceReferences(context.profile.studentId)
                runOnUiThread {
                    replaceCurrentStudentProfile(updatedProfile)
                    renderStudentTable(currentStudentProfiles)
                    showStatus(
                        serverWarning?.let {
                            getString(R.string.face_reference_server_warning, it)
                        } ?: getString(
                            R.string.face_reference_replaced,
                            updatedProfile.name
                        )
                    )
                    showFaceReferencesDialog(updatedProfile, references)
                }
            } catch (error: Exception) {
                showStatus(networkErrorMessage(error))
            } finally {
                isNetworkBusy.set(false)
                setProcessing(false)
            }
        }
    }

    private fun deleteFaceReference(
        profile: StudentProfileData,
        reference: LocalFaceReference
    ) {
        val mode = selectedProcessingMode()
        val serverUrl = if (mode == ProcessingMode.PHONE) null else serverUrlFor(mode)
        runNetworkRequest {
            val remaining = localStore.deleteFaceReference(
                profile.studentId,
                reference.referenceId
            ) ?: throw IOException(getString(R.string.face_reference_not_found))
            val serverWarning = synchronizeFaceReferencesWithServer(profile, mode, serverUrl)
            val updatedProfile = refreshedLocalStudentProfile(profile)
            val references = localStore.faceReferences(profile.studentId)
            runOnUiThread {
                replaceCurrentStudentProfile(updatedProfile)
                renderStudentTable(currentStudentProfiles)
                showStatus(
                    serverWarning?.let {
                        getString(R.string.face_reference_server_warning, it)
                    } ?: resources.getQuantityString(
                        R.plurals.face_reference_deleted,
                        remaining,
                        remaining,
                        updatedProfile.name
                    )
                )
                showFaceReferencesDialog(updatedProfile, references)
            }
        }
    }

    private fun synchronizeFaceReferencesWithServer(
        profile: StudentProfileData,
        mode: ProcessingMode,
        serverUrl: String?
    ): String? {
        if (mode == ProcessingMode.PHONE) return null
        if (serverUrl == null) return getString(R.string.server_unreachable)
        if (!runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)) {
            return getString(R.string.server_unreachable)
        }
        return runCatching {
            val photos = localStore.facePhotosForServerUpload(profile.studentId)
            if (photos.isEmpty()) {
                val encodedId = URLEncoder.encode(
                    profile.studentId,
                    StandardCharsets.UTF_8.toString()
                ).replace("+", "%20")
                deleteJson("$serverUrl/students/$encodedId/recognition")
            } else {
                registerOnServer(
                    serverUrl,
                    profile.studentId,
                    profile.name,
                    profile.sex,
                    photos,
                    replace = true,
                    allowExistingStudent = true
                )
            }
        }.exceptionOrNull()?.message
    }

    private fun refreshedLocalStudentProfile(fallback: StudentProfileData): StudentProfileData {
        val local = localStore.listStudents().firstOrNull {
            it.studentId == fallback.studentId
        } ?: return fallback
        return StudentProfileData(
            studentId = local.studentId,
            name = local.name,
            sex = local.sex,
            embeddingCount = local.embeddingCount,
            photoJpeg = local.photoJpeg
        )
    }

    private fun replaceCurrentStudentProfile(updated: StudentProfileData) {
        currentStudentProfiles = currentStudentProfiles.map { profile ->
            if (profile.studentId == updated.studentId) updated else profile
        }
    }

    private fun showGalleryProcessingDialog(photoCount: Int): GalleryProcessingUi {
        val content = layoutInflater.inflate(R.layout.dialog_gallery_face_processing, null)
        val progress = content.findViewById<ProgressBar>(R.id.galleryProcessingProgress)
        val detail = content.findViewById<TextView>(R.id.galleryProcessingDetailText)
        progress.max = photoCount
        progress.progress = 0
        detail.text = getString(R.string.gallery_processing_preparing)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.gallery_processing_title)
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        return GalleryProcessingUi(dialog, progress, detail)
    }

    private fun updateGalleryProcessing(
        processingUi: GalleryProcessingUi,
        completedPhotos: Int,
        message: String
    ) {
        runOnUiThread {
            processingUi.progress.progress = completedPhotos
            processingUi.detail.text = message
        }
    }

    private fun readGalleryImage(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException(getString(R.string.gallery_image_unavailable))
        input.use { stream ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_GALLERY_IMAGE_BYTES) {
                        throw IOException(getString(R.string.gallery_image_too_large))
                    }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        }
    }

    private fun galleryImageName(uri: Uri): String {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    }

    private fun confirmDeleteStudent(profile: StudentProfileData) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_student_title)
            .setMessage(
                getString(
                    R.string.delete_student_message,
                    profile.name,
                    profile.studentId
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteStudentPermanently(profile)
            }
            .show()
    }

    private fun deleteStudentPermanently(profile: StudentProfileData) {
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        runNetworkRequest {
            var localName: String? = null
            var serverName: String? = null
            var serverWarning: String? = null
            if (mode != ProcessingMode.SERVER) {
                localName = localStore.deleteStudent(profile.studentId)
            }
            if (mode != ProcessingMode.PHONE && serverUrl != null) {
                val serverAvailable = mode == ProcessingMode.SERVER ||
                    runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
                if (serverAvailable) {
                    try {
                        val encodedId = URLEncoder.encode(
                            profile.studentId,
                            StandardCharsets.UTF_8.toString()
                        ).replace("+", "%20")
                        val result = deleteJson("$serverUrl/students/$encodedId")
                        serverName = result.optString("name", profile.name)
                    } catch (error: Exception) {
                        if (mode == ProcessingMode.SERVER) throw error
                        serverWarning = error.message
                    }
                } else {
                    serverWarning = getString(R.string.server_unreachable)
                }
            }
            if (mode == ProcessingMode.SERVER && serverName != null) {
                localName = localStore.deleteStudent(profile.studentId)
            }
            val name = localName ?: serverName
                ?: throw IOException("No existe el estudiante ${profile.studentId}.")
            runOnUiThread {
                currentStudentProfiles = currentStudentProfiles.filterNot {
                    it.studentId == profile.studentId
                }
                renderStudentTable(currentStudentProfiles)
                showStatus(getString(R.string.student_deleted, name))
                appendHistory("Estudiante eliminado: ${profile.studentId} | $name")
                serverWarning?.let { appendHistory("Servidor no actualizado: $it") }
            }
        }
    }

    private fun saveStudentProfile(studentId: String, name: String, sex: String) {
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        runNetworkRequest {
            var updated = false
            var serverWarning: String? = null
            if (mode != ProcessingMode.SERVER) {
                updated = localStore.updateStudentProfile(studentId, name, sex) != null
            }
            if (mode != ProcessingMode.PHONE && serverUrl != null) {
                val serverAvailable = mode == ProcessingMode.SERVER ||
                    runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
                if (serverAvailable) {
                    try {
                        val encodedId = URLEncoder.encode(
                            studentId,
                            StandardCharsets.UTF_8.toString()
                        ).replace("+", "%20")
                        patchJson(
                            "$serverUrl/students/$encodedId",
                            JSONObject().put("name", name).put("sex", sex)
                        )
                        updated = true
                    } catch (error: Exception) {
                        if (mode == ProcessingMode.SERVER || !updated) throw error
                        serverWarning = error.message
                    }
                }
            }
            if (!updated) {
                throw IOException("No existe un estudiante con el carnet $studentId.")
            }
            runOnUiThread {
                currentStudentProfiles = currentStudentProfiles.map { profile ->
                    if (profile.studentId == studentId) {
                        profile.copy(name = name, sex = sex)
                    } else {
                        profile
                    }
                }
                renderStudentTable(currentStudentProfiles)
                showStatus(getString(R.string.student_profile_updated, name))
                appendHistory("Perfil actualizado: $studentId | $name | ${studentSexLabel(sex)}")
                serverWarning?.let { appendHistory("Servidor no actualizado: $it") }
            }
        }
    }

    private fun studentSexLabel(sex: String): String {
        return when (sex) {
            SEX_MALE -> getString(R.string.sex_male)
            SEX_FEMALE -> getString(R.string.sex_female)
            else -> getString(R.string.sex_unspecified)
        }
    }

    private fun deleteRecognition(studentId: String) {
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        runNetworkRequest {
            var localName: String? = null
            var serverName: String? = null
            if (mode != ProcessingMode.SERVER) {
                localName = localStore.clearRecognition(studentId)
            }
            if (mode != ProcessingMode.PHONE && serverUrl != null) {
                val serverAvailable = mode == ProcessingMode.SERVER ||
                    runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
                if (serverAvailable) {
                    try {
                        val result = deleteJson("$serverUrl/students/$studentId/recognition")
                        serverName = result.optString("name", studentId)
                    } catch (error: Exception) {
                        if (mode == ProcessingMode.SERVER) throw error
                    }
                }
            }
            val name = localName ?: serverName
                ?: throw IOException("No existe reconocimiento para el carnet $studentId.")
            runOnUiThread {
                currentStudentProfiles = currentStudentProfiles.map { profile ->
                    if (profile.studentId == studentId) {
                        profile.copy(embeddingCount = 0, photoJpeg = null)
                    } else {
                        profile
                    }
                }
                renderStudentTable(currentStudentProfiles)
                val message = if (localName != null && serverName == null) {
                    getString(R.string.local_recognition_deleted, name)
                } else {
                    getString(R.string.recognition_deleted, name)
                }
                showStatus(message)
                appendHistory("Reconocimiento eliminado: $studentId | $name")
            }
        }
    }

    private fun syncConfiguredServerStudents() {
        val serverUrl = normalizedServerUrl() ?: return
        runNetworkRequest {
            if (!isHealthyServer(serverUrl)) {
                throw ConnectException(getString(R.string.server_unreachable))
            }
            setProcessing(true, getString(R.string.syncing_students))
            val result = synchronizeServerStudents(serverUrl, force = true)
            runOnUiThread {
                val message = getString(
                    R.string.students_synced,
                    result.imported,
                    result.unchanged,
                    result.failed
                )
                showStatus(message)
                appendHistory(message)
            }
        }
    }

    private fun confirmLocalStudentsUpload() {
        val students = runCatching { localStore.listStudentsForServerUpload() }
            .getOrElse { error ->
                showStatus(error.message ?: getString(R.string.upload_students_failed))
                return
            }
        val eligible = students.count {
            it.referenceCount > 0 && (it.sex == SEX_MALE || it.sex == SEX_FEMALE)
        }
        if (students.isEmpty()) {
            showStatus(getString(R.string.no_local_students_to_upload))
            return
        }
        if (eligible == 0) {
            showStatus(getString(R.string.no_valid_local_students_to_upload))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.upload_students_title)
            .setMessage(
                getString(
                    R.string.upload_students_confirmation,
                    eligible,
                    students.size
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.upload_to_server) { _, _ ->
                uploadLocalStudentsToServer()
            }
            .show()
    }

    private fun uploadLocalStudentsToServer() {
        val serverUrl = normalizedServerUrl() ?: return
        runNetworkRequest {
            if (!isHealthyServer(serverUrl)) {
                throw ConnectException(getString(R.string.server_unreachable))
            }
            val students = localStore.listStudentsForServerUpload()
            var uploaded = 0
            var skipped = 0
            var failed = 0

            students.forEachIndexed { index, student ->
                if (
                    student.referenceCount == 0 ||
                    (student.sex != SEX_MALE && student.sex != SEX_FEMALE)
                ) {
                    skipped++
                    return@forEachIndexed
                }
                showStatus(
                    getString(
                        R.string.upload_students_progress,
                        index + 1,
                        students.size,
                        student.name
                    )
                )
                try {
                    val photos = localStore.facePhotosForServerUpload(student.studentId)
                    if (photos.isEmpty()) {
                        skipped++
                        return@forEachIndexed
                    }
                    registerOnServer(
                        serverUrl,
                        student.studentId,
                        student.name,
                        student.sex,
                        photos,
                        replace = false,
                        allowExistingStudent = true
                    )
                    uploaded++
                } catch (error: Exception) {
                    failed++
                    Log.w(
                        LOG_TAG,
                        "No se pudo subir el registro local de ${student.studentId}.",
                        error
                    )
                }
            }

            val result = ServerStudentUploadResult(uploaded, skipped, failed)
            runOnUiThread {
                val message = getString(
                    R.string.students_uploaded,
                    result.uploaded,
                    result.skipped,
                    result.failed
                )
                showStatus(message)
                appendHistory(message)
            }
        }
    }

    private fun synchronizeServerStudents(
        serverUrl: String,
        force: Boolean
    ): ServerStudentSyncResult {
        if (!isStudentSyncing.compareAndSet(false, true)) {
            return ServerStudentSyncResult(0, 0, 0)
        }
        try {
            val students = getJsonArray("$serverUrl/students")
            var imported = 0
            var unchanged = 0
            var failed = 0
            for (index in 0 until students.length()) {
                val student = students.optJSONObject(index) ?: continue
                val studentId = student.optString("student_id").trim()
                val name = student.optString("name").trim()
                val sex = student.optString("sex", SEX_UNSPECIFIED).trim()
                val photoSha256 = if (student.isNull("photo_sha256")) {
                    ""
                } else {
                    student.optString("photo_sha256").trim()
                }
                val hasPhoto = student.optInt("has_photo", 0) == 1
                if (studentId.isBlank() || name.isBlank()) {
                    failed++
                    continue
                }
                if (localStore.isStudentPermanentlyDeleted(studentId)) {
                    unchanged++
                    continue
                }
                if (sex == SEX_MALE || sex == SEX_FEMALE) {
                    localStore.updateStudentSex(studentId, sex)
                }
                if (photoSha256.isBlank() || !hasPhoto) {
                    unchanged++
                    continue
                }
                if (!force && !localStore.needsServerImport(studentId, photoSha256)) {
                    unchanged++
                    continue
                }

                try {
                    val encodedId = URLEncoder.encode(
                        studentId,
                        StandardCharsets.UTF_8.toString()
                    ).replace("+", "%20")
                    val photo = getBytes("$serverUrl/students/$encodedId/photo")
                    val extraction = extractLocalFaces(photo)
                    if (extraction.samples.size != 1) {
                        throw IOException(
                            "La fotografia almacenada debe contener exactamente un rostro."
                        )
                    }
                    localStore.importServerStudent(
                        studentId,
                        name,
                        sex,
                        extraction.samples.single().embedding,
                        photo,
                        photoSha256
                    )
                    imported++
                } catch (error: Exception) {
                    failed++
                    Log.w(LOG_TAG, "No se pudo importar el rostro de $studentId.", error)
                }
            }
            return ServerStudentSyncResult(imported, unchanged, failed)
        } finally {
            isStudentSyncing.set(false)
        }
    }

    private fun refreshAttendanceHistory() {
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        runNetworkRequest {
            val local = if (mode == ProcessingMode.SERVER) {
                emptyList()
            } else {
                localStore.attendanceSummaries().map {
                    AttendanceSummaryData(
                        it.course,
                        it.attendanceDate,
                        it.total,
                        it.male,
                        it.female,
                        it.unspecified
                    )
                }
            }
            val summaries = when (mode) {
                ProcessingMode.PHONE -> local
                ProcessingMode.SERVER -> loadServerAttendanceSummaries(serverUrl!!)
                ProcessingMode.AUTOMATIC -> {
                    if (local.isNotEmpty()) {
                        local
                    } else if (
                        serverUrl != null &&
                        runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
                    ) {
                        runCatching { loadServerAttendanceSummaries(serverUrl) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }
            }
            runOnUiThread {
                renderAttendanceHistory(summaries)
                showStatus(getString(R.string.attendance_lists_count, summaries.size))
            }
        }
    }

    private fun loadServerAttendanceSummaries(serverUrl: String): List<AttendanceSummaryData> {
        val response = getJsonArray("$serverUrl/attendance/summaries")
        return buildList {
            for (index in 0 until response.length()) {
                val item = response.optJSONObject(index) ?: continue
                add(
                    AttendanceSummaryData(
                        course = item.optString("course"),
                        attendanceDate = item.optString("attendance_date"),
                        total = item.optInt("total"),
                        male = item.optInt("male"),
                        female = item.optInt("female"),
                        unspecified = item.optInt("unspecified")
                    )
                )
            }
        }
    }

    private fun renderAttendanceHistory(summaries: List<AttendanceSummaryData>) {
        historyTotalListsText.text = getString(
            R.string.attendance_lists_count,
            summaries.size
        )
        historyTotalStudentsText.text = getString(
            R.string.stat_total,
            summaries.sumOf { it.total }
        )
        historyMaleText.text = getString(R.string.stat_male, summaries.sumOf { it.male })
        historyFemaleText.text = getString(
            R.string.stat_female,
            summaries.sumOf { it.female }
        )
        attendanceHistoryContainer.removeAllViews()
        if (summaries.isEmpty()) {
            attendanceHistoryContainer.addView(TextView(this).apply {
                text = getString(R.string.no_attendance_history)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(28))
            })
            return
        }

        summaries.forEach { summary ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(R.drawable.panel_background)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    showAttendanceReport(summary.course, summary.attendanceDate)
                }
                addView(TextView(context).apply {
                    text = getString(
                        R.string.attendance_history_row,
                        summary.course,
                        summary.attendanceDate,
                        summary.total
                    )
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = getString(
                        R.string.attendance_history_stats,
                        summary.male,
                        summary.female
                    )
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(6), 0, 0)
                })
            }
            attendanceHistoryContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
        }
    }

    private fun recognizeAttendance() {
        val mode = selectedProcessingMode()
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        val course = classSpinner.selectedItem.toString()
        val attendanceDate = attendanceDateEditText.text.toString().trim()
        if (runCatching { LocalDate.parse(attendanceDate) }.isFailure) {
            showStatus("Selecciona una fecha de asistencia valida.")
            return
        }
        val selection = AttendanceSelection(course, attendanceDate, mode, serverUrl)
        captureAttendance(selection)
    }

    private fun captureAttendance(selection: AttendanceSelection) {
        captureAndRunNetwork { jpeg ->
            when (selection.mode) {
                ProcessingMode.PHONE -> {
                    showLocalRecognitionResult(
                        recognizeLocally(selection, jpeg)
                    )
                }
                ProcessingMode.SERVER -> {
                    showRecognitionResult(
                        recognizeOnServer(selection, jpeg)
                    )
                }
                ProcessingMode.AUTOMATIC -> {
                    val useServer =
                        selection.serverUrl != null &&
                        runCatching { isHealthyServer(selection.serverUrl) }.getOrDefault(false)
                    if (useServer) {
                        val serverResult = runCatching {
                            recognizeOnServer(selection, jpeg, verifyProfile = false)
                        }
                        if (serverResult.isSuccess) {
                            showRecognitionResult(serverResult.getOrThrow())
                        } else {
                            Log.w(
                                LOG_TAG,
                                "El motor del servidor fallo; se usa el motor local.",
                                serverResult.exceptionOrNull()
                            )
                            showLocalRecognitionResult(recognizeLocally(selection, jpeg))
                        }
                    } else {
                        showLocalRecognitionResult(recognizeLocally(selection, jpeg))
                    }
                }
            }
        }
    }

    private fun showAttendanceReport() {
        val course = classSpinner.selectedItem.toString()
        val attendanceDate = attendanceDateEditText.text.toString().trim()
        showAttendanceReport(course, attendanceDate)
    }

    private fun showAttendanceReport(course: String, attendanceDate: String) {
        if (runCatching { LocalDate.parse(attendanceDate) }.isFailure) {
            showStatus("Selecciona una fecha de asistencia valida.")
            return
        }
        val mode = selectedProcessingMode()
        if (mode != ProcessingMode.SERVER) {
            val report = localStore.attendanceReport(course, attendanceDate)
            if (report.records.isNotEmpty() || mode == ProcessingMode.PHONE) {
                showAttendanceReportDialog(
                    AttendanceReportData(
                        report.course,
                        report.attendanceDate,
                        report.records.map {
                            AttendanceListItem(it.studentId, it.studentName, it.sex)
                        }
                    )
                )
                return
            }
        }

        val serverUrl = serverUrlFor(mode)
        if (serverUrl == null) {
            showAttendanceReportDialog(
                AttendanceReportData(course, attendanceDate, emptyList())
            )
            return
        }
        runNetworkRequest {
            if (
                mode == ProcessingMode.AUTOMATIC &&
                !runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
            ) {
                runOnUiThread {
                    showAttendanceReportDialog(
                        AttendanceReportData(course, attendanceDate, emptyList())
                    )
                }
                return@runNetworkRequest
            }
            val query = attendanceQuery(course, attendanceDate)
            val result = getJsonObject("$serverUrl/attendance/report?$query")
            val attendance = result.getJSONArray("attendance")
            val students = buildList {
                for (index in 0 until attendance.length()) {
                    val item = attendance.getJSONObject(index)
                    add(
                        AttendanceListItem(
                            item.getString("student_id"),
                            item.getString("name"),
                            item.optString("sex", SEX_UNSPECIFIED)
                        )
                    )
                }
            }
            runOnUiThread {
                showAttendanceReportDialog(
                    AttendanceReportData(course, attendanceDate, students)
                )
            }
        }
    }

    private fun showAttendanceReportDialog(report: AttendanceReportData) {
        val male = report.students.filter { it.sex == SEX_MALE }
        val female = report.students.filter { it.sex == SEX_FEMALE }
        val unspecified = report.students.filter { it.sex == SEX_UNSPECIFIED }
        val body = buildString {
            appendLine("${report.course} | ${report.attendanceDate}")
            appendLine("Total: ${report.students.size}")
            appendLine("Masculino: ${male.size}")
            appendLine("Femenino: ${female.size}")
            if (unspecified.isNotEmpty()) {
                appendLine("Sin especificar: ${unspecified.size}")
            }
            if (report.students.isEmpty()) {
                appendLine()
                append(getString(R.string.attendance_empty))
                return@buildString
            }
            appendAttendanceGroup("MASCULINO", male)
            appendAttendanceGroup("FEMENINO", female)
            if (unspecified.isNotEmpty()) {
                appendAttendanceGroup("SIN ESPECIFICAR", unspecified)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.attendance_report_title)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun StringBuilder.appendAttendanceGroup(
        title: String,
        students: List<AttendanceListItem>
    ) {
        appendLine()
        appendLine("$title (${students.size})")
        students.sortedBy { it.name }.forEachIndexed { index, student ->
            append("${index + 1}. ${student.name} | ${student.studentId}")
            if (index != students.lastIndex) appendLine()
        }
        appendLine()
    }

    private fun finishCurrentSession() {
        val mode = selectedProcessingMode()
        val localSessionId = activeLocalSessionId
        val serverSessionId = activeSessionId
        val hasRelevantSession = when (mode) {
            ProcessingMode.PHONE -> localSessionId != null
            ProcessingMode.SERVER -> serverSessionId != null
            ProcessingMode.AUTOMATIC -> localSessionId != null || serverSessionId != null
        }
        if (!hasRelevantSession) {
            showStatus(getString(R.string.no_active_session))
            return
        }
        val serverUrl = serverUrlFor(mode) ?: if (mode == ProcessingMode.SERVER) return else null
        runNetworkRequest {
            var localFinished = false
            var serverFinished = false
            if (mode != ProcessingMode.SERVER && localSessionId != null) {
                localFinished = localStore.finishSession(localSessionId)
                if (localFinished) activeLocalSessionId = null
            }
            if (mode != ProcessingMode.PHONE && serverSessionId != null && serverUrl != null) {
                val serverAvailable = mode == ProcessingMode.SERVER ||
                    runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
                if (serverAvailable) {
                    postJson("$serverUrl/sessions/$serverSessionId/finish", JSONObject())
                    activeSessionId = null
                    serverFinished = true
                }
            }
            runOnUiThread {
                val message = if (
                    serverSessionId != null && !serverFinished && localFinished
                ) {
                    "Asistencia local finalizada; el servidor queda pendiente."
                } else {
                    getString(R.string.session_finished)
                }
                showStatus(message)
                if (localFinished) {
                    appendHistory("Asistencia local $localSessionId finalizada")
                }
                if (serverFinished) {
                    appendHistory("Asistencia del servidor $serverSessionId finalizada")
                }
            }
        }
    }

    private fun registerOnServer(
        serverUrl: String,
        studentId: String,
        name: String,
        sex: String,
        jpeg: ByteArray
    ) {
        registerOnServer(
            serverUrl,
            studentId,
            name,
            sex,
            listOf(jpeg),
            replace = false,
            allowExistingStudent = false
        )
    }

    private fun registerOnServer(
        serverUrl: String,
        studentId: String,
        name: String,
        sex: String,
        jpegs: List<ByteArray>,
        replace: Boolean = true,
        allowExistingStudent: Boolean = false
    ) {
        postMultipartFiles(
            "$serverUrl/students/register",
            mapOf(
                "student_id" to studentId,
                "name" to name,
                "sex" to sex,
                "replace_embeddings" to replace.toString(),
                "allow_existing_student" to allowExistingStudent.toString()
            ),
            "images",
            jpegs
        )
    }

    private fun recognizeOnServer(
        selection: AttendanceSelection,
        jpeg: ByteArray,
        verifyProfile: Boolean = true
    ): JSONObject {
        val serverUrl = requireNotNull(selection.serverUrl)
        if (verifyProfile && !isHealthyServer(serverUrl)) {
            throw IOException(
                "El servidor no esta disponible o usa un perfil facial incompatible."
            )
        }
        val created = postJson(
            "$serverUrl/sessions",
            JSONObject()
                .put("course", selection.course)
                .put("attendance_date", selection.attendanceDate)
        )
        val sessionId = created.getInt("session_id")
        activeSessionId = sessionId
        return postMultipart(
            "$serverUrl/sessions/$sessionId/recognize",
            emptyMap(),
            "image",
            jpeg
        )
    }

    private fun recognizeLocally(
        selection: AttendanceSelection,
        jpeg: ByteArray
    ): LocalRecognitionBatch {
        setProcessing(true, getString(R.string.local_processing))
        val extraction = extractLocalFaces(jpeg)
        return commitLocalRecognition(selection, extraction)
    }

    private fun commitLocalRecognition(
        selection: AttendanceSelection,
        extraction: LocalFaceExtraction
    ): LocalRecognitionBatch {
        val sessionId = localStore.createSession(
            selection.course,
            selection.attendanceDate
        )
        activeLocalSessionId = sessionId
        return localStore.recognize(
            sessionId,
            extraction.samples,
            extraction.bitmap.width,
            extraction.bitmap.height
        )
    }

    private fun extractLocalFaces(jpeg: ByteArray): LocalFaceExtraction {
        val bitmap = decodeOrientedBitmap(jpeg)
            ?: throw IOException("No se pudo decodificar la fotografia.")
        val analysisBitmap = scaleBitmapForFaceAnalysis(bitmap, 2_048)
        return extractLocalFacesFromBitmap(
            analysisBitmap,
            galleryFaceDetector,
            minimumFaceRatio = 0.02f,
            minimumFacePixels = 48
        )
    }

    private fun extractGalleryFaceReference(
        jpeg: ByteArray,
        onVerificationPass: () -> Unit
    ): LocalFaceExtraction {
        val bitmap = decodeOrientedBitmap(jpeg)
            ?: throw IOException("No se pudo decodificar la fotografia.")
        val initial = extractLocalFacesFromBitmap(
            bitmap,
            localFaceDetector,
            minimumFaceRatio = 0.04f,
            minimumFacePixels = 40
        )

        onVerificationPass()
        val normalizedBitmap = scaleBitmapForFaceAnalysis(bitmap, 1_600)
        val verification = extractLocalFacesFromBitmap(
            normalizedBitmap,
            galleryFaceDetector,
            minimumFaceRatio = 0.02f,
            minimumFacePixels = 32
        )
        Log.d(
            LOG_TAG,
            "Analisis de galeria: primer pase=${initial.samples.size}, " +
                "verificacion=${verification.samples.size}, " +
                "imagen=${bitmap.width}x${bitmap.height}."
        )

        return when {
            initial.samples.size == 1 -> initial
            initial.samples.size > 1 -> initial
            else -> verification
        }
    }

    private fun extractLocalFacesFromBitmap(
        bitmap: Bitmap,
        detector: FaceDetector,
        minimumFaceRatio: Float,
        minimumFacePixels: Int
    ): LocalFaceExtraction {
        val detectedFaces = Tasks.await(
            detector.process(InputImage.fromBitmap(bitmap, 0)),
            30,
            TimeUnit.SECONDS
        )
        val uniqueFaces = removeDuplicateFaceDetections(detectedFaces)
        val minimumFaceSize = (minOf(bitmap.width, bitmap.height) * minimumFaceRatio)
            .toInt()
            .coerceAtLeast(minimumFacePixels)
        val validFaces = uniqueFaces
            .filter {
                it.boundingBox.width() >= minimumFaceSize &&
                    it.boundingBox.height() >= minimumFaceSize
            }
        val samples = validFaces.map { localRecognizer.extract(bitmap, it) }
        Log.d(
            LOG_TAG,
            "Cajas faciales: detectadas=${detectedFaces.size}, " +
                "unicas=${uniqueFaces.size}, validas=${samples.size}, " +
                "limite=${minimumFaceSize}px, " +
                "coordenadas=${uniqueFaces.joinToString { it.boundingBox.toShortString() }}"
        )
        return LocalFaceExtraction(bitmap, samples, validFaces)
    }

    private fun removeDuplicateFaceDetections(faces: List<Face>): List<Face> {
        val accepted = mutableListOf<Face>()
        faces.sortedByDescending { faceArea(it.boundingBox) }.forEach { candidate ->
            val duplicated = accepted.any { kept ->
                overlapOverSmallerFace(candidate.boundingBox, kept.boundingBox) >= 0.75f
            }
            if (!duplicated) accepted += candidate
        }
        return accepted
    }

    private fun faceArea(bounds: Rect): Long {
        return bounds.width().coerceAtLeast(0).toLong() *
            bounds.height().coerceAtLeast(0).toLong()
    }

    private fun overlapOverSmallerFace(first: Rect, second: Rect): Float {
        val intersectionWidth = (
            minOf(first.right, second.right) - maxOf(first.left, second.left)
        ).coerceAtLeast(0)
        val intersectionHeight = (
            minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        ).coerceAtLeast(0)
        val intersection = intersectionWidth.toLong() * intersectionHeight.toLong()
        val smallerArea = minOf(faceArea(first), faceArea(second))
        if (smallerArea <= 0L) return 0f
        return intersection.toFloat() / smallerArea
    }

    private fun scaleBitmapForFaceAnalysis(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        if (largestDimension <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largestDimension
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun showLocalRecognitionResult(result: LocalRecognitionBatch) {
        val lines = mutableListOf<String>()
        val overlayBoxes = result.faces.map { face ->
            val label = if (face.recognized) {
                "${face.studentName} | ${face.studentId}"
            } else {
                "No identificado"
            }
            if (face.recognized) {
                val attendanceState = if (face.attendanceRecorded) {
                    "asistencia registrada"
                } else {
                    "ya registrado"
                }
                lines += "${face.studentName} | ${String.format(Locale.US, "%.2f", face.similarity)} | $attendanceState"
            } else {
                lines += "Rostro no identificado"
            }
            FaceOverlayBox(
                Rect(face.bounds),
                if (face.recognized) FaceBoxState.RECOGNIZED else FaceBoxState.UNKNOWN,
                label
            )
        }
        if (lines.isEmpty()) lines += "No se detectaron rostros en la captura"
        runOnUiThread {
            countText.text = getString(R.string.people_count, result.faces.size)
            showStatus("Modo telefono | Asistencia acumulada: ${result.attendanceCount}")
            lines.reversed().forEach(::appendHistory)
            showAnalyzedPhoto(overlayBoxes, result.imageWidth, result.imageHeight)
        }
    }

    private fun showRecognitionResult(result: JSONObject) {
        val faces = result.getJSONArray("faces")
        val lines = mutableListOf<String>()
        val overlayBoxes = mutableListOf<FaceOverlayBox>()
        for (index in 0 until faces.length()) {
            val face = faces.getJSONObject(index)
            val recognized = face.getBoolean("recognized")
            val box = face.getJSONObject("box")
            val studentId = face.optString("student_id")
            val name = face.optString("student_name", studentId)
            overlayBoxes += FaceOverlayBox(
                bounds = Rect(
                    box.getInt("x1"),
                    box.getInt("y1"),
                    box.getInt("x2"),
                    box.getInt("y2")
                ),
                state = if (recognized) {
                    FaceBoxState.RECOGNIZED
                } else {
                    FaceBoxState.UNKNOWN
                },
                label = if (recognized) "$name | $studentId" else "No identificado"
            )
            if (recognized) {
                val similarity = face.getDouble("similarity")
                val state = if (face.getBoolean("attendance_recorded")) {
                    "asistencia registrada"
                } else {
                    "ya registrado"
                }
                lines += "$name | ${String.format(Locale.US, "%.2f", similarity)} | $state"
            } else {
                lines += "Rostro no identificado"
            }
        }
        if (lines.isEmpty()) lines += "No se detectaron rostros en la captura"
        val attendanceCount = result.getInt("attendance_count")
        val imageWidth = result.getInt("image_width")
        val imageHeight = result.getInt("image_height")
        runOnUiThread {
            countText.text = getString(R.string.people_count, faces.length())
            showStatus("Asistencia acumulada: $attendanceCount")
            lines.reversed().forEach(::appendHistory)
            showAnalyzedPhoto(
                overlayBoxes,
                imageWidth,
                imageHeight
            )
        }
    }

    private fun showAnalyzedPhoto(
        boxes: List<FaceOverlayBox>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        faceOverlay.removeCallbacks(hideRecognitionResult)
        isShowingRecognitionResult.set(true)
        faceOverlay.setResults(boxes, imageWidth, imageHeight)
        faceOverlay.postDelayed(hideRecognitionResult, 5_000L)
    }

    private fun showFrozenPhoto(bitmap: Bitmap?) {
        runOnUiThread {
            faceOverlay.removeCallbacks(hideRecognitionResult)
            faceOverlay.clear()
            isShowingRecognitionResult.set(true)
            if (bitmap != null) {
                capturedImageView.setImageBitmap(bitmap)
                capturedImageView.visibility = View.VISIBLE
            }
        }
    }

    private fun scheduleReturnToLive(delayMillis: Long) {
        runOnUiThread {
            faceOverlay.removeCallbacks(hideRecognitionResult)
            faceOverlay.postDelayed(hideRecognitionResult, delayMillis)
        }
    }

    private fun decodeOrientedBitmap(jpeg: ByteArray): Bitmap? {
        return runCatching {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val orientation = ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) {
                bitmap
            } else {
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    Matrix().apply { postRotate(degrees) },
                    true
                )
            }
        }.getOrNull()
    }

    private fun runNetworkRequest(block: () -> Unit) {
        if (!isNetworkBusy.compareAndSet(false, true)) {
            showStatus(getString(R.string.network_busy))
            return
        }
        setProcessing(true, "Procesando solicitud...")
        networkExecutor.execute {
            try {
                block()
            } catch (error: Exception) {
                showStatus(networkErrorMessage(error))
            } finally {
                isNetworkBusy.set(false)
                setProcessing(false)
            }
        }
    }

    private fun captureAndRunNetwork(block: (ByteArray) -> Unit) {
        if (!isNetworkBusy.compareAndSet(false, true)) {
            showStatus(getString(R.string.network_busy))
            return
        }
        setProcessing(true, "Capturando fotografia en alta resolucion...")
        capturePhoto(
            onSuccess = { jpeg ->
                showFrozenPhoto(decodeOrientedBitmap(jpeg))
                setProcessing(true, "Fotografia congelada. Analizando con la IA...")
                networkExecutor.execute {
                    try {
                        block(jpeg)
                    } catch (error: Exception) {
                        showStatus(networkErrorMessage(error))
                        scheduleReturnToLive(2_500L)
                    } finally {
                        isNetworkBusy.set(false)
                        setProcessing(false)
                    }
                }
            },
            onError = { message ->
                isNetworkBusy.set(false)
                setProcessing(false)
                scheduleReturnToLive(0L)
                showStatus(message)
            }
        )
    }

    private fun capturePhoto(
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!::imageCapture.isInitialized) {
            showStatus(getString(R.string.capture_not_ready))
            onError(getString(R.string.capture_not_ready))
            return
        }

        val photoFile = try {
            File.createTempFile("asistencia_", ".jpg", cacheDir)
        } catch (error: IOException) {
            onError(error.message ?: "No se pudo preparar la fotografia.")
            return
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        onSuccess(photoFile.readBytes())
                    } catch (error: IOException) {
                        onError(error.message ?: "No se pudo leer la fotografia.")
                    } finally {
                        photoFile.delete()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    photoFile.delete()
                    onError(exception.message ?: "No se pudo tomar la fotografia.")
                }
            }
        )
    }

    private fun setProcessing(active: Boolean, message: String? = null) {
        runOnUiThread {
            analysisProgress.visibility = if (active) View.VISIBLE else View.GONE
            attendanceFullscreenCaptureButton.isEnabled = !active
            attendanceFullscreenCaptureButton.alpha = if (active) 0.55f else 1f
            if (message != null) statusText.text = message
        }
    }

    private fun normalizedServerUrl(): String? {
        if (!authStore.isAdministrator()) {
            showStatus(getString(R.string.server_access_restricted))
            return null
        }
        val value = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        if (!value.startsWith("https://")) {
            showStatus("El servidor permanente debe utilizar HTTPS.")
            return null
        }
        return value
    }

    private fun selectedProcessingMode(): ProcessingMode {
        if (!authStore.isAdministrator()) {
            if (processingModeSpinner.selectedItemPosition != ProcessingMode.PHONE.ordinal) {
                runOnUiThread {
                    processingModeSpinner.setSelection(ProcessingMode.PHONE.ordinal)
                }
            }
            return ProcessingMode.PHONE
        }
        val mode = ProcessingMode.entries.getOrElse(processingModeSpinner.selectedItemPosition) {
            ProcessingMode.AUTOMATIC
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PROCESSING_MODE_KEY, mode.ordinal)
            .apply()
        return mode
    }

    private fun serverUrlFor(mode: ProcessingMode): String? {
        if (mode == ProcessingMode.PHONE) return null
        if (!authStore.isAdministrator()) {
            showStatus(getString(R.string.server_access_restricted))
            return null
        }
        return normalizedServerUrl()
    }

    private fun networkErrorMessage(error: Exception): String {
        return when (error) {
            is ConnectException, is SocketTimeoutException -> {
                connectFixedApiServer(showResult = true)
                getString(R.string.server_unreachable)
            }
            else -> error.message ?: getString(R.string.server_unreachable)
        }
    }

    private fun connectFixedApiServer(showResult: Boolean) {
        if (!::networkExecutor.isInitialized || networkExecutor.isShutdown) return
        if (!authStore.isAdministrator()) {
            if (showResult) showStatus(getString(R.string.server_access_restricted))
            return
        }
        networkExecutor.execute {
            val serverUrl = BuildConfig.API_BASE_URL.trim().trimEnd('/')
            val available = runCatching { isHealthyServer(serverUrl) }.getOrDefault(false)
            if (available) {
                runOnUiThread {
                    if (showResult) {
                        showStatus(getString(R.string.server_connected, serverUrl))
                    }
                }
                val syncResult = runCatching {
                    synchronizeServerStudents(serverUrl, force = false)
                }.onFailure { error ->
                    Log.w(LOG_TAG, "No se pudieron migrar los registros antiguos.", error)
                }.getOrNull()
                if (syncResult != null && syncResult.imported > 0) {
                    runOnUiThread {
                        val message = getString(
                            R.string.automatic_students_synced,
                            syncResult.imported
                        )
                        showStatus(message)
                        appendHistory(message)
                    }
                }
            } else if (showResult) {
                runOnUiThread { showStatus(getString(R.string.server_unreachable)) }
            }
        }
    }

    private fun isHealthyServer(baseUrl: String): Boolean {
        if (!authStore.isAdministrator()) return false
        val connection = (URL("$baseUrl/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 2_000
            setRequestProperty("Accept", "application/json")
            applyApiAuthentication(this)
        }
        return try {
            if (connection.responseCode !in 200..299) return false
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val health = JSONObject(body)
            health.optString("status") == "ok" &&
                health.optBoolean("storage_encrypted", false) &&
                isCompatibleRecognitionProfile(health.optJSONObject("recognition_profile"))
        } finally {
            connection.disconnect()
        }
    }

    private fun isCompatibleRecognitionProfile(profile: JSONObject?): Boolean {
        if (profile == null) return false
        fun sameValue(name: String, expected: Float): Boolean {
            return abs(profile.optDouble(name, Double.NaN) - expected.toDouble()) < 0.0001
        }
        return profile.optString("profile_id") == RecognitionPolicy.PROFILE_ID &&
            profile.optString("model_sha256").equals(
                RecognitionPolicy.MODEL_SHA256,
                ignoreCase = true
            ) &&
            profile.optInt("input_size") == RecognitionPolicy.INPUT_SIZE &&
            profile.optInt("embedding_size") == RecognitionPolicy.EMBEDDING_SIZE &&
            sameValue("similarity_threshold", RecognitionPolicy.SIMILARITY_THRESHOLD) &&
            sameValue(
                "single_reference_threshold",
                RecognitionPolicy.SINGLE_REFERENCE_THRESHOLD
            ) &&
            sameValue("minimum_score_margin", RecognitionPolicy.MINIMUM_SCORE_MARGIN) &&
            sameValue(
                "minimum_reference_support",
                RecognitionPolicy.MINIMUM_REFERENCE_SUPPORT
            ) &&
            sameValue("second_reference_weight", RecognitionPolicy.SECOND_REFERENCE_WEIGHT)
    }

    private fun postJson(url: String, payload: JSONObject): JSONObject {
        val connection = openConnection(url, "application/json")
        connection.outputStream.use { it.write(payload.toString().toByteArray()) }
        return readJsonResponse(connection)
    }

    private fun patchJson(url: String, payload: JSONObject): JSONObject {
        val connection = openConnection(url, "application/json", method = "PATCH")
        connection.outputStream.use { it.write(payload.toString().toByteArray()) }
        return readJsonResponse(connection)
    }

    private fun deleteJson(url: String): JSONObject {
        val connection = openConnection(
            url,
            "application/json",
            method = "DELETE",
            outputEnabled = false
        )
        return readJsonResponse(connection)
    }

    private fun attendanceQuery(course: String, attendanceDate: String): String {
        val encodedCourse = URLEncoder.encode(
            course,
            StandardCharsets.UTF_8.toString()
        )
        val encodedDate = URLEncoder.encode(
            attendanceDate,
            StandardCharsets.UTF_8.toString()
        )
        return "course=$encodedCourse&attendance_date=$encodedDate"
    }

    private fun getJsonObject(url: String): JSONObject {
        val connection = openConnection(
            url,
            "application/json",
            method = "GET",
            outputEnabled = false
        )
        return readJsonResponse(connection)
    }

    private fun getJsonArray(url: String): JSONArray {
        val connection = openConnection(
            url,
            "application/json",
            method = "GET",
            outputEnabled = false
        )
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw httpError(status, body)
            return JSONArray(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun getBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
            applyApiAuthentication(this)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                throw httpError(status, body)
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun postMultipart(
        url: String,
        fields: Map<String, String>,
        fileField: String,
        jpeg: ByteArray
    ): JSONObject {
        val boundary = "----AsistenciaIA${System.currentTimeMillis()}"
        val connection = openConnection(url, "multipart/form-data; boundary=$boundary")
        DataOutputStream(connection.outputStream).use { output ->
            fields.forEach { (name, value) ->
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                output.write(value.toByteArray(Charsets.UTF_8))
                output.writeBytes("\r\n")
            }
            output.writeBytes("--$boundary\r\n")
            output.writeBytes(
                "Content-Disposition: form-data; name=\"$fileField\"; " +
                    "filename=\"captura.jpg\"\r\n"
            )
            output.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            output.write(jpeg)
            output.writeBytes("\r\n--$boundary--\r\n")
        }
        return readJsonResponse(connection)
    }

    private fun postMultipartFiles(
        url: String,
        fields: Map<String, String>,
        fileField: String,
        jpegs: List<ByteArray>
    ): JSONObject {
        val boundary = "----AsistenciaIA${System.currentTimeMillis()}"
        val connection = openConnection(url, "multipart/form-data; boundary=$boundary")
        DataOutputStream(connection.outputStream).use { output ->
            fields.forEach { (name, value) ->
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                output.write(value.toByteArray(Charsets.UTF_8))
                output.writeBytes("\r\n")
            }
            jpegs.forEachIndexed { index, jpeg ->
                output.writeBytes("--$boundary\r\n")
                output.writeBytes(
                    "Content-Disposition: form-data; name=\"$fileField\"; " +
                        "filename=\"posicion_${index + 1}.jpg\"\r\n"
                )
                output.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                output.write(jpeg)
                output.writeBytes("\r\n")
            }
            output.writeBytes("--$boundary--\r\n")
        }
        return readJsonResponse(connection)
    }

    private fun openConnection(
        url: String,
        contentType: String,
        method: String = "POST",
        outputEnabled: Boolean = true
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 300_000
            doOutput = outputEnabled
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
            applyApiAuthentication(this)
        }
    }

    private fun applyApiAuthentication(connection: HttpURLConnection) {
        if (!authStore.isAdministrator()) {
            throw IOException(getString(R.string.server_access_restricted))
        }
        BuildConfig.API_KEY.takeIf { it.isNotBlank() }?.let { apiKey ->
            connection.setRequestProperty("X-API-Key", apiKey)
        }
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw httpError(status, body)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun httpError(status: Int, body: String): IOException {
        val detail = runCatching { JSONObject(body).optString("detail") }
            .getOrNull()
            .orEmpty()
        return IOException(if (detail.isBlank()) "Error HTTP $status" else detail)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun showStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }

    private fun appendHistory(message: String) {
        val previous = historyText.text.toString()
        historyText.text = listOf(message, previous)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
