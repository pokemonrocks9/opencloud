/**
 * openCloud Android client application
 *
 * Google Docs / Sheets / Slides style launcher: starred and recent office files from
 * across all spaces (server-side search), opening straight into the in-app editor.
 * Reached through its own launcher icons (activity aliases) so it feels like separate apps.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 */

package eu.opencloud.android.presentation.docs

import android.accounts.Account
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import eu.opencloud.android.R
import eu.opencloud.android.domain.appregistry.usecases.CreateFileWithAppProviderUseCase
import eu.opencloud.android.domain.appregistry.usecases.GetUrlToOpenInWebUseCase
import eu.opencloud.android.domain.spaces.usecases.GetPersonalSpaceForAccountUseCase
import eu.opencloud.android.lib.common.OpenCloudAccount
import eu.opencloud.android.lib.common.SingleSessionManager
import eu.opencloud.android.lib.resources.files.search.SearchFilesRemoteOperation
import eu.opencloud.android.lib.resources.files.search.SearchFilesRemoteOperation.SearchResult
import eu.opencloud.android.presentation.authentication.AccountUtils
import eu.opencloud.android.presentation.weboffice.WebOfficeActivity
import eu.opencloud.android.ui.activity.FileDisplayActivity
import eu.opencloud.android.utils.MimetypeIconUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import timber.log.Timber

class DocsLauncherActivity : AppCompatActivity() {

    enum class Kind(val label: String, val singular: String, val mediaType: String, val newExt: String, val color: Int) {
        DOCS("Docs", "document", "document", "docx", Color.parseColor("#4285F4")),
        SHEETS("Sheets", "spreadsheet", "spreadsheet", "xlsx", Color.parseColor("#0F9D58")),
        SLIDES("Slides", "presentation", "presentation", "pptx", Color.parseColor("#F4B400")),
    }

    private val getUrlToOpenInWebUseCase: GetUrlToOpenInWebUseCase by inject()
    private val createFileWithAppProviderUseCase: CreateFileWithAppProviderUseCase by inject()
    private val getPersonalSpaceForAccountUseCase: GetPersonalSpaceForAccountUseCase by inject()

    private lateinit var account: Account
    private lateinit var kind: Kind
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var tabs: TabLayout
    private lateinit var fab: ExtendedFloatingActionButton
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val current = AccountUtils.getCurrentOpenCloudAccount(this)
        if (current == null) {
            startActivity(Intent(this, FileDisplayActivity::class.java))
            finish()
            return
        }
        account = current

        kind = savedInstanceState?.getString(STATE_KIND)?.let { Kind.valueOf(it) } ?: kindFromLauncher()

