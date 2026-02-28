/// Data models matching the Firebase Realtime Database structure
/// used by the Android child app (KidsMovies).

class SyncedVideo {
  final String title;
  final List<String> collectionNames;
  final bool isFavourite;
  final bool isEnabled;
  final bool isHidden;
  final int duration;
  final int playbackPosition;
  final int? lastWatched;
  final String? thumbnailUrl;
  final String sourceType; // "local" or "onedrive"
  final String? remoteId;

  SyncedVideo({
    this.title = '',
    this.collectionNames = const [],
    this.isFavourite = false,
    this.isEnabled = true,
    this.isHidden = false,
    this.duration = 0,
    this.playbackPosition = 0,
    this.lastWatched,
    this.thumbnailUrl,
    this.sourceType = 'local',
    this.remoteId,
  });

  factory SyncedVideo.fromMap(Map<dynamic, dynamic> map) {
    return SyncedVideo(
      title: map['title'] as String? ?? '',
      collectionNames: (map['collectionNames'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ??
          [],
      isFavourite: map['isFavourite'] as bool? ?? false,
      isEnabled: map['isEnabled'] as bool? ?? true,
      isHidden: map['isHidden'] as bool? ?? false,
      duration: (map['duration'] as num?)?.toInt() ?? 0,
      playbackPosition: (map['playbackPosition'] as num?)?.toInt() ?? 0,
      lastWatched: (map['lastWatched'] as num?)?.toInt(),
      thumbnailUrl: map['thumbnailUrl'] as String?,
      sourceType: map['sourceType'] as String? ?? 'local',
      remoteId: map['remoteId'] as String?,
    );
  }

  String get formattedDuration {
    if (duration <= 0) return '';
    final minutes = duration ~/ 60000;
    final seconds = (duration % 60000) ~/ 1000;
    if (minutes > 0) return '${minutes}m ${seconds}s';
    return '${seconds}s';
  }
}

class SyncedCollection {
  final String name;
  final String type; // "REGULAR", "TV_SHOW", "SEASON", "FRANCHISE"
  final String? parentName;
  final int videoCount;
  final bool isEnabled;
  final bool isHidden;
  final String? thumbnailUrl;

  SyncedCollection({
    this.name = '',
    this.type = 'REGULAR',
    this.parentName,
    this.videoCount = 0,
    this.isEnabled = true,
    this.isHidden = false,
    this.thumbnailUrl,
  });

  factory SyncedCollection.fromMap(Map<dynamic, dynamic> map) {
    return SyncedCollection(
      name: map['name'] as String? ?? '',
      type: map['type'] as String? ?? 'REGULAR',
      parentName: map['parentName'] as String?,
      videoCount: (map['videoCount'] as num?)?.toInt() ?? 0,
      isEnabled: map['isEnabled'] as bool? ?? true,
      isHidden: map['isHidden'] as bool? ?? false,
      thumbnailUrl: map['thumbnailUrl'] as String?,
    );
  }

  bool get isTvShow => type == 'TV_SHOW';
  bool get isSeason => type == 'SEASON';
}

class SyncedChildDevice {
  final String deviceName;
  final String childUid;
  final String childName;
  final int lastSeen;
  final String appVersion;
  final bool isOnline;
  final String? currentlyWatching;
  final int todayWatchTime;

  SyncedChildDevice({
    this.deviceName = '',
    this.childUid = '',
    this.childName = '',
    this.lastSeen = 0,
    this.appVersion = '',
    this.isOnline = false,
    this.currentlyWatching,
    this.todayWatchTime = 0,
  });

  factory SyncedChildDevice.fromMap(Map<dynamic, dynamic> map) {
    return SyncedChildDevice(
      deviceName: map['deviceName'] as String? ?? '',
      childUid: map['childUid'] as String? ?? '',
      childName: map['childName'] as String? ?? '',
      lastSeen: (map['lastSeen'] as num?)?.toInt() ?? 0,
      appVersion: map['appVersion'] as String? ?? '',
      isOnline: map['isOnline'] as bool? ?? false,
      currentlyWatching: map['currentlyWatching'] as String?,
      todayWatchTime: (map['todayWatchTime'] as num?)?.toInt() ?? 0,
    );
  }

  String get displayName =>
      childName.isNotEmpty ? childName : deviceName.isNotEmpty ? deviceName : childUid;

  bool get isRecentlyOnline {
    if (isOnline) return true;
    return DateTime.now().millisecondsSinceEpoch - lastSeen < 5 * 60 * 1000;
  }
}

class LockCommand {
  final String? videoTitle;
  final String? collectionName;
  final bool isLocked;
  final String lockedBy;
  final int lockedAt;
  final int warningMinutes;
  final bool allowFinishCurrentVideo;

  LockCommand({
    this.videoTitle,
    this.collectionName,
    this.isLocked = false,
    this.lockedBy = '',
    this.lockedAt = 0,
    this.warningMinutes = 5,
    this.allowFinishCurrentVideo = false,
  });

  Map<String, dynamic> toMap() => {
        if (videoTitle != null) 'videoTitle': videoTitle,
        if (collectionName != null) 'collectionName': collectionName,
        'isLocked': isLocked,
        'lockedBy': lockedBy,
        'lockedAt': lockedAt,
        'warningMinutes': warningMinutes,
        'allowFinishCurrentVideo': allowFinishCurrentVideo,
      };
}

class AppLockCommand {
  final bool isLocked;
  final String lockedBy;
  final int lockedAt;
  final int? unlockAt;
  final String message;
  final int warningMinutes;
  final bool allowFinishCurrentVideo;

  AppLockCommand({
    this.isLocked = false,
    this.lockedBy = '',
    this.lockedAt = 0,
    this.unlockAt,
    this.message = '',
    this.warningMinutes = 5,
    this.allowFinishCurrentVideo = false,
  });

  factory AppLockCommand.fromMap(Map<dynamic, dynamic> map) {
    return AppLockCommand(
      isLocked: map['isLocked'] as bool? ?? false,
      lockedBy: map['lockedBy'] as String? ?? '',
      lockedAt: (map['lockedAt'] as num?)?.toInt() ?? 0,
      unlockAt: (map['unlockAt'] as num?)?.toInt(),
      message: map['message'] as String? ?? '',
      warningMinutes: (map['warningMinutes'] as num?)?.toInt() ?? 5,
      allowFinishCurrentVideo:
          map['allowFinishCurrentVideo'] as bool? ?? false,
    );
  }

  Map<String, dynamic> toMap() => {
        'isLocked': isLocked,
        'lockedBy': lockedBy,
        'lockedAt': lockedAt,
        if (unlockAt != null) 'unlockAt': unlockAt,
        'message': message,
        'warningMinutes': warningMinutes,
        'allowFinishCurrentVideo': allowFinishCurrentVideo,
      };
}

class ScheduleSettings {
  final bool enabled;
  final List<int> allowedDays;
  final int allowedStartHour;
  final int allowedStartMinute;
  final int allowedEndHour;
  final int allowedEndMinute;
  final String timezone;

  ScheduleSettings({
    this.enabled = false,
    this.allowedDays = const [0, 1, 2, 3, 4, 5, 6],
    this.allowedStartHour = 8,
    this.allowedStartMinute = 0,
    this.allowedEndHour = 20,
    this.allowedEndMinute = 0,
    this.timezone = 'UTC',
  });

  factory ScheduleSettings.fromMap(Map<dynamic, dynamic> map) {
    return ScheduleSettings(
      enabled: map['enabled'] as bool? ?? false,
      allowedDays: (map['allowedDays'] as List<dynamic>?)
              ?.map((e) => (e as num).toInt())
              .toList() ??
          [0, 1, 2, 3, 4, 5, 6],
      allowedStartHour: (map['allowedStartHour'] as num?)?.toInt() ?? 8,
      allowedStartMinute: (map['allowedStartMinute'] as num?)?.toInt() ?? 0,
      allowedEndHour: (map['allowedEndHour'] as num?)?.toInt() ?? 20,
      allowedEndMinute: (map['allowedEndMinute'] as num?)?.toInt() ?? 0,
      timezone: map['timezone'] as String? ?? 'UTC',
    );
  }

  Map<String, dynamic> toMap() => {
        'enabled': enabled,
        'allowedDays': allowedDays,
        'allowedStartHour': allowedStartHour,
        'allowedStartMinute': allowedStartMinute,
        'allowedEndHour': allowedEndHour,
        'allowedEndMinute': allowedEndMinute,
        'timezone': timezone,
      };
}

class TimeLimitSettings {
  final bool enabled;
  final int dailyLimitMinutes;
  final int weekendLimitMinutes;
  final int warningAtMinutesRemaining;

  TimeLimitSettings({
    this.enabled = false,
    this.dailyLimitMinutes = 120,
    this.weekendLimitMinutes = 180,
    this.warningAtMinutesRemaining = 10,
  });

  factory TimeLimitSettings.fromMap(Map<dynamic, dynamic> map) {
    return TimeLimitSettings(
      enabled: map['enabled'] as bool? ?? false,
      dailyLimitMinutes: (map['dailyLimitMinutes'] as num?)?.toInt() ?? 120,
      weekendLimitMinutes:
          (map['weekendLimitMinutes'] as num?)?.toInt() ?? 180,
      warningAtMinutesRemaining:
          (map['warningAtMinutesRemaining'] as num?)?.toInt() ?? 10,
    );
  }

  Map<String, dynamic> toMap() => {
        'enabled': enabled,
        'dailyLimitMinutes': dailyLimitMinutes,
        'weekendLimitMinutes': weekendLimitMinutes,
        'warningAtMinutesRemaining': warningAtMinutesRemaining,
      };
}

class ViewingMetrics {
  final int todayWatchTimeMinutes;
  final int weekWatchTimeMinutes;
  final int totalWatchTimeMinutes;
  final String? lastWatchDate;
  final int videosWatchedToday;
  final String? mostWatchedVideo;
  final String? lastVideoWatched;
  final int? lastWatchedAt;

  ViewingMetrics({
    this.todayWatchTimeMinutes = 0,
    this.weekWatchTimeMinutes = 0,
    this.totalWatchTimeMinutes = 0,
    this.lastWatchDate,
    this.videosWatchedToday = 0,
    this.mostWatchedVideo,
    this.lastVideoWatched,
    this.lastWatchedAt,
  });

  factory ViewingMetrics.fromMap(Map<dynamic, dynamic> map) {
    return ViewingMetrics(
      todayWatchTimeMinutes:
          (map['todayWatchTimeMinutes'] as num?)?.toInt() ?? 0,
      weekWatchTimeMinutes:
          (map['weekWatchTimeMinutes'] as num?)?.toInt() ?? 0,
      totalWatchTimeMinutes:
          (map['totalWatchTimeMinutes'] as num?)?.toInt() ?? 0,
      lastWatchDate: map['lastWatchDate'] as String?,
      videosWatchedToday:
          (map['videosWatchedToday'] as num?)?.toInt() ?? 0,
      mostWatchedVideo: map['mostWatchedVideo'] as String?,
      lastVideoWatched: map['lastVideoWatched'] as String?,
      lastWatchedAt: (map['lastWatchedAt'] as num?)?.toInt(),
    );
  }

  String formatWatchTime(int minutes) {
    if (minutes >= 60) {
      return '${minutes ~/ 60}h ${minutes % 60}m';
    }
    return '${minutes}m';
  }
}

class Family {
  final String familyId;
  final int createdAt;
  final String createdBy;
  final String familyName;

  Family({
    required this.familyId,
    this.createdAt = 0,
    this.createdBy = '',
    this.familyName = '',
  });

  factory Family.fromMap(String id, Map<dynamic, dynamic> map) {
    return Family(
      familyId: id,
      createdAt: (map['createdAt'] as num?)?.toInt() ?? 0,
      createdBy: map['createdBy'] as String? ?? '',
      familyName: map['familyName'] as String? ?? '',
    );
  }
}

class PairingCode {
  final String code;
  final String familyId;
  final int createdAt;
  final int expiresAt;
  final String createdBy;
  final bool used;
  final String? usedBy;

  PairingCode({
    required this.code,
    required this.familyId,
    this.createdAt = 0,
    this.expiresAt = 0,
    this.createdBy = '',
    this.used = false,
    this.usedBy,
  });

  factory PairingCode.fromMap(String code, Map<dynamic, dynamic> map) {
    return PairingCode(
      code: code,
      familyId: map['familyId'] as String? ?? '',
      createdAt: (map['createdAt'] as num?)?.toInt() ?? 0,
      expiresAt: (map['expiresAt'] as num?)?.toInt() ?? 0,
      createdBy: map['createdBy'] as String? ?? '',
      used: map['used'] as bool? ?? false,
      usedBy: map['usedBy'] as String?,
    );
  }

  bool get isExpired =>
      DateTime.now().millisecondsSinceEpoch > expiresAt || used;

  Map<String, dynamic> toMap() => {
        'code': code,
        'familyId': familyId,
        'createdAt': createdAt,
        'expiresAt': expiresAt,
        'createdBy': createdBy,
        'used': used,
        if (usedBy != null) 'usedBy': usedBy,
      };
}

/// Firebase path constants
class FirebasePaths {
  static String familyPath(String familyId) => 'families/$familyId';
  static String childPath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid';
  static String childVideosPath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid/videos';
  static String childCollectionsPath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid/collections';
  static String childDeviceInfoPath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid/deviceInfo';
  static String childLocksPath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid/locks';
  static String childAppLockPath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid/appLock';
  static String childSchedulePath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid/settings/schedule';
  static String childTimeLimitsPath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid/settings/timeLimits';
  static String childMetricsPath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid/metrics';
  static String childSyncRequestPath(String familyId, String childUid) =>
      'families/$familyId/children/$childUid/syncRequest';
  static String oneDriveConfigPath(String familyId) =>
      'families/$familyId/oneDriveConfig';
  static String pairingCodePath(String code) => 'pairingCodes/$code';
}
