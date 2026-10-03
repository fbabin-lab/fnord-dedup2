import { copy } from './copy';

export function formatBytes(value: string | number): string {
  const bytes = BigInt(value);
  if (bytes < 1024n) return bytes.toString() + ' B';
  const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB'];
  let divisor = 1024n, index = 0;
  while (index < units.length - 1 && bytes >= divisor * 1024n) { divisor *= 1024n; index++; }
  const tenth = (bytes % divisor) * 10n / divisor;
  return (bytes / divisor).toString() + '.' + tenth.toString() + ' ' + units[index];
}

export function parseBytes(value: string, label: string): string | undefined {
  const input = value.trim();
  if (!input) return undefined;
  const match = /^(\d+)(?:\.(\d{1,9}))?\s*(B|KB|MB|GB|TB|PB|KIB|MIB|GIB|TIB|PIB)?$/i.exec(input);
  if (!match) throw new Error(`${label}: ${copy.sizeFormat}`);
  const units: Record<string, bigint> = {
    B: 1n, KB: 1000n, MB: 1000n ** 2n, GB: 1000n ** 3n, TB: 1000n ** 4n, PB: 1000n ** 5n,
    KIB: 1024n, MIB: 1024n ** 2n, GIB: 1024n ** 3n, TIB: 1024n ** 4n, PIB: 1024n ** 5n
  };
  const multiplier = units[(match[3] ?? 'B').toUpperCase()];
  const whole = BigInt(match[1]) * multiplier;
  const fractionText = match[2] ?? '';
  const scale = fractionText ? 10n ** BigInt(fractionText.length) : 1n;
  const fraction = fractionText ? (BigInt(fractionText) * multiplier + scale / 2n) / scale : 0n;
  const bytes = whole + fraction;
  if (bytes > 9223372036854775807n) throw new Error(`${label}: ${copy.sizeTooLarge}`);
  return bytes.toString();
}
