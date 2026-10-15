package com.gom.camalert

enum class CameraKind(val label: String) {
    SPEED("과속 카메라"),
    SIGNAL("신호 과속 카메라"),
    SIGNAL_ONLY("신호 위반 카메라"),
    SECTION("구간 단속 시작"),
    SECTION_END("구간 단속 종료"),
    MOBILE("이동식 단속 구간"),
    CHILD_ZONE("어린이 보호구역"),
    OTHER("단속 카메라"),
}

/** 진행 방향 앞쪽에 있는 카메라 하나. 거리는 경로 기준 남은 거리(m). */
data class UpcomingCamera(
    val id: String,
    val distanceMeters: Int,
    val kind: CameraKind,
    val speedLimit: Int? = null,
)

/**
 * 카메라별로 1km / 600m / 300m 지점을 한 번씩만 안내한다.
 * 처음 감지된 카메라가 이미 600m 안쪽이면 그 다음 단계부터 안내한다.
 */
class CameraAnnouncer(private val thresholds: List<Int> = listOf(1000, 600, 300)) {

    // 카메라 id -> 마지막으로 안내한 기준 거리
    private val announced = HashMap<String, Int>()

    fun update(cameras: List<UpcomingCamera>): List<String> {
        announced.keys.retainAll(cameras.map { it.id }.toSet())

        val phrases = mutableListOf<String>()
        for (camera in cameras.sortedBy { it.distanceMeters }) {
            val due = thresholds.filter { camera.distanceMeters <= it }.minOrNull() ?: continue
            val last = announced[camera.id]
            if (last != null && last <= due) continue
            announced[camera.id] = due
            phrases += phrase(camera, due)
        }
        return phrases
    }

    fun reset() = announced.clear()

    private fun phrase(camera: UpcomingCamera, threshold: Int): String {
        // 기준 거리 바로 안쪽이면 기준 거리로, 많이 지나서 처음 잡혔으면 실제 거리를 100m 단위로
        val spoken = if (threshold - camera.distanceMeters <= 100) threshold else camera.distanceMeters / 100 * 100
        val distance = if (spoken >= 1000) "1킬로미터" else "${spoken.coerceAtLeast(100)}미터"
        val limit = camera.speedLimit?.takeIf { it > 0 }?.let { ", 제한속도 $it" } ?: ""
        return "$distance 앞 ${camera.kind.label}$limit"
    }
}
