package com.tbread

import com.tbread.entity.ParsedDamagePacket
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

class DataStorage {
    private val logger = LoggerFactory.getLogger(DataStorage::class.java)
    private val byTargetStorage = ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>>()
    private val byActorStorage = ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>>()
    private val nicknameStorage = ConcurrentHashMap<Int, String>()
    private val summonStorage = HashMap<Int, Int>()
    private val skillCodeData = HashMap<Int, String>()
    private val mobCodeData = HashMap<Int, String>()
    private val mobStorage = HashMap<Int, Int>()
    private var currentTarget:Int = 0
    // pattern 0 (확정 패킷)으로 등록된 UID 집합 — 비확정 패턴이 올바른 닉네임을 덮어쓰는 버그 방지
    private val confirmedNicknameUids = mutableSetOf<Int>()

    @Synchronized
    fun appendDamage(pdp: ParsedDamagePacket) {
        byActorStorage.getOrPut(pdp.getActorId()) { ConcurrentSkipListSet(compareBy<ParsedDamagePacket> { it.getTimeStamp() }.thenBy { it.getUuid() }) }
            .add(pdp)
        byTargetStorage.getOrPut(pdp.getTargetId()) { ConcurrentSkipListSet(compareBy<ParsedDamagePacket> { it.getTimeStamp() }.thenBy { it.getUuid() }) }
            .add(pdp)
    }

    fun setCurrentTarget(targetId:Int){
        currentTarget = targetId
    }

    fun getCurrentTarget():Int{
        return currentTarget
    }

    fun appendMobCode(code: Int, name: String) {
        //이건나중에 파일이나 서버에서 불러오는걸로
        mobCodeData[code] = name
    }

    fun appendMob(mid: Int, code: Int) {
        mobStorage[mid] = code
    }

    fun appendSummon(summoner: Int, summon: Int) {
        summonStorage[summon] = summoner
    }

    fun appendNickname(uid: Int, nickname: String, confirmed: Boolean = false) {
        if (nicknameStorage[uid] != null && nicknameStorage[uid].equals(nickname)) return
        // 확정 닉네임(pattern 0)이 이미 등록된 UID는 비확정 패턴으로 덮어쓸 수 없음
        if (!confirmed && confirmedNicknameUids.contains(uid)) {
            logger.debug("닉네임 등록 시도 취소(확정 보호) {} -x> {}", nicknameStorage[uid], nickname)
            return
        }
        // 비확정 패턴: 현재보다 짧은 닉네임은 덮어쓰지 않음 (오파싱 방지)
        if (!confirmed && nicknameStorage[uid] != null &&
            nickname.toByteArray(Charsets.UTF_8).size < nicknameStorage[uid]!!.toByteArray(Charsets.UTF_8).size
        ) {
            logger.debug("닉네임 등록 시도 취소(짧음, 비확정) {} -x> {}", nicknameStorage[uid], nickname)
            return
        }
        logger.debug("닉네임 등록 {} -> {} (확정: {})", nicknameStorage[uid], nickname, confirmed)
        nicknameStorage[uid] = nickname
        if (confirmed) confirmedNicknameUids.add(uid)
    }

    @Synchronized
    fun flushDamageStorage() {
        byActorStorage.clear()
        byTargetStorage.clear()
        summonStorage.clear()
        logger.info("데미지 패킷 초기화됨")
    }

    private fun flushNicknameStorage() {
        nicknameStorage.clear()
    }

    fun getSkillName(skillCode: Int): String {
        return skillCodeData[skillCode] ?: skillCode.toString()
    }

    fun getBossModeData(): ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>> {
        return byTargetStorage
    }

    fun getNickname(): ConcurrentHashMap<Int, String> {
        return nicknameStorage
    }

    fun getSummonData(): HashMap<Int, Int> {
        return summonStorage
    }

    fun getMobCodeData(): HashMap<Int, String> {
        return mobCodeData
    }

    fun getMobData(): HashMap<Int, Int> {
        return mobStorage
    }
}