package com.example.scraper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    
    private lateinit var tvLogs: TextView
    private val logBuilder = StringBuilder()

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val logMessage = intent?.getStringExtra("LOG") ?: return
            appendLog(logMessage)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val tvStatus = TextView(this).apply {
            text = "Status: Monitoring...\nEnsure Accessibility is ENABLED."
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val btnSettings = android.widget.Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        
        val btnTest = android.widget.Button(this).apply {
            text = "Open Multipack App (Start Scraping)"
            setOnClickListener {
                val launchIntent = packageManager.getLaunchIntentForPackage("kr.co.gmone.multipack_connected_v2")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    android.widget.Toast.makeText(context, "App not installed!", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        tvLogs = TextView(this).apply {
            text = "Waiting for data..."
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(tvLogs)
        }

        layout.addView(tvStatus)
        layout.addView(btnSettings)
        layout.addView(btnTest)
        layout.addView(scrollView)

        setContentView(layout)
        
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver, android.content.IntentFilter("SCRAPER_UPDATE")
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }
    
    private fun appendLog(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logBuilder.append("[$timestamp] $msg\n")
        
        // Keep last 30 lines
        val lines = logBuilder.toString().split("\n")
        if (lines.size > 30) {
            logBuilder.setLength(0)
            logBuilder.append(lines.takeLast(30).joinToString("\n"))
        }
        
        tvLogs.text = logBuilder.toString()
    }
}
