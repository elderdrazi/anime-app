package com.kumo.anime.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kumo.anime.Graph
import com.kumo.anime.data.AniList
import com.kumo.anime.data.FILTERS
import com.kumo.anime.data.Filter
import com.kumo.anime.data.Show
import com.kumo.anime.data.Sort
import com.kumo.anime.data.partLabel
import com.kumo.anime.ui.AnimeCard
import com.kumo.anime.ui.Pill
import com.kumo.anime.ui.ScreenTitle
import com.kumo.anime.ui.SearchField
import com.kumo.anime.ui.theme.TextDim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverState(
    val query: String = "",
    val sort: Sort = Sort.POPULAR,
    val filter: Filter? = null,
    val items: List<Show> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

class DiscoverViewModel : ViewModel() {
    private val _state = MutableStateFlow(DiscoverState())
    val state = _state.asStateFlow()
    private var page = 0
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init { reload() }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            reload()
        }
    }

    fun setSort(s: Sort) {
        if (s == _state.value.sort) return
        _state.update { it.copy(sort = s) }
        reload()
    }

    fun setFilter(f: Filter?) {
        if (f == _state.value.filter) return
        _state.update { it.copy(filter = f) }
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        page = 0
        _state.update { it.copy(items = emptyList(), endReached = false, error = null, loading = false) }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (loadJob?.isActive == true || s.endReached) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // Seasons fold into one card, so a page can add few new shows. Keep going until
                // there's a decent batch to show.
                var added = 0
                var rounds = 0
                var more = true
                while (more && added < 18 && rounds < 3) {
                    // One quiet retry covers the phone's network waking up.
                    val result = try {
                        AniList.browse(page + 1, s.sort, s.filter, s.query)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        delay(1500)
                        AniList.browse(page + 1, s.sort, s.filter, s.query)
                    }
                    page++
                    rounds++
                    more = result.hasNext
                    _state.update { st ->
                        val merged = (st.items + result.items).distinctBy { it.anime.id }
                        added += merged.size - st.items.size
                        st.copy(items = merged, endReached = !more)
                    }
                }
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong") }
            }
        }
    }
}

@Composable
fun DiscoverScreen(onOpen: (Int) -> Unit, vm: DiscoverViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val mine by Graph.social.myEntries.collectAsStateWithLifecycle()
    val statusById = remember(mine) { mine.associate { it.animeId to it.status } }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val toTop: () -> Unit = { scope.launch { grid.scrollToItem(0) } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Discover")
        SearchField(
            value = s.query,
            onValueChange = { vm.setQuery(it); toTop() },
            placeholder = "Search anime",
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            state = grid,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (s.query.isBlank()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(Sort.entries) { sort ->
                                Pill(sort.label, s.sort == sort, { vm.setSort(sort); toTop() }, outlined = false)
                            }
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { Pill("All", s.filter == null, { vm.setFilter(null); toTop() }) }
                        items(FILTERS) { f -> Pill(f.label, s.filter == f, { vm.setFilter(f); toTop() }) }
                    }
                }
            }
            // In search and in the Airing and Upcoming lists, say which season put the show there.
            val nameSeason = s.query.isNotBlank() || s.sort == Sort.AIRING || s.sort == Sort.UPCOMING
            itemsIndexed(s.items, key = { _, show -> show.anime.id }) { i, show ->
                if (i >= s.items.size - 15) LaunchedEffect(s.items.size) { vm.loadMore() }
                val a = show.anime
                val score = (if (s.sort == Sort.TOP) show.via?.score else null) ?: a.score
                AnimeCard(
                    title = a.title,
                    cover = a.cover,
                    tint = a.color,
                    status = statusById[a.id],
                    caption = if (nameSeason && show.via != null) partLabel(a, show.via)
                    else score?.let { "$it% · ${a.year ?: a.format ?: ""}".trimEnd(' ', '·') } ?: a.meta,
                    onClick = { onOpen(a.id) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    when {
                        s.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.error!!, color = TextDim, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { vm.loadMore() }) { Text("Try again") }
                        }
                        s.endReached && s.items.isEmpty() ->
                            Text("Nothing matches that", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
