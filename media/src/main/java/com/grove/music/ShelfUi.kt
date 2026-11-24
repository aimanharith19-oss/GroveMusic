package com.grove.music

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** GroveMedia's own comic/book library: a grid of files/folders added from the device, no online sources. */
@Composable
fun ShelfLibraryScreen(kind: String, accent: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val db = remember { ShelfDb(ctx) }
    var items by remember { mutableStateOf(listOf<ShelfItem>()) }
    var reload by remember { mutableIntStateOf(0) }
    var adultN by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        val all = withContext(Dispatchers.IO) { db.all(kind) }
        val (adult, ok) = all.partition { Adult.isAdultName(it.title) }
        adultN = adult.size; items = ok
    }
    val adultToast = { android.widget.Toast.makeText(ctx, "18+ hidden", android.widget.Toast.LENGTH_SHORT).show() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)?.name ?: "untitled"
            if (Adult.isAdultName(name)) adultToast() else { db.add(kind, name, uri.toString()); reload++ }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.name ?: "untitled"
            if (Adult.isAdultName(name) || (name.endsWith(".epub", true) && Adult.isAdultEpub(ctx, uri))) adultToast()
            else { db.add(kind, name, uri.toString()); reload++ }
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (kind == "comic") "+ add comic folder" else "+ add book file",
                color = accent, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { if (kind == "comic") pickFolder.launch(null) else pickFile.launch(arrayOf("*/*")) },
            )
        }
        if (adultN > 0) Text("$adultN · 18+ hidden", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(if (kind == "comic") "no comics yet — add a folder of images or a .cbz" else "no books yet — add a file",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
            }
            return@Column
        }
        LazyVerticalGrid(GridCells.Fixed(3), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.id }) { item ->
                Column(Modifier.clickable {
                    if (kind == "comic") ComicReaderActivity.open(ctx, item) else ShelfBooks.open(ctx, item)
                }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
                        if (item.cover != null) AsyncImage(model = item.cover, contentDescription = null, modifier = Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize().padding(1.dp), Alignment.Center) {
                            Text(item.title.take(1).uppercase(), color = Color.White.copy(alpha = 0.6f), fontSize = 28.sp)
                        }
                        if (item.pages > 0) Box(Modifier.fillMaxWidth().align(Alignment.BottomStart)) {
                            Box(Modifier.fillMaxWidth((item.page.toFloat() / item.pages).coerceIn(0f, 1f)).padding(0.dp)) {
                                Box(Modifier.fillMaxWidth().padding(0.dp)) {}
                            }
                        }
                    }
                    Text(item.title, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    if (item.pages > 0) Text("${item.page + 1}/${item.pages}", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
        }
    }
}
