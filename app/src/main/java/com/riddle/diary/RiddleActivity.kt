package com.riddle.diary

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.riddle.diary.databinding.ActivityRiddleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RiddleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRiddleBinding
    private lateinit var store: SettingsStore
    private lateinit var eink: EinkHelper
    private lateinit var pen: PenController
    private lateinit var replyPlanner: ReplyPlanner
    private lateinit var oracle: OracleClient

    private var state: DiaryState = DiaryState.Listening()
    private var penDown = false
    private var loopJob: Job? = null
    private var oracleJob: Job? = null
    private var oracleReply: String? = null
    private var oracleError: String? = null

    private val idleCommitMs = 2800L
    private val marginX = 80
    private val drinkStages = 14
    private val fadeStages = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRiddleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = SettingsStore(this)
        if (!store.isConfigured()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        hideSystemUi()
        replyPlanner = ReplyPlanner(this)
        oracle = OracleClient(store.apiKey, store.apiBase, store.apiModel)
        eink = EinkHelper(binding.inkCanvas)

        binding.inkCanvas.onInkChanged = { rect -> eink.refreshRegion(rect, fast = true) }

        pen = PenController(binding.inkCanvas) { sample ->
            handlePenSample(sample)
        }

        binding.inkCanvas.setOnTouchListener { _, event ->
            pen.onTouchEvent(event)
        }

        binding.inkCanvas.post {
            pen.attach()
            eink.fullRefresh()
            startLoop()
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        oracleJob?.cancel()
        pen.detach()
        super.onDestroy()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
    }

    private fun handlePenSample(sample: PenSample) {
        if (!sample.touching) {
            if (penDown) {
                penDown = false
                binding.inkCanvas.penUp()
                if (state is DiaryState.Listening) {
                    state = DiaryState.Listening(lastPenUpAt = SystemClock.elapsedRealtime())
                }
            }
            return
        }

        when (val current = state) {
            is DiaryState.Listening -> {
                penDown = true
                val dirty = if (sample.erasing) {
                    binding.inkCanvas.erasePoint(sample.x, sample.y)
                } else {
                    binding.inkCanvas.penPoint(sample.x, sample.y, sample.pressure)
                }
                eink.refreshRegion(dirty, fast = true)
                state = DiaryState.Listening(lastPenUpAt = SystemClock.elapsedRealtime())
            }
            is DiaryState.Lingering -> {
                state = DiaryState.FadingReply(0, SystemClock.elapsedRealtime(), current.region)
            }
            else -> Unit
        }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = lifecycleScope.launch {
            while (isActive) {
                tick()
                delay(16)
            }
        }
    }

    private suspend fun tick() {
        val now = SystemClock.elapsedRealtime()
        state = when (val s = state) {
            is DiaryState.Listening -> {
                val last = s.lastPenUpAt
                if (!penDown && last != null && now - last >= idleCommitMs && binding.inkCanvas.hasInk()) {
                    if (HelpDetector.looksLikeQuestionMark(binding.inkCanvas.strokeList())) {
                        showGuide()
                        DiaryState.Listening()
                    } else {
                        commitToOracle()
                        DiaryState.Drinking(0, now, binding.inkCanvas.inkBounds.copy())
                    }
                } else {
                    s
                }
            }

            is DiaryState.Drinking -> {
                if (now >= s.nextAt) {
                    binding.inkCanvas.dissolvePass(s.region, s.stage, drinkStages)
                    eink.refreshRegion(s.region.rect(), fast = true)
                    if (s.stage + 1 >= drinkStages) {
                        binding.inkCanvas.clearInkData()
                        DiaryState.Thinking(now)
                    } else {
                        s.copy(stage = s.stage + 1, nextAt = now + 70)
                    }
                } else s
            }

            is DiaryState.Thinking -> {
                oracleReply?.let { text ->
                    oracleReply = null
                    oracleError = null
                    val maxW = binding.inkCanvas.width - marginX * 2
                    val plan = replyPlanner.plan(text, marginX, 120, maxW, binding.inkCanvas.height)
                    DiaryState.Replying(plan, now)
                } ?: oracleError?.let { err ->
                    oracleError = null
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    DiaryState.Listening()
                } ?: run {
                    if (now - s.startedAt > 600) {
                        drawThinkingBlot((now / 600) % 2 == 0L)
                    }
                    s
                }
            }

            is DiaryState.Replying -> {
                if (now >= s.nextAt) {
                    var budget = 52
                    val dirty = BoundingBox()
                    val plan = s.plan
                    while (budget > 0 && plan.strokeIndex < plan.strokes.size) {
                        val stroke = plan.strokes[plan.strokeIndex]
                        if (plan.pointIndex >= stroke.size) {
                            plan.strokeIndex++
                            plan.pointIndex = 0
                            continue
                        }
                        val (x, y) = stroke[plan.pointIndex]
                        if (plan.pointIndex > 0) {
                            val (px, py) = stroke[plan.pointIndex - 1]
                            binding.inkCanvas.drawReplyLine(px, py, x, y)
                            dirty.add(px, py, 4)
                        } else {
                            binding.inkCanvas.drawReplyPoint(x, y)
                        }
                        dirty.add(x, y, 4)
                        plan.pointIndex++
                        budget--
                    }
                    if (!dirty.isEmpty()) eink.refreshRegion(dirty.rect(), fast = true)

                    if (plan.strokeIndex >= plan.strokes.size) {
                        val chars = plan.strokes.sumOf { it.size }
                        val linger = (4000 + chars * 2).coerceAtMost(20_000)
                        DiaryState.Lingering(now + linger, plan.region)
                    } else {
                        s.copy(plan = plan, nextAt = now + 14)
                    }
                } else s
            }

            is DiaryState.Lingering -> {
                if (now >= s.until) {
                    DiaryState.FadingReply(0, now, s.region)
                } else s
            }

            is DiaryState.FadingReply -> {
                if (now >= s.nextAt) {
                    binding.inkCanvas.dissolvePass(s.region, s.stage, fadeStages)
                    eink.refreshRegion(s.region.rect(), fast = true)
                    if (s.stage + 1 >= fadeStages) {
                        eink.fullRefresh()
                        DiaryState.Listening()
                    } else {
                        s.copy(stage = s.stage + 1, nextAt = now + 80)
                    }
                } else s
            }
        }
    }

    private fun commitToOracle() {
        oracleJob?.cancel()
        oracleJob = lifecycleScope.launch {
            val bytes = withContext(Dispatchers.Default) {
                runCatching { binding.inkCanvas.toPngBytes() }.getOrNull()
            }
            if (bytes == null) {
                oracleError = "Could not read your writing"
                return@launch
            }
            val result = oracle.ask(bytes)
            result.onSuccess { oracleReply = it }
                .onFailure { oracleError = it.message ?: "The diary is silent" }
        }
    }

    private fun showGuide() {
        val rect = binding.inkCanvas.inkBounds.rect()
        binding.inkCanvas.fillRect(rect, Color.WHITE)
        eink.refreshRegion(rect, fast = false)

        val guide = """
            Write, then rest your pen — Tom replies.
            Flip the stylus eraser — erase.
            Draw a large ? — this guide.
        """.trimIndent()

        val maxW = binding.inkCanvas.width - marginX * 2
        val plan = replyPlanner.plan(guide, marginX, 200, maxW, binding.inkCanvas.height)
        for (stroke in plan.strokes) {
            for (i in stroke.indices) {
                val (x, y) = stroke[i]
                if (i > 0) {
                    val (px, py) = stroke[i - 1]
                    binding.inkCanvas.drawReplyLine(px, py, x, y)
                } else {
                    binding.inkCanvas.drawReplyPoint(x, y)
                }
            }
        }
        eink.refreshRegion(plan.region.rect(), fast = false)
        binding.inkCanvas.clearInkData()
    }

    private fun drawThinkingBlot(on: Boolean) {
        val margin = 48
        val cx = margin + 14
        val cy = binding.inkCanvas.height - margin - 14
        val rect = Rect(cx - 14, cy - 14, cx + 14, cy + 14)
        binding.inkCanvas.fillRect(rect, if (on) Color.BLACK else Color.WHITE)
        eink.refreshRegion(rect, fast = true)
    }
}

private fun BoundingBox.copy(): BoundingBox = BoundingBox(left, top, right, bottom)