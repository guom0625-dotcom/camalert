package com.gom.camalert

import android.app.Application
import android.content.Context
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.KNSDKInitState
import com.kakaomobility.knsdk.common.gps.KNGPSData
import com.kakaomobility.knsdk.common.gps.KNGPSReceiver
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideRouteChangeReason
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import com.kakaomobility.knsdk.guidance.knguidance.KNDriveGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideFrequencyMode
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Section
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 카카오내비 SDK 안전운행 모드(목적지 없는 주행).
 * 안내 음성과 경고음은 카카오 것을 그대로 쓰고, 설정에 따라 어떤 안내를 낼지만 고른다.
 */
class KakaoCameraSource(private val app: Application) : CameraSource, KNGuidance_VoiceGuideDelegate,
    KNGuidance_LocationGuideDelegate, KNGuidance_SafetyGuideDelegate, KNGuidance_GuideStateDelegate, KNGPSReceiver {

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
    /** 진단 기록용 GPS 속도 */
    @Volatile private var speedKmh: Float? = null
    @Volatile private var accuracyM: Float? = null
    /** SDK GPS가 연속으로 유효해진 시각 (0 = 지금 유효하지 않음) */
    @Volatile private var validSince = 0L
    /** 위험 구간 항목별 마지막 안내 시각. 같은 종류가 몇 초 안에 몰려 나오면 한 번만 */
    private val lastHazardAt = java.util.concurrent.ConcurrentHashMap<GuidanceItem, Long>()
    /** 인증 실패 후 다시 시도할 때까지 기다리는 시간(ms). 실패할수록 늘어남 */
    private var retryDelay = RETRY_MIN_MS
    private val retryInit = Runnable {
        if (!active) return@Runnable
        when (KNSDK.initSt) {
            KNSDKInitState.KNSDKInitState_Initialized -> startGuidance()
            KNSDKInitState.KNSDKInitState_OnInitializing -> Unit
            else -> initialize()
        }
    }
    /** 지금 나오고 있는 안내. 설정에서 음량을 바꾸면 바로 반영하려고 들고 있음 */
    @Volatile private var playing: KNGuide_Voice? = null
    private val volumeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "volume") playing?.setVolume(currentVolume())
    }

    // 진단용: SDK가 GPS와 위치/안전 안내를 실제로 받고 있는지 1분 단위로 센다
    private val sdkGps = AtomicInteger()
    private val sdkGpsValid = AtomicInteger()
    private val locationUpdates = AtomicInteger()
    private val safetyUpdates = AtomicInteger()
    @Volatile private var aroundSafeties = 0

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
        current = this
        retryDelay = RETRY_MIN_MS
        app.getSharedPreferences("camalert", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(volumeListener)

        if (!installed) {
            KNSDK.install(app, File(app.filesDir, "knsdk").absolutePath)
            installed = true
        }
        when (KNSDK.initSt) {
            KNSDKInitState.KNSDKInitState_Initialized -> startGuidance()
            // 진행 중인 초기화 콜백이 current(=이 객체)로 결과를 넘겨줌
            KNSDKInitState.KNSDKInitState_OnInitializing -> Unit
            else -> initialize()
        }
    }

    override fun stop() {
        active = false
        if (current === this) current = null
        main.removeCallbacks(retryInit)
        app.getSharedPreferences("camalert", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(volumeListener)
        playing = null
        guidance?.let {
            it.stop()
            // 공유 객체라 풀어 두지 않으면 다음 운전까지 이 객체가 콜백을 계속 받음
            if (it.voiceGuideDelegate === this) it.voiceGuideDelegate = null
            if (it.locationGuideDelegate === this) it.locationGuideDelegate = null
            if (it.safetyGuideDelegate === this) it.safetyGuideDelegate = null
            if (it.guideStateDelegate === this) it.guideStateDelegate = null
        }
        guidance = null
        KNSDK.sharedGpsManager()?.stopUpdate(this)
        audio.abandonAudioFocusRequest(focus)
    }

    override fun onLocation(location: Location) {
        speedKmh = if (location.hasSpeed()) location.speed * 3.6f else null
        accuracyM = if (location.hasAccuracy()) location.accuracy else null
    }

    /** SDK가 도로 위 위치를 잡고 있는지 (직전 기록 이후 받은 횟수) */
    override fun diag(): String {
        val g = guidance ?: return "카카오 안내 꺼짐"
        val here = g.locationGuide?.let { it.location ?: it.locationForPrimaryRoute }
        val road = here?.roadType?.name?.removePrefix("KNRoadType_") ?: "위치 없음"
        val text = "카카오 상태=${g.guideState.name.removePrefix("KNGuideState_")} 도로=$road" +
            " SDK-GPS=${sdkGps.getAndSet(0)}(유효 ${sdkGpsValid.getAndSet(0)})" +
            " 위치갱신=${locationUpdates.getAndSet(0)} 안전갱신=${safetyUpdates.getAndSet(0)} 주변=$aroundSafeties"
        return text
    }

    override fun didReceiveGpsData(data: KNGPSData) {
        sdkGps.incrementAndGet()
        if (data.valid) {
            sdkGpsValid.incrementAndGet()
            if (validSince == 0L) validSince = SystemClock.elapsedRealtime()
        } else {
            validSince = 0L
        }
    }

    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        locationUpdates.incrementAndGet()
    }

    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        safetyUpdates.incrementAndGet()
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) {
        aroundSafeties = safeties?.size ?: 0
    }

    override fun guidanceGuideStarted(guidance: KNGuidance) = DiagLog.log(app, "카카오: 안내 시작됨")
    override fun guidanceGuideEnded(guidance: KNGuidance) = DiagLog.log(app, "카카오: 안내 끝남")
    override fun guidanceCheckingRouteChange(guidance: KNGuidance) {}
    override fun guidanceRouteUnchanged(guidance: KNGuidance) {}
    override fun guidanceRouteUnchangedWithError(guidance: KNGuidance, error: KNError) =
        DiagLog.log(app, "카카오 오류: code=${error.code} msg=${error.msg}")
    override fun guidanceOutOfRoute(guidance: KNGuidance) {}
    override fun guidanceRouteChanged(
        guidance: KNGuidance, fromRoute: KNRoute, fromLocation: KNLocation,
        toRoute: KNRoute, toLocation: KNLocation, changeReason: KNGuideRouteChangeReason,
    ) {}
    override fun guidanceDidUpdateRoutes(guidance: KNGuidance, routes: List<KNRoute>, multiRouteInfo: KNMultiRouteInfo?) {}
    override fun guidanceDidUpdateIndoorRoute(guidance: KNGuidance, route: KNRoute?) {}

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
            // 초기화 도중 서비스가 다시 만들어졌으면 결과는 새 객체가 받아야 함
            main.post { current?.onInitialized(error) }
        }
    }

    private fun onInitialized(error: KNError?) {
        if (!active) return
        if (error == null) {
            retryDelay = RETRY_MIN_MS
            startGuidance()
            return
        }
        // 지하주차장처럼 통신이 안 되는 곳에서 출발하면 실패하므로 될 때까지 다시 시도
        Log.w(TAG, "KNSDK init failed: ${error.code} ${error.msg}")
        val seconds = retryDelay / 1000
        DiagLog.log(app, "카카오 인증 실패: code=${error.code} msg=${error.msg} tag=${error.tagMsg} → ${seconds}초 뒤 다시 시도")
        setStatus("카카오내비 인증 실패 (${error.code}) · ${seconds}초 뒤 다시 시도")
        main.removeCallbacks(retryInit)
        main.postDelayed(retryInit, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_MS)
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
        // 화면이 꺼져 있거나 앱 화면이 없으면 SDK는 '백그라운드'로 보고 위치 갱신을 버린다.
        // 이 앱은 항상 백그라운드에서 돌아가므로 반드시 켜야 안내가 나온다.
        shared.useBackgroundUpdate = true
        shared.voiceGuideDelegate = this
        shared.locationGuideDelegate = this
        shared.safetyGuideDelegate = this
        shared.guideStateDelegate = this
        val gps = KNSDK.sharedGpsManager()
        if (gps == null) DiagLog.log(app, "카카오 GPS 관리자 없음") else gps.requestUpdate(this)
        shared.startWithTrip(null, KNRoutePriority.KNRoutePriority_Recommand, KNRouteAvoidOption.KNRouteAvoidOption_None.value)
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
            (sectionAverage(safety)?.let { " 구간평균=$it" } ?: "") +
            (distanceTo(guidance, safety)?.let { " 거리=${it}m" } ?: "")
        if (!active || muted) {
            DiagLog.log(app, "안내 무시 (${if (muted) "멈춤 중" else "비활성"}): $what")
            return false
        }
        // null이면 재생, 아니면 거른 이유
        val reason: String? = when (voiceGuide.voiceCode.name) {
            "KNVoiceCode_Safety" -> if (safety == null) "대상 정보 없음" else blockReason(guidance, safety)
            "KNVoiceCode_SchoolZone" -> if (prefs.isOn(GuidanceItem.CHILD_ZONE)) null else "'어린이 보호구역' 꺼짐"
            // 경고음: Alram = 카메라 통과음, Alert = 과속 경고
            "KNVoiceCode_Alert", "KNVoiceCode_Alram" -> if (shouldPlayWarningSound(
                    mode = prefs.overspeedMode,
                    isMobileCamera = safety?.code?.name == "KNSafetyCode_MovableSpeedViolationCamera",
                )) null else "경고음 설정 (모드=${prefs.overspeedMode})"
            // 길 안내 관련 음성(경로 이탈, 회전 등)은 안전운행 모드에서 쓰지 않음
            else -> "길 안내 음성"
        }
        val play = reason == null
        if (play) {
            voiceGuide.setVolume(currentVolume())
            voiceGuide.setAudioAttribute(attributes)
        }
        DiagLog.log(app, (if (play) "안내 재생 (음량 ${prefs.volume}%): " else "안내 거름 ($reason): ") + what)
        return play
    }

    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        playing = voiceGuide
        voiceGuide.setVolume(currentVolume())
        if (!prefs.duckOthers) return
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) DiagLog.log(app, "오디오 포커스 거부됨 (다른 앱이 소리를 점유 중)")
    }

    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        if (playing === voiceGuide) playing = null
        audio.abandonAudioFocusRequest(focus)
    }

    /** 안전 안내를 막는 이유. null이면 재생 (재생으로 판단하면 위험 구간 안내 시각을 기록함) */
    private fun blockReason(guidance: KNGuidance, safety: KNSafety): String? {
        val item = GuidanceItem.of(safety.code.name) ?: return "안내하지 않는 종류"
        if (!prefs.isOn(item)) return "'${item.label}' 꺼짐"
        val hazard = item.group == GuidanceGroup.HAZARD || item.group == GuidanceGroup.INFO
        if (hazard) {
            // 주차장을 나와 GPS가 막 잡히면 위치가 튀면서 주변 방지턱 등이 한꺼번에 쏟아짐. 단속 카메라는 막지 않음
            if (!gpsSettled()) return "GPS 안정화 전 (정확도 ${accuracyM?.toInt() ?: "?"}m)"
            val now = SystemClock.elapsedRealtime()
            val last = lastHazardAt[item]
            if (last != null && now - last < HAZARD_REPEAT_MS) return "방금 같은 안내"
        }
        // 구간단속 구간 안에서 나오는 안내(평균속도, 남은 거리)는 종료 지점이 멀어도 막지 않음
        if (sectionAverage(safety) == null && !withinStartDistance(distanceTo(guidance, safety), startDistance(guidance))) {
            return "안내 시작 거리 ${startDistance(guidance)}m 밖"
        }
        if (hazard) lastHazardAt[item] = SystemClock.elapsedRealtime()
        return null
    }

    /** GPS가 일정 시간 계속 유효하고 정확도도 괜찮은지 */
    private fun gpsSettled(): Boolean {
        val since = validSince
        if (since == 0L || SystemClock.elapsedRealtime() - since < GPS_SETTLE_MS) return false
        return (accuracyM ?: 0f) <= GPS_MAX_ACCURACY_M
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

    /** 구간단속 구간 안이면 지금까지의 평균속도(km/h) */
    private fun sectionAverage(safety: KNSafety?) =
        (safety as? KNSafety_Section)?.sectionAvrSpeed?.takeIf { it > 0 }

    private fun currentVolume() = prefs.volume.coerceIn(0, 100) / 100f

    private fun setStatus(text: String) {
        status = text
        onStatus?.invoke()
    }

    companion object {
        private const val TAG = "KakaoCameraSource"
        /** GPS가 이만큼 계속 유효해야 위험 구간 안내를 냄 */
        private const val GPS_SETTLE_MS = 10_000L
        private const val GPS_MAX_ACCURACY_M = 30f
        /** 같은 위험 구간 종류가 이 안에 또 나오면 거름 (방지턱 연속 등) */
        private const val HAZARD_REPEAT_MS = 5_000L
        private const val RETRY_MIN_MS = 10_000L
        private const val RETRY_MAX_MS = 60_000L
        private var installed = false
        /** 지금 서비스가 쓰고 있는 객체 (메인 스레드에서만 읽고 씀) */
        private var current: KakaoCameraSource? = null

        /** 앱 키가 들어간 빌드인지 */
        val available get() = BuildConfig.KAKAO_APP_KEY.isNotEmpty()

        private fun userKey(context: Context): String {
            val prefs = context.getSharedPreferences("camalert", Context.MODE_PRIVATE)
            return prefs.getString("kakao_user_key", null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString("kakao_user_key", it).apply() }
        }
    }
}
