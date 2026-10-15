package com.gom.camalert

import android.location.Location

/** 전방 카메라 목록을 공급하는 곳 */
interface CameraSource {
    /** 알림창에 보여줄 현재 상태 */
    val status: String

    /** onStatus는 status가 바뀔 때 호출됨 (알림창 갱신용) */
    fun start(onUpdate: (List<UpcomingCamera>) -> Unit, onStatus: () -> Unit)
    fun onLocation(location: Location) {}
    fun stop()
}

class NoCameraSource : CameraSource {
    override val status = "카메라 정보 없음 (카카오 앱 키 없이 빌드됨)"
    override fun start(onUpdate: (List<UpcomingCamera>) -> Unit, onStatus: () -> Unit) {}
    override fun stop() {}
}
