import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:share_plus/share_plus.dart';

import '../../models/sync_models.dart';
import '../../services/family_manager.dart';

class PairingScreen extends StatefulWidget {
  final String familyId;

  const PairingScreen({super.key, required this.familyId});

  @override
  State<PairingScreen> createState() => _PairingScreenState();
}

class _PairingScreenState extends State<PairingScreen> {
  PairingCode? _code;
  bool _isLoading = true;
  Timer? _countdownTimer;
  int _secondsRemaining = 0;
  StreamSubscription? _usedSubscription;

  @override
  void initState() {
    super.initState();
    _generateCode();
  }

  @override
  void dispose() {
    _countdownTimer?.cancel();
    _usedSubscription?.cancel();
    super.dispose();
  }

  Future<void> _generateCode() async {
    setState(() => _isLoading = true);
    _countdownTimer?.cancel();
    _usedSubscription?.cancel();

    final manager = context.read<FamilyManager>();
    final code = await manager.generatePairingCode(widget.familyId);

    if (!mounted) return;

    setState(() {
      _code = code;
      _isLoading = false;
    });

    if (code != null) {
      _startCountdown(code);
      _watchForUsed(code);
    }
  }

  void _startCountdown(PairingCode code) {
    final remaining =
        (code.expiresAt - DateTime.now().millisecondsSinceEpoch) ~/ 1000;
    setState(() => _secondsRemaining = remaining.clamp(0, 900));

    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {
        _secondsRemaining--;
        if (_secondsRemaining <= 0) {
          _countdownTimer?.cancel();
        }
      });
    });
  }

  void _watchForUsed(PairingCode code) {
    final manager = context.read<FamilyManager>();
    _usedSubscription = manager.watchPairingCodeUsed(code.code).listen((used) {
      if (used && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Child device paired successfully!'),
            backgroundColor: Colors.green,
          ),
        );
        Navigator.pop(context);
      }
    });
  }

  void _shareCode() {
    if (_code == null) return;
    Share.share(
      'Enter this code in the KidsMovies app to pair your device: ${_code!.code}',
      subject: 'KidsMovies Pairing Code',
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Add Child Device')),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _code == null
              ? _buildError()
              : _buildCodeDisplay(),
    );
  }

  Widget _buildError() {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.error_outline, size: 64, color: Colors.red),
          const SizedBox(height: 16),
          const Text('Failed to generate pairing code'),
          const SizedBox(height: 16),
          ElevatedButton(
            onPressed: _generateCode,
            child: const Text('Try Again'),
          ),
        ],
      ),
    );
  }

  Widget _buildCodeDisplay() {
    final isExpired = _secondsRemaining <= 0;
    final minutes = _secondsRemaining ~/ 60;
    final seconds = _secondsRemaining % 60;

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.link, size: 64, color: Color(0xFF6C63FF)),
            const SizedBox(height: 24),
            const Text(
              'Enter this code in the\nKidsMovies app on your child\'s device',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 16, color: Colors.white70),
            ),
            const SizedBox(height: 32),
            // Code display
            AnimatedOpacity(
              opacity: isExpired ? 0.3 : 1.0,
              duration: const Duration(milliseconds: 300),
              child: Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 32, vertical: 20),
                decoration: BoxDecoration(
                  color: const Color(0xFF16213E),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: const Color(0xFF6C63FF).withAlpha(128),
                    width: 2,
                  ),
                ),
                child: Text(
                  _code!.code.split('').join(' '),
                  style: const TextStyle(
                    fontSize: 40,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 8,
                    color: Colors.white,
                    fontFamily: 'monospace',
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),
            // Timer
            Text(
              isExpired
                  ? 'Code expired'
                  : 'Expires in ${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}',
              style: TextStyle(
                color: isExpired ? Colors.red : Colors.white54,
                fontSize: 14,
              ),
            ),
            const SizedBox(height: 32),
            // Buttons
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                OutlinedButton.icon(
                  onPressed: _generateCode,
                  icon: const Icon(Icons.refresh),
                  label: const Text('New Code'),
                ),
                const SizedBox(width: 16),
                if (!isExpired)
                  ElevatedButton.icon(
                    onPressed: _shareCode,
                    icon: const Icon(Icons.share),
                    label: const Text('Share'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
