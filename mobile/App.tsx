import React, { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, SafeAreaView, ScrollView, StatusBar, StyleSheet, Text, View } from 'react-native';
import { initializeDatabase } from './src/database';
import { PaletteName, palettes, resolveTheme, Theme } from './src/theme';

type ModuleName = 'Home' | 'Schedule' | 'Tasks' | 'Budget' | 'Notes' | 'Files' | 'Settings';
const modules: { key: ModuleName; symbol: string }[] = [
  { key: 'Home', symbol: '⌂' }, { key: 'Schedule', symbol: '▦' }, { key: 'Tasks', symbol: '✓' },
  { key: 'Budget', symbol: '₱' }, { key: 'Notes', symbol: '▤' }, { key: 'Files', symbol: '▣' }, { key: 'Settings', symbol: '⚙' }
];

function Card({ theme, title, children }: { theme: Theme; title: string; children: React.ReactNode }) {
  return <View style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}><Text style={[styles.cardTitle, { color: theme.text }]}>{title}</Text>{children}</View>;
}

function HomeScreen({ theme, go }: { theme: Theme; go: (name: ModuleName) => void }) {
  return <>
    <View style={[styles.hero, { backgroundColor: theme.primary }]}>
      <Text style={styles.heroEyebrow}>PERSONAL STUDENT PLANNER</Text>
      <Text style={styles.heroTitle}>Good day.</Text>
      <Text style={styles.heroSub}>Your classes, tasks, notes, and plans in one place.</Text>
      <View style={styles.heroStats}>
        {[['0', 'Classes today'], ['0', 'Tasks due'], ['0', 'Subjects']].map(([value, label]) => <View key={label} style={styles.stat}><Text style={styles.statValue}>{value}</Text><Text style={styles.statLabel}>{label}</Text></View>)}
      </View>
    </View>
    <Card theme={theme} title="Today's schedule"><Text style={[styles.muted, { color: theme.muted }]}>Your classes will appear here.</Text><TextButton theme={theme} label="Open schedule" onPress={() => go('Schedule')} /></Card>
    <Card theme={theme} title="Tasks"><Text style={[styles.muted, { color: theme.muted }]}>Keep track of deadlines and checklists.</Text><TextButton theme={theme} label="View tasks" onPress={() => go('Tasks')} /></Card>
    <Card theme={theme} title="Quick access"><View style={styles.quickGrid}>{(['Schedule', 'Notes', 'Budget', 'Files'] as ModuleName[]).map(k => <Pressable key={k} onPress={() => go(k)} style={[styles.quick, { backgroundColor: theme.surfaceAlt }]}><Text style={{ color: theme.primary, fontWeight: '700' }}>{k}</Text></Pressable>)}</View></Card>
  </>;
}

function TextButton({ theme, label, onPress }: { theme: Theme; label: string; onPress: () => void }) {
  return <Pressable onPress={onPress} style={({ pressed }) => [styles.textButton, { opacity: pressed ? 0.65 : 1 }]}><Text style={{ color: theme.primary, fontWeight: '700' }}>{label}  ›</Text></Pressable>;
}

function ModuleScreen({ name, theme, onTheme }: { name: ModuleName; theme: Theme; onTheme: (name: PaletteName) => void }) {
  const copy: Record<Exclude<ModuleName, 'Home' | 'Settings'>, string> = {
    Schedule: 'Manage your weekly timetable, Lecture and Lab sessions, rooms, and class details.',
    Tasks: 'Organize deadlines, checklists, reminders, and subject-linked work.',
    Budget: 'Track expenses, spending categories, and savings goals.',
    Notes: 'Keep dated notes, subject notes, and favorites in one offline workspace.',
    Files: 'Store lecture materials by subject and open supported files offline.'
  };
  if (name === 'Settings') return <Card theme={theme} title="Appearance & design">
    <Text style={[styles.muted, { color: theme.muted }]}>Choose a CampusOS color combination.</Text>
    <View style={styles.paletteGrid}>{(Object.keys(palettes) as PaletteName[]).map(k => <Pressable key={k} onPress={() => onTheme(k)} style={[styles.palette, { borderColor: theme.border }]}><View style={[styles.swatch, { backgroundColor: palettes[k].primary }]} /><Text style={{ color: theme.text, fontSize: 12 }}>{palettes[k].label}</Text></Pressable>)}</View>
  </Card>;
  return <Card theme={theme} title={name}><Text style={[styles.muted, { color: theme.muted }]}>{copy[name as keyof typeof copy]}</Text><View style={[styles.empty, { backgroundColor: theme.surfaceAlt }]}><Text style={{ color: theme.text, fontWeight: '700' }}>Offline {name.toLowerCase()} workspace</Text><Text style={{ color: theme.muted, textAlign: 'center' }}>This module is being migrated from the original CampusOS implementation.</Text></View></Card>;
}

