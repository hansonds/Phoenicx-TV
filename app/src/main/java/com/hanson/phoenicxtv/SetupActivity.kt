package com.hanson.phoenicxtv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView

/** Add / change the playlist: an M3U link or Xtream Codes login. */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val type = findViewById<RadioGroup>(R.id.type)
        val name = findViewById<EditText>(R.id.name)
        val url = findViewById<EditText>(R.id.url)
        val xtream = findViewById<View>(R.id.xtream_fields)
        val server = findViewById<EditText>(R.id.server)
        val user = findViewById<EditText>(R.id.user)
        val pass = findViewById<EditText>(R.id.pass)
        val save = findViewById<Button>(R.id.save)
        val status = findViewById<TextView>(R.id.status)

        fun applyType(isXtream: Boolean) {
            url.visibility = if (isXtream) View.GONE else View.VISIBLE
            xtream.visibility = if (isXtream) View.VISIBLE else View.GONE
        }

        // Pre-fill the current playlist so it can be edited.
        Store.source(this)?.let { s ->
            name.setText(s.name)
            if (s.type == "xtream") {
                type.check(R.id.type_xtream)
                server.setText(s.url)
                user.setText(s.user)
                pass.setText(s.pass)
            } else {
                url.setText(s.url)
            }
        }
        applyType(type.checkedRadioButtonId == R.id.type_xtream)
        type.setOnCheckedChangeListener { _, id -> applyType(id == R.id.type_xtream) }

        save.setOnClickListener {
            val isXtream = type.checkedRadioButtonId == R.id.type_xtream
            val source: Source
            if (isXtream) {
                val host = server.text.toString().trim().trimEnd('/')
                val u = user.text.toString().trim()
                val p = pass.text.toString().trim()
                if (host.isEmpty() || u.isEmpty() || p.isEmpty()) {
                    status.text = getString(R.string.setup_error_empty); return@setOnClickListener
                }
                val full = if (host.startsWith("http")) host else "http://$host"
                source = Source("xtream", name.text.toString().trim().ifEmpty { "Xtream" }, full, u, p)
            } else {
                val link = url.text.toString().trim()
                if (link.isEmpty()) {
                    status.text = getString(R.string.setup_error_empty); return@setOnClickListener
                }
                // An Xtream get.php link pasted as M3U works as a plain M3U link too.
                val full = if (link.startsWith("http")) link else "http://$link"
                source = Source("m3u", name.text.toString().trim().ifEmpty { "Playlist" }, full, "", "")
            }

            save.isEnabled = false
            status.text = getString(R.string.setup_loading)
            Store.refresh(this, source,
                onDone = {
                    Store.saveSource(this, source)
                    setResult(RESULT_OK)
                    finish()
                },
                onError = { msg ->
                    save.isEnabled = true
                    status.text = getString(R.string.setup_error_load, msg)
                })
        }
    }
}
