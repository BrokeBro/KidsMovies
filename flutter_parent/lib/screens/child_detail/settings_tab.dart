import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../models/sync_models.dart';
import '../../services/family_manager.dart';
import '../../widgets/lock_dialog.dart';

class SettingsTab extends StatelessWidget {
  final String familyId;
  final String childUid;

  const SettingsTab({
    super.key,
    required this.familyId,
    required this.childUid,
  });

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _buildAppLockSection(context),
        const SizedBox(height: 16),
        _buildScheduleSection(context),
        const SizedBox(height: 16),
        _buildTimeLimitsSection(context),
        const SizedBox(height: 16),
        _buildMetricsSection(context),
      ],
    );
  }

  // --- App Lock ---

  Widget _buildAppLockSection(BuildContext context) {
    final manager = context.read<FamilyManager>();

    return StreamBuilder<AppLockCommand?>(
      stream: manager.getAppLockStream(familyId, childUid),
      builder: (context, snapshot) {
        final appLock = snapshot.data;
        final isLocked = appLock?.isLocked ?? false;

        return Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(Icons.lock,
                        color: isLocked ? Colors.red : Colors.green),
                    const SizedBox(width: 8),
                    const Text('App Lock',
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.bold)),
                    const Spacer(),
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 10, vertical: 4),
                      decoration: BoxDecoration(
                        color: isLocked
                            ? Colors.red.withAlpha(51)
                            : Colors.green.withAlpha(51),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        isLocked ? 'LOCKED' : 'UNLOCKED',
                        style: TextStyle(
                          color: isLocked ? Colors.red : Colors.green,
                          fontSize: 12,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                  ],
                ),
                if (isLocked && appLock?.unlockAt != null) ...[
                  const SizedBox(height: 8),
                  Text(
                    'Scheduled unlock: ${_formatTime(appLock!.unlockAt!)}',
                    style: const TextStyle(color: Colors.white54, fontSize: 13),
                  ),
                ],
                const SizedBox(height: 16),
                Row(
                  children: [
                    if (!isLocked)
                      Expanded(
                        child: ElevatedButton.icon(
                          onPressed: () => _lockApp(context),
                          icon: const Icon(Icons.lock, size: 18),
                          label: const Text('Lock App'),
                          style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.red),
                        ),
                      ),
                    if (isLocked)
                      Expanded(
                        child: ElevatedButton.icon(
                          onPressed: () => _unlockApp(context),
                          icon: const Icon(Icons.lock_open, size: 18),
                          label: const Text('Unlock App'),
                          style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.green),
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  void _lockApp(BuildContext context) async {
    final result = await showLockDialog(
      context,
      title: 'Lock App',
      message: 'Lock the KidsMovies app on the child\'s device?',
    );
    if (result == null || !context.mounted) return;

    await context.read<FamilyManager>().setAppLock(
          familyId: familyId,
          childUid: childUid,
          isLocked: true,
          warningMinutes: result.warningMinutes,
          allowFinishCurrentVideo: result.allowFinishCurrentVideo,
        );
  }

  void _unlockApp(BuildContext context) async {
    await context.read<FamilyManager>().setAppLock(
          familyId: familyId,
          childUid: childUid,
          isLocked: false,
        );
  }

  // --- Schedule ---

  Widget _buildScheduleSection(BuildContext context) {
    final manager = context.read<FamilyManager>();

    return StreamBuilder<ScheduleSettings?>(
      stream: manager.getScheduleSettingsStream(familyId, childUid),
      builder: (context, snapshot) {
        final schedule = snapshot.data ?? ScheduleSettings();

        return Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.schedule, color: Color(0xFF6C63FF)),
                    const SizedBox(width: 8),
                    const Text('Viewing Schedule',
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.bold)),
                    const Spacer(),
                    Switch(
                      value: schedule.enabled,
                      onChanged: (enabled) => _updateSchedule(
                        context,
                        ScheduleSettings(
                          enabled: enabled,
                          allowedDays: schedule.allowedDays,
                          allowedStartHour: schedule.allowedStartHour,
                          allowedStartMinute: schedule.allowedStartMinute,
                          allowedEndHour: schedule.allowedEndHour,
                          allowedEndMinute: schedule.allowedEndMinute,
                          timezone: schedule.timezone,
                        ),
                      ),
                    ),
                  ],
                ),
                if (schedule.enabled) ...[
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: _TimePickerTile(
                          label: 'Start',
                          hour: schedule.allowedStartHour,
                          minute: schedule.allowedStartMinute,
                          onChanged: (h, m) => _updateSchedule(
                            context,
                            ScheduleSettings(
                              enabled: true,
                              allowedDays: schedule.allowedDays,
                              allowedStartHour: h,
                              allowedStartMinute: m,
                              allowedEndHour: schedule.allowedEndHour,
                              allowedEndMinute: schedule.allowedEndMinute,
                              timezone: schedule.timezone,
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: _TimePickerTile(
                          label: 'End',
                          hour: schedule.allowedEndHour,
                          minute: schedule.allowedEndMinute,
                          onChanged: (h, m) => _updateSchedule(
                            context,
                            ScheduleSettings(
                              enabled: true,
                              allowedDays: schedule.allowedDays,
                              allowedStartHour: schedule.allowedStartHour,
                              allowedStartMinute: schedule.allowedStartMinute,
                              allowedEndHour: h,
                              allowedEndMinute: m,
                              timezone: schedule.timezone,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  _buildDayPicker(context, schedule),
                ],
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildDayPicker(BuildContext context, ScheduleSettings schedule) {
    const days = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: List.generate(7, (index) {
        final isActive = schedule.allowedDays.contains(index);
        return GestureDetector(
          onTap: () {
            final newDays = List<int>.from(schedule.allowedDays);
            if (isActive) {
              newDays.remove(index);
            } else {
              newDays.add(index);
              newDays.sort();
            }
            _updateSchedule(
              context,
              ScheduleSettings(
                enabled: schedule.enabled,
                allowedDays: newDays,
                allowedStartHour: schedule.allowedStartHour,
                allowedStartMinute: schedule.allowedStartMinute,
                allowedEndHour: schedule.allowedEndHour,
                allowedEndMinute: schedule.allowedEndMinute,
                timezone: schedule.timezone,
              ),
            );
          },
          child: CircleAvatar(
            radius: 18,
            backgroundColor: isActive
                ? const Color(0xFF6C63FF)
                : Colors.white.withAlpha(26),
            child: Text(
              days[index],
              style: TextStyle(
                color: isActive ? Colors.white : Colors.white54,
                fontSize: 13,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
        );
      }),
    );
  }

  void _updateSchedule(BuildContext context, ScheduleSettings settings) {
    context
        .read<FamilyManager>()
        .setScheduleSettings(familyId, childUid, settings);
  }

  // --- Time Limits ---

  Widget _buildTimeLimitsSection(BuildContext context) {
    final manager = context.read<FamilyManager>();

    return StreamBuilder<TimeLimitSettings?>(
      stream: manager.getTimeLimitSettingsStream(familyId, childUid),
      builder: (context, snapshot) {
        final limits = snapshot.data ?? TimeLimitSettings();

        return Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.timer, color: Color(0xFF6C63FF)),
                    const SizedBox(width: 8),
                    const Text('Daily Time Limit',
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.bold)),
                    const Spacer(),
                    Switch(
                      value: limits.enabled,
                      onChanged: (enabled) => _updateTimeLimits(
                        context,
                        TimeLimitSettings(
                          enabled: enabled,
                          dailyLimitMinutes: limits.dailyLimitMinutes,
                          weekendLimitMinutes: limits.weekendLimitMinutes,
                        ),
                      ),
                    ),
                  ],
                ),
                if (limits.enabled) ...[
                  const SizedBox(height: 12),
                  _buildLimitDropdown(
                    label: 'Weekday limit',
                    value: limits.dailyLimitMinutes,
                    onChanged: (v) => _updateTimeLimits(
                      context,
                      TimeLimitSettings(
                        enabled: true,
                        dailyLimitMinutes: v,
                        weekendLimitMinutes: limits.weekendLimitMinutes,
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  _buildLimitDropdown(
                    label: 'Weekend limit',
                    value: limits.weekendLimitMinutes,
                    onChanged: (v) => _updateTimeLimits(
                      context,
                      TimeLimitSettings(
                        enabled: true,
                        dailyLimitMinutes: limits.dailyLimitMinutes,
                        weekendLimitMinutes: v,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildLimitDropdown({
    required String label,
    required int value,
    required ValueChanged<int> onChanged,
  }) {
    final options = {
      30: '30 minutes',
      60: '1 hour',
      90: '1.5 hours',
      120: '2 hours',
      180: '3 hours',
      240: '4 hours',
      0: 'Unlimited',
    };

    return Row(
      children: [
        SizedBox(
          width: 120,
          child: Text(label,
              style: const TextStyle(color: Colors.white70, fontSize: 14)),
        ),
        Expanded(
          child: DropdownButton<int>(
            value: options.containsKey(value) ? value : 120,
            isExpanded: true,
            dropdownColor: const Color(0xFF16213E),
            items: options.entries
                .map((e) => DropdownMenuItem(
                      value: e.key,
                      child: Text(e.value),
                    ))
                .toList(),
            onChanged: (v) {
              if (v != null) onChanged(v);
            },
          ),
        ),
      ],
    );
  }

  void _updateTimeLimits(BuildContext context, TimeLimitSettings settings) {
    context
        .read<FamilyManager>()
        .setTimeLimitSettings(familyId, childUid, settings);
  }

  // --- Metrics ---

  Widget _buildMetricsSection(BuildContext context) {
    final manager = context.read<FamilyManager>();

    return StreamBuilder<ViewingMetrics?>(
      stream: manager.getViewingMetricsStream(familyId, childUid),
      builder: (context, snapshot) {
        final metrics = snapshot.data;
        if (metrics == null) return const SizedBox.shrink();

        return Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Row(
                  children: [
                    Icon(Icons.bar_chart, color: Color(0xFF6C63FF)),
                    SizedBox(width: 8),
                    Text('Viewing Stats',
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.bold)),
                  ],
                ),
                const SizedBox(height: 16),
                _metricRow('Today',
                    metrics.formatWatchTime(metrics.todayWatchTimeMinutes)),
                _metricRow('This week',
                    metrics.formatWatchTime(metrics.weekWatchTimeMinutes)),
                _metricRow('All time',
                    metrics.formatWatchTime(metrics.totalWatchTimeMinutes)),
                _metricRow('Videos today',
                    '${metrics.videosWatchedToday}'),
                if (metrics.lastVideoWatched != null)
                  _metricRow('Last watched', metrics.lastVideoWatched!),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _metricRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          SizedBox(
            width: 120,
            child: Text(label,
                style: const TextStyle(color: Colors.white54, fontSize: 14)),
          ),
          Expanded(
            child: Text(value,
                style: const TextStyle(
                    color: Colors.white, fontWeight: FontWeight.w500)),
          ),
        ],
      ),
    );
  }

  String _formatTime(int millis) {
    final dt = DateTime.fromMillisecondsSinceEpoch(millis);
    final hour = dt.hour.toString().padLeft(2, '0');
    final minute = dt.minute.toString().padLeft(2, '0');
    return '$hour:$minute';
  }
}

class _TimePickerTile extends StatelessWidget {
  final String label;
  final int hour;
  final int minute;
  final void Function(int hour, int minute) onChanged;

  const _TimePickerTile({
    required this.label,
    required this.hour,
    required this.minute,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final timeStr =
        '${hour.toString().padLeft(2, '0')}:${minute.toString().padLeft(2, '0')}';

    return GestureDetector(
      onTap: () async {
        final picked = await showTimePicker(
          context: context,
          initialTime: TimeOfDay(hour: hour, minute: minute),
        );
        if (picked != null) {
          onChanged(picked.hour, picked.minute);
        }
      },
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Colors.white.withAlpha(13),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: Colors.white24),
        ),
        child: Column(
          children: [
            Text(label,
                style: const TextStyle(color: Colors.white54, fontSize: 12)),
            const SizedBox(height: 4),
            Text(timeStr,
                style: const TextStyle(
                    color: Colors.white,
                    fontSize: 20,
                    fontWeight: FontWeight.bold)),
          ],
        ),
      ),
    );
  }
}
