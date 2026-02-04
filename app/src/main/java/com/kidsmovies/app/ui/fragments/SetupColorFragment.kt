package com.kidsmovies.app.ui.fragments

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kidsmovies.app.databinding.FragmentSetupColorBinding
import com.kidsmovies.app.databinding.ItemColorSelectorBinding
import com.kidsmovies.app.ui.activities.SetupActivity
import com.kidsmovies.app.utils.ColorSchemes

class SetupColorFragment : Fragment() {

    private var _binding: FragmentSetupColorBinding? = null
    private val binding get() = _binding!!

    private var selectedScheme = "blue"
    private lateinit var adapter: ColorAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupColorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectedScheme = (activity as? SetupActivity)?.getSelectedColorScheme() ?: "blue"

        adapter = ColorAdapter(ColorSchemes.getAllSchemes(), selectedScheme) { scheme ->
            selectedScheme = scheme.name
            (activity as? SetupActivity)?.setSelectedColorScheme(scheme.name)
            adapter.setSelected(scheme.name)
        }

        binding.colorGrid.layoutManager = GridLayoutManager(context, 3)
        binding.colorGrid.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class ColorAdapter(
        private val schemes: List<ColorSchemes.ColorScheme>,
        private var selected: String,
        private val onColorSelected: (ColorSchemes.ColorScheme) -> Unit
    ) : RecyclerView.Adapter<ColorAdapter.ViewHolder>() {

        fun setSelected(schemeName: String) {
            val oldPosition = schemes.indexOfFirst { it.name == selected }
            val newPosition = schemes.indexOfFirst { it.name == schemeName }
            selected = schemeName
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
            if (newPosition >= 0) notifyItemChanged(newPosition)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemColorSelectorBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(schemes[position])
        }

        override fun getItemCount(): Int = schemes.size

        inner class ViewHolder(private val binding: ItemColorSelectorBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(scheme: ColorSchemes.ColorScheme) {
                val drawable = binding.colorView.background as? GradientDrawable
                    ?: GradientDrawable()
                drawable.setColor(Color.parseColor(scheme.primaryColor))
                drawable.shape = GradientDrawable.OVAL
                binding.colorView.background = drawable

                val isSelected = scheme.name == selected
                binding.selectionRing.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                binding.checkIcon.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

                binding.root.setOnClickListener {
                    onColorSelected(scheme)
                }
            }
        }
    }
}
