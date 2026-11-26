package eu.kanade.tachiyomi.ui.offline

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.metroTilt
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.library.components.GroveRack
import eu.kanade.presentation.library.components.RackBook
import eu.kanade.presentation.library.components.RackShelf
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.main.MainActivity
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Book
import java.io.File

/**
 * Grovebook "Books": the offline library, read straight from folders on the device.
 */
data object OfflineTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 1u,
            title = "Books",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Book),
        )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        var refreshKey by remember { mutableIntStateOf(0) }
        var shelves by remember { mutableStateOf<List<OfflineShelf>?>(null) }
        val covers = remember { mutableStateMapOf<Uri, File>() }
        val titles = remember { mutableStateMapOf<Uri, String>() }
        var folders by remember { mutableStateOf(OfflineLibrary.folders(context)) }
        var removeAsk by remember { mutableStateOf<Uri?>(null) }
        var moveAsk by remember { mutableStateOf<OfflineBook?>(null) }
        var newFolderAsk by remember { mutableStateOf(false) }
        var collections by remember { mutableStateOf(OfflineLibrary.collections(context)) }
        var assignTick by remember { mutableIntStateOf(0) }
        var deleteAsk by remember { mutableStateOf(false) }
        // Refresh progress percentages when coming back from the reader
        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
            assignTick++
            onPauseOrDispose {}
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                OfflineLibrary.addFolder(context, uri)
                folders = OfflineLibrary.folders(context)
                refreshKey++
            }
        }

        LaunchedEffect(refreshKey) {
            val result = OfflineLibrary.scan(context)
            shelves = result
            result.flatMap { it.books }.forEach { book ->
                if (book.uri !in covers) {
                    OfflineLibrary.cover(context, book)?.let { covers[book.uri] = it }
                }
                if (book.uri !in titles) {
                    titles[book.uri] = OfflineLibrary.title(context, book)
                }
            }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }

        Scaffold(
            topBar = {
                eu.kanade.presentation.components.RegisterPivotMenu(
                    key = "books",
                    items = listOf(
                        "new folder" to { newFolderAsk = true },
                        "delete a folder" to { deleteAsk = true },
                        "add book folder from phone" to { picker.launch(null) },
                        "rescan folders" to { refreshKey++; Unit },
                    ),
                )
                if (!eu.kanade.presentation.components.LocalInPivot.current) eu.kanade.presentation.components.AppBar(
                    title = "books",
                    actions = {
                        TextButton(onClick = { picker.launch(null) }) { Text("add folder") }
                    },
                )
            },
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
        ) { padding ->
            @Suppress("UNUSED_VARIABLE")
            val refreshAfterMove = assignTick
            val inPivotBooks = eu.kanade.presentation.components.LocalInPivot.current
            fun tile(book: OfflineBook) = RackBook(
                key = book.uri.toString(),
                title = titles[book.uri] ?: OfflineLibrary.cleanName(book.title),
                cover = covers[book.uri],
                badge = OfflineLibrary.progress(context, book.uri)?.let { "$it%" },
                onClick = {
                    context.startActivity(OfflineReaderActivity.newIntent(context, book.uri, titles[book.uri] ?: OfflineLibrary.cleanName(book.title)))
                },
                // inside GroveMedia: long press = small info card ("move to folder" is one of its buttons)
                onLongClick = {
                    if (inPivotBooks) {
                        val name = titles[book.uri] ?: OfflineLibrary.cleanName(book.title)
                        val sp = context.getSharedPreferences("grove_offline", android.content.Context.MODE_PRIVATE)
                        val ms = (sp.all["time:${book.uri}"] as? Number)?.toLong() ?: 0L
                        val pages = (sp.all["pages:${book.uri}"] as? Int) ?: 0
                        com.grove.music.Peek.show(com.grove.music.PeekData(
                            kind = "BOOK", title = name, sub = book.extension.uppercase(), image = covers[book.uri], ratio = 0.7f,
                            rows = listOfNotNull(
                                OfflineLibrary.progress(context, book.uri)?.let { "progress" to "$it%" },
                                pages.takeIf { it > 0 }?.let { "pages" to it.toString() },
                                ms.takeIf { it > 0 }?.let { "reading time" to (it / 60000).let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" } },
                            ),
                            more = { com.grove.music.BookInfo.peek(context, name) },
                            actions = listOf(
                                "read" to { context.startActivity(OfflineReaderActivity.newIntent(context, book.uri, name)) },
                                "move to folder" to { moveAsk = book },
                            ),
                        ))
                    } else {
                        moveAsk = book
                    }
                },
            )
            // The user's own folders first, then whatever is left by device folder
            val allBooks = shelves.orEmpty().flatMap { it.books }
            val assigned = mutableSetOf<String>()
            val own = collections.map { name ->
                val uris = OfflineLibrary.booksIn(context, name)
                assigned += uris
                RackShelf(
                    key = "col:$name",
                    label = name,
                    books = allBooks.filter { it.uri.toString() in uris }.distinctBy { it.uri }.map(::tile),
                )
            }
            val rack = own + shelves.orEmpty().mapNotNull { shelf ->
                val rest = shelf.books.filter { it.uri.toString() !in assigned }
                if (rest.isEmpty()) {
                    null
                } else {
                    RackShelf(key = shelf.path, label = shelf.path, books = rest.map(::tile))
                }
            }
            GroveRack(
                storeKey = "books",
                shelves = rack,
                topPadding = padding.calculateTopPadding(),
                bottomPadding = padding.calculateBottomPadding(),
                emptyText = if (folders.isEmpty()) {
                    "Pick a folder on your phone that holds your books.\nPDF, EPUB, CBZ/CBR, TXT, HTML and FB2 are supported."
                } else if (shelves == null) {
                    "Looking for books…"
                } else {
                    "No books found in your folders yet."
                },
                header = {
                    if (folders.isEmpty()) {
                        item(key = "pick") {
                            Text(
                                text = "choose a book folder",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .metroTilt { picker.launch(null) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                },
                footer = {
                    if (folders.isNotEmpty()) {
                        item(key = "folders") {
                            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Text(
                                    "book folders",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 16.sp,
                                )
                                folders.forEach { uri ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .metroTilt { removeAsk = uri }
                                            .padding(vertical = 8.dp),
                                    ) {
                                        Text(
                                            text = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast(':').lowercase(),
                                            color = androidx.compose.ui.graphics.Color.White,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Light,
                                        )
                                        Text("tap to remove", color = eu.kanade.presentation.components.Metro.Dim, fontSize = 13.sp)
                                    }
                                }
                                listOf("add folder" to { picker.launch(null) }, "rescan" to { refreshKey++ }).forEach { (label, act) ->
                                    Text(
                                        text = label,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Light,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .metroTilt { act() }
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }

        // Move a book into one of the user's folders
        moveAsk?.let { book ->
            AlertDialog(
                onDismissRequest = { moveAsk = null },
                title = { Text("move to folder") },
                text = {
                    Column {
                        Text(book.title, color = eu.kanade.presentation.components.Metro.Dim)
                        (listOf<String?>(null) + collections).forEach { name ->
                            Text(
                                text = name?.lowercase() ?: "no folder",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .metroTilt {
                                        OfflineLibrary.assign(context, book.uri, name)
                                        assignTick++
                                        moveAsk = null
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                        Text(
                            text = "new folder…",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier
                                .fillMaxWidth()
                                .metroTilt { newFolderAsk = true }
                                .padding(vertical = 8.dp),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { moveAsk = null }) { Text("cancel") } },
            )
        }

        if (deleteAsk) {
            AlertDialog(
                onDismissRequest = { deleteAsk = false },
                title = { Text("delete a folder") },
                text = {
                    Column {
                        if (collections.isEmpty()) Text("you have no folders yet")
                        collections.forEach { name ->
                            Text(
                                text = name.lowercase(),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .metroTilt {
                                        // Books go back to their phone folders; nothing is deleted from the phone
                                        OfflineLibrary.deleteCollection(context, name)
                                        collections = OfflineLibrary.collections(context)
                                        deleteAsk = false
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { deleteAsk = false }) { Text("cancel") } },
            )
        }

        if (newFolderAsk) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { newFolderAsk = false },
                title = { Text("new folder") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text("folder name") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        OfflineLibrary.createCollection(context, name)
                        collections = OfflineLibrary.collections(context)
                        // If a book was waiting to be moved, put it straight in
                        moveAsk?.let {
                            OfflineLibrary.assign(context, it.uri, name.trim())
                            assignTick++
                            moveAsk = null
                        }
                        newFolderAsk = false
                    }) { Text("create") }
                },
                dismissButton = { TextButton(onClick = { newFolderAsk = false }) { Text("cancel") } },
            )
        }

        removeAsk?.let { uri ->
            AlertDialog(
                onDismissRequest = { removeAsk = null },
                title = { Text("Remove folder?") },
                text = { Text("Books stay on your phone; GroveMedia just stops showing this folder.") },
                confirmButton = {
                    TextButton(onClick = {
                        OfflineLibrary.removeFolder(context, uri)
                        folders = OfflineLibrary.folders(context)
                        removeAsk = null
                        refreshKey++
                    }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { removeAsk = null }) { Text("Cancel") } },
            )
        }
    }
}
