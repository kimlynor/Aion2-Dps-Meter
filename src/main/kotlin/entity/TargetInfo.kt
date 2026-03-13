package com.tbread.entity

import java.util.UUID

data class TargetInfo(
    private val targetId: Int,
    private var damagedAmount: Long = 0L,
    private var targetDamageStarted: Long,
    private var targetDamageEnded: Long,
    private val processedUuid: MutableSet<UUID> = mutableSetOf(),
) {
    // 마지막으로 피해받은 시각 (타겟 전환 판단용 - Lost Ark Meter 방식 참고)
    private var lastDamageTime: Long = targetDamageStarted

    fun processedUuid(): MutableSet<UUID> = processedUuid
    fun damagedAmount(): Long = damagedAmount
    fun lastDamageTime(): Long = lastDamageTime
    fun targetId(): Int = targetId

    fun processPdp(pdp: ParsedDamagePacket) {
        if (processedUuid.contains(pdp.getUuid())) return
        damagedAmount += pdp.getDamage().toLong()
        val ts = pdp.getTimeStamp()
        if (ts < targetDamageStarted) targetDamageStarted = ts
        else if (ts > targetDamageEnded) targetDamageEnded = ts
        if (ts > lastDamageTime) lastDamageTime = ts
        processedUuid.add(pdp.getUuid())
    }

    fun parseBattleTime(): Long {
        val elapsed = targetDamageEnded - targetDamageStarted
        if (elapsed > 0L) return elapsed
        // 첫 패킷 하나만 있을 때: 현재 시각 기준 경과 시간으로 대체 (0 반환 시 getDps가 빈 데이터 반환하는 버그 방지)
        return maxOf(System.currentTimeMillis() - targetDamageStarted, 1L)
    }
}