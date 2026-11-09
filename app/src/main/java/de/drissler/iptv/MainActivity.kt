package de.drissler.iptv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class MainActivity : AppCompatActivity() {

    companion object {
        const val FAV = "★ Favoriten"
        const val ALL = "Alle Sender"
    }

    private lateinit var groupsView: RecyclerView
    private lateinit var channelsView: RecyclerView
    private lateinit var status: TextView

    private val groupAdapter = GroupAdapter()
    private val channelAdapter = ChannelAdapter()
    private var currentGroup = ALL
    private var loadedUrl: String? = null
    private var settingsOpenedOnce = false
    private var firstShow = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        groupsView = findViewById(R.id.groups)
        channelsView = findViewById(R.id.channels)
        status = findViewById(R.id.status)

        groupsView.layoutManager = LinearLayoutManager(this)
        groupsView.adapter = groupAdapter
        channelsView.layoutManager = LinearLayoutManager(this)
        channelsView.adapter = channelAdapter
        channelsView.setItemViewCacheSize(30)
        groupsView.itemAnimator = null
        channelsView.itemAnimator = null

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener {
            loadedUrl = null
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val url = Prefs.url(this)
        if (url.isBlank()) {
            status.text = "Bitte M3U-Link unter Einstellungen eintragen"
            if (!settingsOpenedOnce) {
                settingsOpenedOnce = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        if (url != loadedUrl) {
            loadedUrl = url
            load(url)
        } else {
            // Favoriten können sich im Player nicht ändern, aber nach Rückkehr neu zeichnen schadet nicht
            channelAdapter.notifyDataSetChanged()
        }
    }

    private fun load(url: String) {
        status.text = "Lade Senderliste …"
        Thread {
            val cached = try { PlaylistRepo.loadCached(this, url) } catch (e: Exception) { emptyList() }
            if (cached.isNotEmpty()) runOnUiThread {
                setChannels(cached)
                status.text = "${cached.size} Sender · aktualisiere …"
            }
            try {
                val fresh = PlaylistRepo.download(this, url)
                runOnUiThread {
                    setChannels(fresh)
                    status.text = "${fresh.size} Sender"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Fehler: ${e.message ?: e.javaClass.simpleName}" +
                        if (cached.isNotEmpty()) " · zeige gespeicherte Liste" else ""
                }
            }
        }.start()
    }

    private fun setChannels(list: List<Channel>) {
        ChannelStore.all = list
        val groups = ArrayList<String>()
        groups.add(FAV)
        groups.add(ALL)
        list.mapTo(LinkedHashSet()) { it.group }.forEach { groups.add(it) }
        groupAdapter.items = groups

        val last = Prefs.lastGroup(this)
        val start = when {
            !firstShow && groups.contains(currentGroup) -> currentGroup
            groups.contains(last) -> last
            Prefs.favorites(this).isNotEmpty() -> FAV
            else -> ALL
        }
        showGroup(start)
        groupAdapter.notifyDataSetChanged()

        if (firstShow) {
            firstShow = false
            channelsView.post { channelsView.getChildAt(0)?.requestFocus() ?: groupsView.getChildAt(0)?.requestFocus() }
        }
    }

    private fun showGroup(g: String) {
        currentGroup = g
        Prefs.setLastGroup(this, g)
        val favs = Prefs.favorites(this)
        channelAdapter.items = when (g) {
            FAV -> ChannelStore.all.filter { favs.contains(it.url) }
            ALL -> ChannelStore.all
            else -> ChannelStore.all.filter { it.group == g }
        }
        channelAdapter.favs = favs
        channelAdapter.notifyDataSetChanged()
        channelsView.scrollToPosition(0)
        // Nur Markierung aktualisieren, kein Neuzeichnen: sonst springt der Fokus
        for (i in 0 until groupsView.childCount) {
            val tv = groupsView.getChildAt(i) as? TextView ?: continue
            tv.isSelected = tv.text.toString() == g
        }
    }

    private fun toggleFavorite(position: Int) {
        val ch = channelAdapter.items.getOrNull(position) ?: return
        val nowFav = Prefs.toggleFavorite(this, ch.url)
        channelAdapter.favs = Prefs.favorites(this)
        if (currentGroup == FAV && !nowFav) showGroup(FAV) else channelAdapter.notifyItemChanged(position)
        status.text = if (nowFav) "★ ${ch.name} zu Favoriten hinzugefügt" else "${ch.name} aus Favoriten entfernt"
    }

    private fun play(position: Int) {
        if (channelAdapter.items.isEmpty()) return
        ChannelStore.playing = channelAdapter.items
        ChannelStore.index = position
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = currentFocus
            if (focused != null && focused.parent == channelsView) {
                toggleFavorite(channelsView.getChildAdapterPosition(focused))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.VH>() {
        var items: List<String> = emptyList()

        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false) as TextView
            val vh = VH(tv)
            tv.setOnFocusChangeListener { _, hasFocus ->
                val g = items.getOrNull(vh.bindingAdapterPosition)
                if (hasFocus && g != null && g != currentGroup) showGroup(g)
            }
            tv.setOnClickListener {
                val g = items.getOrNull(vh.bindingAdapterPosition) ?: return@setOnClickListener
                if (g != currentGroup) showGroup(g)
                channelsView.post { channelsView.getChildAt(0)?.requestFocus() }
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val g = items[position]
            holder.text.text = g
            holder.text.isSelected = g == currentGroup
        }

        override fun getItemCount() = items.size
    }

    inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.VH>() {
        var items: List<Channel> = emptyList()
        var favs: Set<String> = emptySet()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val number: TextView = v.findViewById(R.id.number)
            val logo: ImageView = v.findViewById(R.id.logo)
            val name: TextView = v.findViewById(R.id.name)
            val fav: TextView = v.findViewById(R.id.fav)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            val vh = VH(v)
            v.setOnClickListener {
                val p = vh.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) play(p)
            }
            v.setOnLongClickListener {
                val p = vh.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) toggleFavorite(p)
                true
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            holder.number.text = (position + 1).toString()
            holder.name.text = ch.name
            holder.fav.visibility = if (favs.contains(ch.url)) View.VISIBLE else View.INVISIBLE
            if (ch.logo != null) {
                holder.logo.load(ch.logo) { crossfade(false) }
            } else {
                holder.logo.dispose()
                holder.logo.setImageDrawable(null)
            }
        }

        override fun getItemCount() = items.size
    }
}
