package com.example.scraper

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

private const val TAG = "MULTIPACK_DATA"

class MinimalScraperService : AccessibilityService() {

    private val database by lazy { 
        Firebase.database("https://mycarserver-fb85e-default-rtdb.firebaseio.com/").reference.child("car_status")
    }

    private val fuelRegex = Regex("""(\d+)L""")
    private val mileageRegex = Regex("""([\d,]+)km""")
    private val oilRegex = Regex("""(\d+)%""")
    private val rangeRegex = Regex("""주행가능.*?(\d+)km""")
    private val batteryRegex = Regex("""(\d+\.?\d*)V""")
    private val timeRegex = Regex("""\d{2}-\d{2}\s\d{2}:\d{2}""")
    private val tireRegex = Regex("""(\d+)\s*(psi|kpa|kPa)""")
    private val lastUpdateRegex = Regex("""(\d{4}-\d{2}-\d{2}.\d{2}:\d{2}:\d{2})""")

    private var lastFuel = ""
    private var lastMileage = ""
    private var lastOil = ""
    private var lastRange = ""
    private var lastBattery = ""
    private var lastTime = ""
    private var lastTire = ""

    // Scroll State Management
    // [MODIFIED] Reordered states: Down first, then Up.
    private enum class ScrollState { IDLE, SCROLLING_DOWN, SCROLLING_UP, DONE }
    private var scrollState = ScrollState.IDLE
    
    private var lastScrollTime = 0L
    private val SCROLL_COOLDOWN_MS = 1500L // Reduced from 2500L
    private var scrollAttemptCount = 0
    
    private var lastKnownTimestamp = ""
    private val MAX_UP_ATTEMPTS = 1 
    private val MAX_DOWN_ATTEMPTS = 2 
    
    private val requiredKeys = setOf("oil", "range", "battery_life", "battery", "mileage")
    private val foundKeys = mutableSetOf<String>()
    private var sweepCycleCount = 0
    private val MAX_SWEEP_CYCLES = 3

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val WATCHDOG_INTERVAL_MS = 60000L
    
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (scrollState == ScrollState.DONE || scrollState == ScrollState.IDLE) {
                Log.i(TAG, "WATCHDOG: Triggering periodic re-scan maintenance.")
                scrollState = ScrollState.IDLE
                val root = rootInActiveWindow
                if (root != null) executeScrollLogic(root)
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }
    
