package com.xmoyi.nainaisv

import android.app.Application
import com.xmoyi.nainaisv.data.AppDatabase
import com.xmoyi.nainaisv.data.DramaRepository
import com.xmoyi.nainaisv.data.SettingsStore
import com.xmoyi.nainaisv.network.BilibiliClient
import com.xmoyi.nainaisv.recommendation.RecommendationEngine
import com.xmoyi.nainaisv.update.UpdateManager

class NaiNaiApplication : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val database = AppDatabase.get(application)
    val settings = SettingsStore(application)
    val bilibili = BilibiliClient()
    val recommendation = RecommendationEngine()
    val repository = DramaRepository(database.dramaDao(), settings, bilibili, recommendation)
    val updateManager = UpdateManager(application, bilibiliHttpClient = BilibiliClient.defaultHttpClient())
}
