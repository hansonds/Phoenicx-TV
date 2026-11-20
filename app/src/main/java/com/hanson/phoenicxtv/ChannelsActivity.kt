package com.hanson.phoenicxtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The channels of one group, with logos. Click plays; Back returns here. */
class ChannelsActivity : Activity() {

    private var channels: List<Channel> = emptyList()
    private var lastPlayed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channels)

        val groupName = intent.getStringExtra(EXTRA_GROUP) ?: ""
        findViewById<TextView>(R.id.title).text = groupName
        channels = Store.group(this, groupName)?.channels ?: emptyList()

        val list = findViewById<RecyclerView>(R.id.list)
        // As many ~150 dp square tiles per row as fit the screen (6 on a 1080p TV, 2–3 on a phone).
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density - 48
        val columns = (widthDp / 150f).toInt().coerceIn(2, 8)
        list.layoutManager = GridLayoutManager(this, columns)
        list.adapter = ChannelAdapter()
        list.setItemViewCacheSize(30)

        if (channels.isEmpty()) {
            val status = findViewById<TextView>(R.id.status)
            status.text = getString(R.string.channels_empty)
            status.visibility = View.VISIBLE
        } else {
            list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Coming back from the player: put focus on the channel that was playing.
        val pos = data?.getIntExtra(PlayerActivity.EXTRA_INDEX, lastPlayed) ?: lastPlayed
        val list = findViewById<RecyclerView>(R.id.list)
        list.scrollToPosition(pos)
        list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun play(index: Int) {
        lastPlayed = index
        startActivityForResult(
            Intent(this, PlayerActivity::class.java)
                .putExtra(EXTRA_GROUP, intent.getStringExtra(EXTRA_GROUP))
                .putExtra(PlayerActivity.EXTRA_INDEX, index),
            1
        )
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.logo)
            val name: TextView = v.findViewById(R.id.name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            val h = Holder(v)
            v.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) play(pos)
            }
            return h
        }

        override fun getItemCount(): Int = channels.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val c = channels[position]
            holder.name.text = c.name
            Logos.load(holder.logo, c.logo)
        }
    }

    companion object {
        const val EXTRA_GROUP = "group"
    }
}
