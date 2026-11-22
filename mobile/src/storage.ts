import * as FileSystem from 'expo-file-system/legacy';

const directory = (): string => {
  const root = FileSystem.documentDirectory;
  if (!root) throw new Error('App document storage is unavailable.');
  return root.endsWith('/') ? root + 'lecture-files/' : root + '/lecture-files/';
};

const safeName = (name: string): string => {
  const clean = name.replace(/[\\/:*?"<>|\u0000-\u001F]/g, '_').replace(/^\.+/, '').trim();
  return clean || 'document';
};

export async function persistPickedFile(uri: string, name: string, id: string): Promise<string> {
  const folder = directory();
  await FileSystem.makeDirectoryAsync(folder, { intermediates: true });
  const destination = folder + id.replace(/[^a-zA-Z0-9_-]/g, '_') + '-' + safeName(name);
  await FileSystem.copyAsync({ from: uri, to: destination });
  const info = await FileSystem.getInfoAsync(destination);
  if (!info.exists || !info.size) throw new Error('The selected file could not be copied into app storage.');
  return destination;
}

export async function removeStoredFile(uri: string): Promise<void> {
  if (!uri || !uri.startsWith(directory())) return;
  await FileSystem.deleteAsync(uri, { idempotent: true });
}

export async function readStoredFileBase64(uri: string): Promise<string> {
  return FileSystem.readAsStringAsync(uri, { encoding: FileSystem.EncodingType.Base64 });
}

export async function writeStoredFileBase64(relativePath: string, base64: string): Promise<string> {
  const folder = directory();
  await FileSystem.makeDirectoryAsync(folder, { intermediates: true });
  const relative = relativePath.replace(/\\/g, '/').split('/').filter(part => part && part !== '.' && part !== '..').map(safeName);
  if (relative.length === 0) throw new Error('Backup contains an invalid lecture file path.');
  const destination = folder + relative.join('/');
  const parent = destination.slice(0, destination.lastIndexOf('/'));
  await FileSystem.makeDirectoryAsync(parent, { intermediates: true });
  await FileSystem.writeAsStringAsync(destination, base64, { encoding: FileSystem.EncodingType.Base64 });
  return destination;
}