        buildUi()
        selectKind(kind, reload = true)
    }

    /** Each launcher icon is an alias of this activity; its name tells us which tab to show. */
    private fun kindFromLauncher(): Kind {
        val name = intent.component?.className.orEmpty()
        return when {
            name.endsWith("SheetsLauncher") -> Kind.SHEETS
            name.endsWith("SlidesLauncher") -> Kind.SLIDES
            else -> Kind.DOCS
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DocsLauncherActivity)
            adapter = this@DocsLauncherActivity.adapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(88))
        }
        swipe = SwipeRefreshLayout(this).apply {
            addView(list)
            setOnRefreshListener { load() }
        }
        tabs = TabLayout(this).apply {
            Kind.values().forEach { addTab(newTab().setText(it.label).setTag(it)) }
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = selectKind(tab.tag as Kind, reload = true)
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = load()
            })
        }
        column.addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column)

        fab = ExtendedFloatingActionButton(this).apply {
            setIconResource(R.drawable.ic_add)
            setOnClickListener { promptNewFile() }
        }
        root.addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(dp(16), dp(16), dp(16), dp(72))
        })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun selectKind(newKind: Kind, reload: Boolean) {
        val tab = tabs.getTabAt(newKind.ordinal)
        if (tab != null && !tab.isSelected) {
            tab.select() // the tab listener calls back into selectKind
            return
        }
        kind = newKind
        supportActionBar?.title = newKind.label
        fab.text = "New ${newKind.singular}"
        fab.backgroundTintList = android.content.res.ColorStateList.valueOf(newKind.color)
        fab.setTextColor(Color.WHITE)
        fab.iconTint = android.content.res.ColorStateList.valueOf(Color.WHITE)
        tabs.setSelectedTabIndicatorColor(newKind.color)
        if (reload) load()
    }

    private fun load() {
        val requestedKind = kind
        swipe.isRefreshing = true
        lifecycleScope.launch {
            val (starred, recent) = withContext(Dispatchers.IO) {
                try {
                    val client = SingleSessionManager.getDefaultSingleton()
                        .getClientFor(OpenCloudAccount(account, this@DocsLauncherActivity), this@DocsLauncherActivity)
                    val type = "mediatype:(\"${requestedKind.mediaType}\")"
                    val starredJob = async { SearchFilesRemoteOperation("$type AND is:favorite").execute(client) }
                    val recentJob = async { SearchFilesRemoteOperation(type).execute(client) }
                    val s = starredJob.await()
                    val r = recentJob.await()
                    if (!r.isSuccess) Timber.w("Docs launcher search failed: %s", r.code)
                    Pair(s.data.orEmpty(), r.data.orEmpty())
                } catch (e: Exception) {
                    Timber.e(e, "Docs launcher could not reach the server")
                    Pair(emptyList<SearchResult>(), emptyList<SearchResult>())
                }
            }
            if (requestedKind != kind) return@launch // tab changed meanwhile
            swipe.isRefreshing = false

            val rows = mutableListOf<Row>()
            rows += Row.Header("Starred")
            if (starred.isEmpty()) {
                rows += Row.Hint("Star a ${kind.singular} in OpenCloud to pin it here.")
            } else {
                starred.sortedByDescending { it.lastModified }.forEach { rows += Row.File(it) }
            }
            rows += Row.Header("Recent")
            if (recent.isEmpty()) {
                rows += Row.Hint("No ${kind.label.lowercase()} found.")
            } else {
                recent.sortedByDescending { it.lastModified }.forEach { rows += Row.File(it) }
            }
            adapter.submit(rows)
        }
    }

    private fun open(fileId: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // empty app name = the server's default editor for this file type
                getUrlToOpenInWebUseCase(GetUrlToOpenInWebUseCase.Params(accountName = account.name, fileId = fileId, appName = ""))
            }
            val data = result.getDataOrNull()
            if (data != null) {
                WebOfficeActivity.open(this@DocsLauncherActivity, data)
            } else {
                Toast.makeText(this@DocsLauncherActivity, R.string.open_in_web_error_generic, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptNewFile() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText("Untitled ${kind.singular}")
            selectAll()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("New ${kind.singular}")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> createFile(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createFile(baseName: String) {
        val fileName = "${baseName.trim().ifEmpty { "Untitled" }}.${kind.newExt}"
        lifecycleScope.launch {
            val fileId = withContext(Dispatchers.IO) {
                val space = getPersonalSpaceForAccountUseCase(GetPersonalSpaceForAccountUseCase.Params(account.name))
                    ?: return@withContext null
                createFileWithAppProviderUseCase(
                    CreateFileWithAppProviderUseCase.Params(
                        accountName = account.name,
                        parentContainerId = space.root.id,
                        filename = fileName,
                    )
                ).getDataOrNull()
            }
            if (fileId != null) {
                open(fileId)
                load()
            } else {
                Toast.makeText(this@DocsLauncherActivity, "Couldn't create $fileName", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_KIND, kind.name)
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Hint(val text: String) : Row()
        data class File(val item: SearchResult) : Row()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Hint -> TYPE_HINT
            is Row.File -> TYPE_FILE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                TYPE_HEADER -> TextView(parent.context).apply {
                    setPadding(dp(16), dp(20), dp(16), dp(8))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                }
                TYPE_HINT -> TextView(parent.context).apply {
                    setPadding(dp(16), dp(4), dp(16), dp(12))
                    alpha = 0.7f
                }
                else -> LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    val outValue = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    addView(ImageView(context).apply { id = ID_ICON }, LinearLayout.LayoutParams(dp(36), dp(36)))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), 0, 0, 0)
                        addView(TextView(context).apply {
                            id = ID_NAME
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        addView(TextView(context).apply {
                            id = ID_SUB
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            alpha = 0.7f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).apply {
                    text = row.title
                    setTextColor(kind.color)
                }
                is Row.Hint -> (holder.itemView as TextView).text = row.text
                is Row.File -> {
                    val item = row.item
                    val v = holder.itemView
                    v.findViewById<ImageView>(ID_ICON).setImageResource(
                        MimetypeIconUtil.getFileTypeIconId(item.mimeType, item.name)
                    )
                    v.findViewById<TextView>(ID_NAME).text = item.name
                    val folder = item.path.substringBeforeLast('/').ifEmpty { "My files" }
                    val edited = if (item.lastModified > 0) {
                        DateUtils.getRelativeTimeSpanString(item.lastModified).toString()
                    } else ""
                    v.findViewById<TextView>(ID_SUB).text = listOf(edited, folder).filter { it.isNotEmpty() }.joinToString(" · ")
                    v.setOnClickListener { open(item.fileId) }
                }
            }
        }
    }

    companion object {
        private const val STATE_KIND = "docs_launcher_kind"
        private const val TYPE_HEADER = 0
        private const val TYPE_HINT = 1
        private const val TYPE_FILE = 2
        private val ID_ICON = View.generateViewId()
        private val ID_NAME = View.generateViewId()
        private val ID_SUB = View.generateViewId()
    }
}
