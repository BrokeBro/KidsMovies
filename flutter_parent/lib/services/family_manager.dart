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

/// Result of joining a family with a code
sealed class JoinFamilyResult {}

class JoinFamilySuccess extends JoinFamilyResult {
  final String familyId;
  JoinFamilySuccess(this.familyId);
}

class JoinFamilyError extends JoinFamilyResult {
  final String message;
  JoinFamilyError(this.message);
}

class JoinFamilyCodeInvalid extends JoinFamilyResult {}

class JoinFamilyCodeExpired extends JoinFamilyResult {}

/// Manages all Firebase interactions for the parent app.
class FamilyManager extends ChangeNotifier {
  final FirebaseDatabase _database = FirebaseDatabase.instance;
  final FirebaseAuth _auth = FirebaseAuth.instance;

  Family? _family;
  Family? get family => _family;

  String? get currentUserId => _auth.currentUser?.uid;

  // --- Authentication ---

  Future<void> signInAnonymously() async {
    try {
      if (_auth.currentUser != null) return;
      await _auth.signInAnonymously();
    } catch (e) {
      debugPrint('FamilyManager: Failed to sign in anonymously: $e');
      rethrow;
    }
  }

  // --- Family Management ---

  Future<Family?> getOrCreateFamily() async {
    final userId = _auth.currentUser?.uid;
    if (userId == null) return null;

    try {
      // Check if user created a family
      final snapshot = await _database
          .ref('families')
          .orderByChild('createdBy')
          .equalTo(userId)
          .limitToFirst(1)
          .get();

      if (snapshot.exists) {
        final entry = (snapshot.value as Map).entries.first;
        _family = Family.fromMap(entry.key.toString(),
            Map<dynamic, dynamic>.from(entry.value as Map));
        notifyListeners();
        return _family;
      }

      // Check if user joined a family as a secondary parent
      final allFamilies = await _database.ref('families').get();
      if (allFamilies.exists && allFamilies.value != null) {
        final familiesData =
            Map<dynamic, dynamic>.from(allFamilies.value as Map);
        for (final entry in familiesData.entries) {
          final familyData = Map<dynamic, dynamic>.from(entry.value as Map);
          final parents = familyData['parents'];
          if (parents is Map && parents.containsKey(userId)) {
            _family = Family.fromMap(
                entry.key.toString(), familyData);
            notifyListeners();
            return _family;
          }
        }
      }

      // Create new family
      final familyRef = _database.ref('families').push();
      final familyData = {
        'createdAt': ServerValue.timestamp,
        'createdBy': userId,
        'familyName': 'My Family',
        'parentUids': [userId],
      };
      await familyRef.set(familyData);

      // Also write to the parents sub-node for indexing
      await _database
          .ref('${familyRef.path}/parents/$userId')
          .set({'joinedAt': ServerValue.timestamp, 'role': 'owner'});

      _family = Family(
        familyId: familyRef.key!,
        createdBy: userId,
        familyName: 'My Family',
        parentUids: [userId],
      );
      notifyListeners();
      return _family;
    } catch (e) {
      debugPrint('FamilyManager: Error in getOrCreateFamily: $e');
      rethrow;
    }
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

  Stream<DeviceSettings?> getDeviceSettingsStream(
      String familyId, String childUid) {
    return _database
        .ref(FirebasePaths.childDeviceSettingsPath(familyId, childUid))
        .onValue
        .map((event) {
      if (event.snapshot.value == null) return null;
      return DeviceSettings.fromMap(
          Map<dynamic, dynamic>.from(event.snapshot.value as Map));
    });
  }

  // --- Content Locking ---

  /// Lock or unlock a video. Also updates the video's enabled field directly
  /// so the child app sees the change immediately.
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

    try {
      // Write lock command
      await _database
          .ref('${FirebasePaths.childLocksPath(familyId, childUid)}/$key')
          .set(lock.toMap());

      // Also update the video's enabled field directly for immediate effect
      await _database
          .ref('${FirebasePaths.childVideosPath(familyId, childUid)}/$key/enabled')
          .set(!isLocked);
    } catch (e) {
      debugPrint('FamilyManager: Error setting video lock: $e');
      rethrow;
    }
  }

