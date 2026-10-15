package com.gom.camalert

import android.location.Location

/** 전방 카메라 목록을 공급하는 곳. 카카오내비 SDK 연동은 앱 키가 나오면 이 인터페이스로 붙인다. */
interface CameraSource {
    /** 알림창에 보여줄 현재 상태 */
    val status: String

    fun start(onUpdate: (List<UpcomingCamera>) -> Unit)
    fun onLocation(location: Location) {}
    fun stop()
}

class NoCameraSource : CameraSource {
    override val status = "카메라 정보 연결 전 (카카오 앱 키 필요)"
    override fun start(onUpdate: (List<UpcomingCamera>) -> Unit) {}
    override fun stop() {}
}
