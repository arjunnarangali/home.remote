package com.myhome.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.SuccessResult
import coil3.request.ImageRequest
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DockArtworkInstrumentedTest {
    @Test
    fun remoteDockSvgAndPngLogosAreDownloadedAndDecoded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val imageLoader = ImageLoader.Builder(context).components {
            add(SvgDecoder.Factory())
            add(OkHttpNetworkFetcherFactory())
        }.build()

        try {
            val assets = listOf(
                "youtube-white.svg", "jellyfin-white.svg", "netflix.svg", "netflix-white.svg",
                "fancode.png", "fancode-white.png", "playstation-white.svg",
            )
            assets.forEach { name ->
                val request = ImageRequest.Builder(context)
                    .data("${RemoteApi.DEFAULT_BASE_URL}/icons/$name")
                    .build()
                val result = imageLoader.execute(request)
                assertTrue("Expected $name to load and decode", result is SuccessResult)
            }
        } finally {
            imageLoader.shutdown()
        }
    }
}
