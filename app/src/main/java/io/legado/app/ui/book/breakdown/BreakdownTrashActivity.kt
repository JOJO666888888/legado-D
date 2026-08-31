package io.legado.app.ui.book.breakdown

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.databinding.ActivityBreakdownHomeBinding
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拆书回收站:档案级软删列表,恢复/彻底删除(级联章节段落)
 */
class BreakdownTrashActivity : VMBaseActivity<ActivityBreakdownHomeBinding, BreakdownHomeViewModel>() {

    override val binding by viewBinding(ActivityBreakdownHomeBinding::inflate)
    override val viewModel by viewModels<BreakdownHomeViewModel>()

    private val adapter by lazy {
        BreakdownHomeAdapter(this).apply { callBack = trashCallBack }
    }
    private val trashCallBack = object : BreakdownHomeAdapter.CallBack {
        override fun onBreakdownClick(breakdown: BookBreakdown) {
            showActions(breakdown)
        }

        override fun onBreakdownLongClick(breakdown: BookBreakdown) {
            showActions(breakdown)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setTitle(R.string.breakdown_trash)
        binding.editSearch.visibility = View.GONE
        binding.rvBreakdown.setEdgeEffectColor(primaryColor)
        binding.rvBreakdown.layoutManager = LinearLayoutManager(this)
        binding.rvBreakdown.adapter = adapter
        observeTrash()
    }

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_breakdown_trash, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_restore_all -> restoreAll()
            R.id.menu_clear_trash -> clearTrash()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun observeTrash() {
        lifecycleScope.launch {
            viewModel.flowTrash()
                .flowWithLifecycleAndDatabaseChange(lifecycle, Lifecycle.State.RESUMED, "breakdowns")
                .catch { AppLog.put("拆书回收站更新出错", it) }
                .flowOn(IO)
                .collect { list ->
                    adapter.setItems(list.map { toProgress(it) })
                    binding.tvEmptyMsg.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private fun toProgress(b: BookBreakdown) =
        io.legado.app.data.dao.BreakdownWithProgress(b, emptyList())

    private fun showActions(breakdown: BookBreakdown) {
        selector(
            breakdown.bookName,
            listOf(getString(R.string.breakdown_trash_restore_all), getString(R.string.delete))
        ) { _, _, index ->
            when (index) {
                0 -> lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookBreakdownDao.restore(breakdown.id, System.currentTimeMillis())
                    }
                    BreakdownHelper.notifyChanged()
                    toastOnUi(R.string.breakdown_restored)
                }

                1 -> alert(R.string.delete) {
                    setMessage(getString(R.string.breakdown_purge_confirm))
                    noButton()
                    yesButton {
                        lifecycleScope.launch {
                            withContext(IO) {
                                appDb.bookBreakdownDao.purgeSegments(breakdown.id)
                                appDb.bookBreakdownDao.purgeChapters(breakdown.id)
                                appDb.bookBreakdownDao.purge(breakdown.id)
                            }
                            BreakdownHelper.notifyChanged()
                            toastOnUi(R.string.breakdown_purged)
                        }
                    }
                }
            }
        }
    }

    private fun restoreAll() {
        lifecycleScope.launch {
            val list = withContext(IO) { appDb.bookBreakdownDao.trash }
            withContext(IO) {
                appDb.bookBreakdownDao.restoreAll(System.currentTimeMillis())
            }
            BreakdownHelper.notifyChanged()
            toastOnUi(R.string.breakdown_restored)
        }
    }

    private fun clearTrash() {
        alert(R.string.breakdown_trash_clear) {
            setMessage(R.string.breakdown_trash_clear_confirm)
            noButton()
            yesButton {
                lifecycleScope.launch {
                    val list = withContext(IO) { appDb.bookBreakdownDao.trash }
                    withContext(IO) {
                        list.forEach { b ->
                            appDb.bookBreakdownDao.purgeSegments(b.id)
                            appDb.bookBreakdownDao.purgeChapters(b.id)
                            appDb.bookBreakdownDao.purge(b.id)
                        }
                    }
                    BreakdownHelper.notifyChanged()
                    toastOnUi(R.string.breakdown_purged)
                }
            }
        }
    }
}