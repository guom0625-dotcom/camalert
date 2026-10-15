package com.gom.camalert

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.KNSDKInitState
import com.kakaomobility.knsdk.guidance.knguidance.KNDriveGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import java.io.File
import java.util.UUID

/**
 * 카카오내비 SDK 안전운행 모드(목적지 없는 주행)로 전방 단속 카메라를 받는다.
 * SDK 자체 음성은 끄고, 거리 계산만 해서 CameraAnnouncer로 넘긴다.
 */
class KakaoCameraSource(private val app: Application) :
    CameraSource,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_VoiceGuideDelegate {

    @Volatile
    override var status = "카카오내비 연결 중…"
        private set

    private val main = Handler(Looper.getMainLooper())
    private var onUpdate: ((List<UpcomingCamera>) -> Unit)? = null
    private var onStatus: (() -> Unit)? = null
    private var guidance: KNDriveGuidance? = null
    private var active = false

    private var onGuide: List<KNSafety> = emptyList()
    private var around: List<KNSafety> = emptyList()

    override fun start(onUpdate: (List<UpcomingCamera>) -> Unit, onStatus: () -> Unit) {
        this.onUpdate = onUpdate
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
        onGuide = emptyList()
        around = emptyList()
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
        shared.safetyGuideDelegate = this
        shared.locationGuideDelegate = this
        shared.voiceGuideDelegate = this
        shared.startWithoutTrip()
        guidance = shared
        setStatus("전방 카메라 감시 중 (카카오내비)")
    }

    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        main.post {
            onGuide = safetyGuide?.safetiesOnGuide.orEmpty()
            publish(guidance)
        }
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) {
        main.post {
            around = safeties.orEmpty()
            publish(guidance)
        }
    }

    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        main.post { publish(guidance) }
    }

    // SDK 음성은 쓰지 않음 (앱 TTS와 겹치지 않게)
    override fun shouldPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice, newData: List<ByteArray>) = false
    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {}
    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {}

    private fun publish(guidance: KNGuidance) {
        if (!active || guidance !== this.guidance) return
        val here = guidance.locationGuide?.let { it.location ?: it.locationForPrimaryRoute } ?: return

        val cameras = (onGuide + around)
            .distinctBy { "${it.code.name}:${it.safetyId}" }
            .mapNotNull { safety ->
                val kind = kindOf(safety.code.name) ?: return@mapNotNull null
                if (safety.passed || safety.location.route != here.route) return@mapNotNull null
                val distance = safety.location.distFromS - here.distFromS
                if (distance < 0) return@mapNotNull null
                UpcomingCamera(
                    id = "${safety.code.name}:${safety.safetyId}",
                    distanceMeters = distance,
                    kind = kind,
                    speedLimit = (safety as? KNSafety_Camera)?.speedLimit?.takeIf { it > 0 },
                )
            }
        onUpdate?.invoke(cameras)
    }

    private fun setStatus(text: String) {
        status = text
        onStatus?.invoke()
    }

    companion object {
        private const val TAG = "KakaoCameraSource"
        private var installed = false

        /** 앱 키가 들어간 빌드인지 */
        val available get() = BuildConfig.KAKAO_APP_KEY.isNotEmpty()

        /**
         * 안내할 카메라만 고른다. SDK 버전마다 enum이 조금씩 달라서 이름으로 비교.
         * 주차·버스전용·적재·안전띠 단속 등은 제외.
         */
        fun kindOf(code: String): CameraKind? {
            val name = code.removePrefix("KNSafetyCode_")
            return when {
                name.startsWith("SpeedViolationSectionIn") -> CameraKind.SECTION
                name.startsWith("SpeedViolationSectionOut") -> CameraKind.SECTION_END
                name.startsWith("SignalAndSpeedViolation") -> CameraKind.SIGNAL
                name == "SignalViolationCamera" -> CameraKind.SIGNAL_ONLY
                name == "MovableSpeedViolationCamera" -> CameraKind.MOBILE
                name == "ChildrenProtectionZone" -> CameraKind.CHILD_ZONE
                name in SPEED -> CameraKind.SPEED
                else -> null
            }
        }

        private val SPEED = setOf(
            "SpeedViolationCamera",
            "SpeedViolationBackwardCamera",
            "ViolationCamera",
            "BoxedSpeedViolationCamera",
            "BuslaneAndSpeedViolationCamera",
            "LaneAndSpeedViolationCamera",
        )

        private fun userKey(context: Context): String {
            val prefs = context.getSharedPreferences("camalert", Context.MODE_PRIVATE)
            return prefs.getString("kakao_user_key", null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString("kakao_user_key", it).apply() }
        }
    }
}
