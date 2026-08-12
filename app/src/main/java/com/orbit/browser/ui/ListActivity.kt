package com.orbit.browser.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.orbit.browser.R
import com.orbit.browser.data.BrowserDb
import com.orbit.browser.databinding.ActivityListBinding

/** Geçmiş ve yer imleri; seçilen adres çağıran ekrana sonuç olarak döner. */
class ListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private lateinit var db: BrowserDb
    private lateinit var adapter: EntryAdapter
    private var mode: String = MODE_HISTORY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = BrowserDb.get(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_HISTORY

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(if (mode == MODE_BOOKMARKS) R.string.bookmarks else R.string.history)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = EntryAdapter { entry ->
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URL, entry.url))
            finish()
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        reload()
    }

    private fun reload() {
        val items = if (mode == MODE_BOOKMARKS) db.bookmarks() else db.history()
        adapter.submit(items)
        binding.emptyView.setText(
            if (mode == MODE_BOOKMARKS) R.string.empty_bookmarks else R.string.empty_history
        )
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (mode == MODE_HISTORY) menu.add(0, ID_CLEAR, 0, R.string.clear)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == ID_CLEAR) {
            AlertDialog.Builder(this)
                .setMessage(R.string.pref_clear_now)
                .setPositiveButton(R.string.clear) { _, _ ->
                    db.clearHistory()
                    binding.list.postDelayed({ reload() }, 150)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_URL = "url"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
        private const val ID_CLEAR = 1
    }
}
