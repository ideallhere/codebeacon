import 'package:flutter/material.dart';
import 'home_page.dart';

void main() {
  runApp(const CodeBeaconApp());
}

class CodeBeaconApp extends StatelessWidget {
  const CodeBeaconApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '取件码信标',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFFF6A00),
          brightness: Brightness.light,
        ),
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFFF6A00),
          brightness: Brightness.dark,
        ),
      ),
      themeMode: ThemeMode.system,
      home: const HomePage(),
    );
  }
}