    // ‼️ (New) Auto-Refresh Loop (10 Minutes)
    private val AUTO_REFRESH_INTERVAL_MS = 10 * 60 * 1000L 
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "⏰ Auto-Refresh Triggered (10 min interval)")
            wakeDevice() // [NEW] Wake Screen
            
            // Wait 1s for screen to wake/unlock, then refresh
            handler.postDelayed({
                performRefreshClick()
            }, 1000L)
            
            handler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS)
        }
    }
    
    // [NEW] Wake Logic (Enhanced)
    private fun wakeDevice() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            
            // 1. Check if screen is already on
            if (!pm.isInteractive) {
                Log.i(TAG, "Screen is OFF. Attempting Global Action HOME to wake...")
                // Try Waking via Home Button
                performGlobalAction(GLOBAL_ACTION_HOME)
                
                // Backup: WakeLock (Deprecated but good backup)
                val wakeLock = pm.newWakeLock(
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or 
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, 
                    "Scraper:WakeLock"
                )
                wakeLock.acquire(1000L) // Pulse wake
                wakeLock.release()
            } else {
                 Log.i(TAG, "Screen is already ON.")
            }
            
            // 2. Unlock Gesture (Swipe Up) - Dispatched even if already on, to clear Keyguard
             handler.postDelayed({
                 Log.i(TAG, "Dispatching Unlock Swipe (Up)...")
                 dispatchSwipe(540f, 1800f, 540f, 500f, 300L) 
             }, 500L) // Wait 500ms after wake
             
        } catch (e: Exception) {
            Log.e(TAG, "Wake Failed: ${e.message}")
        }
    }


    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
                com.google.firebase.FirebaseApp.initializeApp(this)
                Log.i(TAG, "Firebase Initialized Manually in Service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Init Failed: ${e.message}")
        }
        Log.e(TAG, "Scraper Service Connected! Ready to extract.")
        android.widget.Toast.makeText(applicationContext, "Scraper Service Started!", android.widget.Toast.LENGTH_LONG).show()
        
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        handler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS) // Start 10-min loop
        
        database.child("cmd_refresh").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val timestamp = snapshot.getValue(Long::class.java) ?: 0L
                val now = System.currentTimeMillis()
                
                if (now - timestamp < 30000) {
                     Log.i(TAG, "RECEIVED REFRESH COMMAND ($timestamp)")
                     wakeDevice() // [NEW] Wake on remote command
                     handler.postDelayed({ performRefreshClick() }, 1000L)
                } else {
                     Log.d(TAG, "Ignored old command: $timestamp (Diff: ${now - timestamp})")
                }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: event?.source
        
        Log.d(TAG, "EventPkg: ${event?.packageName} Type: ${event?.eventType}")

        if (root != null) {
            if (event?.packageName?.toString()?.contains("multipack") == true) {
                 if (ensureStatusTabSelected(root)) {
                     return 
                 }

                 // Start the machine if IDLE
                 if (scrollState == ScrollState.IDLE) {
                     // [MODIFIED] Start with SCROLLING_DOWN (since user requested "Down first")
                     scrollState = ScrollState.SCROLLING_DOWN
                     Log.w(TAG, ">>> Starting Full Sweep: Scrolling DOWN first <<<")
                     
                      android.os.Handler(android.os.Looper.getMainLooper()).post {
                           android.widget.Toast.makeText(applicationContext, "Scraper: Scrolled DOWN -> Scanning...", android.widget.Toast.LENGTH_SHORT).show()
                      }
                 }
                 
                 traverseAndScrape(root, 0)
                 executeScrollLogic(root)
            }
        }
    }

    private fun ensureStatusTabSelected(root: AccessibilityNodeInfo): Boolean {
        val queue = java.util.LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.contains("상태") || desc.contains("Tab 2")) {
                if (node.isSelected) {
                    return false 
                } else {
                    if (node.isClickable) {
                        Log.i(TAG, "SWITCHING TO STATUS TAB")
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true 
                    }
                }
            }
            for (i in 0 until node.childCount) {
                 node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun executeScrollLogic(root: AccessibilityNodeInfo) {
        val currentTime = System.currentTimeMillis()
        
        // Define pulseRunnable first so we can reference it for rescheduling
        val pulseRunnable = object : Runnable {
            override fun run() {
                val r = rootInActiveWindow
                if (r != null) {
                    executeScrollLogic(r)
                } else {
                    Log.w(TAG, "Pulse: Root is null. Retrying in 500ms...")
                    handler.postDelayed(this, 500L)
                }
            }
        }

        if (currentTime - lastScrollTime < SCROLL_COOLDOWN_MS) {
            // Still cooling down. Reschedule check instead of dying.
            val waitTime = SCROLL_COOLDOWN_MS - (currentTime - lastScrollTime) + 100
            Log.d(TAG, "Cooldown active. Waiting ${waitTime}ms...")
            handler.postDelayed(pulseRunnable, waitTime)
            return
        }
        
        val scrollableNode = findScrollableNode(root)
        if (scrollableNode == null) {
            Log.w(TAG, "No scrollable node found. Retrying in 1s...")
            handler.postDelayed(pulseRunnable, 1000L)
            return
        }

        when (scrollState) {
            // [MODIFIED] SCROLLING_DOWN is now the first phase
            ScrollState.SCROLLING_DOWN -> {
                Log.d(TAG, "State: SCROLLING_DOWN (Attempt $scrollAttemptCount)")
                
                if (scrollAttemptCount < MAX_DOWN_ATTEMPTS) {
                    Log.d(TAG, "Swiping UP (to scroll DOWN). Attempt $scrollAttemptCount")
                    // Scroll DOWN = Finger moves UP. Use 1000L for slow read.
                    dispatchSwipe(540f, 1600f, 540f, 600f, 1000L)
                    lastScrollTime = currentTime
                    scrollAttemptCount++
                    handler.postDelayed(pulseRunnable, 2000L) // Wait > Cooldown
                } else {
                    Log.w(TAG, "Max DOWN attempts reached. Switching to UP (Resetting to Top).")
                     android.os.Handler(android.os.Looper.getMainLooper()).post {
                          android.widget.Toast.makeText(applicationContext, "Scraper: Reached Bottom -> Scrolling UP", android.widget.Toast.LENGTH_SHORT).show()
                     }
                    
                    // Transition to SCROLLING_UP
                    // Transition to SCROLLING_UP
                    scrollState = ScrollState.SCROLLING_UP
                    scrollAttemptCount = 0
                    lastScrollTime = currentTime
                    handler.postDelayed(pulseRunnable, 2000L)
                }
            }
            
            // [MODIFIED] SCROLLING_UP is now the second phase (Returns to top, then checks)
            ScrollState.SCROLLING_UP -> {
                Log.d(TAG, "State: SCROLLING_UP (Attempt $scrollAttemptCount)")
                if (scrollAttemptCount < MAX_UP_ATTEMPTS) {
                    Log.d(TAG, "Swiping DOWN (to scroll UP). Attempt $scrollAttemptCount")
                    dispatchSwipe(540f, 600f, 540f, 1600f, 500L)
                    lastScrollTime = currentTime
                    scrollAttemptCount++
                    handler.postDelayed(pulseRunnable, 2000L)
                } else {
                    Log.w(TAG, "Max UP attempts reached. Checking Goals...")
                    
                    val missing = requiredKeys - foundKeys
                    if (missing.isEmpty()) {
                        Log.i(TAG, "ALL DATA FOUND! Stopping sweep.")
                         android.os.Handler(android.os.Looper.getMainLooper()).post {
                             android.widget.Toast.makeText(applicationContext, "Scraping Complete! All Data Found.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        sendData("refresh_status", "success") // Notify completion
                        scrollState = ScrollState.DONE
                    } else {
                        if (sweepCycleCount < MAX_SWEEP_CYCLES) {
                            Log.w(TAG, "MISSING DATA: $missing. Restarting Sweep (Cycle ${sweepCycleCount + 1})")
                             android.os.Handler(android.os.Looper.getMainLooper()).post {
                                 android.widget.Toast.makeText(applicationContext, "Missing: $missing. Re-scanning...", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            // Restart -> Start DOWN again
                            scrollState = ScrollState.SCROLLING_DOWN
                            scrollAttemptCount = 0
                            sweepCycleCount++
                            handler.postDelayed(pulseRunnable, 2000L)
                        } else {
                           Log.w(TAG, "Max Cycles Reached. Giving up. Missing: $missing")
                           android.os.Handler(android.os.Looper.getMainLooper()).post {
                                 android.widget.Toast.makeText(applicationContext, "Gave up. Missing: $missing", android.widget.Toast.LENGTH_LONG).show()
                            }
                           scrollState = ScrollState.DONE
                        }
                    }
                }
            }
            ScrollState.DONE -> {}
            else -> {}
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node

        for (i in 0 until node.childCount) {
            val childResult = findScrollableNode(node.getChild(i))
            if (childResult != null) return childResult
        }
        return null
    }

    private fun performRefreshClick() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "performRefreshClick: rootInActiveWindow is NULL!")
            return
        }
        
        val targetX = 990f
        val targetY = 414f // Adjusted for physical device (Header center)
        
        Log.i(TAG, "DISPATCHING GESTURE CLICK at ($targetX, $targetY)")
        
        // Start of Refresh: Mark status as 'scanning'
        sendData("refresh_status", "scanning")
        
        dispatchClick(targetX, targetY)
        
        scrollState = ScrollState.IDLE
        
        foundKeys.clear()
        sweepCycleCount = 0
        Log.i(TAG, "State Reset to IDLE -> Ready for new sweep after refresh")
        
        sendData("refresh_action", "CLICKED_AT_${System.currentTimeMillis()}")
        
        handler.postDelayed({
            Log.i(TAG, "KICKSTART: Forcing scroll check 3s after refresh.")
            if (scrollState == ScrollState.IDLE) {
                 // [MODIFIED] Start DOWN first
                 scrollState = ScrollState.SCROLLING_DOWN
            }
            val currentRoot = rootInActiveWindow
            if (currentRoot != null) {
                executeScrollLogic(currentRoot)
            }
        }, 3000L)
    }

    private fun dispatchClick(x: Float, y: Float) {
        val path = android.graphics.Path()
        path.moveTo(x, y)
        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d(TAG, "GESTURE COMPLETED successfully")
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.e(TAG, "GESTURE CANCELLED by system")
            }
        }, null)
    }

    private fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = android.graphics.Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d(TAG, "SWIPE GESTURE COMPLETED")
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.e(TAG, "SWIPE GESTURE CANCELLED")
            }
        }, null)
    }

    private fun traverseAndScrape(node: AccessibilityNodeInfo?, depth: Int, previousText: String? = null): String? {
        if (node == null) return previousText
        
        var currentContext = previousText

        val debugText = node.text?.toString()
        val debugDesc = node.contentDescription?.toString()
        val text = debugText ?: debugDesc

        if (text != null && text.isNotEmpty()) {
             parseText(text, currentContext ?: "", node)
             currentContext = text 
        }

        for (i in 0 until node.childCount) {
            currentContext = traverseAndScrape(node.getChild(i), depth + 1, currentContext)
        }
        return currentContext
    }

    private fun parseText(text: String, contextLabel: String, node: AccessibilityNodeInfo? = null) {
        val rect = android.graphics.Rect()
        node?.getBoundsInScreen(rect)
        Log.d(TAG, "Parsing: '$text' (Context: '$contextLabel') Bounds: $rect")

        if (text.contains("요청시간이 초과되었습니다")) {
            Log.w(TAG, "Found Timeout Popup! Attempting to dismiss...")
            val confirmNode = findNodeByText(rootInActiveWindow, "확인")
            if (confirmNode != null && confirmNode.isClickable) {
                confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "CLICKED 'Confirm' on Timeout Popup")
                return 
            }
        }

        if (text.contains("차량정보 수신") || lastUpdateRegex.containsMatchIn(text)) {
            lastUpdateRegex.find(text)?.let {
                val timestamp = it.value
                Log.i(TAG, "DETECTED_LAST_UPDATE: $timestamp")
                sendData("last_update", timestamp)
                
                if (lastKnownTimestamp != timestamp) {
                    Log.i(TAG, "Timestamp Changed ($lastKnownTimestamp -> $timestamp). Triggering Scroll Sweep.")
                    lastKnownTimestamp = timestamp
                    scrollState = ScrollState.IDLE 
                }
            }
        }

        if (timeRegex.matches(text)) {
            if (text != lastTime) {
                lastTime = text
                Log.i(TAG, "DETECTED_TIME: $text")
                sendData("LastUpdate", text)
            }
        }

        if (text.endsWith("%") && (contextLabel.contains("배터리") || contextLabel.contains("Battery"))) {
             if (contextLabel.contains("온도") || contextLabel.contains("Temp")) {
                 val valStr = text.replace("%", "")
                 if (valStr.toIntOrNull() != null) {
                     Log.i(TAG, "DETECTED_BATTERY_LEVEL: $valStr")
                     sendData("battery_level", valStr)
                 }
             } else {
                 val valStr = text.replace("%", "")
                 Log.i(TAG, "DETECTED_BATTERY_LIFE: $valStr") 
                 sendData("battery_life", valStr) 
             }
        }

        if (text.endsWith("%") && (contextLabel.contains("수명") || contextLabel.contains("Lifespan") || contextLabel.contains("Oil"))) {
             if (!contextLabel.contains("배터리")) {
                 oilRegex.find(text)?.let {
                    val value = it.groupValues[1]
                    Log.i(TAG, "DETECTED_OIL: $value")
                    sendData("oil", value)
                 }
             }
        }

        if (text.endsWith("V")) {
            batteryRegex.find(text)?.let {
                val bat = it.groupValues[1]
                Log.i(TAG, "DETECTED_BATTERY: $bat")
                sendData("battery", bat)
            }
        }

        if (text.endsWith("km")) {
            mileageRegex.find(text)?.let {
                val value = it.groupValues[1]
                Log.i(TAG, "DETECTED_MILEAGE: $value")
                if (contextLabel.contains("주행가능") || contextLabel.contains("Range")) {
                     sendData("range", value)
                } else {
                     sendData("mileage", value)
                }
            }
        }
        
        if (text.endsWith("L")) {
            fuelRegex.find(text)?.let {
                val value = it.groupValues[1]
                Log.i(TAG, "DETECTED_FUEL: $value")
                sendData("fuel", value)
            }
        }
        
        if (text.endsWith("psi") || text.endsWith("kpa") || text.endsWith("kPa")) {
            tireRegex.find(text)?.let {
                Log.i(TAG, "DETECTED_TIRE: $text")
                sendData("tire_pressure", text)
            }
        }
    }
    

    private val lastSentValues = mutableMapOf<String, Any>()

    private fun sendData(key: String, value: Any) {
         if (requiredKeys.contains(key)) {
            foundKeys.add(key)
        }
        val intent = android.content.Intent("SCRAPER_UPDATE")
        intent.putExtra("LOG", "$key: $value")
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        if (lastSentValues[key] != value) {
            lastSentValues[key] = value
            try {
                database.child(key).setValue(value).addOnFailureListener {
                    Log.e(TAG, "Firebase Write Error: ${it.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Firebase Error: ${e.message}")
            }
        }
    }


    private fun findNodeByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = java.util.LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll()
            if (node?.text?.toString()?.contains(text) == true) {
                return node
            }
            for (i in 0 until (node?.childCount ?: 0)) {
                node?.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
    override fun onInterrupt() {}
}
