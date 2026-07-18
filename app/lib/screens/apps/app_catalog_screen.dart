import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';

class AppCatalogScreen extends StatefulWidget {
  const AppCatalogScreen({super.key});

  @override
  State<AppCatalogScreen> createState() => _AppCatalogScreenState();
}

class _AppCatalogScreenState extends State<AppCatalogScreen> {
  static const _apps = [
    _OptionalApp(
      id: 'firefox',
      name: 'Firefox',
      description: 'Full desktop web browser.',
      icon: Icons.public_rounded,
      color: Color(0xFFFF7139),
    ),
    _OptionalApp(
      id: 'code_oss',
      name: 'Code OSS',
      description: 'Desktop source-code editor. This is a large download.',
      icon: Icons.code_rounded,
      color: Color(0xFF23A8F2),
    ),
    _OptionalApp(
      id: 'nodejs',
      name: 'Node.js + npm',
      description: 'JavaScript runtime and package manager.',
      icon: Icons.javascript_rounded,
      color: Color(0xFF68A063),
    ),
    _OptionalApp(
      id: 'imagemagick',
      name: 'ImageMagick',
      description: 'Command-line image conversion and processing tools.',
      icon: Icons.image_rounded,
      color: DroidTheme.primaryLight,
    ),
  ];

  static const _proot = _OptionalApp(
    id: 'proot_debian',
    name: 'Debian (PRoot)',
    description: 'Minimal PRoot base system. No applications are included.',
    icon: Icons.inventory_2_rounded,
    color: Color(0xFFD70A53),
  );

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AppState>().refreshOptionalApps();
    });
  }

  Future<void> _install(_OptionalApp app) async {
    final state = context.read<AppState>();
    final ok = await state.installOptionalApp(app.id);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          ok
              ? '${app.name} installed'
              : '${app.name} installation failed. See the log below.',
        ),
        backgroundColor: ok ? DroidTheme.success : DroidTheme.error,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    final apps = state.hasRoot ? _apps : [..._apps, _proot];
    return Scaffold(
      appBar: AppBar(title: const Text('Add applications')),
      body: Container(
        decoration: const BoxDecoration(
          gradient: DroidTheme.backgroundGradient,
        ),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
          children: [
            Text('Desktop Essentials is ready', style: DroidTheme.headingMd),
            const SizedBox(height: 6),
            Text(
              'Install only what you need. Each application is independent and can be safely retried.',
              style: DroidTheme.bodyMd,
            ),
            const SizedBox(height: 20),
            for (final app in apps) ...[
              _buildAppCard(state, app),
              const SizedBox(height: 10),
            ],
            if (state.installingOptionalApp != null ||
                state.optionalInstallLog.isNotEmpty) ...[
              const SizedBox(height: 12),
              _buildInstallPanel(state),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildAppCard(AppState state, _OptionalApp app) {
    final installed = state.optionalApps[app.id] == true;
    final installing = state.installingOptionalApp == app.id;
    final busy = state.installingOptionalApp != null;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: DroidTheme.cardBg,
        borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
        border: Border.all(
          color: installed
              ? DroidTheme.success.withValues(alpha: 0.45)
              : DroidTheme.surfaceBorder,
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: app.color.withValues(alpha: 0.14),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(app.icon, color: app.color),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(app.name, style: DroidTheme.headingSm),
                const SizedBox(height: 3),
                Text(app.description, style: DroidTheme.bodySm),
              ],
            ),
          ),
          const SizedBox(width: 10),
          if (installed)
            const Icon(Icons.check_circle_rounded, color: DroidTheme.success)
          else if (installing)
            const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(strokeWidth: 2.5),
            )
          else
            FilledButton(
              onPressed: busy ? null : () => _install(app),
              child: const Text('Install'),
            ),
        ],
      ),
    );
  }

  Widget _buildInstallPanel(AppState state) {
    final cleanLog = state.optionalInstallLog.replaceAll(
      RegExp(r'\x1B\[[0-?]*[ -/]*[@-~]'),
      '',
    );
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFF080D18),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: DroidTheme.surfaceBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  state.optionalInstallStatus.isEmpty
                      ? 'Package log'
                      : state.optionalInstallStatus,
                  style: DroidTheme.headingSm,
                ),
              ),
              Text(
                '${(state.optionalInstallProgress * 100).round()}%',
                style: DroidTheme.monoSm,
              ),
            ],
          ),
          const SizedBox(height: 10),
          LinearProgressIndicator(
            value: state.installingOptionalApp == null
                ? null
                : state.optionalInstallProgress,
          ),
          if (cleanLog.isNotEmpty) ...[
            const SizedBox(height: 12),
            SizedBox(
              height: 180,
              child: SingleChildScrollView(
                reverse: true,
                child: SelectableText(
                  cleanLog,
                  style: DroidTheme.monoSm.copyWith(height: 1.35),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _OptionalApp {
  final String id;
  final String name;
  final String description;
  final IconData icon;
  final Color color;

  const _OptionalApp({
    required this.id,
    required this.name,
    required this.description,
    required this.icon,
    required this.color,
  });
}
