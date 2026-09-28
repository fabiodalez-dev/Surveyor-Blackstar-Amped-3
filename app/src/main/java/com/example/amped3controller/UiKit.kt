package com.example.amped3controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/* Palette and the controls every page shares: headings, the fader, choice grids and the
   navigation glyphs. Drawn rather than imported so they stay in one visual vocabulary. */
internal val Coal=Color(0xff171719)
internal val Panel=Color(0xff242427)
internal val Paper=Color(0xfff3eeea)
internal val Red=Color(0xffdd0000)
internal val Muted=Color(0xffbab3b0)

@Composable internal fun Heading(title:String,subtitle:String){Column(Modifier.padding(top=10.dp,bottom=14.dp)){Text(subtitle.uppercase(),fontSize=10.sp,color=Red,letterSpacing=1.6.sp);Text(title,fontSize=28.sp,fontWeight=FontWeight.Bold)}}
@Composable internal fun Section(title:String){
    Row(Modifier.fillMaxWidth().padding(top=26.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title.uppercase(),color=Paper,fontSize=12.sp,fontWeight=FontWeight.Black,letterSpacing=2.2.sp)
        HorizontalDivider(Modifier.padding(start=12.dp),color=Color(0xFF2E2E31))
    }
}
/** Glifi della navigazione: manopola, cassa, lista, fader. Disegnati invece di importare un set
 *  di icone, cosi' restano nello stesso vocabolario grafico dei controlli. */
