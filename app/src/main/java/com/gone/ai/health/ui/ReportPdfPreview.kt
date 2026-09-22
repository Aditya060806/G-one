package com.gone.ai.health.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.createBitmap
import com.gone.ai.ui.components.BreadcrumbItem
import com.gone.ai.ui.components.PureBreadcrumbText
import com.gone.ai.ui.theme.ModernBgDark
import com.gone.ai.ui.theme.ModernBgLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A report's PDF shown in the app, page by page, exactly as it will be shared or saved.
 * Pages are drawn with Android's own PDF renderer at the screen's width.
 */
@Composable
fun ReportPdfPreviewScreen(
    reportId: Long,
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateHome: () -> Unit,
    onOpenReport: () -> Unit,
    vm: HealthViewModel
) {
    var pages by remember(reportId) { mutableStateOf<List<Bitmap>?>(null) }
    var problem by remember(reportId) { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(if (isDarkTheme) ModernBgDark else ModernBgLight)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            val widthPx = with(LocalDensity.current) { (maxWidth - 32.dp).roundToPx() }
            LaunchedEffect(reportId, widthPx) {
                val file = vm.reportPdf(reportId)
                if (file == null) {
                    problem = "The PDF could not be prepared."
                } else {
                    pages = runCatching { renderPages(file, widthPx) }
                        .onFailure { problem = "The PDF could not be shown: ${it.message}" }
                        .getOrNull()
                }
            }

            val rendered = pages
            when {
                rendered != null -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 64.dp, bottom = bottomPadding + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(rendered) { index, page ->
                        Image(
                            bitmap = page.asImageBitmap(),
                            contentDescription = "Page ${index + 1} of ${rendered.size}",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        )
                    }
                }
                problem != null -> Text(problem!!, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 18.dp)
                .zIndex(10f)
        ) {
            PureBreadcrumbText(
                items = listOf(BreadcrumbItem("Home", onNavigateHome), BreadcrumbItem("Report", onOpenReport), BreadcrumbItem("PDF")),
                isDarkTheme = isDarkTheme
            )
        }
    }
}

private suspend fun renderPages(file: File, widthPx: Int): List<Bitmap> = withContext(Dispatchers.IO) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            (0 until renderer.pageCount).map { index ->
                renderer.openPage(index).use { page ->
                    val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    createBitmap(widthPx, height).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}
