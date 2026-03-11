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

    fun parseBattleTime(): Long = targetDamageEnded - targetDamageStarted
}