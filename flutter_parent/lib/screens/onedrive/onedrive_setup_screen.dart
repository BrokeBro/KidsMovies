import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:dio_cookie_manager/dio_cookie_manager.dart';
import 'package:cookie_jar/cookie_jar.dart';
import 'package:firebase_database/firebase_database.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../services/family_manager.dart';

/// OneDrive setup screen for connecting a shared folder via public "Anyone" link.
/// Mirrors the Android parent app's OneDriveSetupActivity.
class OneDriveSetupScreen extends StatefulWidget {
  const OneDriveSetupScreen({super.key});

  @override
  State<OneDriveSetupScreen> createState() => _OneDriveSetupScreenState();
}

class _OneDriveSetupScreenState extends State<OneDriveSetupScreen> {
  final _linkController = TextEditingController();
  bool _isConnected = false;
  bool _isLoading = false;
  String? _connectedPath;
  String? _error;

  late final Dio _dio;
  late final CookieJar _cookieJar;

  @override
  void initState() {
    super.initState();
    _cookieJar = CookieJar();
    _dio = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 30),
      receiveTimeout: const Duration(seconds: 30),
      followRedirects: true,
      headers: {
        'User-Agent':
            'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Safari/537.36',
        'Accept': 'application/json',
      },
    ))
      ..interceptors.add(CookieManager(_cookieJar));
    _loadExistingConfig();
  }

  @override
  void dispose() {
    _linkController.dispose();
    _dio.close();
    super.dispose();
  }

  Future<void> _loadExistingConfig() async {
    final prefs = await SharedPreferences.getInstance();
    final isConfigured = prefs.getBool('onedrive_is_configured') ?? false;
    final path = prefs.getString('onedrive_folder_path');
    if (isConfigured && path != null) {
      setState(() {
        _isConnected = true;
        _connectedPath = path;
      });
    }
  }

  String _encodeSharingUrl(String url) {
    // Strip query parameters
    final cleanUrl = url.split('?').first;
    final encoded = base64Url.encode(utf8.encode(cleanUrl));
    return 'u!${encoded.replaceAll('=', '')}';
  }

  Future<void> _establishSession(String shareUrl) async {
    _cookieJar.deleteAll();
    try {
      await _dio.get(
        shareUrl,
        options: Options(
          headers: {
            'Accept':
                'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
          },
          followRedirects: true,
          validateStatus: (status) => status != null && status < 400,
        ),
      );
    } catch (_) {
      // Session establishment may redirect; cookies are saved by CookieManager
    }
  }

  Future<void> _connectLink() async {
    final url = _linkController.text.trim();
    if (url.isEmpty) return;

    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final encoded = _encodeSharingUrl(url);
      final host = Uri.parse(url).host;
      final spBase = 'https://$host/_api/v2.0';

      // Establish session for Business OneDrive
      await _establishSession(url);

      // Try SharePoint session API first
      Map<String, dynamic>? driveItem;
      try {
        final response = await _dio.get(
          '$spBase/shares/$encoded/driveItem',
          options: Options(headers: {'Accept': 'application/json'}),
        );
        driveItem = response.data as Map<String, dynamic>;
      } catch (_) {
        // Fall back to Graph API for personal OneDrive
        final response = await _dio.get(
          'https://graph.microsoft.com/v1.0/shares/$encoded/driveItem',
          options: Options(headers: {'Accept': 'application/json'}),
        );
        driveItem = response.data as Map<String, dynamic>;
      }

      final folderId = driveItem['id'] as String;
      final folderName = driveItem['name'] as String;
      final driveId =
          (driveItem['parentReference'] as Map?)?['driveId'] as String? ?? '';

      // Save config locally
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('onedrive_is_configured', true);
      await prefs.setString('onedrive_access_mode', 'public_link');
      await prefs.setString('onedrive_share_url', url);
      await prefs.setString('onedrive_share_encoded_id', encoded);
      await prefs.setString('onedrive_drive_id', driveId);
      await prefs.setString('onedrive_folder_id', folderId);
      await prefs.setString('onedrive_folder_path', folderName);

      // Save to Firebase so child app can sync
      final manager = context.read<FamilyManager>();
      final family = manager.family;
      if (family != null) {
        await FirebaseDatabase.instance
            .ref('families/${family.familyId}/oneDriveConfig')
            .set({
          'driveId': driveId,
          'folderId': folderId,
          'folderPath': folderName,
          'accessMode': 'public_link',
          'shareEncodedId': encoded,
          'shareUrl': url,
          'configuredAt': ServerValue.timestamp,
        });
      }

      setState(() {
        _isConnected = true;
        _connectedPath = folderName;
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _error = 'Could not resolve link. Check the URL and try again.';
        _isLoading = false;
      });
    }
  }

  Future<void> _disconnect() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('onedrive_is_configured');
    await prefs.remove('onedrive_access_mode');
    await prefs.remove('onedrive_share_url');
    await prefs.remove('onedrive_share_encoded_id');
    await prefs.remove('onedrive_drive_id');
    await prefs.remove('onedrive_folder_id');
    await prefs.remove('onedrive_folder_path');

    final manager = context.read<FamilyManager>();
    final family = manager.family;
    if (family != null) {
      await FirebaseDatabase.instance
          .ref('families/${family.familyId}/oneDriveConfig')
          .remove();
    }

    setState(() {
      _isConnected = false;
      _connectedPath = null;
      _linkController.clear();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('OneDrive Setup')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: _isConnected ? _buildConnectedState() : _buildSetupState(),
      ),
    );
  }

  Widget _buildConnectedState() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Row(
              children: [
                const Icon(Icons.cloud_done,
                    color: Colors.green, size: 40),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Connected',
                          style: TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.bold,
                              color: Colors.green)),
                      const SizedBox(height: 4),
                      Text(_connectedPath ?? 'OneDrive folder',
                          style: const TextStyle(color: Colors.white70)),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 24),
        SizedBox(
          width: double.infinity,
          child: OutlinedButton.icon(
            onPressed: _disconnect,
            icon: const Icon(Icons.link_off, color: Colors.red),
            label: const Text('Disconnect',
                style: TextStyle(color: Colors.red)),
            style: OutlinedButton.styleFrom(
              side: const BorderSide(color: Colors.red),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSetupState() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Icon(Icons.cloud_outlined,
            size: 48, color: Color(0xFF6C63FF)),
        const SizedBox(height: 16),
        const Text('Connect OneDrive',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        const Text(
          'Paste a OneDrive "Anyone with the link" sharing URL to connect a video folder.',
          style: TextStyle(color: Colors.white70),
        ),
        const SizedBox(height: 24),
        TextField(
          controller: _linkController,
          decoration: InputDecoration(
            hintText: 'https://...-my.sharepoint.com/...',
            labelText: 'OneDrive sharing link',
            border: const OutlineInputBorder(),
            errorText: _error,
            suffixIcon: _isLoading
                ? const Padding(
                    padding: EdgeInsets.all(12),
                    child: SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  )
                : null,
          ),
          enabled: !_isLoading,
          onSubmitted: (_) => _connectLink(),
        ),
        const SizedBox(height: 16),
        SizedBox(
          width: double.infinity,
          child: ElevatedButton.icon(
            onPressed: _isLoading ? null : _connectLink,
            icon: const Icon(Icons.link),
            label: const Text('Connect'),
          ),
        ),
      ],
    );
  }
}
