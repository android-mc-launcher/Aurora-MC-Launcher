package com.yuanbao.mcjlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 宽度档位（按窗口实际可用 dp，不是物理屏幕）。 */
enum class WidthClass(
    val label: String,
) {
    COMPACT("紧凑"),
    MEDIUM("中等"),
    EXPANDED("展开"),
}

/** 导航形态：窄窗口底部栏 / 横屏侧边栏 / 大屏常驻抽屉。 */
enum class NavType(
    val label: String,
) {
    BAR("底部导航"),
    RAIL("侧边导航"),
    DRAWER("常驻抽屉"),
}

/**
 * 一次测量得到的窗口规格。全部由 [AdaptiveRoot] 的 BoxWithConstraints 实时计算，
 * 分屏、折叠屏开合、自由窗口拖动、系统忽略横屏请求时都会自动重组，不需要重建 Activity。
 */
data class WindowSpec(
    val widthDp: Dp,
    val heightDp: Dp,
    val widthClass: WidthClass,
    val navType: NavType,
    val columns: Int,
    val isLandscape: Boolean,
    val isShortHeight: Boolean,
) {
    val summary: String
        get() =
            "${widthDp.value.toInt()}×${heightDp.value.toInt()}dp · ${widthClass.label} · " +
                "${navType.label} · $columns 栏 · ${if (isLandscape) "横屏" else "竖屏"}"

    val contentPadding: Dp
        get() =
            when {
                widthDp >= 840.dp -> 20.dp
                widthDp >= 600.dp -> 16.dp
                else -> 12.dp
            }

    val verticalPadding: Dp get() = if (isShortHeight) 8.dp else 12.dp
    val cardGap: Dp get() = if (isShortHeight) 8.dp else 12.dp

    /** 超宽桌面窗口限制内容最大宽度并居中，避免每栏被拉到 900dp 以上。 */
    val maxContentWidth: Dp get() = if (widthDp >= 1400.dp) 1600.dp else Dp.Unspecified
}

/**
 * 自适应根：按窗口 dp 选择导航形态与内容栏数。
 * 断点：宽 <600 底部栏单栏；600–1099 侧边栏（≥700 双栏）；≥1100 常驻抽屉（双栏）。
 * 高度 <480dp 视为「矮横屏」，收紧上下留白，让双栏各自独立滚动。
 */
@Composable
fun AdaptiveRoot(content: @Composable (WindowSpec) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val spec =
            WindowSpec(
                widthDp = w,
                heightDp = h,
                widthClass =
                    when {
                        w < 600.dp -> WidthClass.COMPACT
                        w < 840.dp -> WidthClass.MEDIUM
                        else -> WidthClass.EXPANDED
                    },
                navType =
                    when {
                        w < 600.dp -> NavType.BAR
                        w >= 1100.dp -> NavType.DRAWER
                        else -> NavType.RAIL
                    },
                columns = if (w >= 700.dp) 2 else 1,
                isLandscape = w >= h,
                isShortHeight = h < 480.dp,
            )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.widthIn(max = spec.maxContentWidth).fillMaxWidth()) {
                content(spec)
            }
        }
    }
}

/**
 * 双栏面板：把页面内容拆成 primary / secondary 两段。
 * - 单栏：两段顺序纵向排列，整页可滚动（保持窄窗口下的原阅读顺序）；
 * - 宽屏：左右并排、各自独立滚动，横屏矮屏下不必整页长滚动。
 *
 * 两栏都带 verticalScroll，所以内容里不能出现 Modifier.weight(1f)；
 * 版本库的 LazyColumn 用固定高度 + 自身滚动来兼容这个约束。
 */
@Composable
fun AdaptivePanels(
    spec: WindowSpec,
    modifier: Modifier = Modifier,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    val gap = spec.cardGap
    val hPad = spec.contentPadding
    val vPad = spec.verticalPadding
    // remember 放在条件分支之外，保证窗口尺寸变化时重组位置稳定。
    val primaryScrollState = rememberScrollState()
    val secondaryScrollState = rememberScrollState()
    val base =
        modifier
            .fillMaxSize()
            .padding(horizontal = hPad, vertical = vPad)

    if (spec.columns <= 1) {
        Column(
            modifier = base.verticalScroll(primaryScrollState),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            primary()
            secondary()
        }
    } else {
        Row(modifier = base, horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(primaryScrollState),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) { primary() }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(secondaryScrollState),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) { secondary() }
        }
    }
}

/** 设置页 / 关于页共用的实时布局信息卡。 */
@Composable
fun AdaptiveInfoCard(
    spec: WindowSpec,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("界面布局（实时自适应）", style = MaterialTheme.typography.titleSmall)
            Text(spec.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "横屏固定请求 + 窗口实测：分屏、折叠屏、自由窗口与平板都会重新测量；" +
                    "矮横屏自动收紧留白并拆成多栏独立滚动。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
