package com.example.amped3controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.amped3controller.dsp.CabLab
import com.example.amped3controller.dsp.CabProfile

private val Selected = Color(0xFF2C1F20)
private val Hairline = Color(0xFF34343A)

/** A cabinet seen from the front, small: its cones in their real arrangement, a plug for DI,
 *  one dashed cone when the source never said what the cabinet was. */
@Composable internal fun CabGlyph(size: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    val frame = if (highlighted) Paper else Color(0xFF6E6865)
    val cone = if (highlighted) Red else Color(0xFF57524F)
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val m = Regex("^(\\d+)x(\\d+)").find(size)
        val n = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (size == "DI") {
            val l = this.size.minDimension * 0.5f
            val c = Offset(this.size.width / 2, this.size.height / 2)
            drawRoundRect(frame, Offset(c.x - l / 2, c.y - l / 2), Size(l, l), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(stroke))
            drawCircle(cone, l * 0.18f, c)
            drawLine(frame, Offset(c.x + l / 2, c.y), Offset(this.size.width, c.y), stroke * 1.4f, StrokeCap.Round)
            return@Canvas
        }
        // 2x15 and 8x10 stand upright; everything else is as wide as it is tall or wider
        val (cols, rows) = when {
            n >= 8 -> 2 to 4; n >= 4 -> 2 to 2; n == 2 && size.endsWith("15") -> 1 to 2; n == 2 -> 2 to 1; else -> 1 to 1
        }
        val pad = 3.dp.toPx()
        val cell = minOf((this.size.width - 2 * pad) / cols, (this.size.height - 2 * pad) / rows)
        val w = cell * cols + pad; val h = cell * rows + pad
        val x0 = (this.size.width - w) / 2; val y0 = (this.size.height - h) / 2
        drawRoundRect(frame, Offset(x0, y0), Size(w, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(stroke))
        val r = cell * (if (size.endsWith("10")) 0.36f else 0.4f)
        if (n == 0) {
            drawCircle(cone, r * 1.4f, Offset(x0 + w / 2, y0 + h / 2), style = Stroke(stroke, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
            return@Canvas
        }
        for (i in 0 until cols * rows) {
            val c = Offset(x0 + pad / 2 + cell * (i % cols + 0.5f), y0 + pad / 2 + cell * (i / cols + 0.5f))
            drawCircle(cone, r, c, style = Stroke(stroke))
            drawCircle(cone, r * 0.3f, c)
        }
    }
}

/** The modelled response as a thumbnail, drawn on its own scale: only the shape is compared. */
@Composable internal fun Sparkline(db: DoubleArray?, highlighted: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawLine(Hairline, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
        if (db == null || db.isEmpty()) return@Canvas
        val top = db.max(); val range = 30.0
        val lo = kotlin.math.ln(CabLab.GRID_HZ.first()); val hi = kotlin.math.ln(CabLab.GRID_HZ.last())
        val path = Path()
        db.forEachIndexed { i, v ->
            val x = (size.width * (kotlin.math.ln(CabLab.GRID_HZ[i]) - lo) / (hi - lo)).toFloat()
            val y = (size.height * ((top - v) / range)).toFloat().coerceIn(0f, size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (highlighted) Red else Muted.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable internal fun SourceBadge(source: String) {
    val factory = source == CabCatalog.BLACKSTAR
    Text(CabCatalog.sourceLabel(source).uppercase(), color = if (factory) Red else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, maxLines = 1,
        modifier = Modifier.border(1.dp, if (factory) Red.copy(alpha = 0.6f) else Hairline, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

/** One cabinet in a list: what it is, where it comes from, and the shape of its response. */
@Composable internal fun CabCard(cab: CatalogCab, curve: DoubleArray?, selected: Boolean, favorite: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) Selected else Panel)
        .border(1.dp, if (selected) Red else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp).testTag("cab-${cab.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        CabGlyph(cab.size, selected, Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(cab.title, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Text(cab.summary, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceBadge(cab.source)
                if (cab.style.isNotEmpty()) Text(CabCatalog.styleLabel(cab.style), color = Muted.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1)
                if (favorite) Text("★", color = Red, fontSize = 11.sp)
            }
        }
        Sparkline(curve, selected, Modifier.width(56.dp).height(26.dp))
    }
}

/** One facet as a row of chips that scrolls sideways, each with how many cabinets it leaves. */
@Composable private fun FacetRow(label: String, values: List<Pair<String, Int>>, selected: String, show: (String) -> String, onPick: (String) -> Unit) {
    if (values.isEmpty() && selected.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // the chosen value stays first and visible even when other filters leave it with nothing
            val shown = (if (selected.isNotEmpty() && values.none { it.first == selected }) listOf(selected to 0) else emptyList()) +
                values.sortedByDescending { it.first == selected }
            shown.forEach { (v, n) -> Chip("${show(v)}  $n", v == selected) { onPick(if (v == selected) "" else v) } }
        }
    }
}

/** Search and filters over every cabinet, Blackstar's and the shipped ones alike, grouped by brand. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable internal fun CabBrowserSheet(
    all: List<CatalogCab>, filter: CatalogFilter, onFilter: (CatalogFilter) -> Unit, favorites: List<String>,
    curveOf: (CatalogCab) -> DoubleArray?, selectedId: String, onPick: (CatalogCab) -> Unit, onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val matching = remember(all, filter, favorites) { all.filter { CabCatalog.matches(it, filter, favorites) } }
    var more by remember { mutableStateOf(filter.speaker.isNotEmpty() || filter.mic.isNotEmpty() || filter.style.isNotEmpty()) }
    val list = rememberLazyListState()
    LaunchedEffect(filter) { list.scrollToItem(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Coal, contentColor = Paper,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Hairline) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(T("Scegli la cassa", "Choose a cabinet"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(T("${all.size} casse · ${all.count { it.factory != null }} Blackstar, ${all.count { it.lib != null }} aggiunte",
                        "${all.size} cabinets · ${all.count { it.factory != null }} Blackstar, ${all.count { it.lib != null }} extra"), color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) { Text(T("Chiudi", "Close"), color = Muted) }
            }
            OutlinedTextField(value = filter.query, onValueChange = { onFilter(filter.copy(query = it.take(40))) },
                placeholder = { Text(T("Cerca marca, speaker, mic, pack…", "Search brand, speaker, mic, pack…"), fontSize = 14.sp, maxLines = 1) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("cabinet-search"),
                trailingIcon = { if (filter.query.isNotEmpty()) TextButton(onClick = { onFilter(filter.copy(query = "")) }) { Text("✕", color = Muted) } })
            HorizontalDivider(color = Hairline, modifier = Modifier.padding(top = 10.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("cabinet-list"), state = list,
                contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // the filters scroll away with the list: open, they would leave no room for it
                item(key = "filters") {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("★ " + T("Preferite", "Favourites"), filter.favorites) { onFilter(filter.copy(favorites = !filter.favorites)) }
                            CabCatalog.counts(all, filter, favorites, "source").forEach { (v, n) ->
                                Chip("${CabCatalog.sourceLabel(v)}  $n", filter.source == v) { onFilter(filter.copy(source = if (filter.source == v) "" else v)) }
                            }
                        }
                        FacetRow(T("Marca", "Brand"), CabCatalog.counts(all, filter, favorites, "brand"), filter.brand,
                            { if (it == CabCatalog.OTHER) T("Non indicata", "Not stated") else it }) { onFilter(filter.copy(brand = it)) }
                        FacetRow(T("Taglio", "Size"), CabCatalog.counts(all, filter, favorites, "size"), filter.size, { it }) { onFilter(filter.copy(size = it)) }
                        if (more) {
                            FacetRow("Speaker", CabCatalog.counts(all, filter, favorites, "speaker"), filter.speaker, { it }) { onFilter(filter.copy(speaker = it)) }
                            FacetRow(T("Microfono", "Microphone"), CabCatalog.counts(all, filter, favorites, "mic"), filter.mic, { it }) { onFilter(filter.copy(mic = it)) }
                            FacetRow(T("Stile", "Style"), CabCatalog.counts(all, filter, favorites, "style"), filter.style, { CabCatalog.styleLabel(it) }) { onFilter(filter.copy(style = it)) }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(T("${matching.size} di ${all.size}", "${matching.size} of ${all.size}"), color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { more = !more }) {
                                Text(if (more) T("Meno filtri", "Fewer filters") else T("Speaker, microfono, stile", "Speaker, mic, style"), color = Muted, fontSize = 12.sp)
                            }
                            if (filter != CatalogFilter()) TextButton(onClick = { onFilter(CatalogFilter()) }) { Text(T("Azzera", "Reset"), color = Red, fontSize = 12.sp) }
                        }
                    }
                }
                matching.groupBy { CabCatalog.brandKey(it) }.forEach { (brand, cabs) ->
                    stickyHeader(key = "h:$brand") {
                        Row(Modifier.fillMaxWidth().background(Coal).padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (brand == CabCatalog.OTHER) T("Marca non indicata", "Brand not stated") else brand).uppercase(),
                                color = if (brand == CabCatalog.BLACKSTAR) Red else Paper, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                            Text("  ${cabs.size}", color = Muted, fontSize = 11.sp)
                        }
                    }
                    items(cabs, key = { it.id }) { cab ->
                        CabCard(cab, curveOf(cab), cab.id == selectedId, CabCatalog.isFavorite(cab, favorites)) { onPick(cab) }
                    }
                }
                if (matching.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(T("Nessuna cassa con questi filtri", "No cabinet matches these filters"), color = Muted)
                        TextButton(onClick = { onFilter(CatalogFilter()) }) { Text(T("Azzera i filtri", "Reset the filters"), color = Red) }
                    }
                }
            }
        }
    }
}

/** The cabinet being looked at, on the page: its drawing, what it is, and the way into the browser right under it. */
@Composable internal fun CabSelector(cab: CatalogCab, curve: DoubleArray?, favorite: Boolean, total: Int, onBrowse: () -> Unit, onFavorite: () -> Unit,
                                     drawing: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)) {
        drawing()
        Row(Modifier.fillMaxWidth().clickable(onClick = onBrowse).padding(start = 14.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cab.title, color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(cab.summary, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(cab.source)
                    if (cab.style.isNotEmpty()) Text(CabCatalog.styleLabel(cab.style), color = Muted, fontSize = 10.sp)
                }
            }
            TextButton(onClick = onFavorite, modifier = Modifier.testTag("favorite-cabinet")) {
                Text(if (favorite) "★" else "☆", color = if (favorite) Red else Muted, fontSize = 20.sp)
            }
        }
        Sparkline(curve, true, Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 14.dp))
        HorizontalDivider(color = Hairline, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().clickable(onClick = onBrowse).padding(horizontal = 14.dp, vertical = 12.dp).testTag("cabinet-selector"),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(T("Sfoglia tutte le $total casse", "Browse all $total cabinets"), color = Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(T("per marca, taglio, speaker, microfono, stile", "by brand, size, speaker, microphone, style"), color = Muted, fontSize = 11.sp)
            }
            Text("›", color = Red, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The modelled response of a shipped cabinet, decoded once per id. */
internal fun libraryCurve(cab: LibraryCab): DoubleArray? = CabProfile.decode(cab.header, cab.chunks)?.let { CabLab.curveDb(it) }
