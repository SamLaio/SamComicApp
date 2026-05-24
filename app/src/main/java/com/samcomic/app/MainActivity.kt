package com.samcomic.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private const val PREFS_NAME = "sam_comic_prefs"
private const val KEY_LAST_OPDS_URL = "last_opds_url"
private const val KEY_LAST_USERNAME = "last_username"
private const val KEY_LAST_PASSWORD = "last_password"

class MainActivity : ComponentActivity() {
    private var externalOpenRequest by mutableStateOf<ExternalOpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureExternalOpenRequest(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SamComicApp(externalOpenRequest = externalOpenRequest)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureExternalOpenRequest(intent)
    }

    private fun captureExternalOpenRequest(intent: Intent?) {
        val uri = intent?.externalFileUri() ?: return
        externalOpenRequest = ExternalOpenRequest(
            uri = uri,
            mimeType = intent.type,
            token = System.nanoTime()
        )
    }
}

@Composable
private fun SamComicApp(
    externalOpenRequest: ExternalOpenRequest?,
    vm: ComicViewModel = viewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(externalOpenRequest?.token) {
        val request = externalOpenRequest ?: return@LaunchedEffect
        vm.openExternalFile(context, request.uri, request.mimeType)
    }

    BackHandler(enabled = state.document != null) {
        vm.closeReader()
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.document == null) {
                CatalogScreen(state = state, vm = vm)
            } else {
                ReaderScreen(state = state, vm = vm)
            }

            if (state.loadingFeed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            if (state.downloadingComic) {
                DownloadProgressOverlay(state = state)
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("發生錯誤") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::clearError) {
                    Text("知道了")
                }
            }
        )
    }
}

private data class ExternalOpenRequest(
    val uri: Uri,
    val mimeType: String?,
    val token: Long
)

@Suppress("DEPRECATION")
private fun Intent.externalFileUri(): Uri? {
    return when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> (getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            ?: clipData?.getItemAt(0)?.uri
        else -> null
    }
}

