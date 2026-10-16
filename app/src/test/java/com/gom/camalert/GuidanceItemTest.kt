package com.gom.camalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceItemTest {

    @Test
    fun mapsCameras() {
        assertEquals(GuidanceItem.SIGNAL, GuidanceItem.of("KNSafetyCode_SignalAndSpeedViolationCamera"))
        assertEquals(GuidanceItem.SIGNAL, GuidanceItem.of("KNSafetyCode_SignalViolationCamera"))
        assertEquals(GuidanceItem.FIXED_SPEED, GuidanceItem.of("KNSafetyCode_SpeedViolationCamera"))
        assertEquals(GuidanceItem.FIXED_SPEED, GuidanceItem.of("KNSafetyCode_BuslaneAndSpeedViolationCamera"))
        assertEquals(GuidanceItem.SECTION, GuidanceItem.of("KNSafetyCode_SpeedViolationSectionOutOnlyBackwardCamera"))
        assertEquals(GuidanceItem.SECTION, GuidanceItem.of("KNSafetyCode_SpeedViolationSectionHalf"))
        assertEquals(GuidanceItem.MOBILE, GuidanceItem.of("KNSafetyCode_MovableSpeedViolationCamera"))
    }

    @Test
    fun mapsOtherItems() {
        assertEquals(GuidanceItem.BUS_LANE, GuidanceItem.of("KNSafetyCode_BuslaneViolationCamera"))
        assertEquals(GuidanceItem.DESIGNATED_LANE, GuidanceItem.of("KNSafetyCode_LandChangeViolationSectionInCamera"))
        assertEquals(GuidanceItem.ACCIDENT, GuidanceItem.of("KNSafetyCode_PedestrianAccidentPos"))
        assertEquals(GuidanceItem.CHILD_ZONE, GuidanceItem.of("KNSafetyCode_ChildrenProtectionZone"))
        assertEquals(GuidanceItem.ICE, GuidanceItem.of("KNSafetyCode_FrozenRoadLive"))
        assertEquals(GuidanceItem.REST_AREA, GuidanceItem.of("KNSafetyCode_RestArea"))
        assertNull(GuidanceItem.of("KNSafetyCode_OneTollingGate"))
        assertNull(GuidanceItem.of("KNSafetyCode_HeightLimitPos"))
    }

    @Test
    fun everyItemIsReachable() {
        val codes = listOf(
            "SpeedViolationCamera", "SignalViolationCamera", "SpeedViolationSection", "MovableSpeedViolationCamera",
            "ParkingViolationCamera", "CutInViolationCamera", "ShoulderLaneViolationCamera", "BuslaneViolationCamera",
            "DrivingLaneViolationCamera", "SeatBeltViolationCamera", "OldDieselCamera", "CarAccidentPos",
            "IntentTrafficAccident", "ChildrenProtectionZone", "Hump", "SharpTurnSection", "UphillSection",
            "UnderPassFlood", "SlippingRoad", "FogArea", "RailroadCrossing", "AnimalsAppearingCaution",
            "TrafficCollectionCamera", "RestArea",
        )
        assertEquals(GuidanceItem.entries.toSet(), codes.mapNotNull { GuidanceItem.of("KNSafetyCode_$it") }.toSet())
    }

    @Test
    fun overspeedWarning() {
        // 끄기
        assertFalse(shouldWarnOverspeed(Prefs.OVERSPEED_OFF, 0, false, 100f, 60))
        // 이동식 제외
        assertFalse(shouldWarnOverspeed(Prefs.OVERSPEED_EXCEPT_MOBILE, 0, true, 100f, 60))
        assertTrue(shouldWarnOverspeed(Prefs.OVERSPEED_ALL, 0, true, 100f, 60))
        // 즉시: SDK가 울리면 그대로
        assertTrue(shouldWarnOverspeed(Prefs.OVERSPEED_EXCEPT_MOBILE, 0, false, null, null))
        // 10% 초과: 60 제한이면 66 넘어야
        assertFalse(shouldWarnOverspeed(Prefs.OVERSPEED_ALL, 10, false, 65f, 60))
        assertTrue(shouldWarnOverspeed(Prefs.OVERSPEED_ALL, 10, false, 67f, 60))
        // 기준이 있는데 속도를 모르면 울리지 않음
        assertFalse(shouldWarnOverspeed(Prefs.OVERSPEED_ALL, 5, false, null, 60))
    }

    @Test
    fun startDistance() {
        // 기준 500m + 여유 150m까지는 안내
        assertTrue(withinStartDistance(650, 500))
        assertFalse(withinStartDistance(651, 500))
        assertTrue(withinStartDistance(0, 300))
        // 경로가 달라 거리를 모르면 막지 않음
        assertTrue(withinStartDistance(null, 300))
    }
}
