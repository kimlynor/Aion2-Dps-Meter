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
 * 닉네임 → 아툴(aion2tool.com) 전투력 점수 비동기 조회.
 *
 * 흐름:
 *  1. Plaync 검색 API로 serverId 확보 (IO 스레드)
 *  2. AToolFetcher로 아툴 캐릭터 페이지 로딩 → #dps-score-value 추출 (JavaFX 스레드)
 *  3. 결과 캐시 후 onResult 콜백 호출
 */
object CombatPowerFetcher {
    private val logger = LoggerFactory.getLogger(CombatPowerFetcher::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private data class CacheEntry(val combatPower: Int?, val fetchedAtMs: Long)

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
     * 닉네임으로 아툴 전투력 점수를 비동기 조회한다.
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
                // Step 1: Plaync 검색으로 serverId 확보
                val (cleanName, serverId) = searchCharacter(nickname) ?: run {
                    logger.debug("캐릭터 검색 실패: {}", nickname)
                    inFlight.remove(nickname)
                    return@launch
                }

                // Step 2: 아툴 WebView로 점수 조회 (AToolFetcher 내부에서 JavaFX 스레드 처리)
                AToolFetcher.fetchAsync(serverId, cleanName) { score ->
                    cache[nickname] = CacheEntry(score, System.currentTimeMillis())
                    logger.info("아툴 전투력 조회 완료: {} = {}", nickname, score)
                    inFlight.remove(nickname)
                    onResult(score)
                }
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
     * Plaync 검색 API로 닉네임에 해당하는 (cleanName, serverId) 를 반환한다.
     * 서버태그([무닌])가 있으면 serverName으로 정확히 매칭한다.
     */
    private fun searchCharacter(nickname: String): Pair<String, Int>? {
        val serverTag = SERVER_NAME_REGEX.find(nickname)?.groupValues?.get(1)
        val baseName = SERVER_TAG_REGEX.replace(nickname, "").trim()
        val searchName = baseName.ifEmpty { nickname }

        val encodedName = URLEncoder.encode(searchName, "UTF-8")
        val searchUrl = "https://aion2.plaync.com/ko-kr/api/search/aion2/search/v2/character?keyword=$encodedName"

        logger.debug("캐릭터 검색: {} (원본: {}, 서버태그: {})", searchUrl, nickname, serverTag)

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
        return Pair(searchName, serverId)
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
