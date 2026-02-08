package com.kidsmovies.parent.ui.fragments

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.kidsmovies.parent.ParentApp
import com.kidsmovies.parent.R
import com.kidsmovies.parent.databinding.FragmentChildSettingsBinding
import com.kidsmovies.shared.models.AppLockCommand
import com.kidsmovies.shared.models.ScheduleSettings
import com.kidsmovies.shared.models.TimeLimitSettings
import com.kidsmovies.shared.models.ViewingMetrics
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

class ChildSettingsFragment : Fragment() {

    companion object {
        private const val ARG_FAMILY_ID = "family_id"
        private const val ARG_CHILD_UID = "child_uid"

        fun newInstance(familyId: String, childUid: String): ChildSettingsFragment {
            return ChildSettingsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FAMILY_ID, familyId)
                    putString(ARG_CHILD_UID, childUid)
                }
            }
        }
    }

    private var _binding: FragmentChildSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var app: ParentApp
    private var familyId: String = ""
    private var childUid: String = ""

    private var currentAppLock: AppLockCommand? = null
    private var currentSchedule: ScheduleSettings? = null
    private var currentTimeLimits: TimeLimitSettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            familyId = it.getString(ARG_FAMILY_ID, "")
            childUid = it.getString(ARG_CHILD_UID, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChildSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = requireActivity().application as ParentApp

        setupUI()
        setupListeners()
        observeData()
    }

    private fun setupUI() {
        // Setup time limit spinner
        val timeLimitOptions = arrayOf("30 minutes", "1 hour", "1.5 hours", "2 hours", "3 hours", "4 hours", "Unlimited")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, timeLimitOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.timeLimitSpinner.adapter = adapter
    }

    private fun setupListeners() {
        // App Lock button
        binding.lockAppButton.setOnClickListener {
            showAppLockDialog()
        }

        binding.unlockAppButton.setOnClickListener {
            unlockApp()
        }

        // Schedule toggle
        binding.scheduleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != (currentSchedule?.enabled == true)) {
                updateScheduleEnabled(isChecked)
            }
        }

        // Schedule time pickers
        binding.scheduleStartTime.setOnClickListener {
            showTimePicker(true)
        }

        binding.scheduleEndTime.setOnClickListener {
            showTimePicker(false)
        }

        // Day checkboxes
        binding.daySunday.setOnCheckedChangeListener { _, _ -> updateScheduleDays() }
        binding.dayMonday.setOnCheckedChangeListener { _, _ -> updateScheduleDays() }
        binding.dayTuesday.setOnCheckedChangeListener { _, _ -> updateScheduleDays() }
        binding.dayWednesday.setOnCheckedChangeListener { _, _ -> updateScheduleDays() }
        binding.dayThursday.setOnCheckedChangeListener { _, _ -> updateScheduleDays() }
        binding.dayFriday.setOnCheckedChangeListener { _, _ -> updateScheduleDays() }
        binding.daySaturday.setOnCheckedChangeListener { _, _ -> updateScheduleDays() }

        // Time limits toggle
        binding.timeLimitSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != (currentTimeLimits?.enabled == true)) {
                updateTimeLimitEnabled(isChecked)
            }
        }

        // Time limit spinner
        binding.timeLimitSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val minutes = when (position) {
                    0 -> 30
                    1 -> 60
                    2 -> 90
                    3 -> 120
                    4 -> 180
                    5 -> 240
                    else -> 0 // Unlimited
                }
                if (currentTimeLimits?.dailyLimitMinutes != minutes) {
                    updateDailyTimeLimit(minutes)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun observeData() {
        // Observe app lock status
        viewLifecycleOwner.lifecycleScope.launch {
            app.familyManager.getAppLockFlow(familyId, childUid).collectLatest { appLock ->
                currentAppLock = appLock
                updateAppLockUI(appLock)
            }
        }

        // Observe schedule settings
        viewLifecycleOwner.lifecycleScope.launch {
            app.familyManager.getScheduleSettingsFlow(familyId, childUid).collectLatest { schedule ->
                currentSchedule = schedule
                updateScheduleUI(schedule)
            }
        }

        // Observe time limit settings
        viewLifecycleOwner.lifecycleScope.launch {
            app.familyManager.getTimeLimitSettingsFlow(familyId, childUid).collectLatest { timeLimits ->
                currentTimeLimits = timeLimits
                updateTimeLimitUI(timeLimits)
            }
        }

        // Observe viewing metrics
        viewLifecycleOwner.lifecycleScope.launch {
            app.familyManager.getViewingMetricsFlow(familyId, childUid).collectLatest { metrics ->
                updateMetricsUI(metrics)
            }
        }
    }

    private fun updateAppLockUI(appLock: AppLockCommand?) {
        if (appLock?.isLocked == true) {
            binding.appLockStatus.text = "App is LOCKED"
            binding.appLockStatus.setTextColor(resources.getColor(R.color.locked_badge, null))
            binding.lockAppButton.visibility = View.GONE
            binding.unlockAppButton.visibility = View.VISIBLE

            appLock.unlockAt?.let { unlockTime ->
                val calendar = Calendar.getInstance().apply { timeInMillis = unlockTime }
                binding.appLockScheduledUnlock.visibility = View.VISIBLE
                binding.appLockScheduledUnlock.text = "Unlocks at: ${formatTime(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))}"
            } ?: run {
                binding.appLockScheduledUnlock.visibility = View.GONE
            }
        } else {
            binding.appLockStatus.text = "App is unlocked"
            binding.appLockStatus.setTextColor(resources.getColor(R.color.unlocked_badge, null))
            binding.lockAppButton.visibility = View.VISIBLE
            binding.unlockAppButton.visibility = View.GONE
            binding.appLockScheduledUnlock.visibility = View.GONE
        }
    }

    private fun updateScheduleUI(schedule: ScheduleSettings?) {
        val settings = schedule ?: ScheduleSettings()

        binding.scheduleSwitch.isChecked = settings.enabled
        binding.scheduleStartTime.text = formatTime(settings.allowedStartHour, settings.allowedStartMinute)
        binding.scheduleEndTime.text = formatTime(settings.allowedEndHour, settings.allowedEndMinute)

        // Update day checkboxes
        binding.daySunday.isChecked = settings.allowedDays.contains(0)
        binding.dayMonday.isChecked = settings.allowedDays.contains(1)
        binding.dayTuesday.isChecked = settings.allowedDays.contains(2)
        binding.dayWednesday.isChecked = settings.allowedDays.contains(3)
        binding.dayThursday.isChecked = settings.allowedDays.contains(4)
        binding.dayFriday.isChecked = settings.allowedDays.contains(5)
        binding.daySaturday.isChecked = settings.allowedDays.contains(6)

        // Enable/disable schedule controls
        val enabled = settings.enabled
        binding.scheduleStartTime.isEnabled = enabled
        binding.scheduleEndTime.isEnabled = enabled
        binding.daysContainer.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateTimeLimitUI(timeLimits: TimeLimitSettings?) {
        val settings = timeLimits ?: TimeLimitSettings()

        binding.timeLimitSwitch.isChecked = settings.enabled

        val position = when (settings.dailyLimitMinutes) {
            30 -> 0
            60 -> 1
            90 -> 2
            120 -> 3
            180 -> 4
            240 -> 5
            else -> 6 // Unlimited
        }
        binding.timeLimitSpinner.setSelection(position)

        binding.timeLimitSpinner.isEnabled = settings.enabled
    }

    private fun updateMetricsUI(metrics: ViewingMetrics?) {
        if (metrics == null) {
            binding.metricsCard.visibility = View.GONE
            return
        }

        binding.metricsCard.visibility = View.VISIBLE
        binding.todayWatchTime.text = formatDuration(metrics.todayWatchTimeMinutes)
        binding.weekWatchTime.text = formatDuration(metrics.weekWatchTimeMinutes)
        binding.totalWatchTime.text = formatDuration(metrics.totalWatchTimeMinutes)
        binding.videosWatchedToday.text = "${metrics.videosWatchedToday} videos"
        binding.lastVideoWatched.text = metrics.lastVideoWatched ?: "None"
    }

    private fun showAppLockDialog() {
        val warningOptions = arrayOf("Immediate", "1 minute", "5 minutes", "10 minutes", "15 minutes")
        val warningMinutes = intArrayOf(0, 1, 5, 10, 15)
        var selectedWarning = 2 // Default: 5 minutes
        var allowFinishVideo = true
        var scheduleUnlock = false
        var unlockHour = 17 // 5 PM default
        var unlockMinute = 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Lock App")
            .setMessage("Lock the app on the child's device?")
            .setSingleChoiceItems(warningOptions, selectedWarning) { _, which ->
                selectedWarning = which
            }
            .setMultiChoiceItems(
                arrayOf("Allow finish current video", "Schedule unlock time"),
                booleanArrayOf(allowFinishVideo, scheduleUnlock)
            ) { _, which, isChecked ->
                when (which) {
                    0 -> allowFinishVideo = isChecked
                    1 -> {
                        scheduleUnlock = isChecked
                        if (isChecked) {
                            // Show time picker for unlock time
                            TimePickerDialog(
                                requireContext(),
                                { _, hour, minute ->
                                    unlockHour = hour
                                    unlockMinute = minute
                                },
                                unlockHour,
                                unlockMinute,
                                false
                            ).show()
                        }
                    }
                }
            }
            .setPositiveButton("Lock") { _, _ ->
                val unlockAt = if (scheduleUnlock) {
                    Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, unlockHour)
                        set(Calendar.MINUTE, unlockMinute)
                        if (timeInMillis < System.currentTimeMillis()) {
                            add(Calendar.DAY_OF_YEAR, 1) // Tomorrow if time has passed
                        }
                    }.timeInMillis
                } else null

                lockApp(warningMinutes[selectedWarning], allowFinishVideo, unlockAt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun lockApp(warningMinutes: Int, allowFinishVideo: Boolean, unlockAt: Long?) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                app.familyManager.setAppLock(
                    familyId = familyId,
                    childUid = childUid,
                    isLocked = true,
                    unlockAt = unlockAt,
                    message = "App is locked by parent",
                    warningMinutes = warningMinutes,
                    allowFinishCurrentVideo = allowFinishVideo
                )
                showMessage("App locked")
            } catch (e: Exception) {
                showError("Failed to lock app")
            }
        }
    }

    private fun unlockApp() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                app.familyManager.setAppLock(
                    familyId = familyId,
                    childUid = childUid,
                    isLocked = false
                )
                showMessage("App unlocked")
            } catch (e: Exception) {
                showError("Failed to unlock app")
            }
        }
    }

    private fun showTimePicker(isStartTime: Boolean) {
        val schedule = currentSchedule ?: ScheduleSettings()
        val hour = if (isStartTime) schedule.allowedStartHour else schedule.allowedEndHour
        val minute = if (isStartTime) schedule.allowedStartMinute else schedule.allowedEndMinute

        TimePickerDialog(
            requireContext(),
            { _, selectedHour, selectedMinute ->
                val newSchedule = if (isStartTime) {
                    schedule.copy(allowedStartHour = selectedHour, allowedStartMinute = selectedMinute)
                } else {
                    schedule.copy(allowedEndHour = selectedHour, allowedEndMinute = selectedMinute)
                }
                saveScheduleSettings(newSchedule)
            },
            hour,
            minute,
            false
        ).show()
    }

    private fun updateScheduleEnabled(enabled: Boolean) {
        val schedule = (currentSchedule ?: ScheduleSettings()).copy(enabled = enabled)
        saveScheduleSettings(schedule)
    }

    private fun updateScheduleDays() {
        val days = mutableListOf<Int>()
        if (binding.daySunday.isChecked) days.add(0)
        if (binding.dayMonday.isChecked) days.add(1)
        if (binding.dayTuesday.isChecked) days.add(2)
        if (binding.dayWednesday.isChecked) days.add(3)
        if (binding.dayThursday.isChecked) days.add(4)
        if (binding.dayFriday.isChecked) days.add(5)
        if (binding.daySaturday.isChecked) days.add(6)

        val schedule = (currentSchedule ?: ScheduleSettings()).copy(allowedDays = days)
        saveScheduleSettings(schedule)
    }

    private fun saveScheduleSettings(schedule: ScheduleSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                app.familyManager.setScheduleSettings(familyId, childUid, schedule)
            } catch (e: Exception) {
                showError("Failed to save schedule")
            }
        }
    }

    private fun updateTimeLimitEnabled(enabled: Boolean) {
        val settings = (currentTimeLimits ?: TimeLimitSettings()).copy(enabled = enabled)
        saveTimeLimitSettings(settings)
    }

    private fun updateDailyTimeLimit(minutes: Int) {
        val settings = (currentTimeLimits ?: TimeLimitSettings()).copy(dailyLimitMinutes = minutes)
        saveTimeLimitSettings(settings)
    }

    private fun saveTimeLimitSettings(settings: TimeLimitSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                app.familyManager.setTimeLimitSettings(familyId, childUid, settings)
            } catch (e: Exception) {
                showError("Failed to save time limits")
            }
        }
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        return String.format("%d:%02d %s", hour12, minute, amPm)
    }

    private fun formatDuration(minutes: Long): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) {
            "${hours}h ${mins}m"
        } else {
            "${mins}m"
        }
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
