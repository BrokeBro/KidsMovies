import 'dart:math';

import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_database/firebase_database.dart';
import 'package:flutter/foundation.dart';

import '../models/sync_models.dart';

/// Wrapper types for Firebase-backed child data
class ChildDevice {
  final SyncedChildDevice device;
  final String childUid;

  ChildDevice({required this.device, required this.childUid});

  String get displayName => device.displayName;
  bool get isOnline => device.isRecentlyOnline;
}

class ChildVideo {
  final SyncedVideo video;
  final String firebaseKey;

  ChildVideo({required this.video, required this.firebaseKey});
}

class ChildCollection {
  final SyncedCollection collection;
  final String firebaseKey;

  ChildCollection({required this.collection, required this.firebaseKey});
}

/// Manages all Firebase interactions for the parent app.
class FamilyManager extends ChangeNotifier {
  final FirebaseDatabase _database = FirebaseDatabase.instance;
  final FirebaseAuth _auth = FirebaseAuth.instance;

  Family? _family;
  Family? get family => _family;

  String? get currentUserId => _auth.currentUser?.uid;

  // --- Authentication ---

  Future<void> signInAnonymously() async {
    if (_auth.currentUser != null) return;
    await _auth.signInAnonymously();
  }

  // --- Family Management ---

  Future<Family?> getOrCreateFamily() async {
    final userId = _auth.currentUser?.uid;
    if (userId == null) return null;

    // Check if user already has a family
    final snapshot = await _database
        .ref('families')
        .orderByChild('createdBy')
        .equalTo(userId)
        .limitToFirst(1)
        .get();

    if (snapshot.exists) {
      final entry = (snapshot.value as Map).entries.first;
      _family = Family.fromMap(
          entry.key.toString(), Map<dynamic, dynamic>.from(entry.value as Map));
      notifyListeners();
      return _family;
    }

    // Create new family
    final familyRef = _database.ref('families').push();
    final familyData = {
      'createdAt': ServerValue.timestamp,
      'createdBy': userId,
      'familyName': 'My Family',
    };
    await familyRef.set(familyData);

    _family = Family(
      familyId: familyRef.key!,
      createdBy: userId,
      familyName: 'My Family',
    );
    notifyListeners();
    return _family;
  }

  // --- Real-time Streams ---

  Stream<List<ChildDevice>> getChildrenStream(String familyId) {
    return _database
        .ref('families/$familyId/children')
        .onValue
        .map((event) {
      final children = <ChildDevice>[];
      if (event.snapshot.value == null) return children;

      final data = Map<dynamic, dynamic>.from(event.snapshot.value as Map);
      for (final entry in data.entries) {
        final childUid = entry.key.toString();
        final childData = Map<dynamic, dynamic>.from(entry.value as Map);
        final deviceInfo = childData['deviceInfo'] != null
            ? Map<dynamic, dynamic>.from(childData['deviceInfo'] as Map)
            : <dynamic, dynamic>{};
        children.add(ChildDevice(
          device: SyncedChildDevice.fromMap(deviceInfo),
          childUid: childUid,
        ));
      }
      return children;
    });
  }

  Stream<List<ChildVideo>> getChildVideosStream(
      String familyId, String childUid) {
    return _database
        .ref(FirebasePaths.childVideosPath(familyId, childUid))
        .onValue
        .map((event) {
      final videos = <ChildVideo>[];
      if (event.snapshot.value == null) return videos;

      final data = Map<dynamic, dynamic>.from(event.snapshot.value as Map);
      for (final entry in data.entries) {
        final video = SyncedVideo.fromMap(
            Map<dynamic, dynamic>.from(entry.value as Map));
        videos.add(ChildVideo(video: video, firebaseKey: entry.key.toString()));
      }
      videos.sort((a, b) => a.video.title.compareTo(b.video.title));
      return videos;
    });
  }

  Stream<List<ChildCollection>> getChildCollectionsStream(
      String familyId, String childUid) {
    return _database
        .ref(FirebasePaths.childCollectionsPath(familyId, childUid))
        .onValue
        .map((event) {
      final collections = <ChildCollection>[];
      if (event.snapshot.value == null) return collections;

      final data = Map<dynamic, dynamic>.from(event.snapshot.value as Map);
      for (final entry in data.entries) {
        final collection = SyncedCollection.fromMap(
            Map<dynamic, dynamic>.from(entry.value as Map));
        collections.add(ChildCollection(
            collection: collection, firebaseKey: entry.key.toString()));
      }
      collections
          .sort((a, b) => a.collection.name.compareTo(b.collection.name));
      return collections;
    });
  }

  Stream<AppLockCommand?> getAppLockStream(
      String familyId, String childUid) {
    return _database
        .ref(FirebasePaths.childAppLockPath(familyId, childUid))
        .onValue
        .map((event) {
      if (event.snapshot.value == null) return null;
      return AppLockCommand.fromMap(
          Map<dynamic, dynamic>.from(event.snapshot.value as Map));
    });
  }

  Stream<ScheduleSettings?> getScheduleSettingsStream(
      String familyId, String childUid) {
    return _database
        .ref(FirebasePaths.childSchedulePath(familyId, childUid))
        .onValue
        .map((event) {
      if (event.snapshot.value == null) return null;
      return ScheduleSettings.fromMap(
          Map<dynamic, dynamic>.from(event.snapshot.value as Map));
    });
  }

