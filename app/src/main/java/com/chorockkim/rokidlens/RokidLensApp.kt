package com.chorockkim.rokidlens

import android.app.Application
import com.chorockkim.rokidlens.update.Updater
import okhttp3.OkHttpClient

class RokidLensApp : Application() {
    val http by lazy { OkHttpClient() }
    val updater by lazy { Updater(this) { http } }
}
