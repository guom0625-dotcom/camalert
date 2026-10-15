package com.gom.camalert

import android.app.Application
import android.content.Context
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.KNSDKInitState
import com.kakaomobility.knsdk.guidance.knguidance.KNDriveGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideFrequencyMode
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import java.io.File
import java.util.UUID

/**
 * 카카오내비 SDK 안전운행 모드(목적지 없는 주행).
 * 안내 음성과 경고음은 카카오 것을 그대로 쓰고, 설정에 따라 어떤 안내를 낼지만 고른다.
 */
class KakaoCameraSource(private val app: Application) : CameraSource, KNGuidance_VoiceGuideDelegate {

    @Volatile
    override var status = "카카오내비 연결 중…"
        private set

    @Volatile
    override var muted = false

    private val prefs = Prefs(app)
    private val main = Handler(Looper.getMainLooper())
    private var onStatus: (() -> Unit)? = null
    private var guidance: KNDriveGuidance? = null
    private var active = false
    @Volatile private var speedKmh: Float? = null

    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    override fun start(onStatus: () -> Unit) {
        this.onStatus = onStatus
        active = true

        if (!installed) {
            KNSDK.install(app, File(app.filesDir, "knsdk").absolutePath)
            installed = true
        }
        when (KNSDK.initSt) {
            KNSDKInitState.KNSDKInitState_Initialized -> startGuidance()
            KNSDKInitState.KNSDKInitState_OnInitializing -> Unit // 진행 중인 초기화 콜백에서 시작됨
            else -> initialize()
        }
    }

    override fun stop() {
        active = false
        guidance?.stop()
        guidance = null
        audio.abandonAudioFocusRequest(focus)
    }

    override fun onLocation(location: Location) {
        speedKmh = if (location.hasSpeed()) location.speed * 3.6f else null
    }

    private fun initialize() {
        setStatus("카카오내비 인증 중…")
        KNSDK.initializeWithAppKey(
            BuildConfig.KAKAO_APP_KEY,
            BuildConfig.VERSION_NAME,
            userKey(app),
            null,
            KNLanguageType.KNLanguageType_KOREAN,
        ) { error ->
            main.post {
                if (error != null) {
                    Log.w(TAG, "KNSDK init failed: ${error.code} ${error.msg}")
                    setStatus("카카오내비 인증 실패 (${error.code}) ${error.msg}")
                } else {
                    startGuidance()
                }
            }
        }
    }

    private fun startGuidance() {
        if (!active || guidance != null) return
        val shared = KNSDK.sharedGuidance()
        if (shared == null) {
            setStatus("카카오내비 안내를 시작하지 못했습니다")
            return
        }
        // 모든 단계의 안내를 받아서 거리 설정으로 직접 거름
        shared.safetyFreqModeNormalWay = KNGuideFrequencyMode.KNGuideFrequencyMode_Always
        shared.safetyFreqModeHighWay = KNGuideFrequencyMode.KNGuideFrequencyMode_Always
        shared.voiceGuideDelegate = this
        shared.startWithoutTrip()
        guidance = shared
        setStatus("전방 카메라 감시 중 (카카오내비)")
    }

    override fun shouldPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice, newData: MutableList<ByteArray>): Boolean {
        if (!active || muted) return false
        val safety = voiceGuide.guideObj as? KNSafety
        val play = when (voiceGuide.voiceCode.name) {
            "KNVoiceCode_Safety" -> safety != null && allowed(guidance, safety)
            "KNVoiceCode_SchoolZone" -> prefs.isOn(GuidanceItem.CHILD_ZONE)
            // 과속 경고음
            "KNVoiceCode_Alert", "KNVoiceCode_Alram" -> shouldWarnOverspeed(
                mode = prefs.overspeedMode,
                thresholdPercent = prefs.overspeedPercent,
                isMobileCamera = safety?.code?.name == "KNSafetyCode_MovableSpeedViolationCamera",
                speedKmh = speedKmh,
                limit = (safety as? KNSafety_Camera)?.speedLimit,
            )
            // 길 안내 관련 음성(경로 이탈, 회전 등)은 안전운행 모드에서 쓰지 않음
            else -> false
        }
        if (play) {
            voiceGuide.setVolume(prefs.volume.coerceIn(0, 100) / 100f)
            voiceGuide.setAudioAttribute(attributes)
        }
        return play
    }

    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        audio.requestAudioFocus(focus)
    }

    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        audio.abandonAudioFocusRequest(focus)
    }

    /** 켜 둔 항목이고, 안내 시작 거리 안쪽일 때만 */
    private fun allowed(guidance: KNGuidance, safety: KNSafety): Boolean {
        val item = GuidanceItem.of(safety.code.name) ?: return false
        if (!prefs.isOn(item)) return false

        val here = guidance.locationGuide?.let { it.location ?: it.locationForPrimaryRoute } ?: return true
        if (safety.location.route != here.route) return true
        val distance = safety.location.distFromS - here.distFromS
        val highway = here.roadType?.name == "KNRoadType_Highway"
        val limit = if (highway) prefs.highwayDistance else prefs.localDistance
        // 안내 지점이 기준 거리보다 약간 앞서 나오는 경우를 감안해 여유를 둠
        return distance <= limit + DISTANCE_MARGIN
    }

    private fun setStatus(text: String) {
        status = text
        onStatus?.invoke()
    }

    companion object {
        private const val TAG = "KakaoCameraSource"
        private const val DISTANCE_MARGIN = 150
        private var installed = false

        /** 앱 키가 들어간 빌드인지 */
        val available get() = BuildConfig.KAKAO_APP_KEY.isNotEmpty()

        private fun userKey(context: Context): String {
            val prefs = context.getSharedPreferences("camalert", Context.MODE_PRIVATE)
            return prefs.getString("kakao_user_key", null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString("kakao_user_key", it).apply() }
        }
    }
}