  /// Lock or unlock a collection, cascading to child seasons and videos.
  /// Uses atomic update() so all changes are applied simultaneously.
  Future<void> setCollectionLock({
    required String familyId,
    required String childUid,
    required String collectionName,
    required bool isLocked,
    int warningMinutes = 5,
    bool allowFinishCurrentVideo = false,
  }) async {
    final userId = _auth.currentUser?.uid ?? '';
    final now = DateTime.now().millisecondsSinceEpoch;
    final childRef = _database.ref(FirebasePaths.childPath(familyId, childUid));

    // Build all updates into a single atomic map
    final updates = <String, dynamic>{};

    // 1. Lock the collection itself
    final collectionKey = _sanitizeFirebaseKey(collectionName);
    final collectionLockId = 'collection_$collectionKey';
    updates['collections/$collectionKey/enabled'] = !isLocked;
    updates['locks/$collectionLockId'] = LockCommand(
      collectionName: collectionName,
      isLocked: isLocked,
      lockedBy: userId,
      lockedAt: now,
      warningMinutes: warningMinutes,
      allowFinishCurrentVideo: allowFinishCurrentVideo,
    ).toMap();

    try {
      // 2. Find child seasons and add them to the atomic update
      final collectionsSnapshot = await _database
          .ref(FirebasePaths.childCollectionsPath(familyId, childUid))
          .get();

      final childSeasonNames = <String>[];
      if (collectionsSnapshot.exists && collectionsSnapshot.value != null) {
        final collectionsData =
            Map<dynamic, dynamic>.from(collectionsSnapshot.value as Map);
        for (final entry in collectionsData.entries) {
          final collData =
              Map<dynamic, dynamic>.from(entry.value as Map);
          final parentName = collData['parentName'] as String?;
          if (parentName == collectionName) {
            final seasonKey = entry.key.toString();
            final seasonName = collData['name'] as String? ?? '';
            childSeasonNames.add(seasonName);

            final seasonLockId =
                'collection_${_sanitizeFirebaseKey(seasonName)}';
            updates['collections/$seasonKey/enabled'] = !isLocked;
            updates['locks/$seasonLockId'] = LockCommand(
              collectionName: seasonName,
              isLocked: isLocked,
              lockedBy: userId,
              lockedAt: now,
              warningMinutes: warningMinutes,
              allowFinishCurrentVideo: allowFinishCurrentVideo,
            ).toMap();
          }
        }
      }

      // 3. Find videos in this collection or child seasons and add to atomic update
      final allCollectionNames = [collectionName, ...childSeasonNames];

      final videosSnapshot = await _database
          .ref(FirebasePaths.childVideosPath(familyId, childUid))
          .get();

      if (videosSnapshot.exists && videosSnapshot.value != null) {
        final videosData =
            Map<dynamic, dynamic>.from(videosSnapshot.value as Map);
        for (final entry in videosData.entries) {
          final videoData =
              Map<dynamic, dynamic>.from(entry.value as Map);
          final videoKey = entry.key.toString();
          final videoTitle = videoData['title'] as String? ?? '';
          final videoCollections =
              (videoData['collectionNames'] as List<dynamic>?)
                      ?.map((e) => e.toString())
                      .toList() ??
                  [];

          final belongsToLockedCollection =
              videoCollections.any((c) => allCollectionNames.contains(c));
          if (belongsToLockedCollection) {
            final videoLockId = _sanitizeFirebaseKey(videoTitle);
            updates['videos/$videoKey/enabled'] = !isLocked;
            updates['locks/$videoLockId'] = LockCommand(
              videoTitle: videoTitle,
              isLocked: isLocked,
              lockedBy: userId,
              lockedAt: now,
              warningMinutes: warningMinutes,
              allowFinishCurrentVideo: allowFinishCurrentVideo,
            ).toMap();
          }
        }
      }

      // 4. Apply ALL updates atomically
      await childRef.update(updates);
    } catch (e) {
      debugPrint('FamilyManager: Error setting collection lock with cascade: $e');
      rethrow;
    }
  }

