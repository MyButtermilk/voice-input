package org.futo.voiceinput.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun AnimatedWaveform(
    magnitude: Float,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val micRadiusPx = with(LocalDensity.current) { 89.6.dp.toPx() / 2f }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            SpectralParticleVisualizer(
                magnitude = magnitude,
                micRadiusPx = micRadiusPx,
                modifier = Modifier.fillMaxSize()
            )

            Image(
                painter = painterResource(org.futo.voiceinput.R.drawable.ic_mic_visualizer),
                contentDescription = null,
                modifier = Modifier.size(89.6.dp)
            )
        }
    }
}
