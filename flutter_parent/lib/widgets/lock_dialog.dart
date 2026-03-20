import 'package:flutter/material.dart';

class LockDialogResult {
  final int warningMinutes;
  final bool allowFinishCurrentVideo;

  LockDialogResult({
    required this.warningMinutes,
    required this.allowFinishCurrentVideo,
  });
}

/// Shows a lock configuration dialog with warning time and finish-video options.
/// Returns null if cancelled.
Future<LockDialogResult?> showLockDialog(
  BuildContext context, {
  required String title,
  required String message,
}) async {
  int warningMinutes = 5;
  bool allowFinishVideo = false;

  return showDialog<LockDialogResult>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setState) => AlertDialog(
        title: Text(title),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(message, style: const TextStyle(color: Colors.white70)),
            const SizedBox(height: 20),
            const Text('Warning time:',
                style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                _WarningChip(
                  label: 'Immediate',
                  value: 0,
                  selected: warningMinutes,
                  onTap: () => setState(() => warningMinutes = 0),
                ),
                _WarningChip(
                  label: '1 min',
                  value: 1,
                  selected: warningMinutes,
                  onTap: () => setState(() => warningMinutes = 1),
                ),
                _WarningChip(
                  label: '5 min',
                  value: 5,
                  selected: warningMinutes,
                  onTap: () => setState(() => warningMinutes = 5),
                ),
                _WarningChip(
                  label: '10 min',
                  value: 10,
                  selected: warningMinutes,
                  onTap: () => setState(() => warningMinutes = 10),
                ),
                _WarningChip(
                  label: '15 min',
                  value: 15,
                  selected: warningMinutes,
                  onTap: () => setState(() => warningMinutes = 15),
                ),
              ],
            ),
            const SizedBox(height: 16),
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              value: allowFinishVideo,
              onChanged: (v) =>
                  setState(() => allowFinishVideo = v ?? false),
              title: const Text('Allow finish current video',
                  style: TextStyle(fontSize: 14)),
              subtitle: const Text(
                'Let the child finish watching before locking',
                style: TextStyle(fontSize: 12, color: Colors.white54),
              ),
              controlAffinity: ListTileControlAffinity.leading,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(
              ctx,
              LockDialogResult(
                warningMinutes: warningMinutes,
                allowFinishCurrentVideo: allowFinishVideo,
              ),
            ),
            child: const Text('Lock'),
          ),
        ],
      ),
    ),
  );
}

class _WarningChip extends StatelessWidget {
  final String label;
  final int value;
  final int selected;
  final VoidCallback onTap;

  const _WarningChip({
    required this.label,
    required this.value,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final isSelected = value == selected;
    return GestureDetector(
      onTap: onTap,
      child: Chip(
        label: Text(label,
            style: TextStyle(
              color: isSelected ? Colors.white : Colors.white70,
              fontSize: 12,
            )),
        backgroundColor: isSelected
            ? const Color(0xFF6C63FF)
            : const Color(0xFF16213E),
        side: BorderSide(
          color: isSelected
              ? const Color(0xFF6C63FF)
              : Colors.white24,
        ),
      ),
    );
  }
}
