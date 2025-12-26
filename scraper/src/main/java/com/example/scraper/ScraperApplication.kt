package com.example.scraper

import android.app.Application
import com.google.firebase.FirebaseApp

class ScraperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
        }
    }
}
