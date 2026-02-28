import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'services/family_manager.dart';
import 'screens/home/home_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();

  runApp(const KidsMoviesParentApp());
}

class KidsMoviesParentApp extends StatelessWidget {
  const KidsMoviesParentApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => FamilyManager(),
      child: MaterialApp(
        title: 'KidsMovies Parent',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          brightness: Brightness.dark,
          colorSchemeSeed: const Color(0xFF6C63FF),
          scaffoldBackgroundColor: const Color(0xFF1A1A2E),
          cardColor: const Color(0xFF16213E),
          appBarTheme: const AppBarTheme(
            backgroundColor: Color(0xFF1A1A2E),
            elevation: 0,
          ),
          floatingActionButtonTheme: const FloatingActionButtonThemeData(
            backgroundColor: Color(0xFF6C63FF),
            foregroundColor: Colors.white,
          ),
          switchTheme: SwitchThemeData(
            thumbColor: WidgetStateProperty.resolveWith((states) {
              if (states.contains(WidgetState.selected)) {
                return const Color(0xFF6C63FF);
              }
              return Colors.grey;
            }),
            trackColor: WidgetStateProperty.resolveWith((states) {
              if (states.contains(WidgetState.selected)) {
                return const Color(0xFF6C63FF).withAlpha(128);
              }
              return Colors.grey.withAlpha(77);
            }),
          ),
        ),
        home: const HomeScreen(),
      ),
    );
  }
}