@Composable
private fun CatalogScreen(
    state: ComicUiState,
    vm: ComicViewModel
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefs = remember(context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val hasLoadedFeed = state.feedTitle.isNotBlank()
    var showConnectionPanel by rememberSaveable { mutableStateOf(true) }
    var showSearchPanel by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val catalogListState = rememberLazyListState()
    val titleCollapseFraction = if (catalogListState.firstVisibleItemIndex > 0) {
        1f
    } else {
        (catalogListState.firstVisibleItemScrollOffset / 160f).coerceIn(0f, 1f)
    }
    val shouldCollapseCatalogTitle = isLandscape && hasLoadedFeed && !showConnectionPanel
    val expandedTitleHeight = if (shouldCollapseCatalogTitle) 112.dp else if (isLandscape) 40.dp else 64.dp
    val titleHeight = expandedTitleHeight * (1f - titleCollapseFraction)
    val catalogSpacing = if (isLandscape) 8.dp else 12.dp
    val catalogPageText = if (state.loadingFeed) {
        state.status
    } else {
        state.catalogPageLabel.ifBlank { state.status }
    }

    LaunchedEffect(Unit) {
        val savedUrl = prefs.getString(KEY_LAST_OPDS_URL, "").orEmpty()
        val savedUsername = prefs.getString(KEY_LAST_USERNAME, "").orEmpty()
        val savedPassword = prefs.getString(KEY_LAST_PASSWORD, "").orEmpty()
        if (savedUrl.isNotBlank() && state.opdsUrl.isBlank()) {
            vm.updateOpdsUrl(savedUrl)
            vm.updateUsername(savedUsername)
            vm.updatePassword(savedPassword)
        }
    }

    LaunchedEffect(hasLoadedFeed, state.loadingFeed) {
        if (hasLoadedFeed && !state.loadingFeed) {
            showConnectionPanel = false
        }
    }

    LaunchedEffect(state.successfulConnectionVersion) {
        if (state.successfulConnectionVersion > 0 && state.successfulOpdsUrl.isNotBlank()) {
            prefs.edit {
                putString(KEY_LAST_OPDS_URL, state.successfulOpdsUrl)
                putString(KEY_LAST_USERNAME, state.successfulUsername)
                putString(KEY_LAST_PASSWORD, state.successfulPassword)
            }
        }
    }

    LaunchedEffect(state.catalogVersion) {
        catalogListState.scrollToItem(0)
    }

    LaunchedEffect(state.canSearch) {
        if (!state.canSearch) {
            showSearchPanel = false
            searchText = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(catalogSpacing)
    ) {
        if (isLandscape) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(titleHeight),
                contentAlignment = Alignment.CenterStart
            ) {
                if (titleCollapseFraction < 1f) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sam Comic", style = MaterialTheme.typography.headlineMedium)
                        if (shouldCollapseCatalogTitle && state.feedTitle.isNotBlank()) {
                            CatalogTitleRow(
                                title = state.feedTitle,
                                showSearch = state.canSearch,
                                searchExpanded = showSearchPanel,
                                onSearchToggle = { showSearchPanel = !showSearchPanel },
                                showEdit = true,
                                onEdit = { showConnectionPanel = !showConnectionPanel }
                            )
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(titleHeight),
                contentAlignment = Alignment.CenterStart
            ) {
                if (titleCollapseFraction < 1f) {
                    Text("Sam Comic", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }

        if (showConnectionPanel || !hasLoadedFeed) {
            if (isLandscape) {
                OutlinedTextField(
                    value = state.opdsUrl,
                    onValueChange = vm::updateOpdsUrl,
                    label = { Text("OPDS Feed URL") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = vm::updateUsername,
                    label = { Text("帳號（可選）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = vm::updatePassword,
                        label = { Text("密碼（可選）") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(3f)
                    )
                    Button(
                        onClick = vm::loadRootFeed,
                        enabled = !state.loadingFeed,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("讀取")
                    }
                }
            } else {
                OutlinedTextField(
                    value = state.opdsUrl,
                    onValueChange = vm::updateOpdsUrl,
                    label = { Text("OPDS Feed URL") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = vm::updateUsername,
                    label = { Text("帳號（可選）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = vm::updatePassword,
                    label = { Text("密碼（可選）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = vm::loadRootFeed,
                    enabled = !state.loadingFeed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("讀取")
                }
            }
            if (hasLoadedFeed) {
                if (!shouldCollapseCatalogTitle && state.feedTitle.isNotBlank()) {
                    CatalogTitleRow(
                        title = state.feedTitle,
                        showSearch = state.canSearch,
                        searchExpanded = showSearchPanel,
                        onSearchToggle = { showSearchPanel = !showSearchPanel },
                        showEdit = true,
                        onEdit = { showConnectionPanel = !showConnectionPanel }
                    )
                }
                CatalogNavigationRow(state = state, vm = vm, centerText = catalogPageText)
            }
        } else {
            if (!shouldCollapseCatalogTitle && state.feedTitle.isNotBlank()) {
                CatalogTitleRow(
                    title = state.feedTitle,
                    showSearch = state.canSearch,
                    searchExpanded = showSearchPanel,
                    onSearchToggle = { showSearchPanel = !showSearchPanel },
                    showEdit = true,
                    onEdit = { showConnectionPanel = !showConnectionPanel }
                )
            }
            CatalogNavigationRow(state = state, vm = vm, centerText = catalogPageText)
        }

        if (showSearchPanel && state.canSearch) {
            CatalogSearchRow(
                query = searchText,
                onQueryChange = { searchText = it },
                onSearch = { vm.searchCatalog(searchText) },
                onClear = { searchText = "" }
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = catalogListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = state.entries,
                    key = { index, entry ->
                        val linkKey = entry.links.joinToString("|") { it.href }
                        "${entry.id}|${entry.title}|$linkKey|$index"
                    }
                ) { _, entry ->
                    val readableLinks = vm.readableLinks(entry)
                    val progressLabel = readableLinks.firstOrNull()?.let { link ->
                        vm.readingProgressLabel(context, entry, link)
                    }
                    EntryCard(
                        entry = entry,
                        canNavigate = vm.hasNavigation(entry),
                        readableLinks = readableLinks,
                        progressLabel = progressLabel,
                        onNavigate = { vm.openNavigation(entry) },
                        onRead = { link -> vm.downloadAndOpen(context, entry, link) }
                    )
                }
            }
            CatalogScrollbar(
                state = catalogListState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun CatalogScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val metrics by remember(state) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val totalItems = layoutInfo.totalItemsCount
            if (visibleItems.isEmpty() || totalItems <= visibleItems.size) return@derivedStateOf null

            val firstIndex = visibleItems.first().index
            val lastIndex = visibleItems.last().index
            val canScroll = firstIndex > 0 || lastIndex < totalItems - 1
            if (!canScroll) return@derivedStateOf null

            val viewportFraction = (visibleItems.size.toFloat() / totalItems.toFloat()).coerceIn(0.08f, 1f)
            val progress = (firstIndex.toFloat() / (totalItems - visibleItems.size).coerceAtLeast(1).toFloat())
                .coerceIn(0f, 1f)
            viewportFraction to progress
        }
    }
    val (viewportFraction, progress) = metrics ?: return
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)

    Box(
        modifier = modifier
            .width(4.dp)
            .fillMaxHeight()
            .padding(end = 2.dp)
            .drawWithContent {
                drawContent()
                val radius = size.width / 2f
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(radius, radius)
                )
                val thumbHeight = (size.height * viewportFraction).coerceAtLeast(12f)
                val top = (size.height - thumbHeight).coerceAtLeast(0f) * progress
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, thumbHeight),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
    ) {
    }
}

@Composable
private fun CatalogTitleRow(
    title: String,
    showSearch: Boolean,
    searchExpanded: Boolean,
    onSearchToggle: () -> Unit,
    showEdit: Boolean,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (showSearch) {
            IconButton(onClick = onSearchToggle) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = if (searchExpanded) "收合搜尋" else "搜尋書本"
                )
            }
        }
        if (showEdit) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "編輯 OPDS 設定")
            }
        }
    }
}

@Composable
private fun CatalogSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("搜尋書本") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "清除搜尋")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onSearch,
            enabled = query.isNotBlank()
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("搜尋")
        }
    }
}

