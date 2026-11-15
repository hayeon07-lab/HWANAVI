
package com.example.daesinhwanaecar

import kotlin.math.abs

/**
 * 차량 속도 데이터는 m/s 단위.
 * 시간은 SystemClock.elapsedRealtime() 밀리초 단위.
 *
 * 정차 상태만으로 음성 재생이 안전하다고 판단하지 않는다.
 */
data class VehicleMotion(
    val speedKmh: Float,
    val accelerationMps2: Float?,
    val hardBrake: Boolean,
    val stationary: Boolean
)

class VehicleDeceleration(
    private val hardBrakeThresholdMps2: Float = -4.0f,
    private val minimumStartingSpeedMps: Float = 3.0f,
    private val hardBrakeCooldownMs: Long = 3_000L
) {
    private var lastSpeedMps: Float? = null
    private var lastAtMs: Long? = null
    private var lastBrakingAtMs: Long? = null

    fun reset() {
        lastSpeedMps = null
        lastAtMs = null
        lastBrakingAtMs = null
    }

    fun accept(
        rawSpeedMps: Float,
        nowMs: Long
    ): VehicleMotion? {

        if (
            !rawSpeedMps.isFinite() ||
            abs(rawSpeedMps) > 100f ||
            nowMs < 0L
        ) {
            reset()
            return null
        }

        val speedMps = abs(rawSpeedMps)
        val previousSpeed = lastSpeedMps
        val previousTime = lastAtMs

        lastSpeedMps = speedMps
        lastAtMs = nowMs

        val elapsed = if (previousTime == null) {
            0L
        } else {
            nowMs - previousTime
        }

        val acceleration =
            if (
                previousSpeed != null &&
                elapsed in 150L..2_000L
            ) {
                (speedMps - previousSpeed) /
                        (elapsed / 1_000f)
            } else {
                null
            }

        val isNewHardBrake =
            acceleration != null &&
                    acceleration <= hardBrakeThresholdMps2 &&
                    previousSpeed != null &&
                    previousSpeed >= minimumStartingSpeedMps &&
                    (
                            lastBrakingAtMs == null ||
                                    nowMs - lastBrakingAtMs!! >=
                                    hardBrakeCooldownMs
                            )

        if (isNewHardBrake) {
            lastBrakingAtMs = nowMs
        }

        return VehicleMotion(
            speedKmh = speedMps * 3.6f,
            accelerationMps2 = acceleration,
            hardBrake = isNewHardBrake,
            stationary = speedMps <= 0.5f
        )
    }
}