  /// Hide or unhide a video from the child
  Future<void> setVideoHidden({
    required String familyId,
    required String childUid,
    required String videoTitle,
    required bool isHidden,
  }) async {
    try {
      final key = _sanitizeFirebaseKey(videoTitle);
      await _database
          .ref('${FirebasePaths.childVideosPath(familyId, childUid)}/$key/hidden')
          .set(isHidden);
    } catch (e) {
      debugPrint('FamilyManager: Error setting video hidden: $e');
      rethrow;
    }
  }

  /// Hide or unhide a collection from the child
  Future<void> setCollectionHidden({
    required String familyId,
    required String childUid,
    required String collectionName,
    required bool isHidden,
  }) async {
    try {
      final key = _sanitizeFirebaseKey(collectionName);
      await _database
          .ref('${FirebasePaths.childCollectionsPath(familyId, childUid)}/$key/hidden')
          .set(isHidden);
    } catch (e) {
      debugPrint('FamilyManager: Error setting collection hidden: $e');
      rethrow;
    }
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

    try {
      await _database
          .ref(FirebasePaths.childAppLockPath(familyId, childUid))
          .set(lock.toMap());
    } catch (e) {
      debugPrint('FamilyManager: Error setting app lock: $e');
      rethrow;
    }
  }

  // --- Settings ---

  Future<void> setScheduleSettings(
      String familyId, String childUid, ScheduleSettings settings) async {
    try {
      await _database
          .ref(FirebasePaths.childSchedulePath(familyId, childUid))
          .set(settings.toMap());
    } catch (e) {
      debugPrint('FamilyManager: Error setting schedule: $e');
      rethrow;
    }
  }

  Future<void> setTimeLimitSettings(
      String familyId, String childUid, TimeLimitSettings settings) async {
    try {
      await _database
          .ref(FirebasePaths.childTimeLimitsPath(familyId, childUid))
          .set(settings.toMap());
    } catch (e) {
      debugPrint('FamilyManager: Error setting time limits: $e');
      rethrow;
    }
  }

  // --- Device Settings ---

  /// Enable or disable cloud/OneDrive videos for a specific child device
  Future<void> setCloudVideosEnabled(
      String familyId, String childUid, bool enabled) async {
    try {
      await _database
          .ref('${FirebasePaths.childDeviceSettingsPath(familyId, childUid)}/cloudVideosEnabled')
          .set(enabled);
    } catch (e) {
      debugPrint('FamilyManager: Error setting cloud videos enabled: $e');
      rethrow;
    }
  }

  /// Set the max content rating for TMDB artwork on a specific child device.
  /// null = remove parent override (child controls locally)
  /// "G"/"PG"/"PG-13"/"R" = set specific max rating
  Future<void> setMaxContentRating(
      String familyId, String childUid, String? rating) async {
    try {
      final ref = _database.ref(
          '${FirebasePaths.childDeviceSettingsPath(familyId, childUid)}/maxContentRating');
      if (rating != null) {
        await ref.set(rating);
      } else {
        await ref.remove();
      }
    } catch (e) {
      debugPrint('FamilyManager: Error setting content rating: $e');
      rethrow;
    }
  }

  // --- Sync & Management ---

  Future<void> requestSync(String familyId, String childUid) async {
    try {
      final userId = _auth.currentUser?.uid ?? '';
      await _database
          .ref(FirebasePaths.childSyncRequestPath(familyId, childUid))
          .set({
        'requested': true,
        'requestedAt': ServerValue.timestamp,
        'requestedBy': userId,
      });
    } catch (e) {
      debugPrint('FamilyManager: Error requesting sync: $e');
      rethrow;
    }
  }

