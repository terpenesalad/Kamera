package app.kamera.ui

import android.content.Context
import android.widget.LinearLayout
import app.kamera.film.FilterCatalog
import app.kamera.film.FilterSlot
import app.kamera.film.LensFilters

/** The filter holder: one colour filter, one grad and one effect, stacked like Cokin slots. */
class FilterPanel(
    context: Context,
    initial: LensFilters,
    private val onChange: (LensFilters) -> Unit,
) : LinearLayout(context) {
    private var filters = initial

    init {
        orientation = VERTICAL
        for (slot in FilterSlot.entries) addSlot(slot)
    }

    private fun current(slot: FilterSlot): String? = when (slot) {
        FilterSlot.COLOUR -> filters.colorId
        FilterSlot.GRAD -> filters.gradId
        FilterSlot.EFFECT -> filters.effectId
    }

    private fun update(next: LensFilters) {
        filters = next
        onChange(next)
    }

    private fun addSlot(slot: FilterSlot) {
        addView(
            context.text(slot.label.uppercase(), 10f, Palette.muted, Type.label).apply {
                setPadding(context.dp(10), context.dp(14), 0, context.dp(6))
            },
        )
        val options = FilterCatalog.forSlot(slot)
        val details = context.column()
        val chips = ChipGroup(context).apply {
            setPadding(context.dp(10), 0, context.dp(10), 0)
            clipToPadding = false
        }
        chips.setOptions(listOf("None") + options.map { it.name }, options.indexOfFirst { it.id == current(slot) } + 1) { i ->
            val picked = options.getOrNull(i - 1)
            update(
                when (slot) {
                    FilterSlot.COLOUR -> filters.copy(colorId = picked?.id, colorStrength = 1f)
                    FilterSlot.GRAD -> filters.copy(gradId = picked?.id, gradStrength = 1f, gradSoftness = picked?.edge ?: filters.gradSoftness)
                    FilterSlot.EFFECT -> filters.copy(effectId = picked?.id, effectStrength = 1f)
                },
            )
            buildDetails(slot, details)
        }
        addView(chips, LayoutParams(MATCH, WRAP))
        addView(details, LayoutParams(MATCH, WRAP))
        buildDetails(slot, details)
    }

    private fun buildDetails(slot: FilterSlot, into: LinearLayout) {
        into.removeAllViews()
        val filter = FilterCatalog.find(current(slot)) ?: return
        into.addView(
            context.text(filter.reference, 12f, Palette.faint).apply {
                setPadding(context.dp(10), context.dp(8), context.dp(10), 0)
            },
        )
        fun slider(label: String, value: Float, min: Float, max: Float, neutral: Float, format: (Float) -> String, change: (Float) -> LensFilters) {
            val view = ValueSlider(context, label, min, max, neutral, format) { update(change(it)) }
            view.setValue(value)
            into.addView(view, LayoutParams(MATCH, WRAP))
        }
        val times: (Float) -> String = { "%.2f×".format(it) }
        when (slot) {
            FilterSlot.COLOUR -> slider("Strength", filters.colorStrength, 0f, 1.5f, 1f, times) { filters.copy(colorStrength = it) }
            FilterSlot.GRAD -> {
                slider("Strength", filters.gradStrength, 0f, 1.5f, 1f, times) { filters.copy(gradStrength = it) }
                slider("Position", filters.gradPosition, -1f, 1f, 0f, { ValueSlider.formatSigned(it * 100f) }) { filters.copy(gradPosition = it) }
                slider("Softness", filters.gradSoftness, 0.02f, 1f, filter.edge, { "${(it * 100).toInt()}" }) { filters.copy(gradSoftness = it) }
                slider("Tilt", filters.gradTilt, -45f, 45f, 0f, { "%+.0f°".format(it) }) { filters.copy(gradTilt = it) }
            }
            FilterSlot.EFFECT -> slider("Strength", filters.effectStrength, 0f, 1.5f, 1f, times) { filters.copy(effectStrength = it) }
        }
    }
}
