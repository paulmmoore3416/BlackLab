package com.blacklab.app.ui.camera

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.Folder
import com.blacklab.app.imaging.BitmapIO
import com.blacklab.app.imaging.FilterRenderer
import com.blacklab.app.imaging.PhotoFilter
import com.blacklab.app.imaging.TinyPlanet
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Review a stitched panorama: pick a look, optionally make a Tiny Planet, then save. */
@Composable
fun PanoramaReview(pano: Bitmap, onDiscard: () -> Unit, onSaved: (List<File>) -> Unit) {
    val app = rememberApp()
    val s = settingsState()
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(PhotoFilter.NONE) }
    var planet by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var spin by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    BackHandler(enabled = !saving) { onDiscard() }

    val display by produceState<Bitmap?>(null, pano) {
        value = withContext(Dispatchers.Default) {
            val sc = minOf(1f, 2400f / pano.width, 900f / pano.height)
            if (sc < 1f) Bitmap.createScaledBitmap(pano, (pano.width * sc).toInt(), (pano.height * sc).toInt(), true) else pano
        }
    }
    val planetPreview by produceState<Bitmap?>(null, display, planet, zoom, spin) {
        val d = display ?: return@produceState
        if (!planet) { value = null; return@produceState }
        delay(120)
        value = withContext(Dispatchers.Default) { TinyPlanet.render(d, 900, zoom, spin) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Close, "Discard", tint = Color.White, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !saving) { onDiscard() }.padding(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Panorama", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(String.format(Locale.US, "%,d × %,d px · %.1f MP", pano.width, pano.height, pano.width * pano.height / 1_000_000f), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shown = if (planet) planetPreview else display
                if (shown == null) CircularProgressIndicator()
                else Image(shown.asImageBitmap(), "Panorama preview", contentScale = ContentScale.Fit,
                    colorFilter = if (filter.isIdentity) null else ColorFilter.colorMatrix(filter.composeMatrix()),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).then(if (planet) Modifier.clip(RoundedCornerShape(24.dp)) else Modifier))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tiny planet", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Wrap a 360° sweep into a little world (saved as an extra photo)", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                Switch(planet, { planet = it })
            }
            if (planet) Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Size", color = Color.White, fontSize = 12.sp); Slider(zoom, { zoom = it }, valueRange = 0.5f..2f)
                Text("Rotate", color = Color.White, fontSize = 12.sp); Slider(spin, { spin = it }, valueRange = 0f..360f)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoFilter.entries.forEach { f ->
                    val sel = f == filter
                    Text(f.label, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.14f)).clickable { filter = f }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.weight(1f)) { Text("Discard", color = Color.White) }
                Button(enabled = !saving, modifier = Modifier.weight(1f), onClick = {
                    saving = true
                    scope.launch {
                        val files = withContext(Dispatchers.Default) {
                            val stamp = BlackLabStorage.timestamp()
                            val dir = app.storage.dir(s.activeProfile, Folder.PHOTOS)
                            val out = mutableListOf<File>()
                            val finalPano = if (filter.isIdentity) pano else FilterRenderer.apply(pano, filter, dateStamp = false)
                            File(dir, "PANO_$stamp.jpg").also { BitmapIO.saveJpeg(finalPano, it, 95); out += it }
                            if (planet) {
                                val p = TinyPlanet.render(finalPano, 3072, zoom, spin)
                                File(dir, "PLANET_$stamp.jpg").also { BitmapIO.saveJpeg(p, it, 95); out += it }
                                p.recycle()
                            }
                            out
                        }
                        onSaved(files)
                    }
                }) { Text("Save") }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (saving) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Saving full-resolution panorama…", color = Color.White)
            }
        }
    }
}
