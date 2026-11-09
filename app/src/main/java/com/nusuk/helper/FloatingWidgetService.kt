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

        val realDm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realDm)
        val realW = realDm.widthPixels.toFloat()
        val realH = realDm.heightPixels.toFloat()

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
                    val allTexts = nodes.map { service.getNodeText(it) }
                    val fullText = allTexts.joinToString(" ")

                    // 1. Detect "No Keys" modal
                    if (allTexts.any { it.contains("لا تملك مفاتيح") || it.contains("نفدت المفاتيح") }) {
                        withContext(Dispatchers.Main) { tvStatus.text = "نفدت المفاتيح -> شحن تلقائي..." }
                        // Close modal by clicking "إغلاق" or tapping background
                        val closeBtn = nodes.find {
                            val t = service.getNodeText(it)
                            t.contains("إغلاق")
                        }
                        if (closeBtn != null) {
                            service.clickNode(closeBtn)
                        } else {
                            service.tapCoordinate(screenW * 0.50f, screenH * 0.77f)
                        }
                        delay(1200)
                        executeKeyRefill(5)
                        continue
                    }

                    // 1b. Guard: If on Keys screen and not currently refilling, return to "العب" tab
                    val isOnKeysScreen = allTexts.any {
                        it.contains("أدخل الرمز") || it.contains("أدخل رمز") || it.contains("مفاتيح اليوم") || it.contains("تفعيل مفتاح جديد") || it.contains("مفتاح كل يوم")
                    }
                    if (isOnKeysScreen) {
                        withContext(Dispatchers.Main) { tvStatus.text = "العودة إلى اللعب..." }
                        val playTab = nodes.find {
                            val t = service.getNodeText(it)
                            t.contains("العب") || t.contains("مسابقاتنا") || t.contains("المسابقات")
                        }
                        if (playTab != null) {
                            service.clickNode(playTab)
                        } else {
                            service.tapCoordinate(screenW * 0.90f, screenH * 0.92f)
                        }
                        delay(1500)
                        continue
                    }

                    // 2. Detect Result screen ("مبارك!", "صحيحة", "العب مرة أخرى")
                    val isResultScreen = allTexts.any {
                        it.contains("مبارك") || it.contains("العب مرة أخرى") || it.contains("إجابة صحيحة") || it.contains("خيرها في غيرها")
                    }
                    if (isResultScreen) {
                        withContext(Dispatchers.Main) { tvStatus.text = "العب مرة أخرى..." }
                        val playAgainNode = nodes.find {
                            val t = service.getNodeText(it)
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
                    val openDoorBtn = nodes.find { service.getNodeText(it).contains("افتح الباب") }
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
                    } && !allTexts.any { it.contains("؟") }

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
                        val t = service.getNodeText(it)
                        (t.contains("؟") || (t.length in 15..120 && (t.startsWith("ما ") || t.startsWith("من ") || t.startsWith("كم ") || t.startsWith("في أي") || t.startsWith("أين ") || t.startsWith("متى ") || t.startsWith("ماذا")))) &&
                        !t.contains("هل تريد استخدام") &&
                        !t.contains("تمكنك من") &&
                        !t.contains("مفاتيح") &&
                        !t.contains("أدخل رمز") &&
                        !t.contains("تحدي نسك")
                    }

                    if (questionNode != null) {
                        val qText = service.getNodeText(questionNode).trim()
                        val optionNodes = nodes.filter {
                            it != questionNode &&
                            service.getNodeText(it).isNotBlank() &&
                            service.getNodeText(it).length < 65 &&
                            !service.getNodeText(it).contains("تحدي نسك") &&
                            !service.getNodeText(it).contains("تحقق") &&
                            !service.getNodeText(it).contains("تأكيد") &&
                            !service.getNodeText(it).contains("فاختر الإجابة") &&
                            !service.getNodeText(it).contains("لتضمن دخولك") &&
                            !service.getNodeText(it).contains("مفاتيح")
                        }.take(4)

                        if (optionNodes.size >= 2) {
                            val options = optionNodes.map { service.getNodeText(it).trim() }
                            withContext(Dispatchers.Main) { tvStatus.text = "حل: ${qText.take(15)}..." }

                            var ans = geminiService.matchLocalAnswer(qText, options)
                            if (ans == null && apiKey.isNotBlank()) {
                                withContext(Dispatchers.Main) { tvStatus.text = "استشارة Gemini AI..." }
                                ans = geminiService.askGemini(apiKey, qText, options)
                            }

                            if (ans == null) {
                                withContext(Dispatchers.Main) { tvStatus.text = "اختيار تلقائي للمتابعة..." }
                                ans = options.firstOrNull()
                            }

                            if (ans != null) {
                                val targetNode = optionNodes.find {
                                    val t = service.getNodeText(it).trim()
                                    t.contains(ans) || ans.contains(t)
                                } ?: optionNodes.first()

                                withContext(Dispatchers.Main) { tvStatus.text = "اختيار: ${service.getNodeText(targetNode)}" }
                                service.clickNode(targetNode)
                                delay(600)

                                // Click Confirm / Verify button ("تحقق من إجابتك")
                                withContext(Dispatchers.Main) { tvStatus.text = "تحقق من إجابتك..." }
                                val confirmNode = service.findTextNodes().find {
                                    val t = service.getNodeText(it)
                                    t.contains("تحقق") || t.contains("تأكيد")
                                }
                                if (confirmNode != null) {
                                    service.clickNode(confirmNode)
                                }
                                service.tapCoordinate(screenW * 0.50f, screenH * 0.903f)
                                delay(2200)
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

        val realDm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realDm)
        val realW = realDm.widthPixels.toFloat()
        val realH = realDm.heightPixels.toFloat()

        try {
            // 1. Switch to "المفاتيح" tab (Tab 3 at center: x=50%, y=92%)
            val currentNodes = service.findTextNodes()
            val keysTab = currentNodes.find {
                val t = service.getNodeText(it)
                t.contains("المفاتيح") || t.contains("مفاتيحك") || t.contains("مفاتيح")
            }
            if (keysTab != null) {
                service.clickNode(keysTab)
            } else {
                service.tapCoordinate(screenW * 0.50f, screenH * 0.92f)
            }
            delay(1500)

            for (i in 0 until count) {
                val code = doorKeyRefill.getNextCode() ?: break
                tvStatus.text = "شحن (${i + 1}/$count): $code"

                // Copy code to clipboard
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                delay(250)

                // 1. Tap input box center (x=50%, y=67.5%) and wait 1s for keyboard + clipboard chip to appear
                service.focusInputBox(screenW * 0.50f, screenH * 0.675f)
                delay(1000)

                // 2. Tap the clipboard suggestion chip on top of the keyboard (x=50%, y=62.6%)
                service.pasteFromKeyboardChip(code, screenW, screenH)
                delay(700)

                // 3. Dismiss keyboard and ensure it is completely closed
                service.dismissKeyboard()
                delay(900)
                if (service.isKeyboardShowing()) {
                    service.dismissKeyboard()
                    delay(700)
                }

                // 4. Click "تحقق من الرمز" button using exact physical screen coordinates:
                // Full screen (realH): y = 79.2% (849 / 1072); usable height (screenH): y = 83.1% (848.5 / 1021)
                // IMPORTANT: Do NOT call clickNode(activateBtn) here because Flutter wraps the input box + button
                // in a shared container whose center/ACTION_CLICK re-focuses the EditText and opens the keyboard!
                val verifyBtnX = realW * 0.50f
                val verifyBtnY = maxOf(realH * 0.792f, screenH * 0.831f)
                service.tapCoordinate(verifyBtnX, verifyBtnY)
                delay(2600)

                // 5. Dismiss result popup if any ("إغلاق", "متابعة", "حسناً")
                val popupBtn = service.findTextNodes().find {
                    val t = service.getNodeText(it)
                    !it.isEditable && t.length < 25 && (t.contains("إغلاق") || t.contains("متابعة") || t.contains("حسناً") || t.contains("حسنا"))
                }
                if (popupBtn != null) {
                    service.clickNode(popupBtn)
                    delay(800)
                } else {
                    delay(500)
                }
            }

            // 2. Switch back to "العب" tab (Tab 1 at far right: x=90%, y=92%)
            tvStatus.text = "تم الشحن! العودة للعب..."
            service.dismissKeyboard()
            delay(500)
            val playTab = service.findTextNodes().find {
                val t = service.getNodeText(it)
                t.contains("العب") || t.contains("مسابقاتنا") || t.contains("المسابقات")
            }
            if (playTab != null) {
                service.clickNode(playTab)
            } else {
                service.tapCoordinate(screenW * 0.90f, screenH * 0.92f)
            }
            delay(1500)
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
