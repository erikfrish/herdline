package io.github.erikfrish.herdline.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * One pin reaches the very end ([atEnd] at 0 px), or the follow collector
 * pins again on every layout. lab.9 (10013) pinned by the last item's size,
 * which in a list squeezed below its own padding never gets there: a main
 * thread layout loop, ANR and OOM after a configuration change in landscape
 * Chat with the keyboard up.
 */
class ChatScrollToEndTest {
    @get:Rule
    val compose = createComposeRule()

    private fun pinned(height: Dp, top: Dp, bottom: Dp, item: Dp = 40.dp): LazyListLayoutInfo {
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            LazyColumn(Modifier.height(height), state = list, contentPadding = PaddingValues(top = top, bottom = bottom)) {
                items(20) { Box(Modifier.fillMaxWidth().height(item)) }
            }
        }
        val pin: Job = compose.runOnIdle { scope.launch { list.scrollToEnd() } }
        compose.waitUntil { pin.isCompleted }
        return compose.runOnIdle { list.layoutInfo }
    }

    @Test
    fun aDenseListSqueezedToNothingReachesItsEnd() {
        // ChatView's dense padding is 0 dp over 2 dp; a configuration change can measure the list at 0 px.
        val layout = pinned(height = 0.dp, top = 0.dp, bottom = 2.dp)
        assertTrue(layout.atEnd(0))
    }

    @Test
    fun aListShorterThanItsPaddingReachesItsEnd() {
        val layout = pinned(height = 10.dp, top = 8.dp, bottom = 12.dp)
        assertTrue(layout.atEnd(0))
    }

    @Test
    fun aLastItemTallerThanTheListShowsItsBottom() {
        val layout = pinned(height = 100.dp, top = 8.dp, bottom = 12.dp, item = 400.dp)
        assertTrue(layout.atEnd(0))
        assertEquals(19, layout.visibleItemsInfo.last().index)
    }
}
