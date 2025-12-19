package com.example.carcontroller // (이 부분은 본인의 프로젝트 이름으로 두세요)

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

// AppCompatActivity()를 상속받도록 : AppCompatActivity()를 추가합니다.
class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 레이아웃(xml)에서 WebView를 찾습니다.
        val myWebView: WebView = findViewById(R.id.webview)

        // 2. JavaScript를 활성화합니다. (필수!)
        myWebView.settings.javaScriptEnabled = true

        // 3. 롱프레스, 타이머 등이 작동하도록 DOM 저장을 활성화합니다.
        myWebView.settings.domStorageEnabled = true

        // 4. assets 폴더에 있는 HTML 파일을 로드합니다.
        // "file:///android_asset/" 경로가 assets 폴더를 의미합니다.
        // [중요!] "index.html" 부분을 본인이 넣은 HTML 파일 이름으로 변경하세요!
        myWebView.loadUrl("file:///android_asset/main.html")
    }
}