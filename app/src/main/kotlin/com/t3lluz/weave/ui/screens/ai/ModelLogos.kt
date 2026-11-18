package com.t3lluz.weave.ui.screens.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest

/** A model's maker as a logo on a round plate (assets/models/<brand>.svg). */
@Composable
internal fun ModelLogo(brand: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        val context = LocalContext.current
        val request = remember(brand) {
            ImageRequest.Builder(context)
                .data("file:///android_asset/models/$brand.svg")
                .decoderFactory(SvgDecoder.Factory())
                .build()
        }
        AsyncImage(model = request, contentDescription = null, modifier = Modifier.size(size * 0.58f))
    }
}