  Future<void> removeChild(String familyId, String childUid) async {
    try {
      await _database.ref(FirebasePaths.childPath(familyId, childUid)).remove();
    } catch (e) {
      debugPrint('FamilyManager: Error removing child: $e');
      rethrow;
    }
  }

  // --- Pairing ---

  Future<PairingCode?> generatePairingCode(String familyId) async {
    final userId = _auth.currentUser?.uid;
    if (userId == null) return null;

    final random = Random.secure();
    const maxAttempts = 10;

    try {
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
    } catch (e) {
      debugPrint('FamilyManager: Error generating pairing code: $e');
      rethrow;
    }
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

  // --- Multi-Parent Support ---

  /// Generate a join code for a second parent to join this family
  Future<FamilyJoinCode?> generateFamilyJoinCode(String familyId) async {
    final userId = _auth.currentUser?.uid;
    if (userId == null) return null;

    try {
      final code = (100000 + Random.secure().nextInt(900000)).toString();
      final now = DateTime.now().millisecondsSinceEpoch;
      final joinCode = FamilyJoinCode(
        code: code,
        familyId: familyId,
        createdAt: now,
        expiresAt: now + 15 * 60 * 1000, // 15 minutes
        createdBy: userId,
      );

      await _database
          .ref('familyJoinCodes/$code')
          .set(joinCode.toMap());

      return joinCode;
    } catch (e) {
      debugPrint('FamilyManager: Error generating join code: $e');
      rethrow;
    }
  }

  /// Join an existing family using a join code (for second parent)
  Future<JoinFamilyResult> joinFamilyWithCode(String code) async {
    final userId = _auth.currentUser?.uid;
    if (userId == null) return JoinFamilyError('Not signed in');

    try {
      final codeRef = _database.ref('familyJoinCodes/$code');
      final snapshot = await codeRef.get();

      if (!snapshot.exists) return JoinFamilyCodeInvalid();

      final data = Map<dynamic, dynamic>.from(snapshot.value as Map);
      final familyId = data['familyId'] as String? ?? '';
      final expiresAt = (data['expiresAt'] as num?)?.toInt() ?? 0;
      final used = data['used'] as bool? ?? false;

      if (used) return JoinFamilyCodeInvalid();
      if (DateTime.now().millisecondsSinceEpoch > expiresAt) {
        return JoinFamilyCodeExpired();
      }

      // Add this parent to the family's parents list
      await _database
          .ref('${FirebasePaths.familyPath(familyId)}/parents/$userId')
          .set({'joinedAt': ServerValue.timestamp, 'role': 'parent'});

      // Update the family's parentUids list
      final familyRef = _database.ref(FirebasePaths.familyPath(familyId));
      final familySnapshot = await familyRef.get();
      if (familySnapshot.exists && familySnapshot.value != null) {
        final familyData =
            Map<dynamic, dynamic>.from(familySnapshot.value as Map);
        final parentUids = (familyData['parentUids'] as List<dynamic>?)
                ?.map((e) => e.toString())
                .toList() ??
            [];
        if (!parentUids.contains(userId)) {
          parentUids.add(userId);
          await familyRef.child('parentUids').set(parentUids);
        }
      }

      // Mark the code as used
      await codeRef.child('used').set(true);
      await codeRef.child('usedBy').set(userId);

      return JoinFamilySuccess(familyId);
    } catch (e) {
      debugPrint('FamilyManager: Error joining family: $e');
      return JoinFamilyError(e.toString());
    }
  }

  // --- Helpers ---

  String _sanitizeFirebaseKey(String key) {
    return key.replaceAll(RegExp(r'[.\$#\[\]/]'), '_');
  }
}
