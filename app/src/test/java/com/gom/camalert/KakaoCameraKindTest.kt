package com.gom.camalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KakaoCameraKindTest {

    @Test
    fun mapsEnforcementCameras() {
        assertEquals(CameraKind.SIGNAL, KakaoCameraSource.kindOf("KNSafetyCode_SignalAndSpeedViolationCamera"))
        assertEquals(CameraKind.SIGNAL, KakaoCameraSource.kindOf("KNSafetyCode_SignalAndSpeedViolationBackwardCamera"))
        assertEquals(CameraKind.SIGNAL_ONLY, KakaoCameraSource.kindOf("KNSafetyCode_SignalViolationCamera"))
        assertEquals(CameraKind.SPEED, KakaoCameraSource.kindOf("KNSafetyCode_SpeedViolationCamera"))
        assertEquals(CameraKind.SPEED, KakaoCameraSource.kindOf("KNSafetyCode_BoxedSpeedViolationCamera"))
        assertEquals(CameraKind.SECTION, KakaoCameraSource.kindOf("KNSafetyCode_SpeedViolationSectionInCamera"))
        assertEquals(CameraKind.SECTION, KakaoCameraSource.kindOf("KNSafetyCode_SpeedViolationSectionInOnlyBackwardCamera"))
        assertEquals(CameraKind.SECTION_END, KakaoCameraSource.kindOf("KNSafetyCode_SpeedViolationSectionOutCamera"))
        assertEquals(CameraKind.MOBILE, KakaoCameraSource.kindOf("KNSafetyCode_MovableSpeedViolationCamera"))
        assertEquals(CameraKind.CHILD_ZONE, KakaoCameraSource.kindOf("KNSafetyCode_ChildrenProtectionZone"))
    }

    @Test
    fun ignoresNonSpeedEnforcement() {
        assertNull(KakaoCameraSource.kindOf("KNSafetyCode_ParkingViolationCamera"))
        assertNull(KakaoCameraSource.kindOf("KNSafetyCode_BuslaneViolationCamera"))
        assertNull(KakaoCameraSource.kindOf("KNSafetyCode_SeatBeltViolationCamera"))
        assertNull(KakaoCameraSource.kindOf("KNSafetyCode_SpeedViolationSection"))
        assertNull(KakaoCameraSource.kindOf("KNSafetyCode_Hump"))
    }
}
