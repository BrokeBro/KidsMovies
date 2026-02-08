package com.kidsmovies.parent.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.kidsmovies.parent.ParentApp
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.ActivityChildDetailBinding
import com.kidsmovies.parent.ui.fragments.ContentListFragment
import kotlinx.coroutines.launch

class ChildDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FAMILY_ID = "extra_family_id"
        const val EXTRA_CHILD_UID = "extra_child_uid"
        const val EXTRA_CHILD_NAME = "extra_child_name"
    }

    private lateinit var binding: ActivityChildDetailBinding
    private lateinit var app: ParentApp

    private var familyId: String = ""
    private var childUid: String = ""
    private var childName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as ParentApp

        familyId = intent.getStringExtra(EXTRA_FAMILY_ID) ?: ""
        childUid = intent.getStringExtra(EXTRA_CHILD_UID) ?: ""
        childName = intent.getStringExtra(EXTRA_CHILD_NAME) ?: ""

        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        binding.toolbar.title = childName
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Setup ViewPager with tabs
        binding.viewPager.adapter = ChildDetailPagerAdapter()

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.videos_tab)
                1 -> getString(R.string.collections_tab)
                else -> ""
            }
        }.attach()
    }

    private fun setupListeners() {
        binding.syncFab.setOnClickListener {
            requestSync()
        }
    }

    private fun requestSync() {
        lifecycleScope.launch {
            try {
                app.familyManager.requestSync(familyId, childUid)
                Snackbar.make(
                    binding.root,
                    R.string.sync_requested,
                    Snackbar.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Snackbar.make(
                    binding.root,
                    R.string.error_generic,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private inner class ChildDetailPagerAdapter : FragmentStateAdapter(this) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ContentListFragment.newInstance(
                    ContentListFragment.TYPE_VIDEOS,
                    familyId,
                    childUid
                )
                1 -> ContentListFragment.newInstance(
                    ContentListFragment.TYPE_COLLECTIONS,
                    familyId,
                    childUid
                )
                else -> ContentListFragment.newInstance(
                    ContentListFragment.TYPE_VIDEOS,
                    familyId,
                    childUid
                )
            }
        }
    }
}
