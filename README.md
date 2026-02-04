# Kids Movies

A kid-friendly Android application for managing and playing videos stored on the device. Designed with a colorful, easy-to-use interface perfect for children.

## Features

### Video Library
- **Shelf-style Display**: Videos are displayed in a colorful grid layout similar to a library shelf
- **Auto-scanning**: Automatically finds all videos on the device
- **Configurable Folders**: Parents can configure which folders to scan for videos
- **Subfolder Support**: Option to include or exclude subfolders when scanning

### Organization
- **All Movies**: View all available videos in one place
- **Favourites**: Kids can mark videos as favourites for quick access
- **Recently Watched**: Easy access to recently played videos
- **Tags**: Videos can be tagged for future parental management

### Customization
- **Color Schemes**: Kids can choose their favorite color theme (Blue, Green, Purple, Orange, Pink, Red)
- **Grid Size**: Adjustable grid columns (2-6) for different screen sizes
- **Custom Thumbnails**: Thumbnails are auto-generated but can be customized

### Video Player
- Built-in video player using Android MediaPlayer
- Simple, kid-friendly controls
- Play/Pause, Rewind, Forward
- Progress bar with time display
- Auto-hide controls during playback

### Parental Features
- **Folder Management**: Control which folders are scanned for videos
- **Video Tags**: Tag system for future parental control integration
- **App Enable/Disable**: Online status check for remote app management
- **Schedule Support**: Time-based access control (placeholder)
- **Export/Import**: Backup and restore settings and video database

### Data Management
- **Export**: Export all settings, videos, tags, and preferences to JSON
- **Import**: Import backup files with option to merge or replace existing data

### Future Features (Placeholders)
- **OneDrive Integration**: Stream videos from shared OneDrive folders
- **Parental Control App**: Remote management from parent's device

## Technical Details

### Requirements
- Android 8.0 (API 26) or higher
- Storage permission for video access

### Architecture
- **Language**: Kotlin
- **UI**: Traditional XML layouts with ViewBinding
- **Database**: Room (SQLite)
- **Video Player**: Android MediaPlayer
- **Image Loading**: Glide
- **Async**: Kotlin Coroutines + Flow

### Project Structure
```
app/
├── data/
│   ├── database/
│   │   ├── entities/    # Room entities (Video, Tag, ScanFolder, etc.)
│   │   └── dao/         # Data Access Objects
│   └── repository/      # Repository classes
├── services/
│   ├── VideoScannerService.kt      # Background video scanning
│   └── ParentalControlService.kt   # Online status checking
├── ui/
│   ├── activities/      # All activities
│   ├── fragments/       # Fragments for setup and main screens
│   └── adapters/        # RecyclerView adapters
└── utils/
    ├── Constants.kt             # App constants and color schemes
    ├── FileUtils.kt             # File operations
    ├── ThumbnailUtils.kt        # Thumbnail generation
    └── DatabaseExportImport.kt  # Export/Import functionality
```

## Setup

1. Clone the repository
2. Open in Android Studio
3. Build and run on an Android device or emulator

## First Launch

1. Grant storage permission when prompted
2. Enter your name (optional)
3. Choose your favorite color
4. Wait for video scanning to complete
5. Start watching!

## Parental Control Integration

The app includes infrastructure for parental control:

### Online Status Check
The app can check an online endpoint to determine if it should be enabled:
- Endpoint format: `GET /api/device/{deviceId}/status`
- Response: `{ "enabled": true/false }`
- Falls back to last known status when offline

### Tag System
Videos can be tagged, allowing future integration with a parental control app to:
- Enable/disable specific videos
- Filter content by tags
- Manage viewing permissions

## License

MIT License
