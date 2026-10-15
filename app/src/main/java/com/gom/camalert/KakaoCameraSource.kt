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
        DiagLog.log(app, "카카오 SDK 초기화 시작")
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
                    DiagLog.log(app, "카카오 인증 실패: code=${error.code} msg=${error.msg} tag=${error.tagMsg}")
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
        DiagLog.log(app, "카카오 안전운행 모드 시작")
        guidance = shared
        setStatus("전방 카메라 감시 중 (카카오내비)")
    }

    override fun shouldPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice, newData: MutableList<ByteArray>): Boolean {
        val safety = voiceGuide.guideObj as? KNSafety
        val what = "${voiceGuide.voiceCode.name.removePrefix("KNVoiceCode_")}/${safety?.code?.name?.removePrefix("KNSafetyCode_") ?: "-"}" +
            " 단계=${voiceGuide.voiceDist?.name?.removePrefix("KNVoiceDist_")}" +
            ((safety as? KNSafety_Camera)?.speedLimit?.takeIf { it > 0 }?.let { " 제한=$it" } ?: "") +
            (speedKmh?.let { " 속도=${it.toInt()}" } ?: "") +
            (distanceTo(guidance, safety)?.let { " 거리=${it}m" } ?: "")
        if (!active || muted) {
            DiagLog.log(app, "안내 무시 (${if (muted) "멈춤 중" else "비활성"}): $what")
            return false
        }
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
        DiagLog.log(app, (if (play) "안내 재생: " else "안내 거름 (${filterReason(guidance, voiceGuide, safety)}): ") + what)
        return play
    }

    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) DiagLog.log(app, "오디오 포커스 거부됨 (다른 앱이 소리를 점유 중)")
    }

    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        audio.abandonAudioFocusRequest(focus)
    }

    /** 켜 둔 항목이고, 안내 시작 거리 안쪽일 때만 */
    private fun allowed(guidance: KNGuidance, safety: KNSafety): Boolean {
        val item = GuidanceItem.of(safety.code.name) ?: return false
        if (!prefs.isOn(item)) return false
        val distance = distanceTo(guidance, safety) ?: return true
        // 안내 지점이 기준 거리보다 약간 앞서 나오는 경우를 감안해 여유를 둠
        return distance <= startDistance(guidance) + DISTANCE_MARGIN
    }

    /** 경로상 남은 거리(m). 같은 경로 위에 있지 않으면 null */
    private fun distanceTo(guidance: KNGuidance, safety: KNSafety?): Int? {
        safety ?: return null
        val here = guidance.locationGuide?.let { it.location ?: it.locationForPrimaryRoute } ?: return null
        if (safety.location.route != here.route) return null
        return safety.location.distFromS - here.distFromS
    }

    private fun startDistance(guidance: KNGuidance): Int {
        val here = guidance.locationGuide?.let { it.location ?: it.locationForPrimaryRoute }
        return if (here?.roadType?.name == "KNRoadType_Highway") prefs.highwayDistance else prefs.localDistance
    }

    /** 진단 기록용: 왜 걸렀는지 */
    private fun filterReason(guidance: KNGuidance, voiceGuide: KNGuide_Voice, safety: KNSafety?): String =
        when (voiceGuide.voiceCode.name) {
            "KNVoiceCode_Safety" -> {
                val item = safety?.let { GuidanceItem.of(it.code.name) }
                when {
                    safety == null -> "대상 정보 없음"
                    item == null -> "안내하지 않는 종류"
                    !prefs.isOn(item) -> "'${item.label}' 꺼짐"
                    else -> "안내 시작 거리 ${startDistance(guidance)}m 밖"
                }
            }
            "KNVoiceCode_SchoolZone" -> "'어린이 보호구역' 꺼짐"
            "KNVoiceCode_Alert", "KNVoiceCode_Alram" -> "과속 경고 설정 (모드=${prefs.overspeedMode}, 기준=${prefs.overspeedPercent}%)"
            else -> "길 안내 음성"
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
