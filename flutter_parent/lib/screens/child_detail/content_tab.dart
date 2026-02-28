import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../services/family_manager.dart';
import '../../widgets/lock_dialog.dart';

class ContentTab extends StatelessWidget {
  final String familyId;
  final String childUid;
  final bool isCollectionsMode;

  const ContentTab({
    super.key,
    required this.familyId,
    required this.childUid,
    required this.isCollectionsMode,
  });

  @override
  Widget build(BuildContext context) {
    final manager = context.read<FamilyManager>();

    if (isCollectionsMode) {
      return _buildCollectionsView(context, manager);
    }
    return _buildVideosView(context, manager);
  }

  Widget _buildVideosView(BuildContext context, FamilyManager manager) {
    return StreamBuilder<List<ChildVideo>>(
      stream: manager.getChildVideosStream(familyId, childUid),
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }

        final videos = snapshot.data ?? [];
        if (videos.isEmpty) {
          return _buildEmptyState(
            icon: Icons.movie_outlined,
            message: 'No videos',
            hint: 'Videos will appear here once the child app syncs.',
          );
        }

        return ListView.builder(
          padding: const EdgeInsets.symmetric(vertical: 8),
          itemCount: videos.length,
          itemBuilder: (context, index) =>
              _buildVideoTile(context, videos[index]),
        );
      },
    );
  }

  Widget _buildCollectionsView(BuildContext context, FamilyManager manager) {
    return StreamBuilder<List<ChildCollection>>(
      stream: manager.getChildCollectionsStream(familyId, childUid),
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }

        final collections = snapshot.data ?? [];
        if (collections.isEmpty) {
          return _buildEmptyState(
            icon: Icons.folder_outlined,
            message: 'No collections',
            hint: 'Collections will appear here once the child app syncs.',
          );
        }

        return ListView.builder(
          padding: const EdgeInsets.symmetric(vertical: 8),
          itemCount: collections.length,
          itemBuilder: (context, index) =>
              _buildCollectionTile(context, collections[index]),
        );
      },
    );
  }

  Widget _buildVideoTile(BuildContext context, ChildVideo childVideo) {
    final video = childVideo.video;
    final isLocked = !video.isEnabled;
    final isOneDrive = video.sourceType == 'onedrive';

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: ListTile(
        leading: Icon(
          isOneDrive ? Icons.cloud : Icons.movie,
          color: isLocked
              ? Colors.grey
              : isOneDrive
                  ? const Color(0xFF0078D4)
                  : const Color(0xFF6C63FF),
        ),
        title: Text(
          video.title,
          style: TextStyle(
            color: isLocked ? Colors.white38 : Colors.white,
            fontWeight: FontWeight.w500,
          ),
        ),
        subtitle: _buildVideoSubtitle(video, isOneDrive),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (isLocked)
              Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.red.withAlpha(51),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: const Text('LOCKED',
                    style: TextStyle(color: Colors.red, fontSize: 10)),
              ),
            const SizedBox(width: 8),
            Switch(
              value: isLocked,
              onChanged: (locked) =>
                  _toggleVideoLock(context, video.title, locked),
            ),
          ],
        ),
      ),
    );
  }

  Widget? _buildVideoSubtitle(video, bool isOneDrive) {
    final parts = <String>[];
    if (isOneDrive) parts.add('OneDrive');
    if (video.collectionNames.isNotEmpty) {
      parts.add(video.collectionNames.join(', '));
    }
    if (parts.isEmpty) return null;
    return Text(parts.join(' \u2022 '),
        style: const TextStyle(fontSize: 12));
  }

  Widget _buildCollectionTile(
      BuildContext context, ChildCollection childCollection) {
    final collection = childCollection.collection;
    final isLocked = !collection.isEnabled;

    IconData icon;
    String typeLabel;
    if (collection.isTvShow) {
      icon = Icons.tv;
      typeLabel = 'TV Show';
    } else if (collection.isSeason) {
      icon = Icons.folder;
      typeLabel = 'Season';
    } else {
      icon = Icons.folder;
      typeLabel = collection.type;
    }

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: ListTile(
        leading: Icon(icon,
            color: isLocked ? Colors.grey : const Color(0xFF6C63FF)),
        title: Text(
          collection.name,
          style: TextStyle(
            color: isLocked ? Colors.white38 : Colors.white,
            fontWeight: FontWeight.w500,
          ),
        ),
        subtitle: Text(
          '${collection.videoCount} videos \u2022 $typeLabel',
          style: const TextStyle(fontSize: 12),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (isLocked)
              Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.red.withAlpha(51),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: const Text('LOCKED',
                    style: TextStyle(color: Colors.red, fontSize: 10)),
              ),
            const SizedBox(width: 8),
            Switch(
              value: isLocked,
              onChanged: (locked) =>
                  _toggleCollectionLock(context, collection.name, locked),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmptyState({
    required IconData icon,
    required String message,
    required String hint,
  }) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 80, color: Colors.white24),
          const SizedBox(height: 16),
          Text(message,
              style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: Colors.white)),
          const SizedBox(height: 8),
          Text(hint,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white54)),
        ],
      ),
    );
  }

  void _toggleVideoLock(
      BuildContext context, String videoTitle, bool locked) async {
    if (locked) {
      final result = await showLockDialog(
        context,
        title: 'Lock Video',
        message: 'Lock "$videoTitle"? The child won\'t be able to play it.',
      );
      if (result == null) return;

      await context.read<FamilyManager>().setVideoLock(
            familyId: familyId,
            childUid: childUid,
            videoTitle: videoTitle,
            isLocked: true,
            warningMinutes: result.warningMinutes,
            allowFinishCurrentVideo: result.allowFinishCurrentVideo,
          );
    } else {
      await context.read<FamilyManager>().setVideoLock(
            familyId: familyId,
            childUid: childUid,
            videoTitle: videoTitle,
            isLocked: false,
          );
    }
  }

  void _toggleCollectionLock(
      BuildContext context, String collectionName, bool locked) async {
    if (locked) {
      final result = await showLockDialog(
        context,
        title: 'Lock Collection',
        message:
            'Lock "$collectionName"? All videos in this collection will be inaccessible.',
      );
      if (result == null) return;

      await context.read<FamilyManager>().setCollectionLock(
            familyId: familyId,
            childUid: childUid,
            collectionName: collectionName,
            isLocked: true,
            warningMinutes: result.warningMinutes,
            allowFinishCurrentVideo: result.allowFinishCurrentVideo,
          );
    } else {
      await context.read<FamilyManager>().setCollectionLock(
            familyId: familyId,
            childUid: childUid,
            collectionName: collectionName,
            isLocked: false,
          );
    }
  }
}