@Composable
private fun DownloadProgressOverlay(state: ComicUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("下載中", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = state.status.ifBlank { "正在下載漫畫" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val progress = state.downloadProgress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogNavigationRow(
    state: ComicUiState,
    vm: ComicViewModel,
    centerText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = vm::goBack, enabled = state.canGoBack && !state.loadingFeed) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "返回上一層")
        }
        Text(
            text = centerText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::goPrevious, enabled = state.canGoPrevious && !state.loadingFeed) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一頁")
            }
            IconButton(onClick = vm::goNext, enabled = state.canGoNext && !state.loadingFeed) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一頁")
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: OpdsEntry,
    canNavigate: Boolean,
    readableLinks: List<ReadableLink>,
    progressLabel: String?,
    onNavigate: () -> Unit,
    onRead: (ReadableLink) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canNavigate && readableLinks.isEmpty(), onClick = onNavigate)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val showAuthor = entry.author.isNotBlank() && entry.author != "Unknown" && !canNavigate
            if (showAuthor || (!canNavigate && progressLabel != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showAuthor) {
                        Text(
                            text = "作者：${entry.author}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (progressLabel != null) {
                        Text(
                            text = progressLabel,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                }
            }
            if (entry.summary.isNotBlank()) {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (canNavigate) {
                    Button(
                        onClick = onNavigate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("開啟")
                    }
                }
                readableLinks.forEach { link ->
                    Button(
                        onClick = { onRead(link) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (link.extensionHint == "pdf") {
                                Icons.Filled.PictureAsPdf
                            } else {
                                Icons.Filled.Archive
                            },
                            contentDescription = null
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(link.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    state: ComicUiState,
    vm: ComicViewModel
) {
    val document = state.document ?: return
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showPageJumpDialog by rememberSaveable { mutableStateOf(false) }
    var showReaderToolbar by rememberSaveable { mutableStateOf(true) }
    var pageInput by rememberSaveable(state.pageIndex, document.pageCount) {
        mutableStateOf((state.pageIndex + 1).toString())
    }

    LaunchedEffect(isLandscape, document.pageCount) {
        vm.setTwoPageMode(isLandscape)
        val window = (view.context as? MainActivity)?.window
        if (isLandscape) {
            window?.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            window?.insetsController?.show(WindowInsets.Type.statusBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            (view.context as? MainActivity)
                ?.window
                ?.insetsController
                ?.show(WindowInsets.Type.statusBars())
        }
    }

    val pageLabel = if (state.showTwoPages && state.pageIndex + 1 < document.pageCount) {
        "${state.pageIndex + 1}-${state.pageIndex + 2}/${document.pageCount}"
    } else {
        "${state.pageIndex + 1}/${document.pageCount}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (showReaderToolbar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = vm::closeReader) {
                    Icon(Icons.Filled.Close, contentDescription = "關閉")
                }
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = pageLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        pageInput = (state.pageIndex + 1).toString()
                        showPageJumpDialog = true
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(state.pageIndex, document.pageCount, state.loadingReader) {
                    detectTapGestures { offset ->
                        if (state.loadingReader) return@detectTapGestures
                        when (tapGridCell(offset.x, offset.y, size.width, size.height)) {
                            1, 2, 4, 7 -> vm.previousPage(context)
                            3, 6, 8, 9 -> vm.nextPage(context)
                            5 -> showReaderToolbar = !showReaderToolbar
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val bitmap = state.pageBitmap
            val secondBitmap = state.secondPageBitmap
            if (bitmap != null) {
                if (state.showTwoPages) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "漫畫左頁",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            alignment = Alignment.CenterEnd
                        )
                        if (secondBitmap != null) {
                            Image(
                                bitmap = secondBitmap.asImageBitmap(),
                                contentDescription = "漫畫右頁",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                alignment = Alignment.CenterStart
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "漫畫頁面",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            if (state.loadingReader) {
                CircularProgressIndicator()
            }
            if (!showReaderToolbar) {
                Text(
                    text = pageLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable {
                            pageInput = (state.pageIndex + 1).toString()
                            showPageJumpDialog = true
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

    }

    if (showPageJumpDialog) {
        AlertDialog(
            onDismissRequest = { showPageJumpDialog = false },
            title = { Text("跳頁") },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { value ->
                            pageInput = value.filter { it.isDigit() }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Text("/ ${document.pageCount}")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPageJumpDialog = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetPage = pageInput.toIntOrNull()
                        if (targetPage != null) {
                            vm.goToPage(context, targetPage)
                            showPageJumpDialog = false
                        }
                    }
                ) {
                    Text("確認")
                }
            }
        )
    }
}

private fun tapGridCell(x: Float, y: Float, width: Int, height: Int): Int {
    val column = when {
        x < width / 3f -> 0
        x < width * 2f / 3f -> 1
        else -> 2
    }
    val row = when {
        y < height / 3f -> 0
        y < height * 2f / 3f -> 1
        else -> 2
    }
    return row * 3 + column + 1
}
