package com.nusuk.helper

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*
import kotlin.math.abs

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnRefill: Button

    private lateinit var geminiService: GeminiService
    private var isPlaying = false
    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        geminiService = GeminiService(applicationContext)
        initFloatingView()
    }

    private fun initFloatingView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)

        tvStatus = floatingView.findViewById(R.id.tv_status)
        btnToggle = floatingView.findViewById(R.id.btn_toggle)
        btnRefill = floatingView.findViewById(R.id.btn_refill)
        val btnClose = floatingView.findViewById<Button>(R.id.btn_close)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        btnToggle.setOnClickListener {
            if (isPlaying) {
                stopAutoPlay()
            } else {
                startAutoPlay()
            }
        }

        btnRefill.setOnClickListener {
            scope.launch {
                tvStatus.text = "جارِ جلب رمز المفتاح..."
                val code = DoorKeyRefill.fetchNextCode()
                if (code != null) {
                    tvStatus.text = "الرمز: $code"
                } else {
                    tvStatus.text = "فشل جلب الرمز"
                }
            }
        }

        btnClose.setOnClickListener {
            stopAutoPlay()
            stopSelf()
        }

        windowManager.addView(floatingView, params)
    }

    private fun startAutoPlay() {
        val acc = NusukAccessibilityService.instance
        if (acc == null) {
            tvStatus.text = "يرجى تفعيل إمكانية الوصول أولاً"
            return
        }
        isPlaying = true
        btnToggle.text = "إيقاف"
        btnToggle.setBackgroundColor(0xFFE53935.toInt())
        tvStatus.text = "يعمل تلقائياً..."

        val prefs = getSharedPreferences("nusuk_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        loopJob = scope.launch(Dispatchers.Default) {
            while (isActive && isPlaying) {
                delay(1200)
                try {
                    val nodes = NusukAccessibilityService.instance?.findTextNodes() ?: emptyList()
                    val allTexts = nodes.map { it.text.toString().trim() }

                    // Check for Mubarak / play again screen
                    val playAgainNode = nodes.find {
                        val t = it.text?.toString() ?: ""
                        t.contains("العب مرة أخرى") || t.contains("ابدأ") || t.contains("مبارك")
                    }
                    if (playAgainNode != null) {
                        withContext(Dispatchers.Main) { tvStatus.text = "فتح باب جديد..." }
                        NusukAccessibilityService.instance?.clickNode(playAgainNode)
                        delay(2000)
                        continue
                    }

                    // Detect question and 4 options
                    // Nusuk usually displays options with clickable containers or text nodes
                    val questionNode = nodes.find {
                        val t = it.text?.toString() ?: ""
                        t.endsWith("؟") || t.contains("ما ") || t.contains("من ") || t.contains("كم ") || t.contains("في أي")
                    }

                    if (questionNode != null) {
                        val qText = questionNode.text.toString().trim()
                        val optionNodes = nodes.filter {
                            it != questionNode &&
                            !it.text.isNullOrBlank() &&
                            it.text.length < 50 &&
                            !it.text.contains("تحدي نسك") &&
                            !it.text.contains("تأكيد الإجابة")
                        }.take(4)

                        if (optionNodes.size >= 2) {
                            val options = optionNodes.map { it.text.toString().trim() }
                            withContext(Dispatchers.Main) { tvStatus.text = "حل: ${qText.take(15)}..." }

                            var ans = geminiService.matchLocalAnswer(qText, options)
                            if (ans == null && apiKey.isNotBlank()) {
                                withContext(Dispatchers.Main) { tvStatus.text = "استشارة Gemini AI..." }
                                ans = geminiService.askGemini(apiKey, qText, options)
                            }

                            if (ans != null) {
                                val targetNode = optionNodes.find {
                                    val t = it.text.toString().trim()
                                    t.contains(ans) || ans.contains(t)
                                } ?: optionNodes.first()

                                withContext(Dispatchers.Main) { tvStatus.text = "تم اختيار: ${targetNode.text}" }
                                NusukAccessibilityService.instance?.clickNode(targetNode)
                                delay(600)

                                // Click Confirm button
                                val confirmNode = nodes.find { it.text?.toString()?.contains("تأكيد") == true }
                                if (confirmNode != null) {
                                    NusukAccessibilityService.instance?.clickNode(confirmNode)
                                }
                                delay(2200)
                            } else {
                                withContext(Dispatchers.Main) { tvStatus.text = "سؤال غير معروف" }
                                delay(3000)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun stopAutoPlay() {
        isPlaying = false
        loopJob?.cancel()
        btnToggle.text = "تشغيل"
        btnToggle.setBackgroundColor(0xFF4CAF50.toInt())
        tvStatus.text = "متوقف"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoPlay()
        scope.cancel()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