@Composable internal fun NavGlyph(index:Int, selected:Boolean){
    val tinta = if(selected) Red else Muted
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension; val w = 2.dp.toPx()
        when(index){
            0 -> {
                drawCircle(tinta, radius=s*0.38f, style=Stroke(w))
                drawLine(tinta, Offset(s/2, s/2), Offset(s*0.5f-s*0.2f, s*0.5f-s*0.22f), w, StrokeCap.Round)
            }
            1 -> {
                drawRoundRect(tinta, Offset(s*0.16f, s*0.08f), Size(s*0.68f, s*0.84f),
                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style=Stroke(w))
                drawCircle(tinta, radius=s*0.15f, center=Offset(s*0.5f, s*0.3f), style=Stroke(w))
                drawCircle(tinta, radius=s*0.15f, center=Offset(s*0.5f, s*0.7f), style=Stroke(w))
            }
            2 -> {
                listOf(0.22f, 0.5f, 0.78f).forEachIndexed { i, y ->
                    val larghezza = if (i == 0) s*0.46f else s*0.68f
                    drawLine(tinta, Offset(s*0.16f, s*y), Offset(s*0.16f+larghezza, s*y), w, StrokeCap.Round)
                }
            }
            else -> {
                listOf(0.28f to 0.62f, 0.5f to 0.34f, 0.72f to 0.5f).forEach { (y, x) ->
                    drawLine(tinta.copy(alpha=0.55f), Offset(s*0.14f, s*y), Offset(s*0.86f, s*y), w, StrokeCap.Round)
                    drawLine(tinta, Offset(s*x, s*y-s*0.09f), Offset(s*x, s*y+s*0.09f), 3.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}
@Composable internal fun Choices(label:String,value:Int,options:List<Pair<String,Int>>,enabled:Boolean,onSelect:(Int)->Unit){
    Text(label.uppercase(),color=Muted,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=8.dp))
    Column(verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                row.forEach { (name, id) ->
                    val sel = value == id
                    Box(modifier = Modifier.weight(1f).aspectRatio(2.2f).background(Panel, RoundedCornerShape(8.dp)).clickable(enabled=enabled){onSelect(id)}, contentAlignment=Alignment.Center) {
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Canvas(Modifier.size(6.dp)) {
                                if(sel) { drawCircle(Red); drawCircle(Color.White.copy(alpha=0.6f), radius=1.5f.dp.toPx()) }
                                else { drawCircle(Color(0xFF222222)) }
                            }
                            Text(name.uppercase(), color=if(sel) Color.White else Muted, fontWeight=FontWeight.Bold, fontSize=13.sp, textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                if(row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
@Composable internal fun Parameter(
    label:String, value:Int, max:Int, enabled:Boolean,
    format:(Int)->String={"%.1f".format(java.util.Locale.US,it*10f/127)},
    heat:Boolean=false,
    bipolar:Boolean=false,
    onSet:(Int)->Unit
){
    var dragged by remember(value){mutableFloatStateOf(value.coerceAtLeast(0).toFloat())}
    val frazione = if (max > 0) (dragged / max.toFloat()).coerceIn(0f, 1f) else 0f
    val attivo = enabled && value >= 0
    val testo = if (value < 0) "—" else format(dragged.toInt())
    // Il numero e la parte letterale vanno separati: l'unita' non deve rubare corpo alla cifra,
    // che e' cio' che si legge da lontano.
    val taglio = testo.indexOfFirst { it.isLetter() && it != 'e' }
    val cifra = if (taglio > 0) testo.substring(0, taglio).trim() else testo
    val unita = if (taglio > 0) testo.substring(taglio).trim() else ""

    Column(Modifier.fillMaxWidth().padding(vertical=10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.Bottom) {
            Text(
                label.uppercase(), Modifier.weight(1f).padding(bottom=6.dp),
                color=if(attivo) Muted else Muted.copy(alpha=0.45f),
                fontSize=12.sp, fontWeight=FontWeight.Bold, letterSpacing=1.8.sp
            )
            Text(
                cifra,
                color=if(attivo) Paper else Muted.copy(alpha=0.45f),
                fontSize=40.sp, fontWeight=FontWeight.Black, letterSpacing=(-1).sp,
                // cifre a passo fisso: il numero non balla mentre si trascina
                style=LocalTextStyle.current.copy(fontFeatureSettings="tnum")
            )
            if (unita.isNotEmpty()) Text(
                unita, Modifier.padding(start=3.dp, bottom=7.dp),
                color=if(attivo) Muted else Muted.copy(alpha=0.45f),
                fontSize=15.sp, fontWeight=FontWeight.Bold
            )
        }
        Box(contentAlignment=Alignment.CenterStart, modifier=Modifier.fillMaxWidth().height(44.dp)) {
            Canvas(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=11.dp)) {
                val cy = size.height/2
                val h = 18.dp.toPx()
                val r = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                // incavo della traccia
                drawRoundRect(Color(0xFF0C0C0D), Offset(0f, cy-h/2), Size(size.width, h), r)
                drawRoundRect(Color(0xFF000000), Offset(0f, cy-h/2), Size(size.width, h), r,
                    style=Stroke(1.dp.toPx()))

                val larghezza = size.width * frazione
                if (larghezza > 1f && attivo) {
                    if (heat) {
                        // Valvola: piu' si alza il guadagno, piu' il filamento passa
                        // dall'ambra spenta all'arancio incandescente. Il colore dipende dal
                        // valore, non dalla posizione: a guadagno basso non deve sembrare caldo.
                        // Giallo a guadagno basso, rosso incandescente quando sale: e' cosi' che si
                        // vede scaldare una valvola sotto sforzo.
                        val caldo = frazione * frazione
                        val punta = androidx.compose.ui.graphics.lerp(Color(0xFFFFC21A), Color(0xFFE01500), frazione)
                        val brace = androidx.compose.ui.graphics.lerp(Color(0xFF6A4A00), Color(0xFF7A0A00), frazione)
                        drawRoundRect(Brush.horizontalGradient(listOf(brace, punta), endX=larghezza),
                            Offset(0f, cy-h/2), Size(larghezza, h), r)
                        // alone: cresce con il calore, resta sotto la soglia del fastidio
                        drawRoundRect(punta.copy(alpha=0.10f + 0.22f*caldo),
                            Offset(-4.dp.toPx(), cy-h/2-4.dp.toPx()),
                            Size(larghezza+8.dp.toPx(), h+8.dp.toPx()),
                            androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
                        // filamento
                        drawLine(punta.copy(alpha=0.35f+0.5f*caldo), Offset(2.dp.toPx(), cy), Offset(larghezza-2.dp.toPx(), cy), 2.dp.toPx(), StrokeCap.Round)
                    } else {
                        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF8E0000), Red), endX=larghezza),
                            Offset(0f, cy-h/2), Size(larghezza, h), r)
                    }
                }
                // tacche sotto la traccia, con quella centrale piu' marcata sui parametri bipolari
                val passi = 10
                for (i in 0..passi) {
                    val x = (i * size.width / passi).coerceIn(1f, size.width-1f)
                    val centrale = bipolar && i == passi/2
                    val estremo = i == 0 || i == passi
                    drawLine(
                        if (centrale) Muted.copy(alpha=0.9f) else Color(0xFF3A3A3D),
                        Offset(x, cy + h/2 + 4.dp.toPx()),
                        Offset(x, cy + h/2 + (if (centrale || estremo) 9.dp else 6.dp).toPx()),
                        (if (centrale) 2.dp else 1.5.dp).toPx(), StrokeCap.Round
                    )
                }
                if (attivo) {
                    // cappuccio da fader: si vede da lontano molto piu' di un pallino
                    val x = larghezza.coerceIn(0f, size.width)
                    val cw = 9.dp.toPx(); val ch = 30.dp.toPx()
                    drawRoundRect(Color(0xFF000000).copy(alpha=0.5f), Offset(x-cw/2+1.5f, cy-ch/2+2f), Size(cw, ch),
                        androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    drawRoundRect(Paper, Offset(x-cw/2, cy-ch/2), Size(cw, ch),
                        androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    drawLine(Color(0xFF151516), Offset(x, cy-ch/2+5.dp.toPx()), Offset(x, cy+ch/2-5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            // Solo trascinamento: un cursore che salta al punto toccato, su un telefono
            // appoggiato al pedaliera, manda il guadagno al massimo con una sfiorata.
            Box(Modifier.fillMaxWidth().height(44.dp)
                .semantics {
                    contentDescription = label
                    if (attivo) progressBarRangeInfo = ProgressBarRangeInfo(dragged, 0f..max.toFloat())
                }
                .pointerInput(attivo, max) {
                    if (!attivo) return@pointerInput
                    val larghezzaPx = size.width.toFloat()
                    detectHorizontalDragGestures(
                        onDragEnd = { onSet(dragged.toInt()) },
                        onDragCancel = { onSet(dragged.toInt()) }
                    ) { change, delta ->
                        change.consume()
                        if (larghezzaPx > 0f)
                            dragged = (dragged + delta * max / larghezzaPx).coerceIn(0f, max.toFloat())
                    }
                })
        }
    }
}

@Composable internal fun SegmentedButton(options: List<Pair<String,String>>, selected: String, onSelect: (String) -> Unit) {
    Row(modifier=Modifier.background(Panel, RoundedCornerShape(8.dp)).padding(4.dp)) {
        options.forEach { (label, value) ->
            val sel = selected == value
            Box(modifier=Modifier.background(if(sel) Red else Color.Transparent, RoundedCornerShape(6.dp)).clickable { onSelect(value) }.padding(horizontal=12.dp, vertical=6.dp)) {
                Text(label, color=if(sel) Color.White else Muted, fontWeight=FontWeight.Bold, fontSize=13.sp)
            }
        }
    }
}
