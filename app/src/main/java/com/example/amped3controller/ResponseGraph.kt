package com.example.amped3controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.amped3controller.dsp.CabLab

/** One trace on the graph: a response in dB on [CabLab.GRID_HZ]. */
data class Curve(val db: DoubleArray, val colour: Color, val label: String, val width: Float = 2f)

internal val CurveColours = listOf(Red, Color(0xFF6E6A67), Color(0xFF4FA3D1), Color(0xFF7BD88F), Color(0xFFE8B84A), Color(0xFFB07CD8))

/**
 * Modelled responses, 20 Hz to 16 kHz on a log axis, 48 dB tall and anchored to the loudest
 * curve. No sweep has ever been measured at the pedal's output, so read it as what the maths
 * says, not as what the speaker does; the caption says so every time.
 */
@Composable internal fun ResponseGraph(curves: List<Curve>, height: Int = 130, caption: String? = null) {
    if (curves.isEmpty()) return
    val lo = kotlin.math.ln(CabLab.GRID_HZ.first()); val hi = kotlin.math.ln(CabLab.GRID_HZ.last())
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val top = (curves.maxOf { c -> c.db.max() } + 4).coerceAtLeast(6.0)
            val bottom = top - 48
            fun y(v: Double) = (size.height * (top - v) / (top - bottom)).toFloat().coerceIn(0f, size.height)
            for (g in 1..3) {
                val yy = size.height * g / 4f
                drawLine(Color(0xFF2A2A2D), Offset(0f, yy), Offset(size.width, yy), 1.dp.toPx())
            }
            listOf(100.0, 1000.0, 10000.0).forEach { hz ->
                val x = (size.width * (kotlin.math.ln(hz) - lo) / (hi - lo)).toFloat()
                drawLine(Color(0xFF2A2A2D), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            // draw the first curve last, on top: it is the one being looked at
            curves.asReversed().forEach { c ->
                var previous: Offset? = null
                for (i in c.db.indices) {
                    val x = (size.width * (kotlin.math.ln(CabLab.GRID_HZ[i]) - lo) / (hi - lo)).toFloat()
                    val point = Offset(x, y(c.db[i]))
                    previous?.let { drawLine(c.colour, it, point, c.width * density, StrokeCap.Round) }
                    previous = point
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("20 Hz", color = Muted, fontSize = 10.sp)
            Text("100 Hz · 1 kHz · 10 kHz", color = Color(0xFF3A3A3D), fontSize = 10.sp)
            Text("16 kHz", color = Muted, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            curves.forEach { c -> Text("■ ${c.label}", color = c.colour, fontSize = 10.sp) }
        }
        Text(caption ?: T("risposta modellata dai coefficienti, non misurata", "modelled from the coefficients, not measured"),
            color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The numbers under a curve, in one line. */
internal fun describe(c: CabLab.Character): String =
    "%s · peak %.0f Hz · %.0f–%.0f Hz (−6 dB) · %+.1f dB/oct · presence %+.1f dB".format(
        java.util.Locale.US, c.label, c.peakHz, c.lowCornerHz, c.highCornerHz, c.tiltDbPerOctave, c.presenceDb)