  Stream<TimeLimitSettings?> getTimeLimitSettingsStream(
      String familyId, String childUid) {
    return _database
        .ref(FirebasePaths.childTimeLimitsPath(familyId, childUid))
        .onValue
        .map((event) {
      if (event.snapshot.value == null) return null;
      return TimeLimitSettings.fromMap(
          Map<dynamic, dynamic>.from(event.snapshot.value as Map));
    });
  }

  Stream<ViewingMetrics?> getViewingMetricsStream(
      String familyId, String childUid) {
    return _database
        .ref(FirebasePaths.childMetricsPath(familyId, childUid))
        .onValue
        .map((event) {
      if (event.snapshot.value == null) return null;
      return ViewingMetrics.fromMap(
          Map<dynamic, dynamic>.from(event.snapshot.value as Map));
    });
  }

  // --- Content Locking ---

  Future<void> setVideoLock({
    required String familyId,
    required String childUid,
    required String videoTitle,
    required bool isLocked,
    int warningMinutes = 5,
    bool allowFinishCurrentVideo = false,
  }) async {
    final userId = _auth.currentUser?.uid ?? '';
    final key = _sanitizeFirebaseKey(videoTitle);

    final lock = LockCommand(
      videoTitle: videoTitle,
      isLocked: isLocked,
      lockedBy: userId,
      lockedAt: DateTime.now().millisecondsSinceEpoch,
      warningMinutes: warningMinutes,
      allowFinishCurrentVideo: allowFinishCurrentVideo,
    );

    await _database
        .ref('${FirebasePaths.childLocksPath(familyId, childUid)}/$key')
        .set(lock.toMap());
  }

  Future<void> setCollectionLock({
    required String familyId,
    required String childUid,
    required String collectionName,
    required bool isLocked,
    int warningMinutes = 5,
    bool allowFinishCurrentVideo = false,
  }) async {
    final userId = _auth.currentUser?.uid ?? '';
    final key = _sanitizeFirebaseKey(collectionName);

    final lock = LockCommand(
      collectionName: collectionName,
      isLocked: isLocked,
      lockedBy: userId,
      lockedAt: DateTime.now().millisecondsSinceEpoch,
      warningMinutes: warningMinutes,
      allowFinishCurrentVideo: allowFinishCurrentVideo,
    );

    await _database
        .ref('${FirebasePaths.childLocksPath(familyId, childUid)}/$key')
        .set(lock.toMap());
  }

  Future<void> setAppLock({
    required String familyId,
    required String childUid,
    required bool isLocked,
    int? unlockAt,
    String message = '',
    int warningMinutes = 5,
    bool allowFinishCurrentVideo = false,
  }) async {
    final userId = _auth.currentUser?.uid ?? '';

    final lock = AppLockCommand(
      isLocked: isLocked,
      lockedBy: userId,
      lockedAt: DateTime.now().millisecondsSinceEpoch,
      unlockAt: unlockAt,
      message: message,
      warningMinutes: warningMinutes,
      allowFinishCurrentVideo: allowFinishCurrentVideo,
    );

    await _database
        .ref(FirebasePaths.childAppLockPath(familyId, childUid))
        .set(lock.toMap());
  }

  // --- Settings ---

  Future<void> setScheduleSettings(
      String familyId, String childUid, ScheduleSettings settings) async {
    await _database
        .ref(FirebasePaths.childSchedulePath(familyId, childUid))
        .set(settings.toMap());
  }

  Future<void> setTimeLimitSettings(
      String familyId, String childUid, TimeLimitSettings settings) async {
    await _database
        .ref(FirebasePaths.childTimeLimitsPath(familyId, childUid))
        .set(settings.toMap());
  }

  // --- Sync & Management ---

  Future<void> requestSync(String familyId, String childUid) async {
    final userId = _auth.currentUser?.uid ?? '';
    await _database
        .ref(FirebasePaths.childSyncRequestPath(familyId, childUid))
        .set({
      'requested': true,
      'requestedAt': ServerValue.timestamp,
      'requestedBy': userId,
    });
  }

  Future<void> removeChild(String familyId, String childUid) async {
    await _database
        .ref(FirebasePaths.childPath(familyId, childUid))
        .remove();
  }

  // --- Pairing ---

  Future<PairingCode?> generatePairingCode(String familyId) async {
    final userId = _auth.currentUser?.uid;
    if (userId == null) return null;

    final random = Random.secure();
    const maxAttempts = 10;

    for (var i = 0; i < maxAttempts; i++) {
      final code = (100000 + random.nextInt(900000)).toString();
      final codeRef = _database.ref(FirebasePaths.pairingCodePath(code));
      final snapshot = await codeRef.get();

      if (!snapshot.exists) {
        final now = DateTime.now().millisecondsSinceEpoch;
        final pairingCode = PairingCode(
          code: code,
          familyId: familyId,
          createdAt: now,
          expiresAt: now + 15 * 60 * 1000, // 15 minutes
          createdBy: userId,
        );
        await codeRef.set(pairingCode.toMap());
        return pairingCode;
      }
    }
    return null;
  }

  Stream<bool> watchPairingCodeUsed(String code) {
    return _database
        .ref(FirebasePaths.pairingCodePath(code))
        .onValue
        .map((event) {
      if (event.snapshot.value == null) return false;
      final data =
          Map<dynamic, dynamic>.from(event.snapshot.value as Map);
      return data['used'] as bool? ?? false;
    });
  }

  // --- Helpers ---

  String _sanitizeFirebaseKey(String key) {
    return key.replaceAll(RegExp(r'[.\$#\[\]/]'), '_');
  }
}
