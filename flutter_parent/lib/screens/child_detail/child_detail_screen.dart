import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../services/family_manager.dart';
import 'content_tab.dart';
import 'settings_tab.dart';

class ChildDetailScreen extends StatelessWidget {
  final String familyId;
  final String childUid;
  final String childName;

  const ChildDetailScreen({
    super.key,
    required this.familyId,
    required this.childUid,
    required this.childName,
  });

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: Text(childName),
          bottom: const TabBar(
            tabs: [
              Tab(icon: Icon(Icons.movie), text: 'Videos'),
              Tab(icon: Icon(Icons.folder), text: 'Collections'),
              Tab(icon: Icon(Icons.settings), text: 'Settings'),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            ContentTab(
              familyId: familyId,
              childUid: childUid,
              isCollectionsMode: false,
            ),
            ContentTab(
              familyId: familyId,
              childUid: childUid,
              isCollectionsMode: true,
            ),
            SettingsTab(
              familyId: familyId,
              childUid: childUid,
            ),
          ],
        ),
        floatingActionButton: FloatingActionButton(
          onPressed: () => _requestSync(context),
          tooltip: 'Sync Content',
          child: const Icon(Icons.sync),
        ),
      ),
    );
  }

  void _requestSync(BuildContext context) async {
    final manager = context.read<FamilyManager>();
    await manager.requestSync(familyId, childUid);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Sync requested')),
      );
    }
  }
}
