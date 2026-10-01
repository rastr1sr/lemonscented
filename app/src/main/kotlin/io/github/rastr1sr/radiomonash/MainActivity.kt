package io.github.rastr1sr.radiomonash

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

private val Seed = Color(0xFF0439D9)

internal fun ListItemShapes.flat() = copy(selectedShape = shape)

internal val segmented: ListItemColors
    @Composable get() = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )

internal object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val content = 600.dp
}

internal val margin: Dp
    @Composable get() = if (
        currentWindowAdaptiveInfoV2().windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    ) {
        Spacing.lg
    } else {
        Spacing.md
    }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun <T : Any> ListDetail(
    picked: T?,
    empty: String,
    onClose: () -> Unit,
    detail: @Composable (T, close: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    list: @Composable (twoPane: Boolean) -> Unit,
) {
    val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    val listContent = remember { movableContentOf<Boolean> { list(it) } }
    val detailContent = remember {
        movableContentOf<T, () -> Unit> { item, close -> detail(item, close) }
    }
    if (directive.maxHorizontalPartitions < 2) {
        Box(modifier) {
            listContent(false)
            picked?.let { item -> Sheet(onClose) { close -> detailContent(item, close) } }
        }
        return
    }
    ListDetailPaneScaffold(
        directive = directive,
        value = calculateThreePaneScaffoldValue(
            directive.maxHorizontalPartitions,
            ListDetailPaneScaffoldDefaults.adaptStrategies(),
            ThreePaneScaffoldDestinationItem<Nothing>(ListDetailPaneScaffoldRole.List),
        ),
        listPane = { AnimatedPane { listContent(true) } },
        detailPane = {
            AnimatedPane {
                if (picked == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            empty,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    key(picked) { detailContent(picked) {} }
                }
            }
        },
        modifier = modifier,
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val radio = radio()
        lifecycleScope.launch(Dispatchers.IO) {
            radio.shows.load(force = false)
            Recent.load()
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val look by radio.preferences.look.collectAsStateWithLifecycle()
            LemonScentedTheme(look) {
                Radio()
            }
        }
    }
}

@Serializable
private sealed interface Screen : NavKey {
    @Serializable
    data object Home : Screen

    @Serializable
    data object Chat : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object Logs : Screen
}

@Serializable
private sealed class Tab(val label: Int, val icon: Int, val selected: Int) : NavKey {
    @Serializable
    data object Player : Tab(R.string.player, R.drawable.ic_radio, R.drawable.ic_radio_fill)

    @Serializable
    data object Schedule : Tab(
        R.string.schedule,
        R.drawable.ic_calendar,
        R.drawable.ic_calendar_fill,
    )

    @Serializable
    data object Recent : Tab(R.string.recent, R.drawable.ic_history, R.drawable.ic_history)

    @Serializable
    data object You : Tab(R.string.you, R.drawable.ic_person, R.drawable.ic_person_fill)
}

private val tabs = listOf(Tab.Player, Tab.Schedule, Tab.Recent, Tab.You)

@Composable
private fun Radio(
    model: PlayerViewModel = viewModel {
        val radio = radio()
        PlayerViewModel(Playback(radio), radio.library, radio.shows, radio.preferences)
    },
) {
    val state by model.player.collectAsStateWithLifecycle()
    val screens = rememberNavBackStack(Screen.Home)
    val pop: () -> Unit = { screens.removeAt(screens.lastIndex) }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }
    val forward = (slideInHorizontally(spatial) { shift } + fadeIn(effects)) togetherWith
        (slideOutHorizontally(spatial) { -shift } + fadeOut(effects))
    val back = (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
        (slideOutHorizontally(spatial) { shift } + fadeOut(effects))
    NavDisplay(
        backStack = screens,
        onBack = pop,
        transitionSpec = { forward },
        popTransitionSpec = { back },
        predictivePopTransitionSpec = { back },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Screen.Home> {
                Home(onSettings = { screens.add(Screen.Settings) }) {
                    val saved by model.favourite.collectAsStateWithLifecycle()
                    val sleepUntil by model.sleepUntil.collectAsStateWithLifecycle()
                    val shows by model.schedule.collectAsStateWithLifecycle()
                    val look by model.look.collectAsStateWithLifecycle()
                    PlayerPage(
                        state,
                        saved,
                        sleepUntil,
                        shows,
                        look,
                        onToggle = model::toggle,
                        onFavourite = model::favourite,
                        onSleep = model::sleep,
                        onChat = { screens.add(Screen.Chat) },
                    )
                }
            }
            entry<Screen.Chat> {
                ChatScreen(
                    state.meta,
                    playing = state.playing,
                    enabled = state.ready,
                    onToggle = model::toggle,
                    onBack = pop,
                )
            }
            entry<Screen.Settings> {
                SettingsScreen(onBack = pop, onLogs = { screens.add(Screen.Logs) })
            }
            entry<Screen.Logs> { LogsScreen(onBack = pop) }
        },
    )
}

