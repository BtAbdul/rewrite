package org.cosmicide.rewrite.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import org.cosmicide.rewrite.R

/**
 * A [PreferenceFragmentCompat] subclass to display the preferences UI.
 */
class PreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        recyclerView.layoutAnimation = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.preference_layout_fall_down)
        return recyclerView
    }
}