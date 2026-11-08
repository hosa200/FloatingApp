package com.nusuk.helper

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnRefill: Button

    private lateinit var geminiService: GeminiService
    private lateinit var doorKeyRefill: DoorKeyRefill

    private var isPlaying = false
    private var isRefilling = false
    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 4 sections coordinates (السيرة النبوية, القرآن الكريم, المدينة المنورة, مكة المكرمة)
    private val sections = listOf(
        Pair(0.27f, 0.450f),
        Pair(0.72f, 0.450f),
        Pair(0.27f, 0.700f),
        Pair(0.72f, 0.700f)
    )
    private var sectionIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        geminiService = GeminiService(applicationContext)
        doorKeyRefill = DoorKeyRefill(applicationContext)
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
                executeKeyRefill(1)
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

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()

        loopJob = scope.launch(Dispatchers.Default) {
            while (isActive && isPlaying) {
                if (isRefilling) {
                    delay(1000)
                    continue
                }
                delay(900)
                try {
                    val service = NusukAccessibilityService.instance ?: continue
                    val nodes = service.findTextNodes()
                    val allTexts = nodes.map { it.text.toString().trim() }
                    val fullText = allTexts.joinToString(" ")

                    // 1. Detect "No Keys" modal
                    if (allTexts.any { it.contains("لا تملك مفاتيح") || it.contains("نفدت المفاتيح") }) {
                        withContext(Dispatchers.Main) { tvStatus.text = "نفدت المفاتيح -> شحن تلقائي..." }
                        // Close modal by clicking "إغلاق" or tapping background
                        val closeBtn = nodes.find { it.text?.toString()?.contains("إغلاق") == true }
                        if (closeBtn != null) {
                            service.clickNode(closeBtn)
                        } else {
                            service.tapCoordinate(screenW * 0.50f, screenH * 0.83f)
                        }
                        delay(1200)
                        executeKeyRefill(5)
                        continue
                    }

                    // 2. Detect Result screen ("مبارك!", "صحيحة", "العب مرة أخرى")
                    val isResultScreen = allTexts.any {
                        it.contains("مبارك") || it.contains("العب مرة أخرى") || it.contains("إجابة صحيحة") || it.contains("خيرها في غيرها")
                    }
                    if (isResultScreen) {
                        withContext(Dispatchers.Main) { tvStatus.text = "العب مرة أخرى..." }
                        val playAgainNode = nodes.find {
                            val t = it.text?.toString() ?: ""
                            t.contains("العب مرة أخرى") || t.contains("العب") || t.contains("متابعة")
                        }
                        if (playAgainNode != null) {
                            service.clickNode(playAgainNode)
                        }
                        service.tapCoordinate(screenW * 0.50f, screenH * 0.858f)
                        delay(1800)
                        continue
                    }

                    // 3. Detect "افتح الباب" popup dialog
                    val openDoorDialog = allTexts.any { it.contains("هل تريد استخدام مفتاحك") || it.contains("ستفقد مفتاح") }
                    val openDoorBtn = nodes.find { it.text?.toString()?.contains("افتح الباب") == true }
                    if (openDoorDialog || openDoorBtn != null) {
                        withContext(Dispatchers.Main) { tvStatus.text = "تأكيد: افتح الباب..." }
                        if (openDoorBtn != null) {
                            service.clickNode(openDoorBtn)
                        }
                        service.tapCoordinate(screenW * 0.50f, screenH * 0.817f)
                        delay(2200)
                        continue
                    }

                    // 4. Detect Categories / Sections screen (السيرة النبوية, القرآن الكريم, المدينة, مكة)
                    val isCategoriesScreen = allTexts.any {
                        it.contains("السيرة النبوية") || it.contains("القرآن الكريم") || it.contains("مكة المكرمة") || it.contains("المدينة المنورة")
                    } && !allTexts.any { it.endsWith("؟") }

                    if (isCategoriesScreen) {
                        withContext(Dispatchers.Main) { tvStatus.text = "فتح قسم/باب جديد..." }
                        val sec = sections[sectionIndex % 4]
                        sectionIndex++
                        service.tapCoordinate(screenW * sec.first, screenH * sec.second)
                        delay(1000)

                        // Immediately click "افتح الباب" on popup
                        service.tapCoordinate(screenW * 0.50f, screenH * 0.817f)
                        delay(2200)
                        continue
                    }

                    // 5. Detect Question Screen
                    val questionNode = nodes.find {
                        val t = it.text?.toString() ?: ""
                        (t.endsWith("؟") || t.contains("ما ") || t.contains("من ") || t.contains("كم ") || t.contains("في أي") || t.contains("ماذا")) &&
                        !t.contains("هل تريد استخدام")
                    }

                    if (questionNode != null) {
                        val qText = questionNode.text.toString().trim()
                        val optionNodes = nodes.filter {
                            it != questionNode &&
                            !it.text.isNullOrBlank() &&
                            it.text.length < 65 &&
                            !it.text.contains("تحدي نسك") &&
                            !it.text.contains("تحقق") &&
                            !it.text.contains("تأكيد") &&
                            !it.text.contains("فاختر الإجابة") &&
                            !it.text.contains("لتضمن دخولك")
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

                                withContext(Dispatchers.Main) { tvStatus.text = "اختيار: ${targetNode.text}" }
                                service.clickNode(targetNode)
                                delay(600)

                                // Click Confirm / Verify button ("تحقق من إجابتك")
                                withContext(Dispatchers.Main) { tvStatus.text = "تحقق من إجابتك..." }
                                val confirmNode = service.findTextNodes().find {
                                    val t = it.text?.toString() ?: ""
                                    t.contains("تحقق") || t.contains("تأكيد")
                                }
                                if (confirmNode != null) {
                                    service.clickNode(confirmNode)
                                }
                                service.tapCoordinate(screenW * 0.50f, screenH * 0.903f)
                                delay(2200)
                            } else {
                                withContext(Dispatchers.Main) { tvStatus.text = "سؤال غير مسجل" }
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

    private suspend fun executeKeyRefill(count: Int = 1) = withContext(Dispatchers.Main) {
        val service = NusukAccessibilityService.instance ?: return@withContext
        if (isRefilling) return@withContext
        isRefilling = true
        tvStatus.text = "بدء شحن المفاتيح..."

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()

        try {
            // 1. Switch to "مفاتيحك" tab (middle-bottom tab: x = 50%, y = 87.6%)
            service.tapCoordinate(screenW * 0.50f, screenH * 0.876f)
            delay(1200)

            for (i in 0 until count) {
                val code = doorKeyRefill.getNextCode() ?: break
                tvStatus.text = "شحن كود ($i/$count): $code"

                // Click input field (y = 60.2%)
                service.tapCoordinate(screenW * 0.50f, screenH * 0.602f)
                delay(400)

                // Paste code into the input field via Accessibility or Clipboard
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code", code))

                val inputNode = service.findTextNodes().find {
                    it.isEditable || it.className?.toString()?.contains("EditText") == true
                }
                if (inputNode != null) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
                    }
                    inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
                delay(300)

                // Click "تفعيل" / "تحقق من الرمز" button (y = 68.8%)
                service.tapCoordinate(screenW * 0.50f, screenH * 0.688f)
                delay(2200)

                // Dismiss success popup ("مبارك! تم إضافة مفتاح") (y = 85.8%)
                service.tapCoordinate(screenW * 0.50f, screenH * 0.858f)
                delay(800)
            }

            // 2. Switch back to "العب" tab (right-bottom tab: x = 90%, y = 87.6%)
            tvStatus.text = "تم الشحن! العودة للعب..."
            service.tapCoordinate(screenW * 0.90f, screenH * 0.876f)
            delay(1200)
        } finally {
            isRefilling = false
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
