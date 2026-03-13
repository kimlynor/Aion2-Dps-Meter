package com.tbread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 닉네임 → Plaync API combat_power 비동기 조회.
 *
 * 흐름:
 *  1. Plaync 검색 API로 (characterId, serverId) 확보 (IO 스레드)
 *  2. Plaync 캐릭터 정보 API로 combat_power 직접 추출
 *  3. 결과 캐시 후 onResult 콜백 호출
 *
 * WebView 의존 없이 순수 HTTP 요청으로만 동작함.
 */
object CombatPowerFetcher {
    private val logger = LoggerFactory.getLogger(CombatPowerFetcher::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private data class CacheEntry(val combatPower: Int?, val fetchedAtMs: Long)
    private data class CharacterInfo(val cleanName: String, val serverId: Int, val characterId: String)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val CACHE_TTL_MS = 5 * 60 * 1000L  // 5분

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    // "밤피[무닌]" → "밤피" (서버태그 제거)
    private val SERVER_TAG_REGEX = Regex("\\[.*?]")

    // "밤피[무닌]" → "무닌" (서버명 추출)
    private val SERVER_NAME_REGEX = Regex("\\[(.*?)]")

    // "<strong>밤피</strong>" → "밤피" (HTML 태그 제거)
    private val HTML_TAG_REGEX = Regex("<[^>]*>")

    /**
     * 닉네임으로 전투력을 비동기 조회한다.
     * 캐시 히트 시 즉시 onResult 호출, 미스 시 API 조회 후 onResult 호출.
     * 중복 요청 방지: 같은 닉네임이 이미 조회 중이면 스킵.
     */
    fun fetchAsync(nickname: String, onResult: (Int?) -> Unit) {
        val cached = cache[nickname]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < CACHE_TTL_MS) {
            onResult(cached.combatPower)
            return
        }
        if (!inFlight.add(nickname)) return

        scope.launch {
            try {
                // Step 1: Plaync 검색으로 characterId + serverId 확보
                val charInfo = searchCharacter(nickname) ?: run {
                    logger.debug("캐릭터 검색 실패: {}", nickname)
                    inFlight.remove(nickname)
                    return@launch
                }

                // Step 2: Plaync 캐릭터 정보 API로 combat_power 직접 조회
                val combatPower = fetchCombatPower(charInfo.characterId, charInfo.serverId)
                cache[nickname] = CacheEntry(combatPower, System.currentTimeMillis())
                logger.info("전투력 조회 완료: {} = {}", nickname, combatPower)
                inFlight.remove(nickname)
                onResult(combatPower)
            } catch (e: Exception) {
                logger.warn("전투력 조회 실패 [{}]: {}", nickname, e.message)
                inFlight.remove(nickname)
            }
        }
    }

    fun getCached(nickname: String): Int? {
        val cached = cache[nickname] ?: return null
        if (System.currentTimeMillis() - cached.fetchedAtMs > CACHE_TTL_MS) return null
        return cached.combatPower
    }

    /**
     * Plaync 검색 API로 닉네임에 해당하는 CharacterInfo를 반환한다.
     * 서버태그([무닌])가 있으면 serverName으로 정확히 매칭한다.
     */
    private fun searchCharacter(nickname: String): CharacterInfo? {
        val serverTag = SERVER_NAME_REGEX.find(nickname)?.groupValues?.get(1)
        val baseName = SERVER_TAG_REGEX.replace(nickname, "").trim()
        val searchName = baseName.ifEmpty { nickname }

        val encodedName = URLEncoder.encode(searchName, "UTF-8")
        val searchUrl = "https://aion2.plaync.com/ko-kr/api/search/aion2/search/v2/character?keyword=$encodedName"

        logger.debug("캐릭터 검색: {} (원본: {}, 서버태그: {})", searchName, nickname, serverTag)

        val searchResp = get(searchUrl) ?: return null
        val searchRoot = runCatching { json.parseToJsonElement(searchResp) }.getOrNull() ?: return null

        val list = searchRoot.jsonObject["list"]?.jsonArray
            ?: searchRoot.jsonObject["result"]?.jsonObject?.get("items")?.jsonArray
            ?: searchRoot.jsonObject["items"]?.jsonArray
            ?: run {
                logger.debug("검색 응답에서 list 필드 없음")
                return null
            }

        if (list.isEmpty()) return null

        // 이름 + 서버명 동시 매칭 (서버태그가 있으면 serverName으로 필터)
        val matchedItem = list.firstOrNull { item ->
            val rawName = item.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val cleanName = HTML_TAG_REGEX.replace(rawName, "").trim()
            val itemServer = item.jsonObject["serverName"]?.jsonPrimitive?.contentOrNull
            cleanName.equals(searchName, ignoreCase = true) &&
                (serverTag == null || itemServer == serverTag)
        } ?: list.firstOrNull { item ->
            // 서버 매칭 실패 시 이름만으로 fallback
            val rawName = item.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
            HTML_TAG_REGEX.replace(rawName, "").trim().equals(searchName, ignoreCase = true)
        } ?: list.first()

        val serverId = matchedItem.jsonObject["serverId"]?.jsonPrimitive?.intOrNull ?: return null
        val characterId = matchedItem.jsonObject["characterId"]?.jsonPrimitive?.contentOrNull ?: return null

        return CharacterInfo(searchName, serverId, characterId)
    }

    /**
     * Plaync 캐릭터 정보 API에서 combat_power 필드를 직접 조회한다.
     */
    private fun fetchCombatPower(characterId: String, serverId: Int): Int? {
        val encodedCharId = URLEncoder.encode(characterId, "UTF-8")
        val url = "https://aion2.plaync.com/api/character/info?lang=ko&characterId=$encodedCharId&serverId=$serverId"
        logger.debug("캐릭터 정보 조회: {}", url)

        val resp = get(url) ?: return null
        val root = runCatching { json.parseToJsonElement(resp) }.getOrNull() ?: return null

        val combatPower = root.jsonObject["profile"]
            ?.jsonObject?.get("combat_power")
            ?.jsonPrimitive?.intOrNull

        logger.debug("combat_power 조회 결과: {}", combatPower)
        return combatPower
    }

    private fun get(url: String): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Referer", "https://aion2.plaync.com/ko-kr/characters")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                response.body()
            } else {
                logger.warn("API HTTP {}: {}", response.statusCode(), url)
                null
            }
        } catch (e: Exception) {
            logger.warn("API 요청 실패 [{}]: {}", url, e.message)
            null
        }
    }
}
