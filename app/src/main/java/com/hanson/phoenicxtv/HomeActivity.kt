package com.hanson.phoenicxtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Home: the groups of the saved playlist. */
class HomeActivity : Activity() {

    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var titleView: TextView
    private var groups: List<Group> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        titleView = findViewById(R.id.title)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = GroupAdapter()
        findViewById<TextView>(R.id.about).text = getString(R.string.about, BuildConfig.VERSION_NAME)

        findViewById<Button>(R.id.change).setOnClickListener { openSetup() }
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }

        if (Store.source(this) == null) {
            openSetup()
        } else {
            show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SETUP) {
            if (Store.source(this) == null) finish() else show()
        }
    }

    private fun openSetup() {
        startActivityForResult(Intent(this, SetupActivity::class.java), REQ_SETUP)
    }

    private fun show() {
        titleView.text = Store.source(this)?.name?.ifEmpty { getString(R.string.app_name) } ?: getString(R.string.app_name)
        groups = Store.groups(this)
        list.adapter?.notifyDataSetChanged()
        if (groups.isEmpty()) {
            if (Store.cached(this) == null) {
                refresh()
            } else {
                status.text = getString(R.string.home_empty)
                status.visibility = View.VISIBLE
            }
        } else {
            status.visibility = View.GONE
            list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private fun refresh() {
        val source = Store.source(this) ?: return
        status.text = getString(R.string.home_refreshing)
        status.visibility = View.VISIBLE
        Store.refresh(this, source,
            onDone = { show() },
            onError = { msg ->
                status.text = getString(R.string.setup_error_load, msg)
                status.visibility = View.VISIBLE
            })
    }

    private inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val count: TextView = v.findViewById(R.id.count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
            val h = Holder(v)
            v.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                startActivity(
                    Intent(this@HomeActivity, ChannelsActivity::class.java)
                        .putExtra(ChannelsActivity.EXTRA_GROUP, groups[pos].name)
                )
            }
            return h
        }

        override fun getItemCount(): Int = groups.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val g = groups[position]
            holder.name.text = g.name
            holder.count.text = getString(R.string.home_channels_count, g.channels.size)
        }
    }

    companion object {
        private const val REQ_SETUP = 1
    }
}
