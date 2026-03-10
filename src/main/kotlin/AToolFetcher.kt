package com.tbread

import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.util.Duration
import org.slf4j.LoggerFactory
import java.net.URLEncoder

/**
 * JavaFX WebView를 이용해 aion2tool.com 캐릭터 페이지를 숨김 로딩하고
 * #dps-score-value 요소에서 아툴 전투력 점수를 추출한다.
 *
 * - initialize()는 JavaFX Application Thread에서 한 번 호출해야 한다.
 * - fetchAsync()는 어느 스레드에서든 호출 가능하다.
 * - 요청은 큐에 쌓여 순서대로 처리된다 (WebView 1개 공유).
 */
object AToolFetcher {
    private val logger = LoggerFactory.getLogger(AToolFetcher::class.java)

    private const val TIMEOUT_MS = 25_000L
    private const val POLL_INTERVAL_MS = 500.0

    private data class FetchRequest(
        val serverId: Int,
        val name: String,
        val onResult: (Int?) -> Unit
    )

    private val queue = java.util.concurrent.ConcurrentLinkedQueue<FetchRequest>()
    @Volatile private var isBusy = false
    @Volatile private var currentRequest: FetchRequest? = null
    private var currentStartMs = 0L
    private var pollTimeline: Timeline? = null
    private var webView: WebView? = null

    /** BrowserApp.start() 에서 호출 (JavaFX Application Thread 필수) */
    fun initialize() {
        val wv = WebView()
        wv.isVisible = false
        wv.prefWidth = 1.0
        wv.prefHeight = 1.0
        webView = wv

        wv.engine.loadWorker.stateProperty().addListener { _, _, state ->
            val req = currentRequest ?: return@addListener
            when (state) {
                Worker.State.SUCCEEDED -> startPolling(req, wv.engine)
                Worker.State.FAILED, Worker.State.CANCELLED -> {
                    logger.warn("아툴 페이지 로드 실패: {}", req.name)
                    finishRequest(req, null)
                }
                else -> {}
            }
        }
        logger.info("AToolFetcher 초기화 완료")
    }

    /**
     * serverId와 캐릭터명으로 아툴 점수를 비동기 조회한다.
     * 결과는 onResult 콜백으로 전달된다 (JavaFX Application Thread에서 호출됨).
     */
    fun fetchAsync(serverId: Int, name: String, onResult: (Int?) -> Unit) {
        if (webView == null) {
            logger.warn("AToolFetcher 미초기화 상태에서 fetchAsync 호출됨")
            onResult(null)
            return
        }
        queue.add(FetchRequest(serverId, name, onResult))
        Platform.runLater(::processNext)
    }

    private fun processNext() {
        if (isBusy || queue.isEmpty()) return
        val req = queue.poll() ?: return
        isBusy = true
        currentRequest = req
        currentStartMs = System.currentTimeMillis()

        val encodedName = URLEncoder.encode(req.name, "UTF-8")
        val url = "https://www.aion2tool.com/char/serverid=${req.serverId}/$encodedName"
        logger.info("아툴 로딩 시작: {} ({}서버)", req.name, req.serverId)
        webView!!.engine.load(url)
    }

    private fun startPolling(req: FetchRequest, engine: WebEngine) {
        pollTimeline?.stop()
        pollTimeline = Timeline(KeyFrame(Duration.millis(POLL_INTERVAL_MS), {
            try {
                val text = engine.executeScript(
                    "(function(){ var el = document.getElementById('dps-score-value'); return el ? el.textContent : ''; })()"
                ) as? String ?: ""
                val score = text.replace(",", "").trim().toIntOrNull()
                when {
                    score != null && score > 0 -> {
                        logger.info("아툴 점수 조회 완료: {} = {}", req.name, score)
                        finishRequest(req, score)
                    }
                    System.currentTimeMillis() - currentStartMs > TIMEOUT_MS -> {
                        logger.warn("아툴 조회 타임아웃: {} (마지막값='{}')", req.name, text.take(30))
                        finishRequest(req, null)
                    }
                }
            } catch (e: Exception) {
                if (System.currentTimeMillis() - currentStartMs > TIMEOUT_MS) {
                    finishRequest(req, null)
                }
            }
        })).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    private fun finishRequest(req: FetchRequest, score: Int?) {
        pollTimeline?.stop()
        pollTimeline = null
        currentRequest = null
        isBusy = false
        req.onResult(score)
        Platform.runLater(::processNext)
    }
}
