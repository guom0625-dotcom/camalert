package com.gom.camalert

enum class GuidanceGroup(val title: String) {
    CAMERA("카메라 안내"),
    ENFORCEMENT("기타 단속"),
    HAZARD("위험 구간"),
    INFO("정보"),
}

/** 카카오내비 안전운행 안내 항목. 기본값은 카카오내비 설정과 비슷하게 맞춤. */
enum class GuidanceItem(val label: String, val group: GuidanceGroup, val defaultOn: Boolean = true) {
    FIXED_SPEED("고정식 과속 카메라", GuidanceGroup.CAMERA),
    SIGNAL("신호·과속 카메라", GuidanceGroup.CAMERA),
    SECTION("구간 단속", GuidanceGroup.CAMERA),
    MOBILE("이동식 단속", GuidanceGroup.CAMERA),

    PARKING("주차 단속", GuidanceGroup.ENFORCEMENT),
    CUT_IN("끼어들기 단속", GuidanceGroup.ENFORCEMENT),
    SHOULDER("갓길 단속", GuidanceGroup.ENFORCEMENT),
    BUS_LANE("버스전용차로 단속", GuidanceGroup.ENFORCEMENT),
    DESIGNATED_LANE("지정차로·적재 단속", GuidanceGroup.ENFORCEMENT),
    SEATBELT("안전띠 단속", GuidanceGroup.ENFORCEMENT),
    OLD_DIESEL("노후 경유차 단속", GuidanceGroup.ENFORCEMENT),

    ACCIDENT("사고 다발 구간", GuidanceGroup.HAZARD),
    INTENT_ACCIDENT("고의 사고 다발 구간", GuidanceGroup.HAZARD),
    CHILD_ZONE("어린이 보호구역", GuidanceGroup.HAZARD),
    SPEED_BUMP("과속방지턱", GuidanceGroup.HAZARD),
    SHARP_TURN("급커브", GuidanceGroup.HAZARD),
    STEEP("급경사", GuidanceGroup.HAZARD),
    FLOOD("침수 위험 구간", GuidanceGroup.HAZARD),
    ICE("결빙 주의 구간", GuidanceGroup.HAZARD, false),
    FOG("안개 주의 구간", GuidanceGroup.HAZARD, false),
    RAILROAD("철길 건널목", GuidanceGroup.HAZARD, false),
    ANIMAL("야생동물 출현", GuidanceGroup.HAZARD, false),

    INFO_COLLECTION("교통정보 수집 구간", GuidanceGroup.INFO),
    REST_AREA("휴게소·졸음쉼터", GuidanceGroup.INFO);

    companion object {
        /** 카카오 SDK의 KNSafetyCode 이름을 항목으로. SDK 버전마다 enum이 조금씩 달라서 이름으로 비교. */
        fun of(safetyCode: String): GuidanceItem? {
            val name = safetyCode.removePrefix("KNSafetyCode_")
            return when {
                name.startsWith("SpeedViolationSection") -> SECTION
                name.startsWith("SignalAndSpeedViolation") || name == "SignalViolationCamera" -> SIGNAL
                name == "MovableSpeedViolationCamera" -> MOBILE
                name in FIXED_SPEED_CODES -> FIXED_SPEED
                name == "ParkingViolationCamera" -> PARKING
                name == "CutInViolationCamera" -> CUT_IN
                name == "ShoulderLaneViolationCamera" -> SHOULDER
                name == "BuslaneViolationCamera" -> BUS_LANE
                name == "DrivingLaneViolationCamera" || name.startsWith("LandChangeViolationSection") ||
                    name == "OverloadViolationCamera" || name == "CargoViolationCamera" -> DESIGNATED_LANE
                name == "SeatBeltViolationCamera" -> SEATBELT
                name == "OldDieselCamera" -> OLD_DIESEL
                name.endsWith("AccidentPos") -> ACCIDENT
                name == "IntentTrafficAccident" -> INTENT_ACCIDENT
                name == "ChildrenProtectionZone" -> CHILD_ZONE
                name == "Hump" -> SPEED_BUMP
                name == "SharpTurnSection" -> SHARP_TURN
                name == "SteepDownhillSection" || name == "UphillSection" -> STEEP
                name == "UnderPassFlood" -> FLOOD
                name.startsWith("FrozenRoad") || name == "SlippingRoad" -> ICE
                name.startsWith("FogArea") -> FOG
                name == "RailroadCrossing" -> RAILROAD
                name == "AnimalsAppearingCaution" -> ANIMAL
                name == "TrafficCollectionCamera" -> INFO_COLLECTION
                name == "RestArea" -> REST_AREA
                else -> null
            }
        }

        private val FIXED_SPEED_CODES = setOf(
            "SpeedViolationCamera",
            "SpeedViolationBackwardCamera",
            "ViolationCamera",
            "BoxedSpeedViolationCamera",
            "BuslaneAndSpeedViolationCamera",
            "LaneAndSpeedViolationCamera",
        )
    }
}

/** 과속 경고음을 낼지. speedKmh/limit가 없으면 기준이 "즉시"일 때만 SDK 판단을 따른다. */
fun shouldWarnOverspeed(mode: Int, thresholdPercent: Int, isMobileCamera: Boolean, speedKmh: Float?, limit: Int?): Boolean {
    if (mode == Prefs.OVERSPEED_OFF) return false
    if (mode == Prefs.OVERSPEED_EXCEPT_MOBILE && isMobileCamera) return false
    if (thresholdPercent == 0) return true
    if (speedKmh == null || limit == null || limit <= 0) return false
    return speedKmh > limit * (100 + thresholdPercent) / 100f
}
