package com.nusuk.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnOverlay = findViewById<Button>(R.id.btn_overlay_perm)
        val btnAcc = findViewById<Button>(R.id.btn_acc_perm)
        val btnSaveKey = findViewById<Button>(R.id.btn_save_key)
        val btnStart = findViewById<Button>(R.id.btn_start_floating)
        etApiKey = findViewById(R.id.et_api_key)

        val prefs = getSharedPreferences("nusuk_prefs", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("gemini_api_key", ""))

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "صلاحية العرض مفعلة بالفعل", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnAcc.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "فعّل خدمة Nusuk AI Helper في القائمة", Toast.LENGTH_LONG).show()
        }

        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            prefs.edit().putString("gemini_api_key", key).apply()
            Toast.makeText(this, "تم حفظ مفتاح API بنجاح", Toast.LENGTH_SHORT).show()
        }

        btnStart.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "يرجى منح صلاحية العرض فوق التطبيقات أولاً", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val serviceIntent = Intent(this, FloatingWidgetService::class.java)
            startService(serviceIntent)
            Toast.makeText(this, "تم تشغيل النافذة العائمة! افتح الآن تطبيق نسك", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
