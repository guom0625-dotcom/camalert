package com.gom.camalert

import android.location.Location

/** 안전운행 안내를 내보내는 곳 */
interface CameraSource {
    /** 알림창에 보여줄 현재 상태 */
    val status: String

    /** true면 안내 음성을 내지 않음 (알림창 멈춤) */
    var muted: Boolean

    /** onStatus는 status가 바뀔 때 호출됨 (알림창 갱신용) */
    fun start(onStatus: () -> Unit)
    fun onLocation(location: Location) {}
    fun stop()
}

class NoCameraSource : CameraSource {
    override val status = "카메라 정보 없음 (카카오 앱 키 없이 빌드됨)"
    override var muted = false
    override fun start(onStatus: () -> Unit) {}
    override fun stop() {}
}
