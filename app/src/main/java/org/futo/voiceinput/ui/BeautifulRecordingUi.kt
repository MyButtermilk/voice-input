package org.futo.voiceinput.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.futo.voiceinput.MagnitudeState
import org.futo.voiceinput.R

@Composable
fun BeautifulRecordingUI(
    magnitude: Float,
    state: MagnitudeState,
    finalText: String,
    partialText: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true
) {
    val finalDisplay = finalText.takeIf { it.isNotBlank() }
    val partialDisplay = partialText.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f)
            ) {
                AnimatedWaveform(
                    magnitude = magnitude.coerceIn(0f, 1f),
                    isListening = state != MagnitudeState.MIC_MAY_BE_BLOCKED,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
                    .padding(top = 16.dp, bottom = 24.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color(0x66000000)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    val transcript = buildAnnotatedString {
                        if (finalDisplay != null) {
                            withStyle(
                                SpanStyle(
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            ) {
                                append(finalDisplay)
                            }
                        }
                        if (partialDisplay != null) {
                            if (finalDisplay != null &&
                                !finalDisplay.endsWith(" ") &&
                                !partialDisplay.startsWith(" ")
                            ) {
                                append(" ")
                            }
                            withStyle(
                                SpanStyle(
                                    color = Color(0xFF9CA3AF),
                                    fontWeight = FontWeight.Medium
                                )
                            ) {
                                append(partialDisplay)
                            }
                        }
                    }

                    if (transcript.isNotEmpty()) {
                        Text(
                            text = transcript,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.1f)
            ) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF5A00),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.stop_recording),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
