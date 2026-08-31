package io.legado.app.ui.book.breakdown

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookBreakdown
import io.legado.app.help.breakdown.BreakdownHelper
import io.legado.app.ui.book.material.MaterialTagViews
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.databinding.DialogBreakdownInfoBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 档案书本信息编辑弹层
 */
class BreakdownInfoDialog() : BaseDialogFragment(R.layout.dialog_breakdown_info, true) {

    companion object {
        fun newInstance(id: Long): BreakdownInfoDialog {
            return BreakdownInfoDialog().apply {
                arguments = Bundle().apply {
                    putLong("id", id)
                }
            }
        }
    }

    private val binding by viewBinding(DialogBreakdownInfoBinding::bind)
    private val benchmarks = arrayListOf<String>()
    private var breakdown: BookBreakdown? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val attr = attributes
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val id = arguments?.getLong("id") ?: -1L
        if (id <= 0) {
            dismiss()
            return
        }
        lifecycleScope.launch {
            val bd = withContext(IO) { appDb.bookBreakdownDao.get(id) }
            if (bd == null) {
                dismiss()
            } else {
                breakdown = bd
                benchmarks.clear()
                benchmarks.addAll(bd.benchmarks)
                binding.editCategory.setText(bd.category)
                binding.editAchievement.setText(bd.achievement)
                binding.editTitleFormula.setText(bd.titleFormula)
                binding.editOverall.setText(bd.overallNote)
                upBenchmarks()
                binding.tvAddBenchmark.setOnClickListener { addBenchmark() }
                binding.tvSave.setOnClickListener { save() }
            }
        }
    }

    private fun upBenchmarks() {
        binding.flexBenchmarks.removeAllViews()
        for (value in benchmarks) {
            val pill = MaterialTagViews.newPill(
                requireContext(),
                value,
                closable = true
            ) {
                benchmarks.remove(value)
                upBenchmarks()
            }
            pill.layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 8.dpToPx(), 6.dpToPx()) }
            binding.flexBenchmarks.addView(pill)
        }
    }

    private fun addBenchmark() {
        val text = binding.editBenchmark.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toastOnUi(R.string.breakdown_input_benchmark)
            return
        }
        binding.editBenchmark.setText("")
        benchmarks.add(text)
        upBenchmarks()
    }

    private fun save() {
        val bd = breakdown ?: return
        bd.category = binding.editCategory.text?.toString()?.trim().orEmpty()
        bd.achievement = binding.editAchievement.text?.toString()?.trim().orEmpty()
        bd.titleFormula = binding.editTitleFormula.text?.toString()?.trim().orEmpty()
        bd.benchmarks = benchmarks.toList()
        bd.overallNote = binding.editOverall.text?.toString()?.trim().orEmpty()
        bd.updateTime = System.currentTimeMillis()
        lifecycleScope.launch {
            withContext(IO) {
                appDb.bookBreakdownDao.update(bd)
            }
            BreakdownHelper.notifyChanged()
            toastOnUi(R.string.action_save)
            dismiss()
        }
    }
}