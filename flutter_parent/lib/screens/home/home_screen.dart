import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../models/sync_models.dart';
import '../../services/family_manager.dart';
import '../child_detail/child_detail_screen.dart';
import '../onedrive/onedrive_setup_screen.dart';
import '../pairing/pairing_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  bool _isLoading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    final manager = context.read<FamilyManager>();
    try {
      await manager.signInAnonymously();
      await manager.getOrCreateFamily();
    } catch (e) {
      if (mounted) {
        setState(() => _error = 'Failed to connect: $e');
      }
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('My Children'),
        actions: [
          IconButton(
            icon: const Icon(Icons.cloud_outlined),
            tooltip: 'OneDrive Setup',
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(
                  builder: (_) => const OneDriveSetupScreen()),
            ),
          ),
        ],
      ),
      body: _buildBody(),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _addChild,
        icon: const Icon(Icons.add),
        label: const Text('Add Child'),
      ),
    );
  }

  Widget _buildBody() {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 64, color: Colors.red),
            const SizedBox(height: 16),
            Text(_error!, style: const TextStyle(color: Colors.white70)),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: () {
                setState(() {
                  _isLoading = true;
                  _error = null;
                });
                _initialize();
              },
              child: const Text('Retry'),
            ),
          ],
        ),
      );
    }

    final manager = context.watch<FamilyManager>();
    final family = manager.family;
    if (family == null) {
      return const Center(child: Text('No family found'));
    }

    return StreamBuilder<List<ChildDevice>>(
      stream: manager.getChildrenStream(family.familyId),
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }

        final children = snapshot.data ?? [];

        if (children.isEmpty) {
          return _buildEmptyState();
        }

        return RefreshIndicator(
          onRefresh: () async {
            // Force re-read
            setState(() {});
          },
          child: ListView.builder(
            padding: const EdgeInsets.only(
                top: 8, bottom: 100, left: 16, right: 16),
            itemCount: children.length,
            itemBuilder: (context, index) =>
                _buildChildCard(children[index]),
          ),
        );
      },
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.devices_other,
                size: 96, color: Colors.white.withAlpha(51)),
            const SizedBox(height: 24),
            const Text(
              'No children connected yet',
              style: TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.bold,
                  color: Colors.white),
            ),
            const SizedBox(height: 8),
            Text(
              'Tap "Add Child" to generate a pairing code and connect the KidsMovies app on your child\'s device.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white.withAlpha(179)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildChildCard(ChildDevice child) {
    final isOnline = child.isOnline;

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: ListTile(
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        leading: CircleAvatar(
          backgroundColor:
              isOnline ? Colors.green.withAlpha(51) : Colors.grey.withAlpha(51),
          child: Icon(
            Icons.phone_android,
            color: isOnline ? Colors.green : Colors.grey,
          ),
        ),
        title: Text(
          child.displayName,
          style: const TextStyle(
              fontWeight: FontWeight.bold, color: Colors.white),
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.circle,
                    size: 8,
                    color: isOnline ? Colors.green : Colors.grey),
                const SizedBox(width: 4),
                Text(
                  isOnline ? 'Online' : 'Offline',
                  style: TextStyle(
                    color: isOnline ? Colors.green : Colors.grey,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
            if (child.device.currentlyWatching != null) ...[
              const SizedBox(height: 4),
              Text(
                'Watching: ${child.device.currentlyWatching}',
                style: const TextStyle(
                    color: Color(0xFF6C63FF), fontSize: 12),
              ),
            ],
            if (child.device.todayWatchTime > 0) ...[
              const SizedBox(height: 2),
              Text(
                'Today: ${child.device.todayWatchTime}min',
                style: TextStyle(
                    color: Colors.white.withAlpha(128), fontSize: 12),
              ),
            ],
          ],
        ),
        trailing: PopupMenuButton<String>(
          icon: const Icon(Icons.more_vert, color: Colors.white54),
          onSelected: (value) {
            if (value == 'remove') _confirmRemoveChild(child);
          },
          itemBuilder: (_) => [
            const PopupMenuItem(
              value: 'remove',
              child: Row(
                children: [
                  Icon(Icons.delete, color: Colors.red, size: 20),
                  SizedBox(width: 8),
                  Text('Remove'),
                ],
              ),
            ),
          ],
        ),
        onTap: () => _openChildDetail(child),
      ),
    );
  }

  void _addChild() {
    final family = context.read<FamilyManager>().family;
    if (family == null) return;

    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => PairingScreen(familyId: family.familyId),
      ),
    );
  }

  void _openChildDetail(ChildDevice child) {
    final family = context.read<FamilyManager>().family;
    if (family == null) return;

    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => ChildDetailScreen(
          familyId: family.familyId,
          childUid: child.childUid,
          childName: child.displayName,
        ),
      ),
    );
  }

  void _confirmRemoveChild(ChildDevice child) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Remove Child?'),
        content: Text(
            'Remove ${child.displayName} from your family? This cannot be undone.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(ctx);
              final family = context.read<FamilyManager>().family;
              if (family != null) {
                await context
                    .read<FamilyManager>()
                    .removeChild(family.familyId, child.childUid);
              }
            },
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
  }
}
