package com.wdysln9ux.sakunime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SakunimeProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SakunimeProvider())
    }
}