@Composable
private fun Home(onSettings: () -> Unit, player: @Composable () -> Unit) {
    val backStack = rememberNavBackStack(Tab.Player)
    val current = backStack.last() as Tab
    val go = { tab: Tab ->
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        if (tab != Tab.Player) backStack.add(tab)
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var selection by remember { mutableStateOf<Selection?>(null) }
    val layout = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfoV2())
    val bottomBar = layout == NavigationSuiteType.NavigationBar ||
        layout == NavigationSuiteType.ShortNavigationBarCompact ||
        layout == NavigationSuiteType.ShortNavigationBarMedium
    NavigationSuiteScaffold(
        navigationItems = {
            tabs.forEach {
                val selected = current == it
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { go(it) },
                    icon = { Icon(painterResource(if (selected) it.selected else it.icon), null) },
                    label = { Text(stringResource(it.label)) },
                    navigationSuiteType = layout,
                )
            }
        },
        navigationSuiteType = layout,
        navigationItemVerticalArrangement = Arrangement.Center,
    ) {
        Scaffold(
            Modifier.nestedScroll(scroll.nestedScrollConnection),
            contentWindowInsets = if (bottomBar) {
                ScaffoldDefaults.contentWindowInsets.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                )
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
            topBar = {
                val active = selection
                TopAppBar(
                    title = {
                        Text(
                            if (active != null) {
                                pluralStringResource(
                                    R.plurals.selected_count,
                                    active.count,
                                    active.count,
                                )
                            } else {
                                stringResource(
                                    if (current == Tab.Player) R.string.app_name else current.label,
                                )
                            },
                        )
                    },
                    navigationIcon = {
                        if (active != null) {
                            IconButton(active.onClear) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    stringResource(R.string.clear_selection),
                                )
                            }
                        }
                    },
                    actions = active?.actions ?: {
                        IconButton(onSettings) {
                            Icon(
                                painterResource(R.drawable.ic_settings),
                                stringResource(R.string.settings),
                            )
                        }
                    },
                    scrollBehavior = scroll,
                )
            },
        ) { padding ->
            val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val fade = fadeIn(effects) togetherWith fadeOut(effects)
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeAt(backStack.lastIndex) },
                modifier = Modifier.padding(padding),
                transitionSpec = { fade },
                popTransitionSpec = { fade },
                predictivePopTransitionSpec = { fade },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<Tab.Player> { player() }
                    entry<Tab.Schedule> { Schedule() }
                    entry<Tab.Recent> { History(onSelection = { selection = it }) }
                    entry<Tab.You> { You(onSelection = { selection = it }) }
                },
            )
        }
    }
}

@Composable
fun Failed(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Refreshable(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing,
        onRefresh,
        modifier,
        state,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state,
                isRefreshing,
                Modifier.align(Alignment.TopCenter),
            )
        },
        content = content,
    )
}

internal fun <T> Set<T>.toggle(item: T) = if (item in this) this - item else this + item

internal class Selection(
    val count: Int,
    val onClear: () -> Unit,
    val actions: @Composable RowScope.() -> Unit,
)

@Composable
internal fun PublishSelection(selection: Selection?, onSelection: (Selection?) -> Unit) {
    val publish by rememberUpdatedState(onSelection)
    SideEffect { publish(selection) }
    DisposableEffect(Unit) { onDispose { publish(null) } }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Loading(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.loading)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.semantics { contentDescription = loading })
    }
}

@Composable
internal fun LemonScentedTheme(look: Look = Look(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (look.theme) {
        Theme.System -> isSystemInDarkTheme()
        Theme.Light -> false
        Theme.Dark -> true
    }
    val black = dark && look.black
    val colorScheme = if (look.dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when {
            black -> dynamicDarkColorScheme(context)
                .copy(background = Color.Black, surface = Color.Black)

            dark -> dynamicDarkColorScheme(context)

            else -> dynamicLightColorScheme(context)
        }
    } else {
        rememberDynamicColorScheme(
            seedColor = Seed,
            isDark = dark,
            isAmoled = black,
            style = PaletteStyle.Fidelity,
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let {
                WindowCompat.getInsetsController(it, view).run {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialExpressiveTheme(colorScheme, MotionScheme.expressive(), content = content)
}