export default function App() {
  const [ready, setReady] = useState(false);
  const [active, setActive] = useState<ModuleName>('Home');
  const [palette, setPalette] = useState<PaletteName>('forest');
  const [dark, setDark] = useState(false);
  const theme = useMemo(() => resolveTheme(palette, dark ? 'dark' : 'light'), [palette, dark]);
  useEffect(() => { initializeDatabase().then(() => setReady(true)).catch(() => setReady(true)); }, []);
  if (!ready) return <SafeAreaView style={[styles.loading, { backgroundColor: theme.background }]}><ActivityIndicator color={theme.primary} /><Text style={{ color: theme.text }}>Preparing your offline planner…</Text></SafeAreaView>;
  return <SafeAreaView style={[styles.root, { backgroundColor: theme.background }]}>
    <StatusBar barStyle={dark ? 'light-content' : 'dark-content'} />
    <View style={styles.topbar}><View><Text style={[styles.brand, { color: theme.primary }]}>CampusOS</Text><Text style={[styles.brandSub, { color: theme.muted }]}>PERSONAL PLANNER</Text></View><View style={styles.topActions}><Pressable onPress={() => setDark(v => !v)} style={[styles.themeToggle, { backgroundColor: theme.surfaceAlt }]}><Text style={{ color: theme.text }}>{dark ? '☀' : '☾'}</Text></Pressable></View></View>
    <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
      {active === 'Home' ? <HomeScreen theme={theme} go={setActive} /> : <ModuleScreen name={active} theme={theme} onTheme={setPalette} />}
    </ScrollView>
    <View style={[styles.nav, { backgroundColor: theme.surface, borderTopColor: theme.border }]}>
      {modules.map(m => <Pressable key={m.key} onPress={() => setActive(m.key)} style={[styles.navItem, active === m.key && { backgroundColor: theme.surfaceAlt }]}><Text style={{ color: active === m.key ? theme.primary : theme.muted, fontSize: 18, fontWeight: '700' }}>{m.symbol}</Text><Text numberOfLines={1} style={{ color: active === m.key ? theme.primary : theme.muted, fontSize: 10, fontWeight: active === m.key ? '700' : '500' }}>{m.key}</Text></Pressable>)}
    </View>
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  root: { flex: 1 }, loading: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },
  topbar: { minHeight: 64, paddingHorizontal: 18, paddingVertical: 10, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  brand: { fontSize: 22, fontWeight: '800', letterSpacing: -0.5 }, brandSub: { fontSize: 9, letterSpacing: 2, fontWeight: '700' },
  topActions: { flexDirection: 'row', gap: 8 }, themeToggle: { width: 38, height: 38, borderRadius: 19, alignItems: 'center', justifyContent: 'center' },
  content: { paddingHorizontal: 16, paddingBottom: 28, gap: 14 },
  hero: { borderRadius: 24, padding: 20, marginBottom: 2 },
  heroEyebrow: { color: '#D8F0DF', fontSize: 10, letterSpacing: 1.6, fontWeight: '700' },
  heroTitle: { color: '#FFFFFF', fontSize: 30, fontWeight: '800', marginTop: 8 },
  heroSub: { color: '#E4F4E8', fontSize: 13, marginTop: 4, lineHeight: 19 },
  heroStats: { flexDirection: 'row', marginTop: 20, gap: 8 }, stat: { flex: 1, backgroundColor: '#FFFFFF20', borderRadius: 14, padding: 10 },
  statValue: { color: '#FFFFFF', fontSize: 20, fontWeight: '800' }, statLabel: { color: '#E4F4E8', fontSize: 10, marginTop: 3 },
  card: { borderRadius: 20, borderWidth: 1, padding: 16, gap: 10 }, cardTitle: { fontSize: 16, fontWeight: '800' },
  muted: { fontSize: 13, lineHeight: 20 }, textButton: { alignSelf: 'flex-start', paddingVertical: 6 },
  quickGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 }, quick: { width: '48%', borderRadius: 12, padding: 14 },
  empty: { minHeight: 150, borderRadius: 16, padding: 18, alignItems: 'center', justifyContent: 'center', gap: 8, marginTop: 6 },
  paletteGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 }, palette: { width: '30%', borderWidth: 1, borderRadius: 12, padding: 8, gap: 6 },
  swatch: { height: 28, borderRadius: 8 }, nav: { flexDirection: 'row', justifyContent: 'space-around', paddingHorizontal: 4, paddingTop: 8, paddingBottom: 5, borderTopWidth: 1 },
  navItem: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 3, paddingVertical: 6, borderRadius: 12 }
});
