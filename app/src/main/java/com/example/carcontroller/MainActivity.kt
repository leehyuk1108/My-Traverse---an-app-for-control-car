package com.example.carcontroller

// (import ... 문들은 기존과 동일)
import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileReader
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity(), CoroutineScope by CoroutineScope(Dispatchers.Main) {

    companion object {
        var isActivityVisible: Boolean = false
        const val PREFS_NAME = "CarAppPrefs"
        const val PREF_KEY_API_URL = "BASE_API_URL"
        const val PREF_KEY_LOCATION_LAT = "LAST_LAT"
        const val PREF_KEY_LOCATION_LON = "LAST_LON"
        const val PREF_KEY_LOCATION_TIME = "LAST_TIME"
        const val PREF_KEY_MAC_ADDRESS = "MY_CHEVROLET_MAC_ADDRESS"
        const val ACTION_REQUEST_STATUS = "ACTION_REQUEST_STATUS"
    }

    private val PREF_KEY_ASKED_BACKGROUND_LOCATION = "ASKED_BACKGROUND_LOCATION"

    private var baseApiUrl: String? = null
    private lateinit var prefs: SharedPreferences

    private lateinit var myWebView: WebView

    private lateinit var locationClient: FusedLocationProviderClient

    // --- 상태 관리 변수 ---
    private var isEngineOn = false
    private var isVentilating = false
    private var isHeatEjecting = false
    private var currentEngineTimerString = ""
    private var currentVentilationTimerString = ""
    private var currentHeatEjectTimerString = ""

    private var currentDrivingTime: String = "00:00:00"
    private var currentDrivingDistance: String = "0.0 km"

    private var isBluetoothConnected = false
    private var isBluetoothConnected_VIRTUAL = false

    private var lastKnownAddress: String = "저장된 위치 없음"
    private var lastKnownTime: String = ""

    private val apiDelay = 5000L
    private var lastApiCallTime: Long = 0
    private val apiCooldownMs = 1000L

    private var isPageLoaded = false
    private var isSetupDialogShowing = false
    
    // [NEW] Track Refresh Request Time
    private var lastRefreshRequestTime: Long = 0

    private data class StatusTuple(val message: String, val timeString: String, val icon: String, val isActive: Boolean)

    // --- Bluetooth 프로필 프록시 ---
    private var a2dpProfile: BluetoothA2dp? = null
    private var headsetProfile: BluetoothHeadset? = null

    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> {
                    a2dpProfile = proxy as BluetoothA2dp
                    Log.d("MainActivity", "A2DP profile connected")
                }
                BluetoothProfile.HEADSET -> {
                    headsetProfile = proxy as BluetoothHeadset
                    Log.d("MainActivity", "HEADSET profile connected")
                }
            }
            checkBluetoothStatus()
            updateDashboardStatus()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> {
                    a2dpProfile = null
                    Log.d("MainActivity", "A2DP profile disconnected")
                }
                BluetoothProfile.HEADSET -> {
                    headsetProfile = null
                    Log.d("MainActivity", "HEADSET profile disconnected")
                }
            }
            checkBluetoothStatus()
            updateDashboardStatus()
        }
    }

    // === ‼️ 1. 통합 권한 요청 런처 ===
    private val requestAllPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->

            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                askBackgroundLocationPermission()
            } else if (permissions.containsKey(Manifest.permission.ACCESS_FINE_LOCATION)) {
                Toast.makeText(this, "위치 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }

            if (permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                permissions[Manifest.permission.BLUETOOTH_SCAN] == true
            ) {
                checkBluetoothStatus()
                updateDashboardStatus()
            } else if (permissions.containsKey(Manifest.permission.BLUETOOTH_CONNECT)) {
                Toast.makeText(this, "블루투스 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }

            if (permissions[Manifest.permission.POST_NOTIFICATIONS] == false) {
                Toast.makeText(this, "알림 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

    private val requestBackgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // (백그라운드) 위치 권한 허용됨
            } else {
                Toast.makeText(this, "백그라운드 위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }

    // (TimerService -> UI)
    private val timerUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra(TimerService.EXTRA_IS_RUNNING, false) ?: false
            val timeString = intent?.getStringExtra(TimerService.EXTRA_TIME_STRING) ?: ""
            val taskType = intent?.getStringExtra(TimerService.EXTRA_TASK_TYPE) ?: ""

            when (taskType) {
                "ENGINE" -> {
                    isEngineOn = isRunning
                    currentEngineTimerString = if (isRunning) timeString else ""
                    if (isRunning) {
                        runJs("updateEngineUI(true)") // ‼️ (Fix) Restore UI state
                        runJs("updateEngineTimer('$timeString')")
                    } else {
                        runJs("updateEngineUI(false)")
                    }
                }
                "VENTILATION" -> {
                    isVentilating = isRunning
                    currentVentilationTimerString = if (isRunning) timeString else ""
                    runJs("updateVentilationUI($isRunning, '$timeString')")
                }
                "HEAT_EJECT" -> {
                    isHeatEjecting = isRunning
                    currentHeatEjectTimerString = if (isRunning) timeString else ""
                    runJs("updateHeatEjectUI($isRunning, '$timeString')")
                }
            }

            updateDashboardStatus()
        }
    }

    // ‼️ (신규) DrivingService -> UI
    private val drivingUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            currentDrivingTime = intent?.getStringExtra(DrivingService.EXTRA_DRIVING_TIME) ?: "00:00:00"
            currentDrivingDistance = intent?.getStringExtra(DrivingService.EXTRA_DRIVING_DISTANCE) ?: "0.0 km"

            updateDashboardStatus()
        }
    }

    // ‼️ (New) Firebase Caching Variables
    private var lastFuel: String? = null
    private var lastMileage: String? = null
    private var lastOil: String? = null
    private var lastTireList: String? = null
    private var lastRange: String? = null
    private var lastBattery: String? = null
    private var lastBatteryLevel: String? = null
    private var lastTire: String? = null
    private var lastTime: String? = null
    private var lastRefreshStatus: String = "" // [NEW] Cache for status

    // ‼️ (New) Receiver for Save Completion
    private val drivingSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MainActivity", "Received ACTION_DRIVING_SAVED")
            loadAndShowLocationData()
            showJsStatus("주행 기록이 저장되었습니다.", "success")
            updateDashboardStatus()
        }
    }

    // (Android OS -> UI) 블루투스 이벤트 수신
    @SuppressLint("MissingPermission")
    private val mainActivityBluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d("MainActivity", "Bluetooth Event Received: $action")

            if (!hasBluetoothConnectPermission()) {
                Log.w("MainActivity", "BT Receiver: CONNECT permission missing.")
                return
            }

            checkBluetoothStatus()
            updateDashboardStatus()
        }
    }

    // ‼️ (New) Firebase Listener for Car Status
    private val firebaseListener = object : com.google.firebase.database.ValueEventListener {
        override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
            val fuel = snapshot.child("fuel").getValue(String::class.java) ?: "--"
            val mileage = snapshot.child("mileage").getValue(String::class.java) ?: "--"
            val oil = snapshot.child("oil").getValue(String::class.java) ?: "--" 
            val range = snapshot.child("range").getValue(String::class.java) ?: "--"
            val battery = snapshot.child("battery").getValue(String::class.java) ?: "--"
            val batteryLevel = snapshot.child("battery_level").getValue(String::class.java) ?: "--"
            val tirePressure = snapshot.child("tire_pressure").getValue(String::class.java) ?: "--"
            val tirePressureAll = snapshot.child("tire_pressure_all").getValue(String::class.java) ?: "--" // [NEW] Read list
            val lastUpdateStr = snapshot.child("last_update").getValue(String::class.java)
            val refreshStatus = snapshot.child("refresh_status").getValue(String::class.java) ?: ""
            
            // [NEW] Edge Trigger: Only show when status CHANGES to 'success'
            // This covers both Manual (Button) and Auto (10min) refreshes.
            if (refreshStatus == "success" && lastRefreshStatus != "success") {
                 val displayTime = lastUpdateStr ?: "Just now"
                 showJsStatus("새로고침 및 수집 완료 ($displayTime)", "success")
            }
            lastRefreshStatus = refreshStatus // Update cache
            
            Log.d("MainActivity", "Firebase Update: Status=$refreshStatus (Prev=$lastRefreshStatus)")
            
            // Cache data
            lastFuel = fuel
            lastMileage = mileage
            lastOil = oil
            lastTireList = tirePressureAll
            lastRange = range
            lastBattery = battery
            lastBatteryLevel = batteryLevel
            lastTire = tirePressure
            lastTime = lastUpdateStr
            
            // Send to WebView if loaded
            if (isPageLoaded) {
                 // updateCarStatus(range, battery, batteryLevel, mileage, fuel, lastUpdate, oil, tire, tireList)
                 // Mapping: Range -> Slot 1, Battery -> Slot 2, Mileage -> Slot 3, Fuel -> Slot 4, Time -> Slot 5
                 val safeFuel = fuel ?: "--"
                 val safeTime = lastUpdateStr ?: ""
                 val safeOil = oil ?: "--"
                 val safeTire = tirePressure ?: "--"
                 val safeTireList = tirePressureAll ?: "" // Pass comma string
                 runJs("updateCarStatus('$range', '$battery', '$batteryLevel', '$mileage', '$safeFuel', '$safeTime', '$safeOil', '$safeTire', '$safeTireList')")
            }
        }

        override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
            Log.e("MainActivity", "Firebase Error", error.toException())
            if (isPageLoaded) {
                 showJsStatus("Firebase 오류: ${error.message}", "danger")
            }
        }
    }


    // ======================================================

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        baseApiUrl = loadUrlFromPrefs()

        locationClient = LocationServices.getFusedLocationProviderClient(this)

        myWebView = findViewById(R.id.webview)
        myWebView.settings.javaScriptEnabled = true
        myWebView.settings.domStorageEnabled = true

        myWebView.addJavascriptInterface(WebAppInterface(), "Android")

        myWebView.webChromeClient = android.webkit.WebChromeClient() // ‼️ (신규) 다이얼로그 지원

        myWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("MainActivity", "WebView page loaded.")
                isPageLoaded = true
                loadAndShowLocationData() // ‼️ (수정) 함수 이름 변경
                updateDashboardStatus()
                
                // [NEW] Send cached car status on reload
                if (lastFuel != null || lastMileage != null) {
                    val safeFuel = lastFuel ?: "--"
                    val safeMileage = lastMileage ?: "--"
                    val safeOil = lastOil ?: "--"
                    val safeRange = lastRange ?: "--"
                    val safeBat = lastBattery ?: "--"
                    val safeBatLevel = lastBatteryLevel ?: "--"
                    val safeTire = lastTire ?: "--"
                    val safeTireList = lastTireList ?: ""
                    val safeTime = lastTime ?: ""
                    
                    runJs("updateCarStatus('$safeRange', '$safeBat', '$safeBatLevel', '$safeMileage', '$safeFuel', '$safeTime', '$safeOil', '$safeTire', '$safeTireList')")
                }

                isSetupComplete()
            }
        }

        myWebView.loadUrl("file:///android_asset/main.html")

        askAllPermissions()

        // TimerService용 브로드캐스트
        LocalBroadcastManager.getInstance(this).registerReceiver(
            timerUpdateReceiver, IntentFilter(TimerService.BROADCAST_TIMER_UPDATE)
        )

        // (신규) DrivingService용 브로드캐스트
        LocalBroadcastManager.getInstance(this).registerReceiver(
            drivingUpdateReceiver, IntentFilter(DrivingService.BROADCAST_DRIVING_UPDATE)
        )
        // ‼️ (New) Reigster Saved Receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            drivingSavedReceiver, IntentFilter(DrivingService.ACTION_DRIVING_SAVED)
        )

        // Bluetooth 브로드캐스트 리시버 등록
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        registerReceiver(mainActivityBluetoothReceiver, filter)

        // ‼️ (New) Connect to Firebase (Explicit URL)
        com.google.firebase.ktx.Firebase.database("https://mycarserver-fb85e-default-rtdb.firebaseio.com/").reference.child("car_status")
            .addValueEventListener(firebaseListener)


        // Bluetooth 프로필 프록시 요청
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        adapter?.getProfileProxy(this, bluetoothProfileListener, BluetoothProfile.A2DP)
        adapter?.getProfileProxy(this, bluetoothProfileListener, BluetoothProfile.HEADSET)
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        requestServiceStatusUpdate()

        if (isSetupComplete()) {
            checkBluetoothStatus()
            updateDashboardStatus()
            loadAndShowLocationData() // ‼️ (수정) 함수 이름 변경
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(timerUpdateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(drivingUpdateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(drivingSavedReceiver)

        unregisterReceiver(mainActivityBluetoothReceiver)

        // Bluetooth 프로필 프록시 해제
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            a2dpProfile?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProfile?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        super.onDestroy()
    }

    // --- 헬퍼 함수 ---

    private fun isSetupComplete(): Boolean {
        if (!isPageLoaded) return false
        if (isSetupDialogShowing) return false

        val savedMacAddress = prefs.getString(PREF_KEY_MAC_ADDRESS, null)
        if (savedMacAddress == null) {
            runJs("switchTab('tab-settings')")

            launch(Dispatchers.Main) {
                isSetupDialogShowing = true
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("필수 설정")
                    .setMessage("앱을 정상적으로 사용하려면, 페어링 목록에서 차량 선택 버튼을 눌러 'myChevrolet' 기기를 등록하세요.")
                    .setPositiveButton("확인", null)
                    .setCancelable(false)
                    .setOnDismissListener {
                        isSetupDialogShowing = false
                    }
                    .show()
            }
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val alreadyAsked = prefs.getBoolean(PREF_KEY_ASKED_BACKGROUND_LOCATION, false)

                    if (!alreadyAsked) {
                        launch(Dispatchers.Main) {
                            isSetupDialogShowing = true
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("필수 설정")
                                .setMessage("자동 주차 위치 저장을 위해, 앱의 위치 권한을 '항상 허용'으로 변경해야 합니다. 앱 설정 화면으로 이동합니다.")
                                .setPositiveButton("권한 설정하기") { _, _ ->
                                    prefs.edit().putBoolean(PREF_KEY_ASKED_BACKGROUND_LOCATION, true).apply()

                                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    val uri = Uri.fromParts("package", packageName, null)
                                    intent.data = uri
                                    startActivity(intent)
                                }
                                .setNegativeButton("나중에") { _, _ ->
                                    prefs.edit().putBoolean(PREF_KEY_ASKED_BACKGROUND_LOCATION, true).apply()
                                }
                                .setCancelable(false)
                                .setOnDismissListener {
                                    isSetupDialogShowing = false
                                }
                                .show()
                        }
                    }
                }
            }
        }

        return true
    }


    private fun requestServiceStatusUpdate() {
        val intent = Intent(this, TimerService::class.java).apply {
            action = ACTION_REQUEST_STATUS
        }
        startService(intent)
    }

    private fun runJs(script: String) {
        if (!isPageLoaded) {
            Log.w("MainActivity", "Page not loaded, skipping JS call: $script")
            return
        }

        launch {
            myWebView.loadUrl("javascript:$script")
        }
    }

    // (통합 권한 요청)
    private fun askAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestAllPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun askBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    // --- 권한 헬퍼 ---

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
        } else {
            (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        }
    }

    // ======================================

    @SuppressLint("MissingPermission")
    private fun checkBluetoothStatus() {
        if (!hasBluetoothScanPermission()) {
            isBluetoothConnected = false
            Log.w("MainActivity", "checkBluetoothStatus: BT/Location Permissions not granted.")
            return
        }

        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter ?: run {
                isBluetoothConnected = false
                Log.w("MainActivity", "checkBluetoothStatus: Adapter null.")
                return
            }

            if (!adapter.isEnabled) {
                isBluetoothConnected = false
                Log.w("MainActivity", "checkBluetoothStatus: Adapter disabled.")
                return
            }

            val savedMacAddress = prefs.getString(PREF_KEY_MAC_ADDRESS, null)

            if (savedMacAddress == null) {
                Log.w("MainActivity", "checkBluetoothStatus: MAC Address not saved. Please use 'Select Car' button in settings.")
                if (isBluetoothConnected) {
                    isBluetoothConnected = false
                    Log.d("MainActivity", "Bluetooth DISCONNECTED (MAC address missing). Stopping DrivingService & Saving location.")
                    launch {
                        stopDrivingServiceAndSaveLocation()
                    }
                }
                return
            }

            val gattDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            val a2dpDevices = a2dpProfile?.connectedDevices ?: emptyList()
            val headsetDevices = headsetProfile?.connectedDevices ?: emptyList()

            val allConnectedDevices = (gattDevices + a2dpDevices + headsetDevices).toSet()

            val previousState = isBluetoothConnected

            isBluetoothConnected = allConnectedDevices.any { it.address == savedMacAddress }

            if (!previousState && isBluetoothConnected) {
                Log.d("MainActivity", "Bluetooth just CONNECTED. Cancelling remote tasks & Starting DrivingService.")
                cancelAllRemoteTasksOnBtConnect()
                startDrivingService()
            } else if (previousState && !isBluetoothConnected) {
                Log.d("MainActivity", "Bluetooth just DISCONNECTED. Stopping DrivingService & Saving location.")
                launch {
                    stopDrivingServiceAndSaveLocation()
                }
            }

            if (previousState != isBluetoothConnected) {
                Log.d("MainActivity", "checkBluetoothStatus: State CHANGED to $isBluetoothConnected")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            isBluetoothConnected = false
        }
    }

    private fun cancelAllRemoteTasksOnBtConnect() {
        if (isEngineOn) {
            Log.d("MainActivity", "BT Connected: Stopping Engine timer.")
            isEngineOn = false
            stopTimerService("ENGINE")
            runJs("updateEngineUI(false)")
        }

        if (isVentilating) {
            Log.d("MainActivity", "BT Connected: Stopping Ventilation macro.")
            isVentilating = false
            stopTimerService("VENTILATION")
            runJs("updateVentilationUI(false, '')")
            setExtrasButtonsDisabled(false)
        }

        if (isHeatEjecting) {
            Log.d("MainActivity", "BT Connected: Stopping Heat Eject macro timer.")
            isHeatEjecting = false
            stopTimerService("HEAT_EJECT")
            runJs("updateHeatEjectUI(false, '')")
            setExtrasButtonsDisabled(false)
        }
    }

    private fun updateDashboardStatus() {
        val isBtOn = isBluetoothConnected || isBluetoothConnected_VIRTUAL

        val (message, timeString, icon, isActive) = when {
            isBtOn -> StatusTuple(
                "주행중",
                "$currentDrivingDistance|$currentDrivingTime",
                "car",
                true
            )
            isHeatEjecting -> StatusTuple("열 배출 모드 실행 중", "($currentHeatEjectTimerString)", "temperature-high", true)
            isVentilating -> StatusTuple("환기 모드 실행 중", "($currentVentilationTimerString)", "wind", true)
            isEngineOn -> StatusTuple("시동 켜짐", "($currentEngineTimerString 남음)", "power-off", true)
            else -> StatusTuple(lastKnownAddress, lastKnownTime, "map-marker-alt", false)
        }
        runJs("updateDashboardStatus('$message', '$timeString', '$icon', $isActive, $isBtOn)")
    }

    private fun setButtonFeedback(buttonId: String, state: String) {
        runJs("setButtonFeedback('$buttonId', '$state')")
    }

    private fun setExtrasButtonsDisabled(disabled: Boolean) {
        runJs("setExtrasButtonsDisabled($disabled)")
    }

    private fun showJsStatus(message: String, type: String, duration: Long = 2000) {
        runJs("showStatus('$message', '$type', $duration)")
    }

    private fun saveUrlToPrefs(url: String) {
        prefs.edit().putString(PREF_KEY_API_URL, url).apply()
    }

    private fun loadUrlFromPrefs(): String? {
        return prefs.getString(PREF_KEY_API_URL, null)
    }

    private fun checkApiCooldown(buttonId: String?): Boolean {
        if (baseApiUrl.isNullOrEmpty()) {
            launch(Dispatchers.Main) {
                runJs("switchTab('tab-settings')")
                runJs("populateApiUrl('(API 키 없음)')")
                showJsStatus("API 키를 먼저 입력하고 저장하세요.", "danger", 3000)
            }
            return false
        }

        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastApiCallTime < apiCooldownMs) {
            launch(Dispatchers.Main) {
                runJs("updateDashboardStatus('명령이 너무 빠릅니다.', '', 'exclamation-triangle', true, false)")

                if (buttonId != null) {
                    setButtonFeedback(buttonId, "cooldown")
                }

                delay(2000)
                updateDashboardStatus()
            }
            return false
        }
        lastApiCallTime = currentTime
        return true
    }

    // --- 네이티브 API 호출 ---
    private suspend fun sendCommandInternal(cmd: String): Boolean {
        if (baseApiUrl.isNullOrEmpty()) {
            launch(Dispatchers.Main) {
                showJsStatus("API 키가 설정되지 않았습니다.", "danger")
            }
            return false
        }

        val fullUrl = "$baseApiUrl&cmd=$cmd"
        return try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val url = URL(fullUrl)
                (url.openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    responseCode
                }.disconnect()
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    // --- 서비스 시작/중지 ---
    private fun startTimerService(taskType: String, duration: Long) {
        if (baseApiUrl.isNullOrEmpty()) {
            launch(Dispatchers.Main) {
                showJsStatus("API 키가 설정되지 않았습니다.", "danger")
            }
            return
        }

        val intent = Intent(this@MainActivity, TimerService::class.java).apply {
            action = TimerService.ACTION_START_TIMER
            putExtra("TIMER_DURATION", duration)
            putExtra("TASK_TYPE", taskType)
            putExtra("BASE_API_URL", baseApiUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTimerService(taskType: String) {
        val intent = Intent(this@MainActivity, TimerService::class.java).apply {
            action = TimerService.ACTION_STOP_TIMER
            putExtra("TASK_TYPE", taskType)
        }
        startService(intent)
    }

    private fun startDrivingService() {
        val intent = Intent(this, DrivingService::class.java).apply {
            action = DrivingService.ACTION_START_DRIVING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopDrivingService() {
        val intent = Intent(this, DrivingService::class.java).apply {
            action = DrivingService.ACTION_STOP_DRIVING
        }
        startService(intent)
    }

    // ‼️ (신규) 중지 -> 딜레이 -> UI 갱신을 위한 헬퍼
    private fun stopDrivingServiceAndSaveLocation() {
        stopDrivingService() // 1. 중지 명령 (DrivingService가 비동기로 저장 시작)

        showJsStatus("주행 기록 저장 중...", "warning")

        // 3. ‼️ Fallback: If broadcast doesn't arrive in 3s, reload anyway
        launch {
             delay(3000)
             loadAndShowLocationData()
        }
    }

    // ‼️ (삭제) saveLastKnownLocation() 함수
    // (이 로직은 DrivingService.saveDrivingSessionAndLocation로 이동했음)

    // ‼️ (수정) 함수 이름 및 로직 변경
    private fun convertLocationToAddress(latString: String, lonString: String, timeString: String,
                                         dist: String, driveTime: String, avgSpeed: String, topSpeed: String) {
        launch(Dispatchers.IO) {
            val addressText = try {
                val geocoder = Geocoder(this@MainActivity, Locale.KOREAN)
                val lat = latString.toDouble()
                val lon = lonString.toDouble()

                @Suppress("DEPRECATION")
                val addresses: MutableList<Address>? = geocoder.getFromLocation(lat, lon, 1)

                if (addresses != null && addresses.isNotEmpty()) {
                    addresses[0].getAddressLine(0)
                } else {
                    "주소를 찾을 수 없음"
                }
            } catch (e: IOException) {
                e.printStackTrace()
                "주소 변환 실패"
            }

            // UI 스레드로 복귀하여 HTML 업데이트
            launch(Dispatchers.Main) {
                lastKnownAddress = addressText
                lastKnownTime = "($timeString)"

                // ‼️ (수정) 6개 인자 모두 전달
                runJs("updateSavedLocation('$addressText', '($timeString)', '$dist', '$driveTime', '$avgSpeed', '$topSpeed')")
                updateDashboardStatus()
            }
        }
    }

    // ‼️ (수정) 함수 이름 및 로직 변경
    private fun loadAndShowLocationData() {
        // 1. 모든 키를 SharedPreferences에서 읽어옵니다.
        val lat = prefs.getString(PREF_KEY_LOCATION_LAT, null)
        val lon = prefs.getString(PREF_KEY_LOCATION_LON, null)
        val time = prefs.getString(PREF_KEY_LOCATION_TIME, null)

        val dist = prefs.getString(DrivingService.PREF_KEY_LAST_DRIVE_DIST, "-- km")!!
        val driveTime = prefs.getString(DrivingService.PREF_KEY_LAST_DRIVE_TIME, "--:--:--")!!
        val avgSpeed = prefs.getString(DrivingService.PREF_KEY_LAST_DRIVE_AVG_SPEED, "-- km/h")!!
        val topSpeed = prefs.getString(DrivingService.PREF_KEY_LAST_DRIVE_TOP_SPEED, "-- km/h")!!

        if (lat != null && lon != null && time != null) {
            // (주소 변환은 비동기로 실행됨)
            runJs("updateSavedLocation('주소 변환 중...', '($time)', '$dist', '$driveTime', '$avgSpeed', '$topSpeed')")
            convertLocationToAddress(lat, lon, time, dist, driveTime, avgSpeed, topSpeed)
        } else {
            // (기본값 로드)
            lastKnownAddress = "저장된 위치 없음"
            lastKnownTime = ""
            runJs("updateSavedLocation(lastKnownAddress, lastKnownTime, '$dist', '$driveTime', '$avgSpeed', '$topSpeed')")
            updateDashboardStatus()
        }
    }
    // ============================================

    // --- WebView 인터페이스 ---
    inner class WebAppInterface {

        @JavascriptInterface
        fun saveApiUrl(dirtyUrl: String) {
            val requiredPrefix = "https://mp.gmone.co.kr/api?"

            if (!dirtyUrl.startsWith(requiredPrefix)) {
                showJsStatus("잘못된 URL입니다. $requiredPrefix 로 시작해야 합니다.", "danger", 4000)
                return
            }

            val cleanUrl = if (dirtyUrl.contains("&cmd=")) {
                dirtyUrl.substringBefore("&cmd=")
            } else {
                dirtyUrl
            }

            baseApiUrl = cleanUrl
            saveUrlToPrefs(cleanUrl)

            showJsStatus("API 키가 성공적으로 저장되었습니다.", "success")
            runJs("populateApiUrl('(API 키 저장됨)')")
            runJs("clearApiUrlInput()")
        }

        @JavascriptInterface
        fun loadInitialApiUrl() {
            val status = if (baseApiUrl.isNullOrEmpty()) "(API 키 없음)" else "(API 키 저장됨)"
            runJs("populateApiUrl('$status')")
        }

        // 블루투스 설정 버튼 (페어링된 기기 목록)
        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun showPairedDeviceList() {
            if (!hasBluetoothScanPermission()) {
                showJsStatus("블루투스/위치 권한이 없습니다.", "danger")
                return
            }

            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                showJsStatus("블루투스가 꺼져있습니다.", "danger")
                return
            }

            val pairedDevices = adapter.bondedDevices
            if (pairedDevices.isEmpty()) {
                showJsStatus("페어링된 기기가 없습니다.", "danger")
                return
            }

            val deviceList = pairedDevices.map {
                "${it.name ?: "이름 없음"} (${it.address})"
            }.toTypedArray()

            val deviceMap = pairedDevices.associateBy { "${it.name ?: "이름 없음"} (${it.address})" }

            launch(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("차량 선택 (myChevrolet)")
                    .setItems(deviceList) { _, which ->
                        val selectedKey = deviceList[which]
                        val selectedDevice = deviceMap[selectedKey]

                        if (selectedDevice != null) {
                            prefs.edit().putString(PREF_KEY_MAC_ADDRESS, selectedDevice.address).apply()

                            Log.d("WebAppInterface", "MAC Address Saved: ${selectedDevice.address}")
                            showJsStatus("'${selectedDevice.name ?: "이름 없음"}' 기기가 등록되었습니다.", "success")

                            checkBluetoothStatus()
                            updateDashboardStatus()
                        }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }

        // (디버그 탭 새로고침)
        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun refreshDeviceList() {
            if (!hasBluetoothScanPermission()) {
                showJsStatus("블루투스/위치 권한이 없습니다.", "danger")
                runJs("updateDeviceList('<p><i>블루투스 스캔 권한이 없습니다. (근처 기기 또는 위치)</i></p>')")
                return
            }

            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                runJs("updateDeviceList('<p><i>블루투스가 꺼져있습니다.</i></p>')")
                return
            }

            // (프록시를 사용하여 안정적으로 목록 가져오기)
            val gattDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            val a2dpDevices = a2dpProfile?.connectedDevices ?: emptyList()
            val headsetDevices = headsetProfile?.connectedDevices ?: emptyList()

            val allConnectedDevices = (gattDevices + a2dpDevices + headsetDevices).toSet() // 중복 제거

            if (allConnectedDevices.isEmpty()) {
                runJs("updateDeviceList('<p><i>현재 연결된 기기 없음.</i></p>')")
                return
            }

            // HTML 문자열 생성
            val htmlBuilder = StringBuilder()
            val savedMacAddress = prefs.getString(PREF_KEY_MAC_ADDRESS, null)

            allConnectedDevices.forEach { device ->
                val deviceName = if (device.name == null) {
                    "<span style=\"color: var(--danger-color);\"><i>null</i></span>"
                } else {
                    "<b>${device.name}</b>"
                }

                htmlBuilder.append("<p style=\"font-size: 14px; margin-bottom: 10px;\">")
                htmlBuilder.append("<b>Name:</b> $deviceName<br>")
                htmlBuilder.append("<b>Address:</b> ${device.address}")

                if (device.address == savedMacAddress) {
                    htmlBuilder.append(" <span style=\"color: var(--success-color);\">(등록된 기기)</span>")
                }

                htmlBuilder.append("</p><hr style=\"border-color: var(--border-color);\">")
            }

            val escapedHtml = htmlBuilder.toString().replace("'", "\\'")
            runJs("updateDeviceList('$escapedHtml')")
        }

        @JavascriptInterface
        fun toggleBluetoothTest() {
            val wasConnected = isBluetoothConnected_VIRTUAL
            isBluetoothConnected_VIRTUAL = !isBluetoothConnected_VIRTUAL

            if (!wasConnected && isBluetoothConnected_VIRTUAL) {
                Log.d("MainActivity", "Virtual BT just CONNECTED. Cancelling remote tasks.")
                cancelAllRemoteTasksOnBtConnect()
                startDrivingService()
            }

            if (wasConnected && !isBluetoothConnected_VIRTUAL) {
                // ‼️ (수정) 가상 연결 해제 시에도 딜레이 후 UI 갱신
                launch {
                    stopDrivingServiceAndSaveLocation()
                }
            }

            runJs("updateBluetoothDebugStatus($isBluetoothConnected_VIRTUAL)")

            launch(Dispatchers.Main) {
                val status = if (isBluetoothConnected_VIRTUAL) "ON (VIRTUAL)" else "OFF (VIRTUAL)"
                showJsStatus("블루투스 테스트: $status", "success")
                setButtonFeedback("btnToggleBluetooth", "success")
                updateDashboardStatus()
            }
        }

        @JavascriptInterface
        fun checkBluetoothStatusForDebug() {
            runJs("updateBluetoothDebugStatus(${isBluetoothConnected || isBluetoothConnected_VIRTUAL})")
        }

        @JavascriptInterface
        fun loadSavedLocation() {
            val lat = prefs.getString(PREF_KEY_LOCATION_LAT, null)
            val lon = prefs.getString(PREF_KEY_LOCATION_LON, null)
            val time = prefs.getString(PREF_KEY_LOCATION_TIME, "-")
            var address = prefs.getString(DrivingService.PREF_KEY_LOCATION_ADDRESS, "") ?: ""

            val latVal = lat?.toDoubleOrNull() ?: 37.5665
            val lonVal = lon?.toDoubleOrNull() ?: 126.9780

            if (address.isEmpty() && lat != null && lon != null) {
                // If address is missing but we have coordinates, try to find it now
                launch(Dispatchers.IO) {
                    try {
                        val geocoder = Geocoder(this@MainActivity, Locale.KOREAN)
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(latVal, lonVal, 1)
                        if (!addresses.isNullOrEmpty()) {
                            address = addresses[0].getAddressLine(0)
                            // Save it for next time
                            prefs.edit().putString(DrivingService.PREF_KEY_LOCATION_ADDRESS, address).apply()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Update UI (even if geocoding failed, use empty address so JS handles it or shows fallback)
                    launch(Dispatchers.Main) {
                        runJs("updateParkingMap($latVal, $lonVal, '$time', '$address')")
                    }
                }
            } else {
                // We have address (or defaults), update immediately
                runOnUiThread {
                    runJs("updateParkingMap($latVal, $lonVal, '$time', '$address')")
                }
            }
        }
        
        @JavascriptInterface
        fun openSavedLocationMap() {
            val lat = prefs.getString(PREF_KEY_LOCATION_LAT, null)
            val lon = prefs.getString(PREF_KEY_LOCATION_LON, null)

            if (lat != null && lon != null) {
                try {
                    // geo URI scheme: geo:latitude,longitude?z=zoom&q=latitude,longitude(Label)
                    val label = "주차 위치"
                    val uriString = "geo:$lat,$lon?q=$lat,$lon($label)"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    launch(Dispatchers.Main) {
                        showJsStatus("지도 앱을 실행할 수 없습니다.", "danger")
                    }
                }
            } else {
                launch(Dispatchers.Main) {
                    showJsStatus("저장된 주차 위치가 없습니다.", "danger")
                }
            }
        }

        // ‼️ (신규) 가상 주행 기록 주입 (삭제됨)




        // --- 이하 원격 제어/버튼 처리 로직 (기존 그대로) ---

        @JavascriptInterface
        fun handleClick(cmdKey: String, cmdName: String, buttonId: String) {
            val isBtOn = isBluetoothConnected || isBluetoothConnected_VIRTUAL
            if ((buttonId == "btnEngineStart" || buttonId == "btnEngineStop") && isBtOn) {
                launch(Dispatchers.Main) {
                    runJs("updateDashboardStatus('ACC ON 상태에서는 원격 시동 사용이 불가합니다', '', 'exclamation-triangle', true, $isBtOn)")
                    delay(2000)
                    updateDashboardStatus()
                }
                return
            }

            if (!checkApiCooldown(buttonId)) return

            if (buttonId == "btnEngineStop" && isEngineOn) {
                handleEngineStop(buttonId)
                return
            }

            launch(Dispatchers.IO) {
                val success = sendCommandInternal(cmdKey)
                launch(Dispatchers.Main) {
                    setButtonFeedback(buttonId, if (success) "success" else "danger")
                }
            }
        }

        @JavascriptInterface
        fun handleLongPress(cmdKey: String, cmdName: String, buttonId: String) {
            val isBtOn = isBluetoothConnected || isBluetoothConnected_VIRTUAL

            if ((buttonId == "btnEngineStart" || buttonId == "btnEngineStop" || buttonId == "btnFindMyCar") && isBtOn) {
                launch(Dispatchers.Main) {
                    runJs("updateDashboardStatus('ACC ON 상태에서는 원격 시동 사용이 불가합니다', '', 'exclamation-triangle', true, $isBtOn)")

                    if (buttonId == "btnFindMyCar") {
                        showJsStatus("ACC ON 상태에서는 사용이 불가합니다", "danger", 3000)
                    }

                    delay(2000)
                    updateDashboardStatus()
                }
                return
            }

            if (!checkApiCooldown(buttonId)) return

            if (buttonId == "btnEngineStart") {
                if (isEngineOn) return
                handleEngineStart(buttonId)
                return
            }

            launch(Dispatchers.IO) {
                val success = sendCommandInternal(cmdKey)
                launch(Dispatchers.Main) {
                    setButtonFeedback(buttonId, if (success) "success" else "danger")
                }
            }
        }

        private fun handleEngineStart(buttonId: String) {
            launch(Dispatchers.IO) {
                val success = sendCommandInternal("VEHICLE_START")
                launch(Dispatchers.Main) {
                    setButtonFeedback(buttonId, if (success) "success" else "danger")
                    if (success) {
                        isEngineOn = true
                        runJs("updateEngineUI(true)")
                        startTimerService("ENGINE", 20 * 60 * 1000)
                        updateDashboardStatus()
                    }
                }
            }
        }

        private fun handleEngineStop(buttonId: String) {
            launch(Dispatchers.IO) {
                val success = sendCommandInternal("VEHICLE_STOP")
                launch(Dispatchers.Main) {
                    setButtonFeedback(buttonId, if (success) "success" else "danger")
                    if (success) {
                        isEngineOn = false
                        runJs("updateEngineUI(false)")
                        stopTimerService("ENGINE")
                        updateDashboardStatus()
                    }
                }
            }
        }

        @JavascriptInterface
        fun handleVentilationToggle() {
            val isBtOn = isBluetoothConnected || isBluetoothConnected_VIRTUAL
            if (!isVentilating && isBtOn) {
                launch(Dispatchers.Main) {
                    runJs("updateDashboardStatus('ACC ON 상태에서는 원격 시동 사용이 불가합니다', '', 'exclamation-triangle', true, $isBtOn)")
                    showJsStatus("ACC ON 상태에서는 사용이 불가합니다", "danger", 3000)
                    delay(2000)
                    updateDashboardStatus()
                }
                return
            }

            if (!isVentilating && !checkApiCooldown("btnVentilation")) return

            if (isVentilating) {
                stopVentilation(false)
            } else {
                startVentilation()
            }
        }

        @JavascriptInterface
        fun handleHeatEjectToggle() {
            val isBtOn = isBluetoothConnected || isBluetoothConnected_VIRTUAL
            if (!isHeatEjecting && isBtOn) {
                launch(Dispatchers.Main) {
                    runJs("updateDashboardStatus('ACC ON 상태에서는 원격 시동 사용이 불가합니다', '', 'exclamation-triangle', true, $isBtOn)")
                    showJsStatus("ACC ON 상태에서는 사용이 불가합니다", "danger", 3000)
                    delay(2000)
                    updateDashboardStatus()
                }
                return
            }

            if (!isHeatEjecting && !checkApiCooldown("btnHeatEject")) return

            if (isHeatEjecting) {
                stopHeatEject(false)
            } else {
                startHeatEject()
            }
        }

        private fun startVentilation() {
            if (isHeatEjecting) {
                showJsStatus("열 배출 모드가 실행 중입니다.", "danger")
                return
            }
            isVentilating = true
            runJs("updateVentilationUI(true)")
            setExtrasButtonsDisabled(true)
            updateDashboardStatus()

            launch(Dispatchers.IO) {
                val success = sendCommandInternal("WINDOW_OPEN")
                launch(Dispatchers.Main) {
                    setButtonFeedback("btnVentilation", if (success) "success" else "danger")
                    setExtrasButtonsDisabled(false)
                    if (success) {
                        startTimerService("VENTILATION", 5 * 60 * 1000)
                    } else {
                        isVentilating = false
                        runJs("updateVentilationUI(false)")
                        updateDashboardStatus()
                    }
                }
            }
        }

        private fun stopVentilation(autoClose: Boolean) {
            stopTimerService("VENTILATION")

            launch(Dispatchers.IO) {
                launch(Dispatchers.Main) { setExtrasButtonsDisabled(true) }
                val success = sendCommandInternal("WINDOW_CLOSE")
                launch(Dispatchers.Main) {
                    setButtonFeedback("btnVentilation", if (success) "success" else "danger")
                    isVentilating = false
                    runJs("updateVentilationUI(false)")
                    setExtrasButtonsDisabled(false)
                    updateDashboardStatus()
                }
            }
        }

        private fun startHeatEject() {
            if (isVentilating) {
                showJsStatus("열 배출 모드가 실행 중입니다.", "danger")
                return
            }
            isHeatEjecting = true
            runJs("updateHeatEjectUI(true)")
            setExtrasButtonsDisabled(true)
            updateDashboardStatus()

            launch(Dispatchers.IO) {
                var success = sendCommandInternal("WINDOW_OPEN")
                if (!success) { launch(Dispatchers.Main) { stopHeatEject(false, false) }; return@launch }
                launch(Dispatchers.Main) { setButtonFeedback("btnHeatEject", "success") }
                delay(apiDelay)

                if (!isHeatEjecting) return@launch

                success = sendCommandInternal("SUNROOF_OPEN")
                if (!success) { launch(Dispatchers.Main) { stopHeatEject(false, false) }; return@launch }
                launch(Dispatchers.Main) { setButtonFeedback("btnHeatEject", "success") }
                delay(apiDelay)

                if (!isHeatEjecting) return@launch

                success = sendCommandInternal("VEHICLE_START")
                if (!success) { launch(Dispatchers.Main) { stopHeatEject(false, false) }; return@launch }
                launch(Dispatchers.Main) { setButtonFeedback("btnHeatEject", "success") }

                if (!isHeatEjecting) return@launch

                launch(Dispatchers.Main) {
                    isEngineOn = true
                    runJs("updateEngineUI(true)")
                    startTimerService("ENGINE", 20 * 60 * 1000)
                    startTimerService("HEAT_EJECT", 5 * 60 * 1000)

                    setExtrasButtonsDisabled(false)
                    updateDashboardStatus()
                }
            }
        }

        private fun stopHeatEject(autoClose: Boolean, closeWindows: Boolean = true) {
            isHeatEjecting = false

            stopTimerService("HEAT_EJECT")

            if (closeWindows) {
                launch(context = Dispatchers.IO) {
                    launch(Dispatchers.Main) { setExtrasButtonsDisabled(true) }

                    sendCommandInternal("WINDOW_CLOSE")
                    launch(Dispatchers.Main) { setButtonFeedback("btnHeatEject", "success") }
                    delay(apiDelay)

                    sendCommandInternal("SUNROOF_CLOSE")
                    launch(Dispatchers.Main) {
                        setButtonFeedback("btnHeatEject", "success")
                        runJs("updateHeatEjectUI(false)")
                        setExtrasButtonsDisabled(false)
                        updateDashboardStatus()
                    }
                }
            } else {
                runJs("updateHeatEjectUI(false)")
                updateDashboardStatus()
            }
        }

        @JavascriptInterface
        fun getDrivingHistory(): String {
            return try {
                val file = java.io.File(filesDir, "driving_history.json")
                if (file.exists()) {
                    file.readText()
                } else {
                    "[]"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                "[]"
            }
        }

        // ‼️ (New) Refresh Firebase Data manually
        @JavascriptInterface
        fun refreshCarData() {
            Log.d("MainActivity", "REFRESH_REQUESTED: User tapped refresh button")
            // [NEW] Mark request time
            lastRefreshRequestTime = System.currentTimeMillis()
            
            launch(Dispatchers.Main) {
                showJsStatus("차량 데이터 새로고침 요청 중...", "info")
                val db = com.google.firebase.ktx.Firebase.database("https://mycarserver-fb85e-default-rtdb.firebaseio.com/").reference.child("car_status")
                
                // 1. Send Refresh Command
                db.child("cmd_refresh").setValue(System.currentTimeMillis())

                // 2. Fetch latest data (Fallback/Immediate check)
                db.get().addOnSuccessListener {
                    val range = it.child("range").getValue(String::class.java) ?: "--"
                    val battery = it.child("battery").getValue(String::class.java) ?: "--"
                    val batteryLevel = it.child("battery_level").getValue(String::class.java) ?: "--"
                    val mileage = it.child("mileage").getValue(String::class.java) ?: "--"

                    runJs("updateCarStatus('$range', '$battery', '$batteryLevel', '$mileage')")
                    showJsStatus("새로고침 요청 완료", "success")
                }.addOnFailureListener {
                    showJsStatus("통신 실패: ${it.message}", "danger")
                }
            }
        }

        @JavascriptInterface
        fun clearDrivingHistory() {
            try {
                val file = java.io.File(filesDir, "driving_history.json")
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun parseTimestamp(timeStr: String): Long {
        return try {
            // Try explicit format first (Scraper usually sends this)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.parse(timeStr)?.time ?: 0L
        } catch (e: Exception) {
            try {
                 val sdfShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                 sdfShort.parse(timeStr)?.time ?: 0L
            } catch (e2: Exception) {
                0L
            }
        }
    }
}
