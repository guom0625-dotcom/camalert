package com.gom.camalert

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraAnnouncerTest {

    private fun cam(distance: Int, id: String = "a", limit: Int? = 60) =
        UpcomingCamera(id, distance, CameraKind.SIGNAL, limit)

    @Test
    fun announcesEachThresholdOnce() {
        val announcer = CameraAnnouncer()
        assertEquals(emptyList<String>(), announcer.update(listOf(cam(1500))))
        assertEquals(listOf("1킬로미터 앞 신호 과속 카메라, 제한속도 60"), announcer.update(listOf(cam(980))))
        assertEquals(emptyList<String>(), announcer.update(listOf(cam(900))))
        assertEquals(listOf("600미터 앞 신호 과속 카메라, 제한속도 60"), announcer.update(listOf(cam(590))))
        assertEquals(listOf("300미터 앞 신호 과속 카메라, 제한속도 60"), announcer.update(listOf(cam(290))))
        assertEquals(emptyList<String>(), announcer.update(listOf(cam(100))))
    }

    @Test
    fun firstSeenInsideThresholdSpeaksActualDistance() {
        val announcer = CameraAnnouncer()
        assertEquals(listOf("400미터 앞 신호 과속 카메라, 제한속도 60"), announcer.update(listOf(cam(450))))
        assertEquals(listOf("300미터 앞 신호 과속 카메라, 제한속도 60"), announcer.update(listOf(cam(280))))
    }

    @Test
    fun forgetsCamerasThatDisappear() {
        val announcer = CameraAnnouncer()
        announcer.update(listOf(cam(950)))
        announcer.update(emptyList())
        assertEquals(1, announcer.update(listOf(cam(950))).size)
    }

    @Test
    fun omitsMissingSpeedLimit() {
        val announcer = CameraAnnouncer()
        assertEquals(listOf("1킬로미터 앞 신호 과속 카메라"), announcer.update(listOf(cam(1000, limit = null))))
    }
}
